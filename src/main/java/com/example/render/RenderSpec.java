package com.example.render;

/**
 * Internal render unit handed to the WebView pool: resolved HTML plus output geometry.
 * (The HTTP layer produces this from a JSON payload + Thymeleaf template; the pool never sees JSON.)
 *
 * @param html        the HTML to render. In reload mode: a complete document for {@code loadContent}.
 *                    In inject mode: the body-only fragment pushed into the resident shell via JS.
 * @param inject      true → skip {@code loadContent} and inject {@code html} into the already-loaded
 *                    shell with a JS call (the throughput optimisation); false → full page load.
 * @param width       viewport / output width in CSS px
 * @param height      output height in CSS px; ignored when {@code autoHeight} is true
 * @param autoHeight  measure the rendered document height and size the capture to fit the content
 * @param scale       output pixel density multiplier (hi-dpi)
 */
public record RenderSpec(String html, boolean inject, int width, int height, boolean autoHeight, double scale) {

    public RenderSpec {
        if (html == null || html.isBlank()) {
            throw new RenderException("html is required");
        }
        width  = Math.clamp(width,  1, 4000);
        height = Math.clamp(height, 1, 8000);
        scale  = Math.clamp(scale, 0.5, 3.0);
    }
}
