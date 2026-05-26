# Autofocus Configuration Editor

> Menu: Extensions > QP Scope > Utilities > Autofocus Configuration Editor...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Configure the full autofocus system in an easy-to-use tabbed GUI. The editor exposes
all three sections of the v2 autofocus schema:

- **Tab 1 -- Per-Objective Parameters:** per-objective hardware tuning (search range,
  step count, sweep autofocus, AF scheduling grid, safety-net multipliers).
- **Tab 2 -- Strategies:** the strategy library (named validity+score recipes like
  `dense_texture`, `sparse_signal`, `dark_field`, `manual_only`).
- **Tab 3 -- Modality Bindings:** which strategy each modality uses, with optional
  per-modality parameter overrides.

Use this tool when setting up a new objective, adjusting autofocus behavior for
different sample types, tuning the validity gate for a new modality, or binding a
custom strategy to a scope-specific modality.

![Autofocus Configuration Editor dialog](../images/Docs_AutofocusConfigurationEditor.png)

## Prerequisites

- Microscope configuration YAML loaded with objective definitions
- At least one objective defined in the configuration

## Accessing the Editor

The Autofocus Configuration Editor can be opened in two ways:

1. **From the menu:** Extensions > QP Scope > Utilities > Autofocus Configuration Editor...
2. **From the Live Viewer:** Right-click the **Autofocus** button in the Live Viewer's focus controls toolbar. This shortcut is useful when you want to switch autofocus methods while the button is greyed out (e.g. during acquisition or between live frames).

## Options

### Live Viewer Autofocus Method

At the top of the dialog, radio buttons let you choose which autofocus method the Live Viewer's **Autofocus** button will run:

| Option | Description |
|--------|-------------|
| **Streaming** | Continuous-Z autofocus (fast, ~1 second on PPM). Requires a stage capable of slow continuous motion. May fail on stages without a speed property, with long exposures, or with saturated images. |
| **Sweep** | Stepped step-and-snap autofocus (slower, ~15-30 seconds, but more compatible). Works on any camera and any stage. Recommended for long exposures or rigs with speed-limited stages. |

Your choice is stored per QuPath installation, so each microscope rig naturally tracks its preferred method. The Live Viewer dialog always uses the method you select here.

### Acquisition Frequency & Safety Nets

Controls how densely autofocus is scheduled across the tile grid.

| Parameter | Type | Typical Range | Description |
|-----------|------|---------------|-------------|
| Objective | ComboBox | - | Select the objective to configure. The initial selection auto-detects the currently mounted optics by querying Micro-Manager's reported pixel size and matching it against the active config (via the shared `ObjectiveSelector` component). Falls back to the last-used objective preference, then the first entry. Replaces the previous always-defaults-to-10x behavior so editing the right objective does not require a manual change first. |
| Run AF every (tiles) | Spinner | 3-10 | AF grid spacing (`n_tiles`). Also sets `af_min_distance = n_tiles x mean(camera FOV)` |
| Index gap multiplier | Spinner | 1-5 (default 3) | Force AF after multiplier × n_tiles positions without one (`gap_index_multiplier`). Safety net for scan-order gaps |
| Spatial gap multiplier | Text | 1.0-3.0 (default 2.0) | Force AF when nearest AF exceeds multiplier × af_min_distance (`gap_spatial_multiplier`). Safety net for disconnected fragments |
| Min AF distance (derived) | Read-only | - | Computed from `n_tiles x mean FOV`. Updates live as inputs change. Also shows effective force-AF thresholds in tile count and micrometers |

