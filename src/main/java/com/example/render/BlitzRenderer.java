package com.example.render;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * In-process binding to the Rust Blitz renderer ({@code libblitz_render.so}) via the Java 25 Foreign
 * Function &amp; Memory API (Panama). This is the fast rendering backend: the JVM calls
 * {@code blitz_render} directly — no socket, no serialization — and drives parallelism by invoking
 * {@link #render} concurrently from many threads (the native call is thread-safe and re-entrant).
 *
 * <p>Requires {@code --enable-native-access=ALL-UNNAMED} (JEP 472) at launch.
 *
 * <p>C ABI (see {@code render-ffi/src/lib.rs}):
 * <pre>
 *   int32_t blitz_init(uint32_t threads);
 *   int32_t blitz_render(const uint8_t* html, size_t html_len,
 *                        uint32_t width, double scale, int32_t auto_height, int32_t quantize,
 *                        uint8_t** out_ptr, size_t* out_len, uint32_t* out_w, uint32_t* out_h);
 *   void    blitz_free(uint8_t* ptr, size_t len);
 * </pre>
 */
public final class BlitzRenderer implements AutoCloseable {

    // size_t is 8 bytes on the 64-bit Linux target.
    private static final ValueLayout.OfLong C_SIZE_T = ValueLayout.JAVA_LONG;

    private final Arena libArena;
    private final MethodHandle hInit;
    private final MethodHandle hRender;
    private final MethodHandle hFree;

    public BlitzRenderer(Path libraryPath) {
        Linker linker = Linker.nativeLinker();
        this.libArena = Arena.ofShared();
        SymbolLookup lib = SymbolLookup.libraryLookup(libraryPath, libArena);

        this.hInit = linker.downcallHandle(
                lib.find("blitz_init").orElseThrow(() -> missing("blitz_init")),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        this.hRender = linker.downcallHandle(
                lib.find("blitz_render").orElseThrow(() -> missing("blitz_render")),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,   // html
                        C_SIZE_T,              // html_len
                        ValueLayout.JAVA_INT,  // width
                        ValueLayout.JAVA_DOUBLE, // scale
                        ValueLayout.JAVA_INT,  // auto_height
                        ValueLayout.JAVA_INT,  // quantize
                        ValueLayout.ADDRESS,   // out_ptr (uint8_t**)
                        ValueLayout.ADDRESS,   // out_len (size_t*)
                        ValueLayout.ADDRESS,   // out_w (uint32_t*)
                        ValueLayout.ADDRESS)); // out_h (uint32_t*)

        this.hFree = linker.downcallHandle(
                lib.find("blitz_free").orElseThrow(() -> missing("blitz_free")),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, C_SIZE_T));
    }

    /** Optional warmup (font system / thread pool). Safe to skip. */
    public void init(int threads) {
        try {
            int rc = (int) hInit.invoke(threads);
            if (rc != 0) {
                throw new RenderException("blitz_init failed rc=" + rc);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RenderException("blitz_init invoke failed", t);
        }
    }

    /**
     * Render {@code html} to PNG bytes. Thread-safe: call concurrently from many threads.
     *
     * @param quantize true → 2-bit indexed {black,white,red,blue} PNG; false → RGBA8 PNG.
     */
    public byte[] render(String html, int width, double scale, boolean autoHeight, boolean quantize) {
        if (html == null || html.isBlank()) {
            throw new RenderException("html is required");
        }
        byte[] htmlBytes = html.getBytes(StandardCharsets.UTF_8);
        // Confined arena: thread-local, cheap, auto-freed. Each render owns its own scratch memory.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment htmlSeg = arena.allocate(htmlBytes.length);
            MemorySegment.copy(htmlBytes, 0, htmlSeg, ValueLayout.JAVA_BYTE, 0, htmlBytes.length);

            MemorySegment outPtr = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment outLen = arena.allocate(C_SIZE_T);
            MemorySegment outW = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment outH = arena.allocate(ValueLayout.JAVA_INT);

            int rc;
            try {
                rc = (int) hRender.invoke(htmlSeg, (long) htmlBytes.length, width, scale,
                        autoHeight ? 1 : 0, quantize ? 1 : 0, outPtr, outLen, outW, outH);
            } catch (Throwable t) {
                throw new RenderException("blitz_render invoke failed", t);
            }
            if (rc != 0) {
                throw new RenderException("blitz_render failed rc=" + rc
                        + " (1=bad args, 2=non-utf8, 3=render/encode, 4=panic)");
            }

            MemorySegment pngAddr = outPtr.get(ValueLayout.ADDRESS, 0);
            long pngLen = outLen.get(C_SIZE_T, 0);
            if (pngAddr.equals(MemorySegment.NULL) || pngLen <= 0) {
                throw new RenderException("blitz_render returned empty buffer");
            }
            try {
                // The returned address has zero size until reinterpreted; then copy out to a heap array.
                byte[] png = pngAddr.reinterpret(pngLen).toArray(ValueLayout.JAVA_BYTE);
                return png;
            } finally {
                try {
                    hFree.invoke(pngAddr, pngLen);
                } catch (Throwable t) {
                    // A free failure must not mask a successful render, but should not be silent either.
                    throw new RenderException("blitz_free invoke failed", t);
                }
            }
        }
    }

    @Override
    public void close() {
        libArena.close();
    }

    private static IllegalStateException missing(String symbol) {
        return new IllegalStateException("symbol not found in libblitz_render: " + symbol);
    }
}
