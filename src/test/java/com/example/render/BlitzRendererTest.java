package com.example.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the full fast-path boundary: Thymeleaf (JSON→HTML) → Rust Blitz renderer over Java FFM →
 * PNG. Skipped automatically when {@code libblitz_render.so} is not present (e.g. a plain host build);
 * the container build sets {@code -Dblitz.lib=/app/libblitz_render.so}.
 */
class BlitzRendererTest {

    private static Path libPath() {
        String p = System.getProperty("blitz.lib",
                System.getenv().getOrDefault("BLITZ_LIB", "render-ffi/target/release/libblitz_render.so"));
        return Path.of(p);
    }

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private static Map<String, Object> sampleReceipt() {
        return Map.ofEntries(
                Map.entry("storeName", "Nimbus Goods Co."),
                Map.entry("storeAddress", "742 Market Street, San Francisco, CA 94103"),
                Map.entry("storeEmail", "support@nimbusgoods.example"),
                Map.entry("orderId", "NG-2026-0041872"),
                Map.entry("orderDate", "July 1, 2026"),
                Map.entry("paymentMethod", "Visa 4242"),
                Map.entry("customerName", "Jordan Rivera"),
                Map.entry("customerEmail", "jordan.rivera@example.com"),
                Map.entry("currency", "$"),
                Map.entry("items", List.of(
                        Map.of("name", "Aurora Wireless Headphones", "sku", "AUR-BLK-01", "qty", 1, "price", 129.00, "total", 129.00),
                        Map.of("name", "USB-C Braided Cable (2m)", "sku", "CBL-USBC-2M", "qty", 2, "price", 14.50, "total", 29.00),
                        Map.of("name", "Travel Hard Case", "sku", "CASE-TRV-01", "qty", 1, "price", 39.50, "total", 39.50))),
                Map.entry("subtotal", 197.50),
                Map.entry("discount", 20.00),
                Map.entry("shipping", 6.99),
                Map.entry("tax", 15.80),
                Map.entry("total", 200.29));
    }

    @Test
    void rendersReceiptThroughFfi() throws Exception {
        assumeTrue(Files.exists(libPath()), "libblitz_render.so not present at " + libPath() + " — skipping FFI test");

        // Clean document produced for the Blitz backend by the gateway (no JS, no leading comment).
        String html = new TemplateService().blitzDocument("receipt-fragment", sampleReceipt());

        try (BlitzRenderer renderer = new BlitzRenderer(libPath())) {
            renderer.init(0);

            // 4-colour indexed PNG (the production output format).
            byte[] indexed = renderer.render(html, 480, 2.0, true, true);
            assertValidPng(indexed);
            assertTrue(indexed.length > 500, "indexed PNG suspiciously small: " + indexed.length);
            Files.createDirectories(Path.of("target"));
            Files.write(Path.of("target/receipt-blitz-ffi.png"), indexed);

            // RGBA path also works.
            byte[] rgba = renderer.render(html, 480, 2.0, true, false);
            assertValidPng(rgba);
            Files.write(Path.of("target/receipt-blitz-ffi-rgba.png"), rgba);

            System.out.printf("FFI render OK: indexed=%d bytes, rgba=%d bytes%n", indexed.length, rgba.length);
        }
    }

    @Test
    void concurrentRendersAreThreadSafe() throws Exception {
        assumeTrue(Files.exists(libPath()), "libblitz_render.so not present — skipping FFI concurrency test");

        String html = new TemplateService().blitzDocument("receipt-fragment", sampleReceipt());
        int threads = 8;

        try (BlitzRenderer renderer = new BlitzRenderer(libPath())) {
            renderer.init(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Callable<byte[]>> jobs = IntStream.range(0, threads * 4)
                        .<Callable<byte[]>>mapToObj(i -> () -> renderer.render(html, 480, 2.0, true, true))
                        .toList();
                for (Future<byte[]> f : pool.invokeAll(jobs)) {
                    assertValidPng(f.get());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static void assertValidPng(byte[] png) {
        assertTrue(png != null && png.length >= 8, "not a PNG (too short)");
        byte[] header = new byte[8];
        System.arraycopy(png, 0, header, 0, 8);
        assertArrayEquals(PNG_MAGIC, header, "missing PNG signature");
    }
}