See [AUTOFOCUS.md](../AUTOFOCUS.md#how-af-position-selection-works) for the full explanation of how the grid and safety nets interact.

### Standard Autofocus

| Parameter | Type | Typical Range | Description |
|-----------|------|---------------|-------------|
| Search range (um) | Spinner | 10-50 | Total Z range to search (`search_range_um`) |
| Z samples | Spinner | 5-20 | Number of Z positions to sample (`n_steps`) |

### Sweep Autofocus

The Sweep Autofocus section configures a periodic Z sweep that monitors focus drift during acquisition.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| Sweep range (um) | Spinner | 10 | Total Z range for the sweep (`sweep_range_um`) |
| Z samples | Spinner | 6 | Number of Z positions to sample during each sweep (`sweep_n_steps`) |
| Edge retries | Spinner | 2 | Extra attempts when the best focus is at the sweep boundary (`edge_retries`) |
| Score metric | ComboBox | normalized_variance | Focus quality metric used to evaluate each Z position |

**Available score_metric options:**

| Metric | Description |
|--------|-------------|
| `normalized_variance` | Default and recommended. Variance of the image normalized by its mean intensity. |
| `laplacian_variance` | Variance of the Laplacian-filtered image. |
| `sobel` | Sum of Sobel edge magnitudes. |
| `brenner_gradient` | Brenner gradient-based focus measure. |
| `p98_p2` | Difference between 98th and 2nd intensity percentiles. |

**Test Sweep Autofocus** button: runs a single sweep at the current position so you can verify parameters before acquisition.

### Test Streaming Autofocus

Below the streaming-AF panel, the **Test Streaming Autofocus** button runs a single streaming-AF pass at the current stage position with frame dumping enabled (server-side `--dump`). On completion a non-modal popup opens with:

- **Status header** -- `SUCCESS` / `UNAVAILABLE` / `FAILED` plus a one-line reason from the server.
- **Diagnostic hint** (on non-SUCCESS) -- If the test fails or is unavailable, a human-readable hint explains the likely cause and points you to where to change the AF strategy.
- **Dump path** -- absolute path to the folder of dumped TIFs and the per-frame metric/Z log, so you can open the captures in your tool of choice for offline review.
- **Time-domain chart** -- focus metric vs `wall_ms`, with the actual stage Z (offset + scaled) overlaid as a second series. Lets you see whether the metric peak lands while the stage is moving or after it has settled.
- **Space-domain chart** -- focus metric vs `Z_actual`, sorted by Z so the polyline does not zig-zag when the stage was stationary at the start of the run. This is the curve the streaming-AF peak finder actually fits.

Use this to diagnose noisy metric curves (raise CLAHE / change metric), to verify a metric switch did not invert the peak, or to confirm the stage motion profile actually covers the search range you configured. Streaming AF has to be available on the active objective (no exposure-budget gate, no missing speed property) for the button to enable; otherwise the popup reports `UNAVAILABLE` with the gating reason.

### Buttons

| Button | Action |
|--------|--------|
| **Write to File** | Save all settings to YAML file |
| **OK** | Save and close dialog |
| **Cancel** | Discard unsaved changes |

### Tab 2 -- Strategies (v2)

The strategy library defines named recipes for how AF decides whether a tile has
enough signal to focus on. Each strategy is a collapsible card showing:

| Field | Type | Description |
|-------|------|-------------|
| Description | TextArea | Human-readable explanation of when to use this strategy |
| Score metric | ComboBox | Focus quality algorithm: `laplacian_variance` (default), `normalized_variance`, `brenner_gradient`, `sobel`, `p98_p2`, `none`. Each row in the dropdown shows constraint badges (e.g. `-- not for fluorescence`, `-- needs 20x+`) sourced from the metric manifest's `valid_modalities` and `min_magnification` fields, so you can see which picks are sample-restricted before saving. |
| Validity check | ComboBox | How the system decides a tile is focusable: `texture_and_area`, `bright_spot_count`, `total_gradient_energy`, `always_false` |
| Validity params | Dynamic grid | Parameters for the chosen validity check. The fields change when the validity check selection changes. |
| On failure | ComboBox | `defer` (skip tile, try next), `proceed` (run AF anyway), `manual` (pop focus dialog) |

Use **+ Add Strategy** to create a new custom strategy, or **Delete Strategy** inside
a card to remove one. At minimum, `dense_texture` and `manual_only` should be present.

**Manifest constraint surfacing (`valid_modalities` / `min_magnification`):** the score-metric dropdown and the per-modality override picker both render constraint-aware cells. A metric whose `valid_modalities` list excludes the binding's modality is rendered in red with a `-- not for <modality>` suffix; selecting it surfaces a wrap-text warning label below the picker. Constraints are informational only -- they do not block saving, so operators retain the ability to use a metric outside its declared range when they have a reason. The recognised `valid_modalities` values are `brightfield`, `ppm`, `fluorescence`, `dark_field`, and `other`; modality keys like `bf_dia`, `BF_IF`, or `Brightfield` all canonicalise to the same bucket.

### Tab 3 -- Modality Bindings (v2)

Each row assigns a modality key to a strategy from Tab 2. The modality key is matched
via longest-prefix-wins lookup (case-insensitive), so both `bf` and `brightfield` can
independently point to the same or different strategies.

| Field | Type | Description |
|-------|------|-------------|
| Modality key | Label | The modality name (e.g. `bf`, `fluorescence`, `ppm`, `bf_if`) |
| Strategy | ComboBox | Which strategy from Tab 2 this modality uses |
| Overrides | CheckBox | When checked, expands a parameter grid to override specific validity_params from the base strategy |
| X | Button | Delete this binding |

Use **+ Add Binding** to map a new modality. The user can still override the strategy
per-acquisition via the autofocus dropdown in the acquisition wizard.

## Workflow

1. Open the Autofocus Configuration Editor from the menu.
2. **Tab 1 (Per-Objective):**
   a. Select the objective from the dropdown.
   b. Adjust **n_tiles** (AF scheduling density). Watch **af_min_distance** update live.
   c. Tune safety-net multipliers if needed for warped or fragmented samples.
   d. Adjust **n_steps**, **search_range_um**, and sweep autofocus parameters.
   e. Click **Test Sweep Autofocus** to verify at the current position.
3. **Tab 2 (Strategies):** Review the strategy library. For most users the defaults
   (`dense_texture`, `sparse_signal`, `dark_field`, `manual_only`) are sufficient.
   Expand a strategy card to view or tweak its validity parameters.
4. **Tab 3 (Modality Bindings):** Verify each modality on your scope maps to the
   correct strategy. Adjust overrides as needed (e.g., PPM gets a wider
   `tissue_mask_range` because polarized images have wider intensity distributions).
5. Click **Write to File** to save settings, or **OK** to save and close.

## Output

Settings are saved to `autofocus_{microscope}.yml` in the microscope configuration
directory with `schema_version: 2`. The file contains three sections:
`autofocus_settings` (per-objective parameters), `strategies` (the strategy library),
and `modalities` (the per-modality bindings).

## Parameter Guidelines

| Objective | n_steps | search_range_um | n_tiles |
|-----------|---------|-----------------|---------|
| 10X | 9 | 15 | 5 |
| 20X | 11 | 15 | 5 |
| 40X | 15 | 10 | 7 |

## Tips & Troubleshooting

- **Higher magnification objectives** need more steps and a smaller search range
  because depth of field is narrower.
- **Lower n_tiles** means more frequent autofocus, which is slower but more reliable
  for uneven samples.
- **Thick samples** may need a larger search_range_um to accommodate Z variation.
- If autofocus consistently fails, try increasing n_steps or widening search_range_um.
- **Tilted slides going out of focus between AF points:** tighten `gap_index_multiplier`
  (try 2 or even 1.5) rather than widening `sweep_range_um`. A wider sweep just tolerates
  more drift at individual AF points; tighter index gaps reduce how much drift can
  accumulate in the first place.
- **Vertical focus bands in serpentine scans:** `gap_index_multiplier` is probably too
  small and recreating the "AF pillar" pattern. Raise it towards 4-5.
- **Disconnected tissue fragments going out of focus:** lower `gap_spatial_multiplier`
  (try 1.0-1.5) so the spatial safety net catches the jump to the next fragment.
- **`af_min_distance` shows "FOV unavailable"** in the editor: the objective isn't
  referenced by any modality with a valid camera/FOV in the microscope config. The
  value is still computed correctly at runtime by Python using the active modality,
  so this is a display-only limitation.
- Use the [Autofocus Parameter Benchmark](autofocus-benchmark.md) to systematically
  find optimal settings for your sample type.

## See Also

- [Autofocus Parameter Benchmark](autofocus-benchmark.md) -- Systematically find optimal autofocus settings
- [All Tools](../UTILITIES.md) -- Complete utilities reference
