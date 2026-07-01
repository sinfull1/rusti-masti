package com.example.render;

import io.micronaut.context.annotation.Value;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bounded WebView pool + admission control. This is where the 500&nbsp;MB / 10-img&nbsp;s constraints
 * are actually enforced:
 *
 * <ul>
 *   <li><b>pool.size</b> caps resident WebKit engines — the dominant (native, off-heap) memory cost.
 *       Keep this at 1–2 under a 500&nbsp;MB ceiling.</li>
 *   <li><b>An admission {@link Semaphore}</b> ({@code pool.size + queue.max} permits) sheds load:
 *       excess requests fail fast with {@link RejectedRenderException} → HTTP 503 instead of piling
 *       up unbounded image buffers and OOM-ing the box.</li>
 *   <li><b>Per-render timeout</b> stops one pathological page from wedging a scarce cell forever.</li>
 * </ul>
 */
@Singleton
public class
RenderService {

    private static final Logger LOG = LoggerFactory.getLogger(RenderService.class);

    private final JavaFxPlatform fx;
    private final TemplateService templateService;

    @Value("${render.pool.size:2}")
    int poolSize;

    @Value("${render.queue.max:16}")
    int maxQueue;

    @Value("${render.timeout-ms:5000}")
    long timeoutMs;

    @Value("${render.recycle-after:200}")
    int recycleAfter;

    // Reload mode does a fresh parse + style/font resolution, so it needs a longer paint settle than
    // inject mode, which swaps already-styled content. Tuned separately (see the load-test in DESIGN.md).
    @Value("${render.settle-ms:80}")
    long settleMs;

    @Value("${render.inject-settle-ms:30}")
    long injectSettleMs;

    private ArrayBlockingQueue<WebViewCell> freeCells;
    private Semaphore admission;
    private ExecutorService encodeExecutor;

    public RenderService(JavaFxPlatform fx, TemplateService templateService) {
        this.fx = fx;
        this.templateService = templateService;
    }

    @PostConstruct
    void init() {
        this.freeCells = new ArrayBlockingQueue<>(poolSize);
        this.admission = new Semaphore(poolSize + maxQueue);
        // Small CPU pool for PNG encoding (kept off the JAT). 2 threads is plenty at ~10 img/s.
        this.encodeExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "png-encode");
            t.setDaemon(true);
            return t;
        });

        // Cells must be constructed on the JAT. Each gets the (constant) empty shell used by inject mode.
        String emptyShell = templateService.emptyShell();
        CountDownLatch built = new CountDownLatch(1);
        fx.runOnFxThread(() -> {
            for (int i = 0; i < poolSize; i++) {
                freeCells.add(new WebViewCell(i, settleMs, injectSettleMs, emptyShell));
            }
            built.countDown();
        });
        awaitUninterruptibly(built);
        LOG.info("RenderService ready: pool={}, admission={}, timeout={}ms, recycleAfter={}",
                poolSize, poolSize + maxQueue, timeoutMs, recycleAfter);
    }

    /**
     * Submit a render. The returned future completes with PNG bytes, or fails with
     * {@link RejectedRenderException} (overloaded) or {@link RenderException} (load/timeout error).
     * Caller should run this on a blocking-capable thread — it briefly parks waiting for a free cell.
     */
    public CompletableFuture<byte[]> render(RenderSpec spec) {
        if (spec == null) {
            return CompletableFuture.failedFuture(new RenderException("spec is required"));
        }
        // Admission: fail fast rather than queue unbounded work against a 500 MB budget.
        if (!admission.tryAcquire()) {
            return CompletableFuture.failedFuture(
                    new RejectedRenderException("render queue full (" + (poolSize + maxQueue) + ")"));
        }

        WebViewCell cell;
        try {
            // Wait briefly for a cell; admission already bounds how many can be here at once.
            cell = freeCells.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            admission.release();
            return CompletableFuture.failedFuture(new RenderException("interrupted", e));
        }
        if (cell == null) {
            admission.release();
            return CompletableFuture.failedFuture(new RejectedRenderException("no cell available"));
        }

        CompletableFuture<byte[]> out = new CompletableFuture<>();
        fx.runOnFxThread(() -> {
            try {
                cell.render(spec, encodeExecutor)
                        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .whenComplete((bytes, err) -> releaseAndComplete(cell, out, bytes, err));
            } catch (Throwable t) {
                releaseAndComplete(cell, out, null, t);
            }
        });
        return out;
    }

    private void releaseAndComplete(WebViewCell cell, CompletableFuture<byte[]> out,
                                    byte[] bytes, Throwable err) {
        // Recycle on the JAT, then return the cell to the pool and free the admission permit.
        fx.runOnFxThread(() -> {
            try {
                if (cell.needsRecycle(recycleAfter)) {
                    // Periodic hard recycle: rebuild the WebView to return WebKit native memory to the
                    // OS (about:blank alone leaves anon RSS creeping — see the soak test in DESIGN.md).
                    cell.hardRecycle();
                } else if (err != null) {
                    // On error the cell may be mid-load; parking on about:blank re-baselines it cheaply.
                    cell.park();
                }
            } finally {
                freeCells.add(cell);
                admission.release();
            }
        });
        if (err != null) {
            out.completeExceptionally(err instanceof RenderException || err instanceof RejectedRenderException
                    ? err : new RenderException("render failed", err));
        } else {
            out.complete(bytes);
        }
    }

    public Duration timeout() {
        return Duration.ofMillis(timeoutMs);
    }

    @PreDestroy
    void shutdown() {
        if (encodeExecutor != null) {
            encodeExecutor.shutdownNow();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}