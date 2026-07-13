# Autofocus System

QPSC uses a multi-layer autofocus system that keeps images in sharp focus during tiled acquisitions. This page explains how the system works, when each mode is used, and how to configure it. For hands-on configuration, see the [Autofocus Editor](tools/autofocus-editor.md) and [Autofocus Benchmark](tools/autofocus-benchmark.md) tool guides.

The autofocus system is in the middle of a rollout from a single fixed gate (tissue texture + area) to a **modality-aware strategy library** so that sparse fluorescence, dark-field / SHG, and brightfield runs can each use a validity gate appropriate for their contrast. See [Modality-Aware Autofocus](#modality-aware-autofocus) for the schema, strategy list, and current partial-rollout status.

---

## Overview

During a tiled acquisition, the sample's focal plane can shift due to slide tilt, warping, or mechanical drift. QPSC addresses this with three complementary strategies:

| Strategy | When Used | Speed | Accuracy |
|----------|-----------|-------|----------|
| [Standard Autofocus](#standard-autofocus) | First tissue position per annotation | ~55s | High (full Z search) |
| [Sweep Autofocus](#sweep-autofocus) | Subsequent AF positions during tiling | ~9s | Good (narrow Z range) |
| [Z-Focus Tilt Model](#z-focus-tilt-prediction) | Between annotations | Instant | Approximate (guides AF search center) |

Additionally, the [Live Viewer](tools/live-viewer.md) provides an interactive **Autofocus** button (runs either Streaming or Sweep Autofocus depending on your selection in the Autofocus Configuration dialog).

---

## How It Works During Acquisition

### 1. Annotation Ordering

When you start a multi-annotation acquisition, QPSC sorts annotations by **spatial proximity** starting from the annotation nearest to the current stage position. This means the stage travels efficiently and nearby annotations share similar Z focus values.

### 2. Z-Focus Hint

Before each annotation's autofocus runs, the system provides a **Z-focus hint** -- an estimate of where focus should be. This centers the AF search range so it covers the correct Z neighborhood.

The hint comes from (in order of preference):
1. **Tilt prediction model** -- After 6+ completed annotations, a least-squares plane fit predicts Z based on the target annotation's XY position (see [Tilt Prediction](#z-focus-tilt-prediction))
2. **Last acquisition Z** -- The final Z from the previous annotation (good for nearby annotations thanks to proximity ordering)
3. **First-annotation starting Z** -- for the very first annotation, before any acquisition or tilt data exists:
   - **Persisted focus-Z seed** -- when an alignment is reused and its JSON carries a focus Z captured during a previous refinement, that value seeds the first annotation's AF so it starts near focus instead of searching from scratch (a meaningful saving at high magnification).
   - **Current microscope Z** -- otherwise, the user's current focus position.

   Either way this is only the *starting* hint: the first annotation's AF still runs, and the tilt model / last-acquisition Z take over for subsequent annotations. A stale seed is bounded by the max-focus-step clamp, so it degrades to a normal search rather than driving the stage to a wrong Z.

### 3. Per-Tile Focus During Acquisition

Within each annotation's tile grid, autofocus runs at selected tile positions:

- **Small grids (9 tiles or fewer):** Autofocus at **every** tile for safety
- **Large grids:** Autofocus every N tiles (configurable via `n_tiles` parameter), with positions spread to maintain minimum spacing

At each AF position:

1. **Tissue detection** -- A test image is snapped and checked for sufficient tissue content (texture and area thresholds). Blank or near-blank tiles are skipped and AF is deferred to the next suitable tile.
2. **Focus measurement** -- Either Standard AF (first position) or Sweep Autofocus (subsequent positions) runs to find optimal Z.
3. **Quality validation** -- The focus curve is checked for a clear peak. If validation fails, a [manual focus dialog](#manual-focus-fallback) may appear.

### 4. WSI Tissue Scoring (Existing Image Workflow)

When acquiring from an existing image, QPSC can pre-score tiles from the overview image to select the best starting tile for autofocus. This avoids placing the first AF on a hole in tissue or a blank region. The scoring uses three configurable thresholds in the autofocus YAML:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `wsi_tissue_threshold` | 0.15 | Minimum fraction of tissue pixels to accept a tile |
| `wsi_tissue_white_threshold` | 230 | Pixel mean RGB above this = white/blank |
| `wsi_tissue_dark_threshold` | 20 | Pixel mean RGB below this = background/artifact |

---

## Standard Autofocus

The initial autofocus for each annotation uses a thorough Z search:

1. Moves Z through `n_steps` positions spanning `search_range_um` (centered on the Z hint)
2. At each position, snaps an image and computes a focus score using the configured `score_metric`
3. Validates the focus curve for a clear peak (prominence, symmetry, ascending/descending trends)
4. Interpolates the curve to find the sub-step Z with maximum sharpness
5. If the primary metric fails validation, a **fallback metric** (p98_p2) is tried on the same data without re-acquiring images

### Focus Metrics

The full registry lives in `microscope_configurations/focus_metrics_manifest.yml` (canonical) with bundled copies at `qupath-extension-qpsc/src/main/resources/focus/focus_metrics_manifest.yml` and `microscope_imageprocessing/microscope_imageprocessing/focus/_packaged_manifest.yml`. Both Java (editor dropdown, header generator) and Python (runtime dispatcher) read it. Edit the canonical file -- never hardcode metric names in code.

| Metric | Speed | Best For | Constraints |
|--------|-------|----------|-------------|
| `tenengrad` | ~3ms | Brightfield, PPM tissue (streaming-AF default for tissue) | -- |
| `laplacian_variance` | ~5ms | Lower-mag BF; **best for sparse fluorescence** (FISH spots, beads, scattered objects) | -- |
| `brenner_gradient` | ~3ms | Fast checks, low-contrast / dark-field | -- |
| `normalized_variance` | ~5ms | High-mag brightfield with non-uniform illumination | **BF/PPM only, 20x+** -- the var/mean ratio is illumination-dominated at low mag (the OWS3 BF 10x failure mode from 2026-05-01) and unstable on FL where both var and mean are noise-dominated |
| `vollath_f5` | ~5ms | Fluorescence and other shot-noise-dominated samples; periodic structure | streaming-only path |
| `sobel` | ~5ms | High-contrast edge-rich tissue | standard / strategy paths only |
| `p98_p2` | ~3ms | Fallback metric (auto-computed alongside the primary in standard AF) | **modality-agnostic as a peak finder, NOT as a "higher = sharper" comparator** -- focus pulls p2 down on BF and pulls p98 up on FL, so the curve peaks in both cases but the sign and magnitude meanings flip |
| `robust_sharpness_metric` | ~20ms | dense_texture strategy with bright-particle noise | strategy path only |
| `hybrid_sharpness_metric` | ~8ms | dense_texture strategy on BF/PPM tissue | **BF/PPM only** -- the mid-gray soft mask weights mid-gray pixels highest, which actively suppresses fluorescence (sharper FL field -> lower score) |

**Manifest-declared constraints:** the `valid_modalities` and `min_magnification` fields on each metric entry drive the editor dropdown's constraint badges (`[BF/PPM only]`, `[20x+]`) and the per-modality binding warning. Constraints are informational, not enforced -- operators retain the ability to pick a metric outside its declared range when they have reason. See `claude-reports/2026-05-10_focus-metric-modality-and-streaming-af-roi-restore.md` for the design.

**Channel reduction is uniform across modalities.** All metrics see a 2D grayscale array produced by an equal-weighted color mean across channels (alpha dropped). RGB BF (JAI 3-CCD BGRA) is averaged across B/G/R; mono BF (Hamamatsu) and fluorescence are pass-through. The earlier BT.601 luminance reduction over-weighted G and let cover-slip features in R win on PPM eosin (z=83 instead of z=92); the equal-mean form was confirmed on the standard-AF path and applied to streaming AF on 2026-05-04 (commit `4f35859`).

### Peak Validation

The focus curve must show:
- **Minimum score variation** -- At least 0.5% relative range (rejects flat/featureless regions)
- **Peak quality >= 0.5** -- Weighted combination of prominence (40%), ascending trend (20%), descending trend (20%), and symmetry (20%)
- **Peak prominence >= 0.15** -- The peak must stand out from the mean

If validation fails on both the primary and fallback metrics, autofocus reports failure and triggers the manual focus fallback.

**Unbracketed edge-peak refusal (2026-06-02):** When the edge-retry budget is exhausted (or stage Z limits leave no room) and the peak is *still* at the window edge with a one-sided, never-bracketed trend, the metric never found focus in any window searched -- committing that boundary Z is how a contrast-inverted / saturated metric walks the stage off the sample. Standard AF now refuses the unbracketed boundary peak: it falls back to `p98_p2` (which must itself bracket -- a fallback that is also an edge guess is rejected) and otherwise defers to manual focus, targeting the original hint Z rather than the unverified boundary. See `claude-reports/2026-06-02_autofocus-focus-runaway.md`.

### Saturation and autofocus

Saturation is the *root* cause behind the contrast-inverted focus curves the guards above defend against: when a channel clips at 255, in-focus highlights flatten (low gradient) while defocus spreads them back below clipping (higher gradient), so the focus metric inverts and ramps toward defocus. Autofocus cannot recover real focus from a clipped frame, so the system does two things (2026-06-07):

1. **AF auto-reduces its own exposure / illumination** (server-side, `_guard_af_saturation`). Once per run, at the first autofocus, it snaps a frame at the AF profile and -- if the brightest channel is saturated beyond the strategy's tolerance -- halves the AF exposure (or, for illumination-driven modalities, the illumination power) and re-snaps until it is under tolerance, then remembers that reduction for the rest of the run. This only touches the *autofocus* frames; the acquisition's own per-tile exposure is unchanged (your images are still exposed as you set them). If halving hits its floor while still saturated, AF proceeds but logs a clear warning.

2. **The end-of-run Saturation Summary warns you** when concerning saturation is high that it degrades autofocus and that the *sample* exposure/illumination should be lowered. The auto-reduction is a stopgap for focus quality, not a substitute for correct exposure.

**The tolerance is per-strategy** (a `saturation_threshold` fraction in each strategy's `validity_params` in `autofocus_<scope>.yml`, falling back to a code default): dense tissue / confluent fluorescence **0.10** (10%), sparse-signal (beads, FISH spots) and dark-field **0.03** -- a sparse field clips *all* of its real signal in only a few percent of pixels, so a tissue-style 10% gate would never fire even when every spot is blown out. `manual_only` opts out entirely. See `claude-reports/2026-06-02_autofocus-focus-runaway.md` (Follow-up).

---

## Sweep Autofocus

After the first tissue position uses standard AF, subsequent positions use a faster **sweep autofocus** to correct for small Z drift between tiles. Sweep Autofocus runs entirely server-side (via the `TESTADAF` socket command) with no per-frame client loop.

The server-side sweep:

- Sweeps a narrow Z range (configured via `sweep_range_um`, default 6-10um)
- Uses fewer steps (`sweep_n_steps`, default 6-10)
- **Boundary retry (3 attempts):** When the peak is at a sweep boundary (monotonic profile), the sweep extends up to 2 additional times in the peak direction, each shifting the window by one full range. Total coverage is 3x `sweep_range_um` (e.g., 30um for a 10um setting), clamped to stage Z limits. This prevents dead zones where autofocus cannot recover from a bad starting Z on tilted samples.
- Rejects flat profiles (score variation < 2% -- no retry, since retrying won't help)
- **Total-drift cap (2026-06-02):** A drift check corrects small thermal drift, not lost focus. The committed correction is capped at one search window (`max_total_drift_um`, default = `sweep_range_um`) from the starting Z. If the edge-retry would chase a boundary peak beyond the cap -- the signature of a contrast-inverted / saturated metric ramping toward an edge -- the sweep **holds at the starting Z** and leaves real focus loss to the standard AF / manual path. This is the containment net for the focus runaway analyzed in `claude-reports/2026-06-02_autofocus-focus-runaway.md` (PPM 40x walked Z=7 -> 34 -> 104 um). A contrast-inverted *symmetric* U-shape is also held (separate guard).
- Failed sweeps with flat profiles (no slope, no drift) are not recorded in the AF position map, preventing stale Z values from propagating to neighboring tiles via nearest-neighbor lookup

The sweep is designed for corrections of 1-5um per attempt, with the retry loop extending reach to ~15um (bounded by the total-drift cap above).

In the Live Viewer, when you select Sweep Autofocus, clicking the Autofocus button sends a `TESTADAF` command to the server and displays "Sweeping..." on the button while the server performs the scan. The focus range dropdown always defers to the `sweep_range_um` YAML setting for Sweep Autofocus (unlike Streaming AF, where explicit um values override the YAML).

**Speed note (2026-04-14):** The internal Z-wait path in `microscope_control/hardware/stage.py` now uses a tight `device_busy` poll instead of `wait_for_device`. On the Prior ProScan this cut the blocking round-trip for a 20 um move from ~240 ms to ~80 ms (~3x) with no behavioral change. Per-sweep savings are ~900 ms across a 6-step check, which adds up to ~11 minutes across a 750-sweep acquisition. Other stages see smaller but still positive gains.

---

## Autofocus (Live Viewer)

The [Live Viewer](tools/live-viewer.md) contains a single **Autofocus** button that runs either **Streaming** or **Sweep** autofocus depending on your selection in the Autofocus Configuration dialog (Extensions > QP Scope > Utilities > Autofocus Configuration). The choice is stored per QuPath installation, so each rig naturally tracks its own preferred method.

### Streaming Autofocus

Streaming autofocus uses continuous-Z scanning. Unlike the stepped Sweep Autofocus above, it does not stop and snap at each Z position. Instead it:

1. Drops the stage speed property (`MaxSpeed` on Prior, `Velocity` on ASI/Marzhauser, etc.) to a slow value
2. Starts the camera in continuous sequence acquisition
3. Fires a **non-blocking** Z move across the scan range
4. Pops every frame from the circular buffer as it arrives, recording `(t_ms, z_at_pop, metric)` per frame
5. Parabolic-fits the (z, metric) curve
6. Commits the peak Z with a blocking move
7. Restores the stage speed property

On PPM at the production 0.73 ms exposure, a 6 um Streaming scan completes in ~1 second and delivers ~25-30 usable (z, metric) samples -- far denser than the stepped Sweep could produce in the same time.

### Feasibility envelope

Streaming autofocus runs only when the server confirms three pre-flight gates. If any fails, the Autofocus button triggers the failure dialog and explains why:

| Gate | What it checks | Typical failure |
|---|---|---|
| Stage speed property | `MaxSpeed`, `Velocity`, `Speed`, or `MaxVelocity` exists and is writable on the focus device | Piezo stages without a velocity knob; demo adapters |
| **Motion blur budget** | `expected_blur = min_velocity * exposure_ms` must be within 25% of DOF (~0.5 um default) | Long exposures on slow stages -- e.g., above ~43 ms on Prior at MaxSpeed=1 |
| Saturation | Saturated-pixel fraction below the per-modality threshold (brightfield 50%, PPM 50%, fluorescence/widefield 2%, laser-scanning/SHG 1%) | Camera overexposed -- focus metric would not discriminate |

If streaming fails on any gate, the failure dialog appears with the diagnostic reason. If you encounter these gates frequently (e.g., your stage is slow or your exposures are long), switch to **Sweep Autofocus** in the Autofocus Configuration dialog -- Sweep uses blocking step-and-snap moves and has no blur constraint or stage-speed requirement.

If no peak is found but a focus slope is detected (monotonic profile after edge retries), the stage is left at the best Z found rather than returning to the starting position.

**What you see during an edge retry (the "pop out of focus").** When the metric peaks at a window edge, the true focus is outside the scanned range, so the retry shifts the whole search window one full range in the inferred direction (no overlap with the previous window) and scans there. In the Live Viewer you watch the stage physically travel away from your current focus and the image go badly out of focus mid-scan, then snap back sharp when a peak is found (or bracketed between two windows). That transient excursion is normal -- it is the scan exploring Z it has not seen yet. It should **end sharp**; if the image instead *settles* out of focus, that is a genuine miss (a large XY move can put focus well outside the configured window -- widen the range or use Sweep Autofocus for far-from-focus recovery, per below). This is also why the excursion is larger the wider your `sweep_range_um` and the farther focus has drifted.

### When it helps

- **Tilted samples** where the current sweep window is marginal but a wider range would be prohibitively slow stepped
- **Short exposures** (<=20 ms at 20X-ish blur budget on a Prior): blur is negligible and sample density is high
- **Testing / iteration on focus workflows** -- the Live Viewer button lets you A/B against Sweep Autofocus on real tissue

### When to stick with Sweep Autofocus

- **Long-exposure modalities** (dark fluorescence, low-angle PPM): Autofocus's blur budget is dominated by exposure, and above the per-stage ceiling it will refuse
- **Slow/fast-readout cameras where `snap_image` is competitive with streaming**: Autofocus's advantage disappears (and Sweep is already 3-4x faster thanks to the busy-poll wait)
- **First-AF-from-scratch situations**: Streaming Autofocus is designed for drift correction, not as a full search. Use a wider range (select from the Live Viewer dropdown) or Sweep Autofocus for recovery from far-from-focus starting positions.

### Configuration

Both Streaming and Sweep autofocus read `sweep_range_um` from `autofocus_<scope>.yml` per objective. There is no separate range field -- both methods share the same range knob. Change it in the [Autofocus Configuration Editor](tools/autofocus-editor.md) and both paths use the new value.

Select your preferred method (Streaming or Sweep) via the radio buttons in the Autofocus Configuration dialog (Extensions > QP Scope > Utilities > Autofocus Configuration). The choice is stored per QuPath installation. Each rig typically has one preferred method:
- **Streaming** on rigs with stages capable of slow continuous motion (e.g., PPM)
- **Sweep** on rigs with stages that cannot move slowly (e.g., OWS3), or for long-exposure modalities where Streaming's blur budget is restrictive

**Live Viewer dropdown behavior:** The focus range dropdown ("Config" or explicit um values) affects Streaming AF only. Streaming AF honors explicit dropdown overrides to let you widen the scan window on-the-fly. Sweep Autofocus always reads `sweep_range_um` from the YAML and ignores dropdown overrides.

**YAML setting (server-side):** `stage.streaming_af.enabled: false` in `config_<scope>.yml` is a separate, server-side guard, independent of the dialog radio. When false, the server refuses streaming-AF requests outright. The dialog radio controls which method the *client* Live Viewer button sends; the YAML flag controls what the *server* does with a streaming request. On a rig where streaming AF genuinely cannot work (e.g. OWS3, whose stage cannot do slow continuous motion), keep the YAML flag false *and* set the dialog radio to Sweep.

### How the server picks an objective

The Live Viewer button does not currently pass an objective identifier, so the server resolves it in a 3-tier lookup:

1. Client-provided `--objective` (not used from the Live Viewer yet; used by TESTAF-style commands)
2. **Pixel-size auto-match**: query `core.get_pixel_size_um()` and find the objective in `config.hardware.objectives` whose `pixel_size_xy_um[*]` is within 0.01 um
3. First entry in `autofocus_<scope>.yml` with a warning

If your rig's MM pixel size is not wired up, the Autofocus Editor still lets you configure the per-objective values, but Autofocus will fall back to the yaml's first entry.

### Characterization

The feasibility envelope for a new rig can be measured with the **PROBEZ** diagnostic probe. See [developer/PROBEZ.md](developer/PROBEZ.md) for the detailed guide. PROBEZ is also the right first diagnostic if Autofocus is returning UNAVAILABLE unexpectedly on a working rig -- its Step 0 property snapshot and Step 5 metric-stability sweeps show exactly which gate is failing and why.

---

## Z-Focus Tilt Prediction

Across a multi-annotation acquisition, QPSC builds a **tilt model** that predicts the focal plane across the slide. After each completed annotation, the final Z position and stage coordinates are added to a least-squares plane fit: `z = ax + by + c`.

### Guardrails

The model is conservative -- it only makes predictions when confident:

| Check | Threshold | Purpose |
|-------|-----------|---------|
| Minimum data points | 6 (standard) or 4 (jumps >1mm) | Enough redundancy that one bad AF doesn't corrupt the fit |
| Residual error | < 5 um RMS | Rejects the model if data doesn't fit a plane (warped sample or bad data) |
| Deviation check | < 20 um from last known Z | Catches wild extrapolation |

When any check fails, the system falls back to the **last known good Z** from the previous annotation.

### When It Helps

The tilt model is most valuable when proximity ordering must eventually jump back across the slide (e.g., after working along one edge, the closest remaining annotation is far away). Without the model, the Z hint would come from the distant last annotation. With the model, Z is predicted from the global tilt, which is much more accurate for the target position.

---

## Manual Focus Fallback

When autofocus fails (both primary and fallback metrics), the system can request manual intervention:

- A dialog appears in QuPath asking the user to manually adjust focus
- Options: **Retry** (re-runs AF at current position), **Use Current** (accept whatever Z is set), **Cancel** (abort acquisition)
- The dialog now includes a **diagnostic hint** explaining why AF failed and suggesting how to fix it. Common failures include:
  - **Sparse vs. dense strategy mismatch** -- e.g., pollen with a tissue-texture strategy (switch to `sparse_signal`)
  - **Z starting position far from focus** -- widen the search range or move closer manually and retry
  - **Exposure issues** -- saturation (reduce exposure) or too-long exposure for streaming AF (use Sweep Autofocus)
  - **Narrow search range** -- increase `sweep_range_um` for this objective
- The hint also points you to where to change the AF strategy: the acquisition wizard's Advanced panel (one-time override) or Settings > Autofocus Configuration > Modality Bindings (persistent)
- The server sends keepalive pings every 30 seconds while waiting

This can be disabled with the **"No Manual Autofocus"** preference (Extensions > QP Scope > Preferences), in which case failures automatically use the current Z position.

## Disable All Autofocus

The **"Disable All Autofocus"** preference (Extensions > QP Scope > Preferences > "Disable All Autofocus (Danger)") suppresses *every* AF call for an acquisition, not just manual fallback. When ticked, the Java side emits `--af-disabled` on the wire (in place of the `--af-tiles`/`--af-steps`/`--af-range` triplet). The server's `_configure_autofocus` short-circuits on this flag: no autofocus YAML load required, no AF positions scheduled, no pre-acquisition AF, no per-tile sweep autofocus, no manual-focus prompts. Use only when Z drift over the acquisition window is known to be small or you're staging a hint-Z manually. Server log shows a single `Autofocus DISABLED for this acquisition` line at workflow start.

## Last-Tile AF Skip

When a tile scheduled for AF lands at the end of the position list (e.g. spatial-coverage AF picks `[0, 11]` for a 12-tile acquisition), the corrected Z would never be applied to a subsequent tile. The server skips AF in that case automatically, saving ~3-5s per acquisition. Server log shows `Skipping AF at final position N/N (no downstream tiles to use the result)`.

---

## Modality-Aware Autofocus

**Status: COMPLETE -- 2026-04-15.** Both AF call sites in `workflow.py` (pre-acquisition validation and per-tile sweep autofocus) now route through `af_strategy.is_valid()` / `af_strategy.brightness_acceptable()`. The strategy's `StrategyFailureMode` (DEFER / PROCEED / MANUAL) drives the existing defer-to-next-tile / manual-focus-dialog dispatch. The autofocus editor GUI (Extensions > QP Scope > Utilities > Autofocus Configuration Editor) has been extended with two new tabs that expose the full v2 strategy library and modality bindings for editing. This redesign was driven by autofocus failures on sparse-fluorescence samples (pollen, beads, FISH) where the old `has_sufficient_tissue` area gate was the wrong question to ask.

### Why

The existing autofocus validity gate (`has_sufficient_tissue`) assumed H&E-style contrast: enough textured pixels covering enough of the FOV. That works for brightfield H&E, IHC, PPM, and confluent IF, but it's the wrong gate for:

- **Sparse fluorescence** (beads, pollen, scattered cells, FISH spots) -- mostly dark background with a handful of bright spots, fails the area gate, triggers the "dim image -> double exposure" safety loop, and eventually saturates perfectly good samples.
- **Dark-field / SHG / LSM** -- ~black background with localized gradient energy, fails both the texture and area gates.
- **Manual-only modalities** -- the user wants the focus dialog every time, regardless of image content.

### Schema (v2)

The new YAML schema is opt-in via a top-level `schema_version` field in `autofocus_<scope>.yml`. Setting `schema_version: 2` enables the strategies library and modality bindings; omitting it (or setting `1`) preserves the exact legacy flat-loader behavior. Existing configs continue to work unchanged.

```yaml
schema_version: 2

# Legacy per-objective hardware rows still live here as before:
objectives:
  LOCI_OBJECTIVE_OLYMPUS_10X_001:
    search_range_um: 100
    n_steps: 25
    score_metric: normalized_variance
    # ... existing parameters ...

# New: named strategies library
strategies:
  dense_texture:
    validity: { kind: texture_area, texture_threshold: 0.012, tissue_area_threshold: 0.15 }
    score:    { kind: laplacian_variance }
    failure_mode: DEFER
  sparse_signal:
    validity: { kind: bright_spot_count, min_spots: 3, k_mad: 4.0 }
    brightness_check: dynamic_range   # NOT median floor -- do not saturate dark bg
    score:
      kind: laplacian_variance_on_spots
      fallback: brenner_gradient
    failure_mode: PROCEED
  dark_field:
    validity: { kind: fov_gradient_energy, min_energy: 0.01 }
    score: { kind: brenner_gradient }
    failure_mode: PROCEED
  manual_only:
    validity: { kind: always_invalid }
    failure_mode: MANUAL_DIALOG

# New: per-modality bindings
modalities:
  bf:          { strategy: dense_texture }
  ppm:         { strategy: dense_texture, mask_range: [0.10, 0.90] }
  fl:          { strategy: sparse_signal }
  fluorescence: { strategy: sparse_signal }
  bf_if:       { strategy: dense_texture }
  lsm:         { strategy: dark_field }
  shg:         { strategy: dark_field }
  '2p':        { strategy: dark_field }
  confocal:    { strategy: dark_field }
```

### The Five Shipped Strategies

| Strategy | Validity Gate | Score Metric | Failure Mode | Target Modalities |
|----------|---------------|--------------|--------------|-------------------|
| `dense_texture` | Texture threshold + tissue area fraction | `laplacian_variance` | `DEFER` (skip tile, try next) | H&E, IHC, PPM, confluent IF, BF channel of BF+IF |
| `sparse_signal` | Bright-spot count using `bg_median + k*MAD` threshold (no area requirement) | `laplacian_variance` on the spot ROIs, fallback `brenner_gradient` | `PROCEED` (run AF anyway on whatever signal is present) | Sparse fluorescence (beads, pollen, scattered cells, FISH spots) |
| `dense_fluorescence` | Whole-FOV gradient energy above a floor (`total_gradient_energy`) | `vollath_f5` -- autocorrelation form rejects shot noise | `PROCEED` | Confluent fluorescent signal (whole-cell IF, dense membrane stains, packed nuclei). Closes the gap where picking `dense_texture` for an FL binding inherited `laplacian_variance` and its sparse-FL weakness. Added 2026-05-08 (commits `c67bd14`, `bd535e9`, `447d4dd`). |
| `dark_field` | Whole-FOV gradient energy above a minimum | `brenner_gradient` | `PROCEED` | SHG, LSM, 2P, confocal, dark-field contrast |
| `manual_only` | Always returns invalid | -- | `MANUAL_DIALOG` (always pop the manual focus dialog) | User-requested manual focus |

Two design choices deserve special mention:

- **`sparse_signal` uses a dynamic-range brightness check, not a median floor.** The legacy `has_sufficient_tissue` gate used median brightness as a proxy for "is this image dark enough to need more exposure?", which caused the autofocus exposure-doubling safety loop to fire on dark-background samples with legitimate bright spots -- and then saturate the spots that would have focused perfectly well. Replacing the median floor with a dynamic-range test (peak minus background) makes the check signal-aware instead of average-aware.
- **Failure modes are per-strategy.** `DEFER` (skip the tile, advance to the next candidate) is correct for dense samples where any given tile might legitimately land on a hole in tissue. `PROCEED` (run the focus search on whatever signal we have) is correct for sparse and dark-field samples where the next tile is not guaranteed to be any better than this one.

### Focus-Channel Interaction

For channel-based modalities (widefield IF, BF+IF), the user picks which channel autofocus runs against via the "Focus" radio-button column in the channel picker (see [CHANNELS.md](CHANNELS.md#focus-channel-radio-new----2026-04-13)). That choice has two effects:

1. The picked channel is **the AF reference**. The strategy's validity gate and score metric evaluate against an image acquired with that channel's hardware state (filter, LED, exposure, any per-run intensity override from the picker).
2. The picked channel is **moved to position 0** in the per-tile acquisition sequence. The AF snap and the first real image for each tile therefore share hardware state, avoiding a filter-wheel toggle between focus and the first channel.

This pairing is what makes sparse-signal autofocus practical: the user can point AF at the brightest channel (e.g. DAPI on nuclei) without paying a filter-turret penalty, and the rest of the channels are collected immediately afterward from the same Z.

### Naming: `has_sufficient_tissue` -> `has_sufficient_signal`

The legacy validity predicate has been renamed from `has_sufficient_tissue` to `has_sufficient_signal` to reflect the fact that it now also covers fluorescence and dark-field contrast where "tissue" was never really the right word. A thin compatibility shim keeps the old name callable and logs a one-time deprecation `INFO` message on first call, so existing scripts and configs continue to work during the transition.

---

## Configuration

Autofocus parameters are configured **per objective** in the autofocus YAML file (e.g., `autofocus_PPM.yml`). Use the [Autofocus Editor](tools/autofocus-editor.md) GUI to modify these settings.

### Key Parameters

| Parameter | Description | Typical Range |
|-----------|-------------|---------------|
| `n_steps` | Z positions to sample during standard AF | 15-40 |
| `search_range_um` | Total Z search range in micrometers | 12-50 |
| `n_tiles` | Spatial grid spacing for AF positions (every ~N tiles in each axis) | 3-7 |
| `score_metric` | Focus quality algorithm | `normalized_variance` |
| `texture_threshold` | Min texture for tissue detection | 0.005-0.030 |
| `tissue_area_threshold` | Min tissue coverage fraction | 0.05-0.30 |
| `sweep_range_um` | Z range for sweep autofocus | 6-10 |
| `sweep_n_steps` | Steps in sweep autofocus | 4-8 (per 10 um), scale up ~1 per um for wider ranges |
| `edge_retries` | Additional sweep attempts on boundary peaks. Applies to BOTH the dense pre-acquisition AF and the mid-acquisition sweep autofocus. Each retry doubles the search range and shifts the center toward the inferred peak direction; sweeps are clamped to the configured stage Z limits, and retries that have nowhere new to search are skipped. (0=disable, 2=default) | 0-3 |
| `gap_index_multiplier` | Force AF after this x `n_tiles` without AF (safety net) | 2-5 (default 3) |
| `gap_spatial_multiplier` | Force AF when nearest AF exceeds this x grid spacing (fragment safety net) | 1.5-3.0 (default 2.0) |

### How AF Position Selection Works

Autofocus positions are selected in three layers:

1. **Spatial grid (primary):** A uniform 2D lattice is laid over the scan area with spacing of `((fov_x + fov_y) / 2) * n_tiles` micrometers. Each lattice point is mapped to its nearest tile. This produces evenly-distributed AF positions independent of scan order, avoiding the "vertical pillar" pattern that scan-order-based selection produces in serpentine scans.

2. **Index gap safety net:** If more than `gap_index_multiplier x n_tiles` tiles pass in scan order without any AF (because the spatial grid happens to skip a region), force AF on the next tile. The `3x` default is loose enough to avoid recreating the pillar pattern but tight enough that focus cannot drift catastrophically between grid AF points. Set to a smaller multiplier for warped slides; larger for very flat samples.

3. **Spatial gap safety net:** If the current tile is more than `gap_spatial_multiplier x af_min_distance` from any completed AF position, force AF. This catches **disconnected tissue fragments** within a single annotation -- two tiles can be adjacent in the positions list but physically far apart if tile generation skipped blank space between fragments. The default `2.0x` ensures this only fires for true fragments, not for normal inter-row transitions (which sit at roughly 1x distance).

For non-AF tiles, the Z value is inherited from the **spatially nearest** completed AF position (not the most recently applied AF), preventing horizontal Z bands across serpentine rows.

### Magnification Guidelines

| Magnification | search_range_um | n_steps | n_tiles | sweep_range_um |
|---------------|----------------|---------|---------|----------------|
| Low (4x-10x) | 50-100 | 15-35 | 3-5 | 10 |
| Medium (20x-40x) | 25-50 | 15-40 | 3-7 | 6-10 |
| High (60x-100x) | 10-25 | 15-50 | 1-3 | 4-6 |

See the [Autofocus Editor](tools/autofocus-editor.md) for detailed parameter descriptions and the [Autofocus Benchmark](tools/autofocus-benchmark.md) for systematic parameter optimization.

---

## Troubleshooting

### Reading autofocus failure hints

When autofocus fails, QPSC now provides diagnostic hints in:
- The **Live Viewer** error dialog (Autofocus or Sweep Autofocus)
- The **Autofocus Editor** Test button result popup
- The **Manual Focus** dialog (during acquisition)

These hints name the likely cause and suggest fixes. Common hints:
- **metric_flat / within noise** -- Sample type doesn't match the current AF strategy (e.g., sparse particles with a dense-texture strategy). Switch to `sparse_signal` for beads/pollen or `dark_field` for low-contrast samples. Two places to change: acquisition wizard Advanced panel (one-time) or Settings > Autofocus Configuration > Modality Bindings (persistent).
- **no peak found / edge** -- Z starting position is far from focus. Move the stage closer manually, or widen `sweep_range_um` for this objective.
- **saturation** -- Camera is overexposed. Reduce exposure time, close the aperture, or lower illumination.
- **blur** -- Exposure is too long for streaming AF's motion blur budget. Try Sweep Autofocus instead, which has no blur constraint.
- **no_slow_speed** -- Stage hardware cannot run streaming AF. Use Sweep Autofocus, or update the stage's configuration.

### Autofocus fails on first tile
- The AF position may be on a blank region or hole in tissue
- **For existing image workflows:** WSI tissue scoring should select a better tile automatically. Check that `wsi_tissue_threshold` is not too high for sparse tissue.
- **For bounded acquisitions:** The diagonal heuristic moves 1 FOV inward from the corner. If tissue doesn't extend to the corner, lower `tissue_area_threshold`.

### Focus drifts during large acquisitions
- Reduce `n_tiles` to refocus more frequently
- Check that `sweep_range_um` is large enough to capture the drift between AF positions
- For warped samples, consider `n_tiles=1` (every tile)

### Autofocus locks onto wrong Z (false peak)
- The search range may not be centered near the correct focus. This can happen if the Z hint is far from reality.
- Check the server log for the Z hint value. If it's consistently wrong, verify that the acquisition completed successfully on prior annotations (failed AF can corrupt the tilt model).
- Increase `search_range_um` if the correct focus is outside the search window.

### Pre-acquisition AF pops a manual focus dialog ("peak at edge" / "peak too close to start/end")
- This means the Z hint inherited from the previous annotation is more than half a search window away from the true focus for this region.
- As of 2026-04-26, the dense pre-acq AF auto-retries with `edge_retries` widen-and-shift attempts before falling through to the manual dialog. The retries are logged at WARNING level (`Edge retry N/M: center X -> Y, range A -> B`).
- If you are still seeing the manual dialog, either: (a) the stage Z limits prevent any further expansion (the retry log will say so explicitly), (b) the failure mode isn't a directional edge (e.g. flat scoring curve), or (c) `edge_retries` is set to 0. Bump `edge_retries` to 2-3 in `autofocus_<scope>.yml` for slides with significant region-to-region Z drift.
- If the bumped retries still don't reach the true focus, the underlying `search_range_um` is probably too narrow for the slide's Z drift -- raise it in the YAML.

### All tiles show "no tissue detected"
- Lower `texture_threshold` (try 0.003-0.005 for low-contrast samples)
- Lower `tissue_area_threshold` (try 0.05 for sparse tissue)
- Check that the test image exposure is appropriate (not too dark or too bright)

### Sweep autofocus shows "score range < 2%"
- The focus metric cannot discriminate focus in the narrow sweep range
- This is normal for very flat, featureless regions -- the system keeps the current Z and continues
- If it happens on tissue, try a different `score_metric` or increase `sweep_range_um`

### Sparse brightfield walks out of focus on empty tiles (wrong strategy)
- **Symptom:** on a sparse monochrome brightfield slide (mostly empty tiles), Z drifts badly between samples; the min-intensity projection shows defocused, bright-cornered tiles.
- **Root cause:** the modality is bound to `sparse_signal` instead of `dense_texture`. `sparse_signal`'s validity check is `bright_spot_count` (bright dots on a *dark* background) with `on_failure: proceed`. Brightfield is the inverse (dark features on a *bright* background), so the check effectively always fails and `proceed` then **runs autofocus on every tile, including empty ones**, walking Z off focus on noise.
- **Fix:** bind brightfield to `dense_texture` (validity `texture_and_area`, `on_failure: defer`) in `autofocus_<scope>.yml` -- the documented default (see [Modality-Aware Autofocus](#modality-aware-autofocus)). Empty tiles then fail the texture gate and **defer** (AF is skipped; the stage holds / reuses the nearest completed-AF Z) instead of focusing on noise. OWS3 had drifted to `sparse_signal` for brightfield and was corrected to `dense_texture` (2026-06-21).
- This is the brightfield counterpart of the sparse-*fluorescence* mismatch in [Reading autofocus failure hints](#reading-autofocus-failure-hints): match the gate to the contrast (bright-on-dark -> `sparse_signal`; dark-on-bright tissue -> `dense_texture`).
- **Note (16-bit mono residual):** the `texture_and_area` gate normalizes each tile by its own min-max range, so a *noisy* blank bright tile can occasionally pass the texture gate (mono has no brightness pre-rejection -- the `rgb_brightness_threshold` check only fires for 3-channel RGB). The sweep's U-shape / boundary-peak guards and the drift cap bound any resulting walk; a monochrome absolute-contrast blank guard in `focus/validity.py` would remove it entirely if it recurs.

### Z-stack used in place of autofocus (background-correction artifacts)
- Acquiring a Z-stack with autofocus **disabled** (`--af-disabled`) fixes the stack center at the last/drifting Z. On a brightfield slide with flat-field (background) correction, the divide correction (`image * background_mean / background_pixel`) amplifies the vignetted corners wherever the acquisition Z no longer matches the *focused* Z at which the background reference was captured -- producing bright-cornered tiles that survive the min-intensity projection.
- Background correction is applied **per Z-plane before the projection** (verified 2026-06-21), so the projection operates on already-corrected planes; the artifact is the focus/reference mismatch, not the projection order.
- **Fix:** re-enable autofocus (uncheck [Disable All Autofocus](#disable-all-autofocus-danger)) so tiles are acquired at the focused Z that matches the background reference, and keep only a small Z bracket (e.g. +/-5-10 um) as residual safety rather than a coarse +/-20 um stack.

---

## Related Documentation

- [Autofocus Editor](tools/autofocus-editor.md) -- GUI for per-objective parameter configuration
- [Autofocus Benchmark](tools/autofocus-benchmark.md) -- Systematic parameter optimization tool
- [Live Viewer](tools/live-viewer.md) -- Interactive focus tools (Autofocus, Sweep Autofocus)
- [Preferences](PREFERENCES.md) -- "No Manual Autofocus" setting
- [Autofocus YAML Template](../../microscope_configurations/templates/autofocus_template.yml) -- Full parameter reference with inline documentation
