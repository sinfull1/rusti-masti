package com.example.render;

import javafx.animation.PauseTransition;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.concurrent.Worker;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * One reusable rendering slot: a single {@link WebView} attached to its own {@link Scene}.
 *
 * <p><b>Threading:</b> every method that touches {@code webView}/{@code engine}/{@code scene} runs on
 * the JavaFX Application Thread. A {@link WebViewCell} is never used by two jobs at once — the pool
 * ({@link RenderService}) hands out cells under a permit, so there is no in-cell locking.
 *
 * <p><b>Why a pool of cells at all,</b> given the JAT serialises everything: WebKit page loading has
 * async, off-JAT phases. While cell A waits for its {@code loadContent} to reach SUCCEEDED, the JAT
 * is free to snapshot cell B. A tiny pool (1–2) overlaps load latency with rasterisation.
 *
 * <p><b>Memory recycling:</b> after {@code maxRendersBeforeReset} jobs the cell parks on
 * {@code about:blank} to release page state; the pool can also rebuild the cell entirely to hard-reset
 * native RSS (see DESIGN.md).
 */
final class WebViewCell {

    private static final Logger LOG = LoggerFactory.getLogger(WebViewCell.class);

    private final int id;
    private final long reloadSettleMillis;
    private final long injectSettleMillis;
    private final String emptyShell;
    private WebView webView;
    private Scene scene;
    private Stage stage;
    private int rendersSinceReset;
    // Inject mode: whether this WebView already has the (CSS + __render JS) shell loaded. Reset to
    // false whenever the WebView is rebuilt (hardRecycle), forcing a one-time shell reload.
    private boolean shellLoaded;

    /** Must be constructed on the JAT. */
    WebViewCell(int id, long reloadSettleMillis, long injectSettleMillis, String emptyShell) {
        this.id = id;
        this.reloadSettleMillis = reloadSettleMillis;
        this.injectSettleMillis = injectSettleMillis;
        this.emptyShell = emptyShell;
        build();
    }

    /** Create the WebView + a shown Stage. On the JAT. */
    private void build() {
        this.webView = new WebView();
        this.webView.setContextMenuEnabled(false);
        this.scene = new Scene(webView, 480, 640);
        // A WebView only PAINTS (composites WebKit's output into the scene graph) when its Scene is on
        // a SHOWN Stage — an offscreen Scene lays out but snapshots blank. On the virtual X server
        // (Xvfb) this Stage is an invisible window; nothing appears on any real display.
        this.stage = new Stage();
        this.stage.setScene(scene);
        this.stage.show();
    }

    int id() { return id; }

    /**
     * Render {@code spec} to PNG bytes. Runs on the JAT; the returned future completes once the async
     * snapshot callback fires, then the (CPU-heavy) PNG encode is offloaded to {@code encodeExecutor}.
     *
     * <p><b>Inject mode</b> ({@code spec.inject()}): once the shell (CSS + {@code __render} JS) is
     * resident, each render is a single synchronous {@code executeScript} that swaps the body and
     * returns the new height — no {@code loadContent}, no navigation lifecycle, no {@code LoadWorker}
     * round-trip. Only the first render on a fresh WebView pays a one-time shell load.
     *
     * <p><b>Reload mode:</b> full {@code loadContent} of a standalone document every time.
     */
    CompletableFuture<byte[]> render(RenderSpec spec, Executor encodeExecutor) {
        assert javafx.application.Platform.isFxApplicationThread() : "render() must run on the JAT";

        int w = spec.width();
        // Width must be applied BEFORE load/inject so WebKit lays out (and text-wraps) at the target
        // width; that makes the height measurement correct for auto-height.
        applyWidth(w);
        CompletableFuture<byte[]> result = new CompletableFuture<>();

        if (spec.inject()) {
            if (shellLoaded) {
                injectAndSnapshot(spec, w, encodeExecutor, result);   // hot path: no load at all
            } else {
                loadThen(emptyShell, result, () -> {
                    shellLoaded = true;
                    injectAndSnapshot(spec, w, encodeExecutor, result);
                });
            }
        } else {
            loadThen(spec.html(), result, () -> {
                int h = spec.autoHeight() ? measureContentHeight() : spec.height();
                resizeAndSettleSnapshot(w, h, spec.scale(), reloadSettleMillis, encodeExecutor, result);
            });
        }
        return result;
    }

