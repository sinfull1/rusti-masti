//! Blitz (Stylo) HTML -> PNG renderer, exposed over a C ABI so the Micronaut JVM can call it in-process
//! via the Java 25 Foreign Function & Memory API (Panama). Each call is independent, thread-safe and
//! re-entrant: the JVM drives parallelism by calling `blitz_render` from many threads at once (proven
//! at 14 threads in the benchmark). Panics are caught at the boundary so they never unwind into Java.

use anyrender::{PaintScene as _, render_to_buffer};
use anyrender_vello_cpu::VelloCpuImageRenderer;
use blitz_dom::{DocumentConfig, util::Color};
use blitz_html::HtmlDocument;
use blitz_paint::paint_scene;
use blitz_traits::shell::{ColorScheme, Viewport};
use peniko::Fill;
use peniko::kurbo::Rect;
use std::panic::{self, AssertUnwindSafe};

// 4-colour output palette (index order = 2-bit codes): 0=black 1=white 2=red 3=blue.
const PALETTE: [(u8, u8, u8); 4] = [(0, 0, 0), (255, 255, 255), (255, 0, 0), (0, 0, 255)];
// Ported verbatim from the Java WebViewCell quantiser so both backends produce identical output.
const SAT_THRESHOLD: i32 = 60;
const LUM_THRESHOLD: i32 = 180;

/// Optional one-time warmup hook (font system, etc.). Currently a no-op; returns 0.
#[no_mangle]
pub extern "C" fn blitz_init(_threads: u32) -> i32 {
    0
}

/// Render `html` to a PNG. On success (return 0) writes an owned buffer to `*out_ptr`/`*out_len`
/// which the caller MUST release with `blitz_free`. Non-zero returns: 1 bad args, 2 non-UTF8 html,
/// 3 render/encode error, 4 panic caught.
///
/// # Safety
/// `html` must point to `html_len` valid bytes; the out-pointers must be valid for writes.
#[no_mangle]
pub unsafe extern "C" fn blitz_render(
    html: *const u8,
    html_len: usize,
    width: u32,
    scale: f64,
    auto_height: i32,
    quantize: i32,
    out_ptr: *mut *mut u8,
    out_len: *mut usize,
    out_w: *mut u32,
    out_h: *mut u32,
) -> i32 {
    if html.is_null() || out_ptr.is_null() || out_len.is_null() {
        return 1;
    }
    let html_bytes = std::slice::from_raw_parts(html, html_len);
    let html_str = match std::str::from_utf8(html_bytes) {
        Ok(s) => s,
        Err(_) => return 2,
    };

    let outcome = panic::catch_unwind(AssertUnwindSafe(|| {
        render_impl(html_str, width, scale, auto_height != 0, quantize != 0)
    }));

    match outcome {
        Ok(Ok((png, w, h))) => {
            // Hand ownership to the caller. cap == len via into_boxed_slice, so blitz_free can rebuild it.
            let boxed = png.into_boxed_slice();
            let len = boxed.len();
            *out_ptr = Box::into_raw(boxed) as *mut u8;
            *out_len = len;
            if !out_w.is_null() {
                *out_w = w;
            }
            if !out_h.is_null() {
                *out_h = h;
            }
            0
        }
        Ok(Err(_)) => 3,
        Err(_) => 4,
    }
}

/// Free a buffer previously returned by `blitz_render`.
///
/// # Safety
/// `ptr`/`len` must be exactly what a prior `blitz_render` produced, freed at most once.
#[no_mangle]
pub unsafe extern "C" fn blitz_free(ptr: *mut u8, len: usize) {
    if !ptr.is_null() && len > 0 {
        let slice = std::slice::from_raw_parts_mut(ptr, len);
        drop(Box::from_raw(slice as *mut [u8]));
    }
}

