package com.example.render;

import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Semaphore;

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
    private final BeanProvider<RenderService> webViewProvider; // lazy: only instantiated on fallback
    @Nullable
    private final BlitzRenderer blitz;                          // null when the native lib is absent

    @Value("${render.backend:auto}")
    String backend;

    @Value("${render.fragment-template:receipt-fragment}")
    String fragmentTemplate;

    @Value("${render.mode:inject}")
    String mode;

    @Value("${render.width:480}")
    int width;

    @Value("${render.height:1200}")
    int height;

    @Value("${render.auto-height:true}")
    boolean autoHeight;

    @Value("${render.device-scale:2.0}")
    double deviceScale;

    @Value("${render.quantize:true}")
    boolean quantize;

    // Bounds concurrent NATIVE Blitz renders (each ~14 MB + a core). 0 => number of available
    // processors (honours the container CPU limit). This is the authoritative concurrency cap,
    // independent of how the blocking executor is sized.
    @Value("${render.blitz.max-concurrent:0}")
    int maxConcurrentCfg;

    // Extra requests allowed to wait for a render slot before we shed with 503.
    @Value("${render.blitz.max-queue:32}")
    int maxQueue;

    // slots: at most maxConcurrent renders run at once. admission: total in-flight (running + waiting)
    // is capped at maxConcurrent + maxQueue; beyond that tryAcquire fails fast → RejectedRenderException
    // → HTTP 503. Two levels so bursts queue briefly but sustained overload sheds instead of piling up
    // unbounded native renders that would blow the memory budget.
    private Semaphore slots;
    private Semaphore admission;

    public RenderDispatcher(TemplateService templateService,
                            BeanProvider<RenderService> webViewProvider,
                            @Nullable BlitzRenderer blitz) {
        this.templateService = templateService;
        this.webViewProvider = webViewProvider;
        this.blitz = blitz;
    }

    @PostConstruct
    void init() {
        int maxConcurrent = maxConcurrentCfg > 0
                ? maxConcurrentCfg
                : Runtime.getRuntime().availableProcessors();
        this.slots = new Semaphore(maxConcurrent);
        this.admission = new Semaphore(maxConcurrent + maxQueue);
        if (blitz != null) {
            LOG.info("Blitz admission: maxConcurrent={}, maxQueue={}", maxConcurrent, maxQueue);
        }
    }

    /** Render {@code payload} to PNG bytes, choosing a backend. May throw {@link RenderException} or
     *  {@link RejectedRenderException} (overload → 503). */
    public byte[] render(Map<String, Object> payload) {
        boolean browserRequested = "browser".equalsIgnoreCase(str(payload.get("renderMode")))
                || "webview".equalsIgnoreCase(backend);

        if (!browserRequested && blitz != null
                && ("rust".equalsIgnoreCase(backend) || "auto".equalsIgnoreCase(backend))) {
            try {
                return renderWithBlitz(payload);
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
        return renderWithWebView(payload);
    }

    private byte[] renderWithBlitz(Map<String, Object> payload) {
        if (!admission.tryAcquire()) {
            throw new RejectedRenderException("render overloaded (blitz queue full)");
        }
        boolean slotHeld = false;
        try {
            slots.acquire();          // bounded wait: admission caps waiters to maxQueue
            slotHeld = true;
            String html = templateService.blitzDocument(fragmentTemplate, payload);
            return blitz.render(html, width, deviceScale, autoHeight, quantize);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RenderException("interrupted awaiting render slot", e);
        } finally {
            if (slotHeld) {
                slots.release();
            }
            admission.release();
        }
    }

    private byte[] renderWithWebView(Map<String, Object> payload) {
        boolean inject = "inject".equalsIgnoreCase(mode);
        String html = inject
                ? templateService.fragment(fragmentTemplate, payload)
                : templateService.fullDocument(fragmentTemplate, payload);
        RenderSpec spec = new RenderSpec(html, inject, width, height, autoHeight, deviceScale);
        return webViewProvider.get().render(spec).join();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
