# White Balance Calibration (JAI Camera)

> Menu: Extensions > QP Scope > Utilities > Image Quality > JAI Camera > JAI White Balance Calibration...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

**Note:** This tool is specific to **JAI 3-CCD prism cameras** and only appears in the menu when a JAI camera is detected. Other cameras may use different white balance approaches (see [Generic White Balance](../TROUBLESHOOTING.md) for future plans).

## Purpose

Calibrate per-channel (R, G, B) exposure times for JAI 3-CCD prism cameras. This
ensures neutral white balance at each polarization angle without digital gain
artifacts. Each color channel is adjusted independently so that a white reference
area produces equal R, G, B intensity values.

Use this tool before background collection and acquisition whenever the camera,
light source, or optical path has changed.

![White Balance Calibration dialog](../images/Docs_WhiteBalanceCalibration.png)

## Prerequisites

- Microscope positioned at a clean, white reference area (blank slide or uniform
  bright background)
- JAI or other prism-based camera selected in the configuration
- Connected to microscope server

## Options

Modes are now grouped into colour-coded collapsible sections so the active mode is unambiguous at a glance. Each mode's header band identifies which calibration regime will be written, and only one section needs to be expanded at a time:

| Section | Header colour | What it produces |
|---|---|---|
| **Simple (90 deg)** | Blue | Calibrates R/G/B at 90 deg first, then rotates to each remaining angle and re-calibrates using that result as a seed. ~1-2 min for all angles. |
| **PPM White Balance (4 Angles)** | Distinct accent | Independent calibration at all 4 angles with per-angle target intensities; most accurate. |
| **Camera AWB (Manual Setup Required)** | Distinct accent | Camera-side auto-WB; only relevant on detectors that expose AWB hooks. |
| **Advanced Settings** | Neutral | Black level + gain bounds (see table below). |

**Defocus tip (shown inside the Simple panel):** "When you acquire tiles, select the matching WB mode ('Simple (90 deg)') in the Hardware Configuration section." A short defocus-the-reference recommendation lives there too, so calibration is always run on a slightly-defocused clean area to wash out dust/texture artifacts.

Each mode panel also exposes a **"See White Balance documentation for details"** link that jumps to this page, and the dialog has an **Advice** button (top right) for one-glance reminders about reference-slide cleanliness, exposure ranges, and saturation pitfalls.

Common controls (Shared Settings panel, applies to every mode):

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Tolerance | Spinner | 5 | Acceptable deviation from target intensity |
| Output Folder | Path | From config | Where calibration files are written |
| Objective / Detector | Read-only labels | From hardware | Confirms which calibration set will be updated |

Mode-specific controls (Simple example):

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Base Exposure (ms) | Spinner | 20 | Seed exposure for the 90 deg calibration loop |
| Target Intensity | Spinner | 180 | Target grayscale value to achieve per channel |
| Small Angle Target | Spinner | -- | Target intensity for the +/- 7 deg angles |

Advanced panel:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Max Analog Gain | Spinner | - | Maximum allowed analog gain setting |
| Gain Threshold Ratio | Spinner | - | Ratio at which to start using analog gain |
| Calibrate Black Level | CheckBox | OFF | Also calibrate black level offsets |

### PPM Mode Per-Angle Targets

When PPM mode is selected, individual targets can be configured for each angle:

| Angle | Typical Target | Notes |
|-------|---------------|-------|
| Crossed (0 deg) | ~245 | Darkest angle -- higher target to compensate |
| Uncrossed (90 deg) | ~125 | Brightest angle -- lower target to avoid saturation |
| Positive (+7 deg) | Intermediate | Between crossed and uncrossed |
| Negative (-7 deg) | Intermediate | Between crossed and uncrossed |

## Workflow

1. Position the microscope on a clean, white reference area.
2. Open the White Balance Calibration dialog.
3. Select **Simple** or **PPM** mode:
   - **Simple**: Calibrates R/G/B at 90 degrees first, then automatically rotates
     to each remaining angle and calibrates there using the 90-degree result as a
     starting point. Takes ~1-2 minutes for all angles.
   - **PPM**: Fully independent calibration at each angle with configurable
     per-angle target intensities. Most accurate but takes longer.
4. Set the target intensity (default 200 is suitable for most cases).
5. Click Start.
6. The system iteratively adjusts R, G, B exposure times for each channel
   independently until all channels reach the target intensity within tolerance.
7. Both modes produce per-angle exposure settings for all angles.
8. Calibration values are automatically saved.

## Output

Calibration data is stored in the YAML configuration file
(`imageprocessing_{microscope}.yml`):

```yaml
imaging_profiles:
  ppm:
    LOCI_OBJECTIVE_20X:
      LOCI_DETECTOR_JAI:
        angles:
          crossed:
            exposures_ms: {r: 45.2, g: 38.1, b: 52.3}
```

Subsequent background collection and acquisitions automatically use these
per-channel exposure settings.

## Tips & Troubleshooting

- **Run BEFORE background collection** -- white balance calibration must be done
  first so that background images use the correct per-channel exposures.
- **Each objective/detector combination** needs separate calibration since the light
  path characteristics differ.
- **Recalibrate** after any hardware changes affecting the light path (lamp
  replacement, filter changes, optical realignment).
- **Target intensity and saturation during acquisition** -- During acquisition on PPM modalities, if the birefringence saturation guard detects overexposure in the initial tiles, a dialog prompts you to choose between continuing despite saturation or canceling to lower settings. The saturation guard is triggered when the White Balance Target Intensity (set here during calibration) is too high for your sample. If you experience saturation dialogs during acquisition, lower the target intensity value here (try -10 to -20 from current) and recalibrate.
- If calibration fails to converge, check that the reference area is truly uniform
  and bright. Dust or sample residue will skew results.
- The tolerance setting controls how precise the calibration needs to be. A tighter
  tolerance (e.g., 2) takes longer but produces more accurate white balance.

## See Also

- [Noise Characterization](noise-characterization.md) -- Measure camera noise statistics
- [Polarizer Calibration](polarizer-calibration.md) -- Calibrate the PPM rotation stage offset
- [All Tools](../UTILITIES.md) -- Complete utilities reference
