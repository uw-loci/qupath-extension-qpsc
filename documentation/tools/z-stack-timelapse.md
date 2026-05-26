# Z-Stack / Time-Lapse

> Menu: Extensions > QP Scope > Utilities > Z-Stack / Time-Lapse...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

![Z-Stack / Time-Lapse dialog](../images/Docs_ZStackTimelapse.png)

## Purpose

Acquire single-tile Z-stacks or time-lapse sequences at the current stage position. These are basic acquisition modes for capturing 3D depth information or temporal dynamics without the complexity of multi-tile stitching.

## Prerequisites

- Connected to the microscope server
- Stage positioned at the area of interest (use the Live Viewer to navigate)
- For Z-stacks: focus set to the approximate center of the desired Z range
- Microscope YAML configuration file with `acquisition_profiles` entries

## Setup Section

At the top of the dialog, a **Setup** panel configures the microscope hardware state before acquisition:

| Control | Description |
|---------|-------------|
| **Modality** | Select the imaging modality (e.g., Brightfield, PPM, Widefield IF) based on your microscope configuration. **Modality changes sync across all open dialogs** (Acquisition Wizard, Live Viewer Camera tab, Background Collection, Sample Setup, etc.), so selecting a modality here updates all other open dialogs' selectors automatically and drives the hardware via APPLYPR (filter cube, lamp, condenser, etc.). |
| **Profile** | Select the acquisition profile, which sets objective, detector, illumination, exposure, and filters for the selected modality |
| **Channel** (when applicable) | If the modality supports multiple channels, select which channel to acquire. Channel row is hidden for single-channel modalities |

**Important:** Before clicking **Start Z-Stack** or **Start Time-Lapse**, you must select a modality and profile. If channels are shown, you must also select a channel. The system will apply these settings to the microscope hardware via `APPLYPR` and `APPLYCH` commands, ensuring consistent exposure and illumination.

**Error state:** If required controls are not set, the status label will display "Pick a modality, profile, and channel (if shown) in Setup first." Configure the Setup row and try again.

## Z-Stack

Acquires images at multiple Z positions centered on the current focus, using the modality and profile selected in the Setup section.

| Parameter | Description | Default |
|-----------|-------------|---------|
| Total range (um) | Full Z range to sweep (centered on current Z) | 20 |
| Step size (um) | Distance between Z planes | 1.0 |
| Output folder | Directory for saved images | `<project>/zstack/` |

The info label shows the computed Z range and number of planes. For example, 20 um range with 1 um step = 21 planes centered on current Z.

**Note:** This dialog acquires a single channel per run -- the one picked in Setup. For widefield IF, run the dialog once per channel; for brightfield / PPM, the Channel control is hidden and the profile's settings drive the capture.

### Output

Individual TIFF files named `z0000_Z<position>.tif` with metadata including Z position and plane index.

## Time-Lapse

Acquires images at the current position at regular intervals, using the modality and profile selected in the Setup section.

| Parameter | Description | Default |
|-----------|-------------|---------|
| Timepoints | Number of acquisitions | 10 |
| Interval (sec) | Seconds between time points (0 = as fast as possible) | 5.0 |
| Output folder | Directory for saved images | `<project>/timelapse/` |

The info label shows total acquisition duration.

**Note:** This dialog acquires a single channel per run -- the one picked in Setup. For widefield IF, run the dialog once per channel; for brightfield / PPM, the Channel control is hidden and the profile's settings drive the capture.

### Output

Individual TIFF files named `t00000_T<elapsed>s.tif` with metadata including timepoint index and elapsed time.

## Modality Support

Both Z-stack and time-lapse support every modality configured in the microscope YAML. The Setup section's **Modality** dropdown lists all modalities that have acquisition profiles defined. Selecting a modality populates the **Profile** dropdown with compatible profiles for that modality.

When you start acquisition, the selected profile's objective, detector, and illumination settings are applied to the microscope hardware before image capture.

Behavior depends on the selected modality:

- **PPM / multi-angle:** all configured rotation angles are acquired at each Z plane or time point. Files suffixed with the angle: `z0000_Z10.0_angle90.tif`. Channel control is hidden for this modality.
- **Brightfield (single-angle, single-channel):** standard Z-stack per Z plane. Channel control is hidden for this modality.
- **Widefield IF / fluorescence (multi-channel):** the **Channel** dropdown appears in Setup. Pick one channel per run; the dialog applies that channel's profile (filter wheel, exposure, intensity, illumination) before snapping. To capture multiple channels, run the dialog once per channel. The multi-channel loop-order toggle described below applies to the **Bounded** and **Existing Image** acquisition dialogs, not to this single-tile dialog.

