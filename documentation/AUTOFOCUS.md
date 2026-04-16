# Autofocus System

QPSC uses a multi-layer autofocus system that keeps images in sharp focus during tiled acquisitions. This page explains how the system works, when each mode is used, and how to configure it. For hands-on configuration, see the [Autofocus Editor](tools/autofocus-editor.md) and [Autofocus Benchmark](tools/autofocus-benchmark.md) tool guides.

The autofocus system is in the middle of a rollout from a single fixed gate (tissue texture + area) to a **modality-aware strategy library** so that sparse fluorescence, dark-field / SHG, and brightfield runs can each use a validity gate appropriate for their contrast. See [Modality-Aware Autofocus](#modality-aware-autofocus) for the schema, strategy list, and current partial-rollout status.

---

## Overview

During a tiled acquisition, the sample's focal plane can shift due to slide tilt, warping, or mechanical drift. QPSC addresses this with three complementary strategies:

| Strategy | When Used | Speed | Accuracy |
|----------|-----------|-------|----------|
| [Standard Autofocus](#standard-autofocus) | First tissue position per annotation | ~55s | High (full Z search) |
| [Sweep Drift Check](#sweep-drift-check) | Subsequent AF positions during tiling | ~9s | Good (narrow Z range) |
| [Z-Focus Tilt Model](#z-focus-tilt-prediction) | Between annotations | Instant | Approximate (guides AF search center) |

Additionally, the [Live Viewer](tools/live-viewer.md) provides an interactive **Autofocus** button (primary, uses streaming scan when available) with **Sweep Focus** and **Refine Focus** as fallbacks that appear only when Autofocus is unavailable.

---

## How It Works During Acquisition

### 1. Annotation Ordering

When you start a multi-annotation acquisition, QPSC sorts annotations by **spatial proximity** starting from the annotation nearest to the current stage position. This means the stage travels efficiently and nearby annotations share similar Z focus values.

### 2. Z-Focus Hint

Before each annotation's autofocus runs, the system provides a **Z-focus hint** -- an estimate of where focus should be. This centers the AF search range so it covers the correct Z neighborhood.

The hint comes from (in order of preference):
1. **Tilt prediction model** -- After 6+ completed annotations, a least-squares plane fit predicts Z based on the target annotation's XY position (see [Tilt Prediction](#z-focus-tilt-prediction))
2. **Last acquisition Z** -- The final Z from the previous annotation (good for nearby annotations thanks to proximity ordering)
3. **Current microscope Z** -- The user's focus position (first annotation only)

### 3. Per-Tile Focus During Acquisition

Within each annotation's tile grid, autofocus runs at selected tile positions:

- **Small grids (9 tiles or fewer):** Autofocus at **every** tile for safety
- **Large grids:** Autofocus every N tiles (configurable via `n_tiles` parameter), with positions spread to maintain minimum spacing

At each AF position:

1. **Tissue detection** -- A test image is snapped and checked for sufficient tissue content (texture and area thresholds). Blank or near-blank tiles are skipped and AF is deferred to the next suitable tile.
2. **Focus measurement** -- Either Standard AF (first position) or Sweep Drift Check (subsequent positions) runs to find optimal Z.
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

| Metric | Speed | Best For |
|--------|-------|----------|
| `normalized_variance` | ~5ms | General purpose (recommended default) |
| `laplacian_variance` | ~5ms | Strong edges and gradients |
| `sobel` | ~5ms | Edge-based, good for sharp features |
| `brenner_gradient` | ~3ms | Fastest, simple gradient |
| `robust_sharpness` | ~20ms | Noisy backgrounds, particles |
| `hybrid_sharpness` | ~8ms | Balanced multi-metric |

### Peak Validation

The focus curve must show:
- **Minimum score variation** -- At least 0.5% relative range (rejects flat/featureless regions)
- **Peak quality >= 0.5** -- Weighted combination of prominence (40%), ascending trend (20%), descending trend (20%), and symmetry (20%)
- **Peak prominence >= 0.15** -- The peak must stand out from the mean

If validation fails on both the primary and fallback metrics, autofocus reports failure and triggers the manual focus fallback.

---

## Sweep Drift Check

After the first tissue position uses standard AF, subsequent positions use a faster **sweep drift check** to correct for small Z drift between tiles:

- Sweeps a narrow Z range (configured via `sweep_range_um`, default 6-10um)
- Uses fewer steps (`sweep_n_steps`, default 6-10)
- **Boundary retry (3 attempts):** When the peak is at a sweep boundary (monotonic profile), the sweep extends up to 2 additional times in the peak direction, each shifting the window by one full range. Total coverage is 3x `sweep_range_um` (e.g., 30um for a 10um setting), clamped to stage Z limits. This prevents dead zones where autofocus cannot recover from a bad starting Z on tilted samples.
- Rejects flat profiles (score variation < 2% -- no retry, since retrying won't help)
- If all attempts fail to find an interior peak, **keeps the current Z and continues** (no expensive fallback)
- Failed sweeps (zero drift) are not recorded in the AF position map, preventing stale Z values from propagating to neighboring tiles via nearest-neighbor lookup

The sweep is designed for corrections of 1-5um per attempt, with the retry loop extending reach to ~15um.

**Speed note (2026-04-14):** The internal Z-wait path in `microscope_control/hardware/stage.py` now uses a tight `device_busy` poll instead of `wait_for_device`. On the Prior ProScan this cut the blocking round-trip for a 20 um move from ~240 ms to ~80 ms (~3x) with no behavioral change. Per-sweep savings are ~900 ms across a 6-step check, which adds up to ~11 minutes across a 750-sweep acquisition. Other stages see smaller but still positive gains.

---

## Autofocus (Live Viewer)

**Autofocus** is a streaming-based continuous-Z autofocus accessed from the [Live Viewer](tools/live-viewer.md) as the primary focus button. Sweep Focus is hidden by default and only appears when Autofocus returns UNAVAILABLE (pre-flight refusal).

Unlike the stepped Sweep Drift Check above, Autofocus does not stop and snap at each Z position. Instead it:

1. Drops the stage speed property (`MaxSpeed` on Prior, `Velocity` on ASI/Marzhauser, etc.) to a slow value
2. Starts the camera in continuous sequence acquisition
3. Fires a **non-blocking** Z move across the scan range
4. Pops every frame from the circular buffer as it arrives, recording `(t_ms, z_at_pop, metric)` per frame
5. Parabolic-fits the (z, metric) curve
6. Commits the peak Z with a blocking move
7. Restores the stage speed property

On PPM at the production 0.73 ms exposure, a 6 um Autofocus scan completes in ~1 second and delivers ~25-30 usable (z, metric) samples -- far denser than the stepped sweep could produce in the same time.

### Feasibility envelope

Autofocus is **opt-in with a graceful fallback**. Before running, the server checks three gates and returns `UNAVAILABLE` with a specific reason if any fail. When this happens, the Refine Focus and Sweep Focus buttons appear in the Live Viewer toolbar:

| Gate | What it checks | Typical failure |
|---|---|---|
| Stage speed property | `MaxSpeed`, `Velocity`, `Speed`, or `MaxVelocity` exists and is writable on the focus device | Piezo stages without a velocity knob; demo adapters |
| **Motion blur budget** | `expected_blur = min_velocity * exposure_ms` must be within 25% of DOF (~0.5 um default) | Long exposures on slow stages -- e.g., above ~43 ms on Prior at MaxSpeed=1 |
| Saturation | Fewer than 5% of pixels saturated in a pre-scan snap | Camera overexposed -- metric would not discriminate |

When Autofocus returns UNAVAILABLE, the button shows "NO FOCUS" in orange with a tooltip explaining the reason, and the Refine Focus and Sweep Focus fallback buttons appear. UNAVAILABLE is informational, not an error.

### When it helps

- **Tilted samples** where the current sweep window is marginal but a wider range would be prohibitively slow stepped
- **Short exposures** (<=20 ms at 20X-ish blur budget on a Prior): blur is negligible and sample density is high
- **Testing / iteration on focus workflows** -- the Live Viewer button lets you A/B against Sweep Focus on real tissue

### When to stick with Sweep Focus

- **Long-exposure modalities** (dark fluorescence, low-angle PPM): Autofocus's blur budget is dominated by exposure, and above the per-stage ceiling it will refuse
- **Slow/fast-readout cameras where `snap_image` is competitive with streaming**: Autofocus's advantage disappears (and Sweep is already 3-4x faster thanks to the busy-poll wait)
- **First-AF-from-scratch situations**: Autofocus is designed as a drift check, not a full search. Use Standard Autofocus or Refine Focus for recovery.

### Configuration

Autofocus reads `sweep_range_um` from `autofocus_<scope>.yml` per objective. There is no separate range field -- Autofocus and the stepped Sweep Drift Check share the same range knob. Change it in the [Autofocus Configuration Editor](tools/autofocus-editor.md) and both paths use the new value.

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
- The server sends keepalive pings every 30 seconds while waiting

This can be disabled with the **"No Manual Autofocus"** preference (Extensions > QP Scope > Preferences), in which case failures automatically use the current Z position.

---

## Modality-Aware Autofocus

**Status: COMPLETE -- 2026-04-15.** Both AF call sites in `workflow.py` (pre-acquisition validation and per-tile drift check) now route through `af_strategy.is_valid()` / `af_strategy.brightness_acceptable()`. The strategy's `StrategyFailureMode` (DEFER / PROCEED / MANUAL) drives the existing defer-to-next-tile / manual-focus-dialog dispatch. The autofocus editor GUI (Extensions > QP Scope > Utilities > Autofocus Configuration Editor) has been extended with two new tabs that expose the full v2 strategy library and modality bindings for editing. See `claude-reports/2026-04-13_modality-aware-autofocus-design.md` for the original design and decision log.

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

### The Four Strategies

| Strategy | Validity Gate | Score Metric | Failure Mode | Target Modalities |
|----------|---------------|--------------|--------------|-------------------|
| `dense_texture` | Texture threshold + tissue area fraction (current behavior) | `laplacian_variance` | `DEFER` (skip tile, try next) | H&E, IHC, PPM, confluent IF, BF channel of BF+IF |
| `sparse_signal` | Bright-spot count using `bg_median + k*MAD` threshold (no area requirement) | `laplacian_variance` on the spot ROIs, fallback `brenner_gradient` | `PROCEED` (run AF anyway on whatever signal is present) | Sparse fluorescence (beads, pollen, scattered cells, FISH spots) |
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
| `sweep_range_um` | Z range for drift check | 6-10 |
| `sweep_n_steps` | Steps in drift check | 6-10 |
| `edge_retries` | Additional sweep attempts on boundary peaks (0=disable, 2=default) | 0-3 |
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

### All tiles show "no tissue detected"
- Lower `texture_threshold` (try 0.003-0.005 for low-contrast samples)
- Lower `tissue_area_threshold` (try 0.05 for sparse tissue)
- Check that the test image exposure is appropriate (not too dark or too bright)

### Sweep drift check shows "score range < 2%"
- The focus metric cannot discriminate focus in the narrow sweep range
- This is normal for very flat, featureless regions -- the system keeps the current Z and continues
- If it happens on tissue, try a different `score_metric` or increase `sweep_range_um`

---

## Related Documentation

- [Autofocus Editor](tools/autofocus-editor.md) -- GUI for per-objective parameter configuration
- [Autofocus Benchmark](tools/autofocus-benchmark.md) -- Systematic parameter optimization tool
- [Live Viewer](tools/live-viewer.md) -- Interactive focus tools (Sweep Focus, Refine Focus)
- [Preferences](PREFERENCES.md) -- "No Manual Autofocus" setting
- [Autofocus YAML Template](../../microscope_configurations/templates/autofocus_template.yml) -- Full parameter reference with inline documentation
