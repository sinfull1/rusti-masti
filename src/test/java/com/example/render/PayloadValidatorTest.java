package com.example.render;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayloadValidatorTest {

    private static PayloadValidator validator() {
        PayloadValidator v = new PayloadValidator();
        v.maxListSize = 1000;
        v.maxMapSize = 200;
        v.maxStringLength = 8192;
        v.maxDepth = 12;
        v.maxTotalNodes = 20000;
        return v;
    }

    @Test
    void acceptsNormalPayload() {
        Map<String, Object> ok = Map.of(
                "storeName", "Nimbus Goods Co.",
                "items", List.of(Map.of("name", "A", "qty", 1, "price", 1.0)),
                "total", 200.29);
        assertDoesNotThrow(() -> validator().validate(ok));
    }

    @Test
    void rejectsOversizedList() {
        List<Object> big = IntStream.range(0, 1001).<Object>mapToObj(i -> Map.of("name", "x")).toList();
        assertThrows(InvalidRequestException.class,
                () -> validator().validate(Map.of("items", big)));
    }

    @Test
    void rejectsLongString() {
        String huge = "x".repeat(8193);
        assertThrows(InvalidRequestException.class,
                () -> validator().validate(Map.of("storeName", huge)));
    }

    @Test
    void rejectsDeepNesting() {
        Object node = "leaf";
        for (int i = 0; i < 15; i++) {
            node = Map.of("k", node);
        }
        Object deep = node;
        assertThrows(InvalidRequestException.class, () -> validator().validate(deep));
    }

    @Test
    void rejectsTooManyTotalNodes() {
        // Many small lists that each pass the per-list cap but blow the aggregate node budget.
        List<Object> outer = IntStream.range(0, 500)
                .<Object>mapToObj(i -> Collections.nCopies(60, "n"))
                .toList();
        assertThrows(InvalidRequestException.class,
                () -> validator().validate(Map.of("items", outer)));
    }
}
