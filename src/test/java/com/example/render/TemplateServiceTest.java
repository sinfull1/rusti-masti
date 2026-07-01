package com.example.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the Thymeleaf receipt template against a representative payload WITHOUT booting JavaFX
 * (which needs a display). Renders the HTML, writes it to target/ for eyeballing, and asserts that
 * the model bindings + number formatting produced the expected text.
 */
class TemplateServiceTest {

    private static Map<String, Object> sampleReceipt() {
        return Map.ofEntries(
                Map.entry("storeName", "Nimbus Goods Co."),
                Map.entry("storeAddress", "742 Market Street, San Francisco, CA 94103"),
                Map.entry("storeEmail", "support@nimbusgoods.example"),
                Map.entry("orderId", "NG-2026-0041872"),
                Map.entry("orderDate", "July 1, 2026"),
                Map.entry("paymentMethod", "Visa •••• 4242"),
                Map.entry("customerName", "Jordan Rivera"),
                Map.entry("customerEmail", "jordan.rivera@example.com"),
                Map.entry("currency", "$"),
                Map.entry("items", List.of(
                        Map.of("name", "Aurora Wireless Headphones", "sku", "AUR-BLK-01",
                                "qty", 1, "price", 129.00, "total", 129.00),
                        Map.of("name", "USB-C Braided Cable (2m)", "sku", "CBL-USBC-2M",
                                "qty", 2, "price", 14.50, "total", 29.00),
                        Map.of("name", "Travel Hard Case", "sku", "CASE-TRV-01",
                                "qty", 1, "price", 39.50, "total", 39.50))),
                Map.entry("subtotal", 197.50),
                Map.entry("discount", 20.00),
                Map.entry("shipping", 6.99),
                Map.entry("tax", 15.80),
                Map.entry("total", 200.29));
    }

    @Test
    void rendersReceiptHtml() throws Exception {
        TemplateService svc = new TemplateService();
        // fullDocument = shell (CSS) + fragment; exercises both the raw shell read and Thymeleaf binding.
        String html = svc.fullDocument("receipt-fragment", sampleReceipt());

        // Written for manual inspection: open target/receipt-sample.html in a browser.
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/receipt-sample.html"), html);

        assertTrue(html.contains("__render"), "shell JS injector present");
        assertTrue(html.contains("Nimbus Goods Co."), "store name bound");
        assertTrue(html.contains("NG-2026-0041872"), "order id bound");
        assertTrue(html.contains("Aurora Wireless Headphones"), "line item bound");
        assertTrue(html.contains("$129.00"), "currency + price formatting");
        assertTrue(html.contains("$200.29"), "grand total formatting");
        assertTrue(html.contains("-$20.00"), "discount row rendered with minus sign");
        assertTrue(html.contains("Thank you for your order!"), "footer rendered");
    }
}