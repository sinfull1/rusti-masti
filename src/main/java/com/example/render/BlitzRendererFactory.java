package com.example.render;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Creates the {@link BlitzRenderer} bean only when the native library is present (see
 * {@link BlitzLibPresent}). Where it is absent, no bean exists and {@link RenderDispatcher} treats the
 * Blitz backend as unavailable (WebView-only).
 */
@Factory
public class BlitzRendererFactory {

    private static final Logger LOG = LoggerFactory.getLogger(BlitzRendererFactory.class);

    @Singleton
    @Requires(condition = BlitzLibPresent.class)
    BlitzRenderer blitzRenderer(
            @Value("${render.blitz.lib-path:/app/libblitz_render.so}") String libPath,
            @Value("${render.blitz.init-threads:0}") int initThreads) {
        LOG.info("loading Blitz renderer from {}", libPath);
        BlitzRenderer renderer = new BlitzRenderer(Path.of(libPath));
        renderer.init(initThreads);
        return renderer;
    }
}
