package com.example.render;

/**
 * Renderer tunables passed to the native library at {@code blitz_init}. Sourced from Micronaut config
 * (env-overridable) so the palette, quantiser thresholds and dimension bounds live in one place rather
 * than baked into the {@code .so}.
 *
 * @param palette        12 bytes: 4 colours × RGB, in index order (0..3 = the 2-bit codes)
 * @param satThreshold   saturation below which a pixel is treated as neutral (black/white by luminance)
 * @param lumThreshold   neutral pixels darker than this become black, else white
 * @param defaultHeight  viewport/layout height (CSS px) when auto-height is off
 * @param maxWidth       upper bound (CSS px) on render width — caps the output buffer
 * @param maxHeight      upper bound (CSS px) on auto-measured content height — caps the output buffer
 */
public record BlitzConfig(byte[] palette, int satThreshold, int lumThreshold,
                          int defaultHeight, int maxWidth, int maxHeight) {
    public BlitzConfig {
        if (palette == null || palette.length != 12) {
            throw new IllegalArgumentException("palette must be 12 bytes (4 RGB colours)");
        }
    }
}
