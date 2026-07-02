package com.example.render;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Creates the {@link BlitzRenderer} bean only when the native library is present (see
 * {@link BlitzLibPresent}), reading the renderer tunables from config (env-overridable) and passing
 * them to the native lib at init. Where the lib is absent, no bean exists and {@link RenderDispatcher}
 * treats the Blitz backend as unavailable (WebView-only).
 */
@Factory
public class BlitzRendererFactory {

    private static final Logger LOG = LoggerFactory.getLogger(BlitzRendererFactory.class);

    @Singleton
    @Requires(condition = BlitzLibPresent.class)
    BlitzRenderer blitzRenderer(
            @Value("${render.blitz.lib-path:/app/libblitz_render.so}") String libPath,
            @Value("${render.blitz.palette:000000,FFFFFF,FF0000,0000FF}") List<String> palette,
            @Value("${render.blitz.sat-threshold:60}") int satThreshold,
            @Value("${render.blitz.lum-threshold:180}") int lumThreshold,
            @Value("${render.blitz.default-height:800}") int defaultHeight,
            @Value("${render.blitz.max-width:4000}") int maxWidth,
            @Value("${render.blitz.max-height:8000}") int maxHeight) {
        LOG.info("loading Blitz renderer from {} (palette={}, sat={}, lum={}, maxWxH={}x{})",
                libPath, palette, satThreshold, lumThreshold, maxWidth, maxHeight);
        BlitzRenderer renderer = new BlitzRenderer(Path.of(libPath));
        renderer.init(new BlitzConfig(parsePalette(palette),
                satThreshold, lumThreshold, defaultHeight, maxWidth, maxHeight));
        return renderer;
    }

    /** Parse 4 {@code RRGGBB} (or {@code #RRGGBB}) hex colours into a 12-byte RGB array (index order). */
    static byte[] parsePalette(List<String> colours) {
        if (colours == null || colours.size() != 4) {
            throw new IllegalArgumentException("render.blitz.palette must list exactly 4 colours");
        }
        byte[] out = new byte[12];
        for (int i = 0; i < 4; i++) {
            String hex = colours.get(i).trim();
            if (hex.startsWith("#")) {
                hex = hex.substring(1);
            }
            if (hex.length() != 6) {
                throw new IllegalArgumentException("palette colour must be RRGGBB hex: " + colours.get(i));
            }
            int rgb = Integer.parseInt(hex, 16);
            out[i * 3] = (byte) ((rgb >> 16) & 0xFF);
            out[i * 3 + 1] = (byte) ((rgb >> 8) & 0xFF);
            out[i * 3 + 2] = (byte) (rgb & 0xFF);
        }
        return out;
    }
}