fn render_impl(
    html: &str,
    width: u32,
    scale: f64,
    auto_height: bool,
    quantize: bool,
) -> Result<(Vec<u8>, u32, u32), String> {
    let vp_w = (width as f64 * scale) as u32;
    let vp_h = (800.0 * scale) as u32;

    let mut document = HtmlDocument::from_html(
        html,
        DocumentConfig {
            viewport: Some(Viewport::new(vp_w, vp_h, scale as f32, ColorScheme::Light)),
            ..Default::default()
        },
    );
    // Resolve styles + layout. Our HTML is self-contained (no external assets) so one pass suffices.
    document.as_mut().resolve(0.0);

    let content_h = document.as_ref().root_element().final_layout.size.height as f64;
    let rw = vp_w.max(1);
    let rh = if auto_height {
        ((content_h.max(1.0).min(8000.0)) * scale) as u32
    } else {
        vp_h
    }
    .max(1);

    let rgba = render_to_buffer::<VelloCpuImageRenderer, _>(
        |scene| {
            scene.fill(
                Fill::NonZero,
                Default::default(),
                Color::WHITE,
                Default::default(),
                &Rect::new(0.0, 0.0, rw as f64, rh as f64),
            );
            paint_scene(scene, document.as_mut(), scale, rw, rh, 0, 0);
        },
        rw,
        rh,
    );

    let png = if quantize {
        encode_indexed(&rgba, rw, rh)?
    } else {
        encode_rgba(&rgba, rw, rh)?
    };
    Ok((png, rw, rh))
}

fn encode_rgba(rgba: &[u8], w: u32, h: u32) -> Result<Vec<u8>, String> {
    let mut out = Vec::with_capacity(64 * 1024);
    {
        let mut enc = png::Encoder::new(&mut out, w, h);
        enc.set_color(png::ColorType::Rgba);
        enc.set_depth(png::BitDepth::Eight);
        let mut wr = enc.write_header().map_err(|e| e.to_string())?;
        wr.write_image_data(rgba).map_err(|e| e.to_string())?;
        wr.finish().map_err(|e| e.to_string())?;
    }
    Ok(out)
}

/// 2-bit indexed PNG limited to {black,white,red,blue} — identical mapping to the Java quantiser.
fn encode_indexed(rgba: &[u8], w: u32, h: u32) -> Result<Vec<u8>, String> {
    let wu = w as usize;
    let bytes_per_row = (wu + 3) / 4; // 4 pixels per byte at 2 bits each, rows byte-aligned
    let mut packed = vec![0u8; bytes_per_row * h as usize];
    for y in 0..h as usize {
        for x in 0..wu {
            let i = (y * wu + x) * 4;
            let idx = map_to_palette(rgba[i], rgba[i + 1], rgba[i + 2]);
            let shift = 6 - (x % 4) * 2; // MSB-first packing, matching PNG bit order
            packed[y * bytes_per_row + x / 4] |= idx << shift;
        }
    }
    let palette: Vec<u8> = PALETTE.iter().flat_map(|&(r, g, b)| [r, g, b]).collect();
    let mut out = Vec::with_capacity(32 * 1024);
    {
        let mut enc = png::Encoder::new(&mut out, w, h);
        enc.set_color(png::ColorType::Indexed);
        enc.set_depth(png::BitDepth::Two);
        enc.set_palette(palette);
        let mut wr = enc.write_header().map_err(|e| e.to_string())?;
        wr.write_image_data(&packed).map_err(|e| e.to_string())?;
        wr.finish().map_err(|e| e.to_string())?;
    }
    Ok(out)
}

fn map_to_palette(r: u8, g: u8, b: u8) -> u8 {
    let (ri, gi, bi) = (r as i32, g as i32, b as i32);
    let sat = ri.max(gi).max(bi) - ri.min(gi).min(bi);
    if sat <= SAT_THRESHOLD {
        let lum = (299 * ri + 587 * gi + 114 * bi) / 1000;
        return if lum < LUM_THRESHOLD { 0 } else { 1 };
    }
    let mut best = 0u8;
    let mut best_dist = i64::MAX;
    for (i, &(pr, pg, pb)) in PALETTE.iter().enumerate() {
        let dr = ri - pr as i32;
        let dg = gi - pg as i32;
        let db = bi - pb as i32;
        let d = (dr * dr + dg * dg + db * db) as i64;
        if d < best_dist {
            best_dist = d;
            best = i as u8;
        }
    }
    best
}
