# HTML → PNG render service — design & feasibility

Render self-contained HTML (CSS inlined) in a JavaFX **WebView**, snapshot it, return a PNG.
Targets: **~10 images/sec**, **≤ 500 MB RAM total**, headless server.

## Request flow

```
POST /render (JSON payload — e.g. a receipt: storeName, items[], totals …)
        │
   RenderController        @ExecuteOn(BLOCKING) — parks for a free cell = backpressure
        │  data-only request; template + geometry come from application.properties
   TemplateService         Thymeleaf: render.template (templates/<name>.html) + payload → HTML
        │                  parsed template is CACHED; payload exposed as `receipt`
   RenderService           admission Semaphore (pool.size + queue.max) → 503 when full
        │  borrow WebViewCell from bounded pool
        ▼
   [JavaFX Application Thread — the ONE thread]\
   
     cell.render(): loadContent → LoadWorker SUCCEEDED → (auto-height: measure scrollHeight)
                    → async snapshot(pulse-safe)
        │  WritableImage
        ▼
   png-encode pool         SwingFXUtils → ImageIO PNG   (kept OFF the JAT)
        │
   return image/png ; recycle cell on JAT ; release permit
```

Key components: `JavaFxPlatform` (starts the toolkit once, headless), `TemplateService` (JSON→HTML via
Thymeleaf), `WebViewCell` (one WebView+Scene, JAT-only, auto-height), `RenderService` (pool +
admission + timeout + recycling), `RenderController`.

**Templating.** The request carries only data; the layout is a Thymeleaf template selected by
`render.template` in `application.properties` (default `receipt` → `templates/receipt.html`). Swapping
layouts is a config change. The payload binds as `receipt` (a `Map` of the JSON body), so templates use
plain property syntax (`${receipt.storeName}`, `${item.total}`). The `TemplateEngine` caches parsed
templates — parsing per request would not survive the throughput target. A sample body is in
`src/main/resources/sample-receipt.json`; height follows content via `render.auto-height`.

## The three hard constraints, and how the design meets them

### 1. One JavaFX Application Thread (JAT) — the real throughput ceiling
There is exactly **one** JavaFX runtime and **one** JAT per JVM. Every WebView/WebEngine/Scene/snapshot
call must run on it. So layout + paint + snapshot for *every* image funnel through a single thread —
you cannot get CPU-parallel rendering from one JVM no matter how many WebViews you create.

Ceiling ≈ `1000 ms / (per-image JAT time)`. For **simple, inlined** pages the JAT-bound work
(parse + layout + paint + snapshot) is roughly **40–90 ms** under software rendering ⇒ **~11–25 img/s**.
For heavy pages (large DOM, web fonts, big viewports) it climbs past 150 ms ⇒ you fall **below** 10/s.

Design responses:
- **PNG encoding is moved off the JAT** (`png-encode` pool). Encoding a 1200×630 PNG is 5–20 ms of
  pure CPU; doing it on the JAT alone would blow the budget.
- **Async `snapshot(...)`** is used, not the synchronous form. It captures on the next render pulse,
  which (a) avoids the blank/half-painted-frame race and (b) lets the JAT interleave other cells'
  work while waiting.
- A **small cell pool (2)** overlaps WebKit's async *load* phase of cell A with the *snapshot* of
  cell B. Beyond ~2 cells you buy almost no throughput (JAT still serial) but pay full RAM per engine.

### 2. ≤ 500 MB RAM — the binding constraint, and it's mostly *native*
WebKit is the memory hog, and it is largely **off-heap/native**, so `-Xmx` does **not** bound it.
RSS is dominated by native allocations. Rough resident budget:

| Component                         | Approx RSS |
|-----------------------------------|-----------|
| JVM base + Micronaut/Netty        | 80–140 MB |
| JavaFX runtime + Prism (sw)       | 40–70 MB  |
| **WebKit engine, per WebView**    | **100–200 MB each** |
| Transient image buffers (1200×630 ARGB ≈ 3 MB) | few MB |

⇒ Under 500 MB you can realistically run **1, maybe 2** WebView engines. That is *the* reason
`render.pool.size` defaults to 2 and should not be pushed higher without measuring RSS.

