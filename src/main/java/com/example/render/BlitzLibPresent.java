package com.example.render;

import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * True when the Rust render library ({@code render.blitz.lib-path}) actually exists on disk, so the
 * {@link BlitzRenderer} bean is only created where the native lib is present (production image / the
 * FFI container) and absent on plain dev builds — the dispatcher then falls back to WebView.
 */
public final class BlitzLibPresent implements Condition {
    @Override
    public boolean matches(ConditionContext context) {
        String path = context.getProperty("render.blitz.lib-path", String.class)
                .orElse("/app/libblitz_render.so");
        return Files.exists(Path.of(path));
    }
}
