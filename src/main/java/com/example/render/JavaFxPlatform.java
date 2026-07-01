package com.example.render;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Owns the single, JVM-wide JavaFX runtime and its one JavaFX Application Thread (JAT).
 *
 * <p>There is exactly one JavaFX runtime per JVM and exactly one JAT. Every WebView / WebEngine /
 * Scene / snapshot interaction in this service MUST happen on the JAT. That single thread is the
 * fundamental serialization point and the ultimate throughput ceiling of the whole service — see
 * {@code DESIGN.md}.
 *
 * <p>Headless server rendering is configured here via system properties BEFORE the toolkit starts:
 * <ul>
 *   <li>Monocle "Headless" Glass platform — no X server / display required.</li>
 *   <li>Prism software pipeline ({@code prism.order=sw}) — no GPU. WebKit already rasterises page
 *       content on the CPU, so the only thing "sw" changes is scene-graph compositing.</li>
 * </ul>
 * If Monocle proves brittle on your JDK/JavaFX combination, run under Xvfb instead and drop the
 * Monocle properties (see DESIGN.md).
 */
// Lazy singleton: JavaFX/WebKit (~180 MB + Xvfb) starts only when the WebView fallback is first used,
// not at boot — so a Blitz-only deployment never pays the WebKit tax. Created on demand when
// RenderService is first resolved (via BeanProvider in RenderDispatcher).
@Singleton
public final class JavaFxPlatform {

    private static final Logger LOG = LoggerFactory.getLogger(JavaFxPlatform.class);

    private volatile boolean started;

    @PostConstruct
    void start() {
        // These MUST be set before Platform.startup() / any toolkit touch. The Glass *platform*
        // (Xvfb vs Monocle) is chosen by the launcher (run.sh) via -Dglass.platform, NOT hardcoded
        // here — the default deployment runs under Xvfb, which needs no extra dependency. We only
        // fill in software-rendering + low-memory-text defaults if the launcher didn't.
        setIfAbsent("java.awt.headless", "true");
        setIfAbsent("prism.order", "sw");
        // WebKit/Prism text: keep font memory + LCD AA off for determinism and lower RSS.
        setIfAbsent("prism.text", "t2k");
        setIfAbsent("prism.lcdtext", "false");

        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyStarted) {
            // Toolkit already running in this JVM (e.g. test harness) — reuse it.
            ready.countDown();
        }
        try {
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX toolkit did not start within 30s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting JavaFX toolkit", e);
        }
        // Keep the runtime alive even when no Stage is showing.
        Platform.setImplicitExit(false);
        started = true;
        LOG.info("JavaFX toolkit started (glass.platform={}, software pipeline)",
                System.getProperty("glass.platform", "default/Xvfb"));
    }

    /** Run {@code task} on the JavaFX Application Thread. Returns immediately (fire-and-forget). */
    public void runOnFxThread(Runnable task) {
        if (!started) {
            throw new IllegalStateException("JavaFX toolkit not started");
        }
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    public boolean isStarted() {
        return started;
    }

    @PreDestroy
    void stop() {
        if (started) {
            Platform.exit();
            started = false;
            LOG.info("JavaFX toolkit shut down");
        }
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}