Design responses:
- **`render.pool.size`** caps resident engines (the dominant cost).
- **Admission `Semaphore`** (`pool.size + queue.max`) sheds excess load as **503** instead of letting
  image buffers and in-flight jobs accumulate to OOM.
- **Cell recycling** (`render.recycle-after`): WebKit's native heap creeps across loads (caches, JIT,
  DOM retention). Parking on `about:blank` releases page state; for a hard RSS reset the pool can be
  extended to rebuild the cell (new WebView) entirely. **Monitor RSS over hours — creep is the most
  likely thing that breaks a long-running 500 MB deployment.**
- `run.sh` keeps the **Java heap small** (`MaxRAMPercentage=45`, SerialGC, small metaspace/stacks) to
  leave headroom for native memory, and relies on a **cgroup/container memory limit** as the true
  ceiling (Java's `-Xmx` can't enforce it here).

### 3. Headless rendering (no display, no GPU)
**Prism software** pipeline (`prism.order=sw`) — no GPU. WebKit already rasterises page content on
the CPU, so `sw` only affects scene-graph compositing — acceptable, at some speed cost.
For the display, the **default is Xvfb** (a virtual X server): reliable for WebView and needs no
extra Java dependency — see `run.sh`. The Glass platform is chosen by the launcher, not hardcoded.
**Alternative:** pure-Java **Monocle Headless** (no X server) — but Gluon's `monocle` artifact is not
on Maven Central, so it requires adding the Gluon repo + dependency (see `pom.xml`) and the
`-Dglass.platform=Monocle -Dmonocle.platform=Headless` flags (commented in `run.sh`).

## Output format — 4-colour indexed PNG, no anti-aliasing

The snapshot is quantised to a **2-bit indexed PNG** with a fixed 4-colour palette: **black, white,
red, blue** (`WebViewCell.encodePng`). Mapping rule:
- **Near-neutral pixels** (low saturation, `sat ≤ 60`) → hard black/white by a luminance threshold
  (`lum < 180 → black`). This is what removes anti-aliasing robustly: AA/sub-pixel edge pixels snap to
  a solid colour instead of surviving as grey or as WebKit's faint blue/red sub-pixel fringe. The high
  threshold keeps mid/light-grey text as solid black rather than dropping it to white.
- **Saturated pixels** → nearest of {black, white, red, blue} by squared-RGB distance, so intentional
  red/blue survive (e.g. the discount line).

Thresholds are constants in `WebViewCell` (`SAT_THRESHOLD`, `LUM_THRESHOLD`). The snapshot uses an
opaque white fill so never-painted areas don't quantise to black.

**Template implication:** a 4-colour palette cannot represent light-grey-on-white — it is either black
or white. So the receipt template was recoloured to solid colours (black text, **red** discount);
blue is in the palette but currently unused (available for headers/accents on request). Any template
targeting this encoder should avoid greys and use only strong black/red/blue on white.

## Empirical results — containerised, hard 500 MB cap (podman `--memory=500m`)

Measured on this box: image `receipt-render`, JRE 25 + Xvfb + software Prism, rendering the receipt
template at 480px × 2.0 scale (960×1388 PNG, ~188 KB). Load driver: `wrk`. See `Containerfile`,
`docker-entry.sh`, `loadtest/`.

| Config                                  | Throughput   | Peak RSS      | Outcome                    |
|-----------------------------------------|--------------|---------------|----------------------------|
| pool=1, settle=150 ms, RGBA PNG         | **4.0 img/s**  | 430 MB        | serialised on one cell     |
| pool=2, settle=150 ms, RGBA PNG         | **7.1 img/s**  | 500 MB (@cap) | survived, no OOM           |
| pool=2, settle=60 ms, RGBA PNG          | **12.4 img/s** | 500 MB (@cap) | meets 10/s target, no OOM  |
| **pool=2, settle=80 ms, 4-colour PNG**  | **14.1 img/s** | **418 MB**    | **best**: meets target *with* headroom |

The 4-colour indexed output (2-bit, ~15 KB vs ~190 KB RGBA) both raises throughput and *lowers* peak
RSS — smaller encode buffers and far less bytes to transfer — turning the previous at-the-cap result
into a comfortable ~80 MB margin. Sequential latency also dropped to ~140 ms.

