package com.example.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies each size template renders as a full document with the shared receipt body inserted
 * (th:insert of receipt-fragment :: body) and its size-specific width. No native/JavaFX needed.
 */
class SizeTemplateTest {

    private static Map<String, Object> sample() {
        return Map.of(
                "storeName", "Nimbus Goods Co.",
                "orderId", "NG-2026-0041872",
                "currency", "$",
                "items", List.of(Map.of("name", "Aurora Wireless Headphones", "sku", "AUR-BLK-01",
                        "qty", 1, "price", 129.00, "total", 129.00)),
                "subtotal", 129.00, "discount", 20.00, "total", 109.00);
    }

    @Test
    void allSizeTemplatesRender() throws Exception {
        TemplateService svc = new TemplateService();
        Files.createDirectories(Path.of("target"));
        record Size(String tpl, String width) {}
        List<Size> sizes = List.of(
                new Size("receipt-small", "320px"),
                new Size("receipt-medium", "480px"),
                new Size("receipt-large", "640px"),
                new Size("receipt-xlarge", "800px"));

        for (Size s : sizes) {
            String html = svc.document(s.tpl(), sample());
            Files.writeString(Path.of("target/" + s.tpl() + ".html"), html);
            assertTrue(html.contains("<html"), s.tpl() + " should be a full document");
            assertTrue(html.contains("width: " + s.width()), s.tpl() + " should use width " + s.width());
            assertTrue(html.contains("Aurora Wireless Headphones"), s.tpl() + " should include the body");
            assertTrue(html.contains("Nimbus Goods Co."), s.tpl() + " should bind data");
        }
    }
}
