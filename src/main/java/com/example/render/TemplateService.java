package com.example.render;

import jakarta.inject.Singleton;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the JSON request payload into HTML. Supports two shapes, sharing ONE source of CSS and ONE
 * data-bound fragment template:
 *
 * <ul>
 *   <li>{@link #fullDocument} — shell (CSS + head) with the fragment spliced in. A complete document
 *       for {@code loadContent} (reload mode).</li>
 *   <li>{@link #fragment} — just the receipt body, for JS injection into the resident shell
 *       (inject mode). Paired with {@link #emptyShell()} which is loaded once per WebView.</li>
 * </ul>
 *
 * The Thymeleaf {@link TemplateEngine} caches the parsed fragment template ({@code setCacheable});
 * the shell is read once as a raw resource (so its {@code <script>}/CSS pass through unescaped) and
 * kept as a string with a {@code __BODY__} splice marker.
 */
@Singleton
public class TemplateService {

    private static final String BODY_MARKER = "__BODY__";

    private final TemplateEngine engine;
    private final String shellRaw;
    private final String css;

    public TemplateService() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);

        this.engine = new TemplateEngine();
        this.engine.setTemplateResolver(resolver);
        this.shellRaw = readResource("templates/receipt-shell.html");
        if (!shellRaw.contains(BODY_MARKER)) {
            throw new IllegalStateException("receipt-shell.html missing " + BODY_MARKER + " marker");
        }
        this.css = extractCss(shellRaw);
    }

    /** Render {@code fragmentTemplate} with {@code model} exposed as {@code receipt}. Body-only HTML. */
    public String fragment(String fragmentTemplate, Map<String, Object> model) {
        Context ctx = new Context(Locale.US);
        ctx.setVariable("receipt", model);
        return engine.process(fragmentTemplate, ctx);
    }

    /**
     * Render a complete standalone template (its own {@code <head>}/CSS, e.g. a size variant) with
     * {@code model} exposed as {@code receipt}. Returns a full HTML document ready for either backend.
     */
    public String document(String templateName, Map<String, Object> model) {
        Context ctx = new Context(Locale.US);
        ctx.setVariable("receipt", model);
        return engine.process(templateName, ctx);
    }

    /** Full standalone document: shell + fragment spliced into the body (for reload mode). */
    public String fullDocument(String fragmentTemplate, Map<String, Object> model) {
        return shellRaw.replace(BODY_MARKER, fragment(fragmentTemplate, model));
    }

    /** The shell with an empty body, loaded once per WebView; JS then fills the body (inject mode). */
    public String emptyShell() {
        return shellRaw.replace(BODY_MARKER, "");
    }

    /**
     * Clean standalone document for the Blitz (Rust) backend: minimal {@code <head>} with only the
     * shared CSS (single source: the shell's {@code <style>}) and the fragment body — no JS
     * {@code <script>} (Blitz runs none) and no comment before {@code <html>} (Blitz's alpha parser
     * duplicates the document on that). This is the shape that renders as a single, correct page.
     */
    public String blitzDocument(String fragmentTemplate, Map<String, Object> model) {
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><style>"
                + css
                + "</style></head><body>"
                + fragment(fragmentTemplate, model)
                + "</body></html>";
    }

    private static String extractCss(String shell) {
        int a = shell.indexOf("<style>");
        int b = shell.indexOf("</style>");
        if (a < 0 || b <= a) {
            throw new IllegalStateException("receipt-shell.html missing <style> block");
        }
        return shell.substring(a + "<style>".length(), b);
    }

    private static String readResource(String path) {
        try (InputStream in = TemplateService.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + path, e);
        }
    }
}