### Loop-order toggle (added 2026-05-14)

Both the **Bounded Acquisition** and **Existing Image Acquisition** dialogs grow a "Loop order:" row inside the Z-stack section when:

- Z-stack is enabled, AND
- the active modality is widefield (channel-based) and 2+ channels are selected, OR the active modality is PPM (the toggle always applies if z-stack is on -- angle count comes from the config).

The toggle is hidden for modalities that don't participate (brightfield, LSM single-channel, etc.).

| Modality | Default | Alternative | Trade-off |
|---|---|---|---|
| Widefield IF | **Z per channel** (channel-outer / z-inner). One channel sweeps the full z-stack, then switch channels. | **Channels per Z** (z-outer / channel-inner). Every channel re-images at each z plane. | Default: fewer filter-cube switches, fast for fixed slides. Alternative: slower (channels x z_planes switches per tile), but tightly z-registered for live samples or drifting workflows. |
| PPM | **Angle per Z** (z-outer / angle-inner). Each angle re-images at every z plane. | **Z per angle** (angle-outer / z-inner). Each angle sweeps the full z-stack before rotation. | Default: the historical PPM ordering. Alternative: fewer rotation-stage moves per tile (angles instead of angles x z_planes) -- faster when z-stacking thicker tissue slides on a 40x objective. |

The choice persists per-modality-family via PersistentPreferences (`qpscAcqLoopOrder.widefield` / `qpscAcqLoopOrder.ppm`). The wire format adds one optional `--inner-axis {z|channel|angle}` flag on the `acquire_` command (see `documentation/developer/SOCKET_PROTOCOL.md`); the flag is omitted when the user keeps the default, so callers that don't care produce byte-identical command lines.

Future extension: when time-lapse / multi-position support adds a third axis, `--inner-axis` either grows new values (`time`) or pairs with a `--axis-order` flag for full N-axis nesting -- the named-axis vocabulary is intentionally additive.

## Per-Tile Z-Stacks (Multi-Tile Acquisition)

The main QPSC acquisition workflow supports Z-stacks at every tile position. This is essential for SHG and multiphoton imaging where tissue extends through multiple focal planes, and for thick-section fluorescence where the signal is distributed across Z.

When Z-stack parameters are included in an acquisition command (`--z-stack --z-start --z-end --z-step`), the system:

1. Performs autofocus at each tile position to find the optimal Z (or skips when `--af-disabled`).
2. Acquires multiple Z-planes centered on the autofocus result -- per channel for IF, per angle for PPM, single-pass for BF.
3. Computes a projection (e.g., max intensity) to produce a single 2D tile (per channel / angle when applicable).
4. Saves the projected tile for stitching (same pipeline as 2D acquisition).

### Projection Types

| Projection | Flag | Description | Use case |
|-----------|------|-------------|----------|
| **Max intensity** | `--z-projection max` | Brightest value at each pixel across Z | SHG, fluorescence (default) |
| **Min intensity** | `--z-projection min` | Darkest value at each pixel across Z | Absorption / transmitted light |
| **Sum** | `--z-projection sum` | Total signal across Z (overflow-safe) | Thick-section fluorescence |
| **Mean** | `--z-projection mean` | Average across Z (noise reduction) | General denoising |
| **Std deviation** | `--z-projection std` | Variability across Z | Highlighting Z-localized structures |

### Output Layout

Per-tile output structure depends on the modality:

| Modality | Projected output | Raw Z planes (with `--save-raw`) |
|---|---|---|
| Brightfield (single-shot) | `<output>/<tile>.tif` | `<output>/z000/<tile>.tif`, `z001/`, ... |
| PPM (multi-angle) | `<output>/<angle>/<tile>.tif` | `<output>/<angle>/z000/<tile>.tif`, ... |
| Widefield IF (channels) | `<output>/<channel>/<tile>.tif` | `<output>/<channel>/z000/<tile>.tif`, ... |

### Raw Z-Plane Storage

Add `--save-raw` to save individual Z-planes alongside the projected tiles. Planes land in `z000/`, `z001/`, etc. subdirectories under the appropriate per-angle / per-channel / output directory (see table above).

## Future Expansion

- Per-tile time-lapse (conditional): trigger time-lapse at specific positions based on image content
- Combined multi-tile Z-stack + time-lapse

## See Also

- [Live Viewer](live-viewer.md) -- Navigate to the position of interest
- [Camera Control](camera-control.md) -- Adjust exposure and gain before acquisition
