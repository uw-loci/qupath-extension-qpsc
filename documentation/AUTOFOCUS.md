# Autofocus System

QPSC uses a multi-layer autofocus system that keeps images in sharp focus during tiled acquisitions. This page explains how the system works, when each mode is used, and how to configure it. For hands-on configuration, see the [Autofocus Editor](tools/autofocus-editor.md) and [Autofocus Benchmark](tools/autofocus-benchmark.md) tool guides.

---

## Overview

During a tiled acquisition, the sample's focal plane can shift due to slide tilt, warping, or mechanical drift. QPSC addresses this with three complementary strategies:

| Strategy | When Used | Speed | Accuracy |
|----------|-----------|-------|----------|
| [Standard Autofocus](#standard-autofocus) | First tissue position per annotation | ~55s | High (full Z search) |
| [Sweep Drift Check](#sweep-drift-check) | Subsequent AF positions during tiling | ~9s | Good (narrow Z range) |
| [Z-Focus Tilt Model](#z-focus-tilt-prediction) | Between annotations | Instant | Approximate (guides AF search center) |

Additionally, the [Live Viewer](tools/live-viewer.md) provides interactive focus tools:
- **Sweep Focus** -- Rapid Z scan for initial focusing
- **Refine Focus** -- Hill-climbing for fine adjustment near focus

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
- Rejects boundary peaks (monotonic profiles indicate no real focus peak in range)
- Rejects flat profiles (score variation < 2%)
- If the sweep fails to find a good peak, **keeps the current Z and continues** (no expensive fallback)

The sweep is designed for corrections of 1-5um, not for finding focus from scratch.

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
