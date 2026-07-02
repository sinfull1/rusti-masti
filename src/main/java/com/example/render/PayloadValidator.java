package com.example.render;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;

/**
 * Bounds the size/complexity of a render payload BEFORE it reaches the template, so a small request
 * body cannot expand into an enormous DOM (which would exhaust CPU/memory during layout — the
 * request-size limit alone doesn't prevent this: 4&nbsp;MB of JSON can encode tens of thousands of
 * list items). Recursively caps: any list length, any map size, any string length, and nesting depth.
 *
 * <p>Limits are configurable ({@code render.limits.*}); violations throw {@link InvalidRequestException}
 * → HTTP 400.
 */
@Singleton
public class PayloadValidator {

    @Value("${render.limits.max-list-size:1000}")
    int maxListSize;

    @Value("${render.limits.max-map-size:200}")
    int maxMapSize;

    @Value("${render.limits.max-string-length:8192}")
    int maxStringLength;

    @Value("${render.limits.max-depth:12}")
    int maxDepth;

    @Value("${render.limits.max-total-nodes:20000}")
    int maxTotalNodes;

    public void validate(Object payload) {
        walk(payload, 0, new int[]{0});
    }

    private void walk(Object node, int depth, int[] nodeCount) {
        if (depth > maxDepth) {
            throw new InvalidRequestException("payload nesting exceeds max depth " + maxDepth);
        }
        if (++nodeCount[0] > maxTotalNodes) {
            throw new InvalidRequestException("payload exceeds max total nodes " + maxTotalNodes);
        }
        switch (node) {
            case null -> { /* ok */ }
            case String s -> {
                if (s.length() > maxStringLength) {
                    throw new InvalidRequestException(
                            "string field exceeds max length " + maxStringLength + " (" + s.length() + ")");
                }
            }
            case Map<?, ?> map -> {
                if (map.size() > maxMapSize) {
                    throw new InvalidRequestException("object exceeds max size " + maxMapSize);
                }
                for (Object v : map.values()) {
                    walk(v, depth + 1, nodeCount);
                }
            }
            case List<?> list -> {
                if (list.size() > maxListSize) {
                    throw new InvalidRequestException(
                            "array exceeds max size " + maxListSize + " (" + list.size() + ")");
                }
                for (Object e : list) {
                    walk(e, depth + 1, nodeCount);
                }
            }
            default -> { /* numbers, booleans: bounded, ignore */ }
        }
    }
}