Overload test — 60 concurrent requests vs. 18 admission permits (pool 2 + queue 16): **18 × 200 +
42 × 503**, no OOM (repeatable). Backpressure sheds load exactly at the configured limit.

### Inject mode — resident template, JS body-swap (throughput optimisation)

Instead of a full `loadContent` per request, the CSS + a `__render(base64)` JS injector are loaded into
each WebView **once** (the *shell*); each subsequent render is a single synchronous `executeScript`
that swaps `document.body.innerHTML` and returns the new height — no navigation lifecycle, no
parse-from-scratch, no `LoadWorker` round-trip. Templates are split: `receipt-shell.html` (CSS + JS,
read raw) and `receipt-fragment.html` (data-bound body, Thymeleaf). `render.mode = inject | reload`.

Output is **byte-identical** to reload mode (same 15,125-byte PNG, same colour histogram) — verified,
and 40/40 images captured under concurrency were non-degraded.

A/B and the settle sweep it unlocked (pool=2, 500 MB cap, same session):

| Mode / settle          | Throughput    | Correctness under load |
|------------------------|---------------|------------------------|
| reload, settle=80 ms   | 10.4 img/s    | ok (baseline)          |
| inject, settle=80 ms   | 15.2 img/s    | ok (+46% from skipping load) |
| inject, settle=40 ms   | 22.5 img/s    | ok                     |
| inject, settle=30 ms (**shipped**) | **25.4 img/s** | **40/40 ok, soak ok** |
| inject, settle=20 ms   | 29.4 img/s    | 40/40 ok (less margin) |
| inject, settle=10 ms   | 34.7 img/s    | single-render only — not load-validated |

Two wins compound: (1) skipping `loadContent` frees the JAT to interleave cells (+46% at equal settle),
and (2) inject swaps *already-styled* content so it paints faster, letting the settle — **the dominant
per-image cost** — drop from 80→30 ms. Net **14.1 → 25.4 img/s (~1.8×)** at the same 500 MB cap.
`render.inject-settle-ms` is the main throughput lever; 20 ms was validated under load, 30 shipped for
margin. Anon RSS during a 3-min / 4,566-render soak stayed flat in a [339, 364] MB band with 24 hard
recycles firing — the inject hot path and periodic WebView rebuild coexist (a recycle resets the shell,
the next render reloads it once).

**Feasibility verdict: yes — the biggest single throughput win in this project, at no memory cost and
no output change.** Caveat: inject assumes a fixed template whose *structure* is constant and only the
*data* varies (true for receipts). It is not a minimal DOM diff — each render rebuilds the body from the
server-rendered fragment; the saving is skipping document parse/navigation, not skipping DOM work.

### Integrated stack — Micronaut + Blitz (Rust) via Java FFM, single process, 500 MB

The production shape: one JVM process, HTTP → Thymeleaf → `RenderDispatcher` → `BlitzRenderer`
(Java 25 FFM downcall) → Rust `libblitz_render.so` → 4-colour PNG. WebView is retained as a lazy
`auto` fallback (JavaFX never starts on the Blitz path). Load driver: `wrk`; concurrency bounded by a
fixed blocking pool (`micronaut.executors.blocking.n-threads`).

| Blocking threads | Throughput     | Peak RSS | Outcome |
|------------------|----------------|----------|---------|
| 8                | 274 img/s      | 296 MB   | no OOM  |
| **12**           | **~290–322 img/s** | **363 MB** | **best**, no OOM |
| 16               | 234 img/s      | 413 MB   | CPU oversubscription drops throughput |

At 12 threads: avg latency 74 ms, correctness intact under load (clean 4-colour receipt), no OOM.

Constrained to **2 CPUs + 500 MB** (a realistic small container), the bottleneck flips from memory to
CPU — software rasterization is CPU-bound:

| Blocking threads | Throughput | Latency | Peak RSS |
|------------------|------------|---------|----------|
| **2**            | **94.5 img/s** | 84 ms  | 191 MB  |
| 3                | 78 img/s   | 153 ms  | 165 MB  |
| 4                | 53 img/s   | 301 ms  | 178 MB  |

