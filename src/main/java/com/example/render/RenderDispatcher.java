package com.example.render;

import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Chooses the rendering backend per request and produces the PNG.
 *
 * <ul>
 *   <li><b>Blitz (Rust/FFM)</b> — the fast default: clean document → in-process native render. ~6× the
 *       throughput at a fraction of the memory, but no JS and ~95% CSS fidelity.</li>
 *   <li><b>WebView (JavaFX)</b> — full-browser fallback for pages that need JS or that Blitz renders
 *       imperfectly. Loaded <em>lazily</em> (via {@link BeanProvider}) so JavaFX/WebKit only starts —
 *       and only takes its ~180&nbsp;MB — when a fallback is actually needed.</li>
 * </ul>
 *
 * {@code render.backend}: {@code rust} | {@code webview} | {@code auto} (default). In {@code auto},
 * Blitz is tried first and a render error falls through to WebView; a request carrying
 * {@code "renderMode":"browser"} is routed straight to WebView.
 */
@Singleton
public class RenderDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(RenderDispatcher.class);

    private final TemplateService templateService;
    private final PayloadValidator validator;
    private final BeanProvider<RenderService> webViewProvider; // lazy: only instantiated on fallback
    @Nullable
    private final BlitzRenderer blitz;                          // null when the native lib is absent

    @Value("${render.backend:auto}")
    String backend;

    // Size used when a request doesn't specify one.
    @Value("${render.default-size:medium}")
    String defaultSize;

    @Value("${render.quantize:true}")
    boolean quantize;

    // size name -> profile (template + output geometry), built from the injected SizeProfile beans.
    private final Map<String, SizeProfile> sizes;

    // Bounds concurrent NATIVE Blitz renders (each ~14 MB + a core). 0 => number of available
    // processors (honours the container CPU limit). This is the authoritative concurrency cap,
    // independent of how the blocking executor is sized.
    @Value("${render.blitz.max-concurrent:0}")
    int maxConcurrentCfg;

    // Extra requests allowed to wait for a render slot before we shed with 503.
    @Value("${render.blitz.max-queue:32}")
    int maxQueue;

    // Hard deadline for a single native render. Input is already size-capped (PayloadValidator), so
    // this is a safety net against a pathological/buggy render; on timeout the client gets an error
    // while the native call finishes in the background (its slot is freed only when it actually ends,
    // so a stuck render can never over-subscribe concurrency).
    @Value("${render.blitz.timeout-ms:10000}")
    long blitzTimeoutMs;

    // slots: at most maxConcurrent renders run at once. admission: total in-flight (running + waiting)
    // is capped at maxConcurrent + maxQueue; beyond that tryAcquire fails fast → RejectedRenderException
    // → HTTP 503. Two levels so bursts queue briefly but sustained overload sheds instead of piling up
    // unbounded native renders that would blow the memory budget.
    private Semaphore slots;
    private Semaphore admission;
    // Runs the native render off the request thread so a slow render can be timed out without the
    // caller blocking forever. Sized to maxConcurrent — a slot is held for every submit, so it never
    // queues or over-subscribes.
    private ExecutorService renderExecutor;

    public RenderDispatcher(TemplateService templateService,
                            PayloadValidator validator,
                            List<SizeProfile> sizeProfiles,
                            BeanProvider<RenderService> webViewProvider,
                            @Nullable BlitzRenderer blitz) {
        this.templateService = templateService;
        this.validator = validator;
        this.webViewProvider = webViewProvider;
        this.blitz = blitz;
        this.sizes = sizeProfiles.stream()
                .collect(Collectors.toMap(p -> p.getName().toLowerCase(Locale.ROOT), p -> p));
    }

    @PostConstruct
    void init() {
        int maxConcurrent = maxConcurrentCfg > 0
                ? maxConcurrentCfg
                : Runtime.getRuntime().availableProcessors();
        this.slots = new Semaphore(maxConcurrent);
        this.admission = new Semaphore(maxConcurrent + maxQueue);
        this.renderExecutor = Executors.newFixedThreadPool(maxConcurrent, r -> {
            Thread t = new Thread(r, "blitz-render");
            t.setDaemon(true);
            return t;
        });
        if (blitz != null) {
            LOG.info("Blitz admission: maxConcurrent={}, maxQueue={}, timeout={}ms",
                    maxConcurrent, maxQueue, blitzTimeoutMs);
        }
    }

    @PreDestroy
    void shutdown() {
        if (renderExecutor != null) {
            renderExecutor.shutdownNow();
        }
    }

    /**
     * Render {@code payload} at the requested {@code sizeName} (null → default size) to PNG bytes,
     * choosing a backend. May throw {@link InvalidRequestException} (bad payload / unknown size → 400),
     * {@link RejectedRenderException} (overload → 503), or {@link RenderException} (render error).
     */
    public byte[] render(Map<String, Object> payload, String sizeName) {
        // Bound payload size/complexity BEFORE templating so it can't expand into a huge DOM (DoS).
        validator.validate(payload);
        SizeProfile size = resolveSize(sizeName);

        boolean browserRequested = "browser".equalsIgnoreCase(str(payload.get("renderMode")))
                || "webview".equalsIgnoreCase(backend);

        if (!browserRequested && blitz != null
                && ("rust".equalsIgnoreCase(backend) || "auto".equalsIgnoreCase(backend))) {
            try {
                return renderWithBlitz(size, payload);
            } catch (RejectedRenderException overloaded) {
                // Overload sheds as 503 — do NOT fall back to the (also-limited, slower) WebView.
                throw overloaded;
            } catch (RenderException e) {
                if (!"auto".equalsIgnoreCase(backend)) {
                    throw e;
                }
                LOG.warn("Blitz render failed, falling back to WebView: {}", e.getMessage());
            }
        }
        return renderWithWebView(size, payload);
    }

    private SizeProfile resolveSize(String sizeName) {
        String key = (sizeName == null || sizeName.isBlank())
                ? defaultSize.toLowerCase(Locale.ROOT)
                : sizeName.toLowerCase(Locale.ROOT);
        SizeProfile size = sizes.get(key);
        if (size == null) {
            throw new InvalidRequestException("unknown size '" + key + "'; available: " + sizes.keySet());
        }
        return size;
    }

    private byte[] renderWithBlitz(SizeProfile size, Map<String, Object> payload) {
        if (!admission.tryAcquire()) {
            throw new RejectedRenderException("render overloaded (blitz queue full)");
        }
        try {
            slots.acquire();          // bounded wait: admission caps waiters to maxQueue
        } catch (InterruptedException e) {
            admission.release();
            Thread.currentThread().interrupt();
            throw new RenderException("interrupted awaiting render slot", e);
        }

        final String html;
        try {
            html = templateService.document(size.getTemplate(), payload);
        } catch (RuntimeException e) {
            slots.release();
            admission.release();
            throw e;
        }

        // Run the native render off the request thread. slots + admission are released exactly once,
        // when the render actually completes (success, error, or executor-rejected) — NOT on timeout —
        // so a render that overruns the deadline still can't over-subscribe concurrency.
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        future.whenComplete((r, e) -> {
            slots.release();
            admission.release();
        });
        try {
            renderExecutor.execute(() -> {
                try {
                    future.complete(blitz.render(html, size.getWidth(), size.getDeviceScale(),
                            size.isAutoHeight(), quantize));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException rex) {
            future.completeExceptionally(rex); // triggers whenComplete → releases permits
        }

        try {
            return future.get(blitzTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            LOG.warn("Blitz render exceeded {}ms deadline", blitzTimeoutMs);
            throw new RenderException("render timed out after " + blitzTimeoutMs + "ms");
        } catch (ExecutionException ee) {
            Throwable c = ee.getCause() != null ? ee.getCause() : ee;
            throw (c instanceof RenderException re) ? re : new RenderException("blitz render failed", c);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RenderException("interrupted awaiting render", ie);
        }
    }

    private byte[] renderWithWebView(SizeProfile size, Map<String, Object> payload) {
        // Size templates are complete documents, so the WebView path loads them directly (reload mode).
        String html = templateService.document(size.getTemplate(), payload);
        RenderSpec spec = new RenderSpec(html, false, size.getWidth(), size.getHeight(),
                size.isAutoHeight(), size.getDeviceScale());
        return webViewProvider.get().render(spec).join();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