    /** loadContent + wait for SUCCEEDED, then run {@code onLoaded} on the JAT (failures complete result). */
    private void loadThen(String content, CompletableFuture<byte[]> result, Runnable onLoaded) {
        var worker = webView.getEngine().getLoadWorker();
        var listener = new javafx.beans.value.ChangeListener<Worker.State>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends Worker.State> obs,
                                Worker.State old, Worker.State state) {
                if (state == Worker.State.SUCCEEDED) {
                    worker.stateProperty().removeListener(this);
                    try {
                        onLoaded.run();
                    } catch (Throwable t) {
                        result.completeExceptionally(new RenderException("post-load step failed", t));
                    }
                } else if (state == Worker.State.FAILED || state == Worker.State.CANCELLED) {
                    worker.stateProperty().removeListener(this);
                    result.completeExceptionally(new RenderException("Page load " + state + " for cell " + id));
                }
            }
        };
        worker.stateProperty().addListener(listener);
        webView.getEngine().loadContent(content, "text/html");
    }

    /** Inject mode hot path: swap the body via JS (which returns the new scrollHeight), then snapshot. */
    private void injectAndSnapshot(RenderSpec spec, int w, Executor encodeExecutor,
                                   CompletableFuture<byte[]> result) {
        String b64 = Base64.getEncoder().encodeToString(spec.html().getBytes(StandardCharsets.UTF_8));
        // __render sets document.body.innerHTML and returns the content height in one round-trip.
        Object hObj = webView.getEngine().executeScript("__render('" + b64 + "')");
        int measured = (hObj instanceof Number n) ? n.intValue() : 0;
        int h = spec.autoHeight() ? Math.clamp(measured <= 0 ? 640 : measured, 1, 8000) : spec.height();
        resizeAndSettleSnapshot(w, h, spec.scale(), injectSettleMillis, encodeExecutor, result);
    }

    /** Size the window to the content, wait one settle for WebKit to paint, then snapshot. */
    private void resizeAndSettleSnapshot(int w, int h, double scale, long settleMs, Executor encodeExecutor,
                                         CompletableFuture<byte[]> result) {
        applySize(w, h);
        // Grow the window to cover the full content so WebKit paints all of it (a smaller window would
        // only composite the visible viewport into the snapshot).
        stage.setWidth(w);
        stage.setHeight(h);
        // A correct height only means the DOM has LAID OUT; WebKit may not have committed its PAINT
        // yet, so an immediate snapshot can be blank. Settle one beat, then snapshot. The pause is a
        // JAT timer — it does not block other cells from rendering.
        PauseTransition settle = new PauseTransition(Duration.millis(settleMs));
        settle.setOnFinished(ev -> snapshotAsync(w, h, scale, encodeExecutor, result));
        settle.play();
    }

    /** Content height from the loaded DOM, so receipts of any length are captured without clipping. */
    private int measureContentHeight() {
        Object h = webView.getEngine().executeScript(
                "Math.ceil(Math.max(document.documentElement.scrollHeight, document.body.scrollHeight))");
        int px = (h instanceof Number n) ? n.intValue() : 0;
        return Math.clamp(px <= 0 ? 640 : px, 1, 8000);
    }

    private void snapshotAsync(int w, int h, double scale, Executor encodeExecutor,
                               CompletableFuture<byte[]> result) {
        SnapshotParameters params = new SnapshotParameters();
        // Opaque white background: without it, areas the page never painted are transparent and would
        // quantise to black (nearest palette colour of RGB 0,0,0). White keeps them white.
        params.setFill(Color.WHITE);
        if (scale != 1.0) {
            params.setTransform(new Scale(scale, scale));
        }
        WritableImage buffer = new WritableImage(
                (int) Math.ceil(w * scale), (int) Math.ceil(h * scale));

        // Async snapshot: JavaFX waits for the next render pulse (which applies the resize + layout)
        // before capturing, avoiding the blank/half-painted-frame race of a synchronous snapshot.
        webView.snapshot(snapResult -> {
            WritableImage img = snapResult.getImage();
            rendersSinceReset++;
            encodeExecutor.execute(() -> {
                try {
                    result.complete(encodePng(img));
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
            return null;
        }, params, buffer);
    }

    private void applyWidth(int w) {
        webView.setMinWidth(w);
        webView.setPrefWidth(w);
        webView.setMaxWidth(w);
        webView.resize(w, webView.getHeight() <= 0 ? 640 : webView.getHeight());
    }

    private void applySize(int w, int h) {
        webView.setMinSize(w, h);
        webView.setPrefSize(w, h);
        webView.setMaxSize(w, h);
        webView.resize(w, h);
    }

    // Fixed 4-colour output palette. Index order defines the 2-bit codes: 0=black 1=white 2=red 3=blue.
    private static final int BLACK = 0, WHITE = 1;
    private static final int[] PALETTE_R = { 0x00, 0xFF, 0xFF, 0x00 };
    private static final int[] PALETTE_G = { 0x00, 0xFF, 0x00, 0x00 };
    private static final int[] PALETTE_B = { 0x00, 0xFF, 0x00, 0xFF };

    // A pixel is "neutral" (grey-ish) when its saturation (max-min channel) is below this. Neutral
    // pixels are forced to black/white by a luminance threshold — this is what actually removes
    // anti-aliasing cleanly and stops WebKit's faint sub-pixel colour fringes on text edges from
    // being sent to red/blue. Only genuinely saturated pixels reach red/blue.
    private static final int SAT_THRESHOLD = 60;
    // Neutral pixels darker than this become black, else white. Set high so mid/light grey text
    // (labels, addresses) resolves to solid black rather than dropping out to white.
    private static final int LUM_THRESHOLD = 180;

    /**
     * Encode as a 2-bit INDEXED PNG limited to {black, white, red, blue}. Near-neutral pixels are
     * hard-thresholded to black/white by luminance (removing AA); saturated pixels map to the nearest
     * palette colour so intentional red/blue survive.
     */
    private static byte[] encodePng(WritableImage img) throws Exception {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        int[] argb = new int[w * h];
        img.getPixelReader().getPixels(0, 0, w, h,
                PixelFormat.getIntArgbInstance(), argb, 0, w);

        byte[] r = { (byte) PALETTE_R[0], (byte) PALETTE_R[1], (byte) PALETTE_R[2], (byte) PALETTE_R[3] };
        byte[] g = { (byte) PALETTE_G[0], (byte) PALETTE_G[1], (byte) PALETTE_G[2], (byte) PALETTE_G[3] };
        byte[] b = { (byte) PALETTE_B[0], (byte) PALETTE_B[1], (byte) PALETTE_B[2], (byte) PALETTE_B[3] };
        // 2 bits/pixel, 4 entries -> ImageIO writes a genuine 2-bit palette PNG.
        IndexColorModel icm = new IndexColorModel(2, 4, r, g, b);
        BufferedImage indexed = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY, icm);
        WritableRaster raster = indexed.getRaster();

        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int p = argb[row + x];
                raster.setSample(x, y, 0,
                        mapToPalette((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF));
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        if (!ImageIO.write(indexed, "png", out)) {
            throw new RenderException("No PNG ImageIO writer available");
        }
        return out.toByteArray();
    }

    private static int mapToPalette(int red, int green, int blue) {
        int sat = Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue));
        if (sat <= SAT_THRESHOLD) {
            int lum = (299 * red + 587 * green + 114 * blue) / 1000;
            return lum < LUM_THRESHOLD ? BLACK : WHITE;
        }
        return nearestPaletteIndex(red, green, blue);
    }

    private static int nearestPaletteIndex(int red, int green, int blue) {
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            long dr = red - PALETTE_R[i], dg = green - PALETTE_G[i], db = blue - PALETTE_B[i];
            long d = dr * dr + dg * dg + db * db;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    boolean needsRecycle(int maxRendersBeforeRecycle) {
        return rendersSinceReset >= maxRendersBeforeRecycle;
    }

    /**
     * On the JAT: park on {@code about:blank}. Releases the current page's DOM/JS state. Cheap, used
     * after an errored render to re-baseline the cell. Does NOT reclaim WebKit's native caches — for
     * that see {@link #hardRecycle()}.
     */
    void park() {
        assert javafx.application.Platform.isFxApplicationThread();
        webView.getEngine().load("about:blank");
        LOG.debug("cell {} parked on about:blank", id);
    }

    /**
     * On the JAT: fully dispose the WebView + Stage and rebuild. Unlike {@link #park()}, this actually
     * returns WebKit's native memory (JIT code, font/texture/image caches) to the OS — soak tests
     * showed {@code about:blank} alone leaves anon RSS creeping ~3-4 MB/min. Called every
     * {@code render.recycle-after} renders to keep RSS flat under the 500 MB budget.
     */
    void hardRecycle() {
        assert javafx.application.Platform.isFxApplicationThread();
        try {
            webView.getEngine().load(null);
            stage.hide();
            stage.setScene(null);
        } catch (Throwable t) {
            LOG.warn("cell {} teardown hiccup during hardRecycle: {}", id, t.toString());
        }
        webView = null;
        scene = null;
        stage = null;
        build();
        shellLoaded = false;   // fresh WebView has no shell; next inject render reloads it once
        rendersSinceReset = 0;
        LOG.info("cell {} hard-recycled (WebView rebuilt to reclaim native memory)", id);
    }
}