Optimal concurrency = **core count** (2 threads on 2 CPUs → ~95 img/s ≈ **47 img/s per core**);
oversubscribing hurts (context-switch/contention on CPU-bound work). Memory is a non-constraint at
this CPU level (~190 MB peak, big headroom) — you can't trade the spare RAM for throughput because
the cores are saturated. Capacity planning: scale cores, size the blocking pool to core count.

Squeezing to **2 CPU + 200 MB** (near the memory floor):

| Config                | Throughput | Peak RSS / 200 cap | Margin |
|-----------------------|------------|--------------------|--------|
| threads=2             | **87 img/s** | 195 MB           | tight (~5 MB) |
| threads=1             | 50 img/s   | 147 MB             | comfortable |

200 MB is viable — throughput barely drops from the 500 MB run (95→87) — but at threads=2 the peak
sits right on the cap. Peak RSS is **dominated by native/off-heap** (Blitz working set + metaspace +
Netty direct buffers), not the Java heap: shrinking `-Xmx`/heap % or switching GC does **not** lower it
(SerialGC + smaller heap actually cut throughput). So the practical floor for 2-thread operation is
~190–200 MB. For safe margin in 200 MB use **threads=1** (~50 img/s, 147 MB); for threads=2 comfort,
give it ~256 MB.

**vs. the WebView service (25 img/s @ ~350 MB): ~12× the throughput at lower memory — and ~30× the
original 10 img/s target.** Findings: (1) the JVM/HTTP/Thymeleaf/**FFM** layer adds negligible
overhead — in-process FFM has no socket/serialization cost, so throughput is render-bound, not
stack-bound; (2) sweet spot ~8–12 concurrent renders, beyond which software rasterization contends on
CPU; (3) memory scales ~linearly with concurrency and stays well inside 500 MB.

**Admission control (implemented).** The Blitz path in `RenderDispatcher` bounds concurrency with a
two-level semaphore: `render.blitz.max-concurrent` simultaneous native renders (default =
`availableProcessors`, honouring the CPU limit) + `render.blitz.max-queue` waiters; beyond that,
`tryAcquire` fails fast → `RejectedRenderException` → **HTTP 503** (overload does *not* fall back to
WebView). Validated: under sustained 100-connection overload (2 CPU, `max-concurrent=2`, `max-queue=16`),
the app served ~1,250 renders (200) at capacity and **shed 866 as 503**, no crash, RSS bounded (217 MB).

Operational notes learned here:
- **Leave the blocking executor at its default (virtual threads).** Micronaut 5 on Java 21+ backs
  `@ExecuteOn(BLOCKING)` with virtual threads — cheap, no fixed stacks. Overriding it to a large *fixed*
  pool blows memory (each platform thread stack ~1 MB; 200 of them OOM'd a 200 MB box). The semaphore,
  not the pool, is the concurrency bound. (`micronaut.executors.blocking.type=virtual` is invalid — the
  default already is virtual; don't set it.)
- **Size `max-concurrent` ≈ core count** (CPU-bound); `max-queue` can be generous since waiters are
  parked virtual threads (cheap).

### Soak test — memory creep over sustained load

8-minute continuous load (pool=2, settle=80, recycle-after=200): **6,941 renders @ 14.46 img/s**,
2 timeouts (0.03%), no OOM. Memory sampled every 3 s (cgroup `memory.current` and — the number that
actually matters — `anon` from `memory.stat`, since `current` includes reclaimable page cache):

| Window                | anon RSS            | trend         |
|-----------------------|---------------------|---------------|
| warmup (0–120 s)      | 208 → 345 MB        | +8.9 MB/min (ramp, not creep) |
| **steady (120 s–end)**| 345 → 359 MB, band [335, 363] | **+0.6 MB/min ≈ flat** |

So once warm, the **unreclaimable footprint plateaus at ~360–370 MB** — no unbounded creep. `current`
runs closer to the cap (≈470–490 MB) but is flat/declining and mostly reclaimable cache; the kernel
reclaims it before OOM, which is why ~10k renders across the soaks never OOM'd.

**Recycling knob, and a real gotcha it exposed.** `render.recycle-after` periodically does a *hard*
recycle — dispose + rebuild the WebView (`WebViewCell.hardRecycle`), not just `about:blank`, since only
a full teardown returns WebKit's native caches. A confirmation run at recycle-after=**100** logged 28
recycle events (proving the path fires) but pushed `current` to the 500 cap (band [482, 500]):
**recycling *more* aggressively is worse**, because JavaFX frees a WebView's native memory only when the
old object is GC'd/finalized, so rebuilding faster than GC reclaims transiently holds two engines. Keep
recycle-after moderate (**200 default**); it is insurance for multi-hour runs, not a per-minute tuning
knob. Net: at the default, RSS is stable and OOM-safe under sustained 14 img/s.

**Takeaways, confirmed:** (1) 10 img/s within 500 MB is achievable for this light, inlined template —
with **pool=2** and a **trimmed settle (~60 ms)**. (2) The 500 MB cap holds ~**2 WebKit engines**; pool=2
runs *at* the ceiling (peak == cap), so 2 is the practical max for this budget and pool=3 would OOM —
precisely the single-JVM limit predicted below. (3) Throughput is gated by `settle × pool`, not CPU.

### Three fixes that were required to get a non-blank image (JavaFX WebView headless gotchas)
1. **The WebView must be on a *shown* `Stage`.** An offscreen `Scene` lays out (correct `scrollHeight`)
   but never composites WebKit's paint into the scene graph, so `snapshot()` returns a blank white
   image. Each cell shows an invisible Stage on Xvfb (`WebViewCell`).
2. **A settle delay after `LoadWorker.SUCCEEDED` before snapshot.** WebKit commits its paint a beat
   after the DOM lays out; an immediate capture is blank regardless of the async-snapshot pulse.
3. **Xvfb start-up:** launch `Xvfb … -ac` (access control off) and set `DISPLAY` before the JVM —
   `xvfb-run`'s Xauthority handshake otherwise fails with *"Authorization required…"*. Plus the GTK/X
   libs + fonts the WebView Glass backend links against (`Containerfile`).

## Feasibility verdict

- **~10 img/s within 500 MB is achievable — but only for light, fully-inlined pages** (fixed template,
  no external network fetches, no heavy web fonts, modest viewport), with PNG encoding off the JAT and
  a 1–2 cell pool. It sits close to the edge, so treat 10/s as a target that needs load-testing, not a
  guarantee.
- **It will miss 10/s** for heavy/complex pages or large viewports (JAT-bound paint dominates), or if
  WebKit RSS creep isn't contained (forces frequent full-engine rebuilds that stall the JAT).
- **The 500 MB ceiling forecloses horizontal scaling *inside the budget*.** The natural scale-out —
  N single-WebView JVMs behind a load balancer — needs ~250–350 MB *each*, so two processes already
  breach 500 MB total. Under a hard 500 MB cap you are effectively limited to **one JVM, 1–2 cells**,
  and thus to that single JAT's ceiling.
- **Why WebView and not headless Chromium (Playwright/CDP)?** Chromium renders faster and more
  accurately but its baseline RSS (~200–400 MB + per-page tabs) is *worse* under a 500 MB cap. For a
  tight RAM budget with simple templates, JavaFX WebView is a defensible choice; if the RAM budget
  could rise to ~1–1.5 GB, Chromium would be the higher-throughput, higher-fidelity option.

### If 10/s must be guaranteed
1. Raise RAM to ~1 GB and run 2–3 single-WebView worker processes behind a balancer (sidesteps the
   single-JAT ceiling with process-level parallelism), **or**
2. Switch the engine to headless Chromium and budget ~1–1.5 GB, **or**
3. Constrain inputs hard (fixed small viewport, no web fonts, strictly inlined assets) and keep the
   single-JVM 2-cell design — the cheapest option, viable only if page complexity stays bounded.

## Tuning knobs (`application.properties`)
`render.pool.size` · `render.queue.max` · `render.timeout-ms` · `render.recycle-after`.
Start at pool=2, load-test, watch **RSS** and **p99 latency** together — they move against each other.