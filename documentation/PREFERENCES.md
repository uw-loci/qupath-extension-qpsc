# QPSC Preferences Reference

This document provides comprehensive documentation for all QPSC preferences available in QuPath's Preferences panel.

**Location:** Edit > Preferences > QuPath SCope

---

## Quick Reference Table

| Preference | Type | Default | Description |
|------------|------|---------|-------------|
| [Flip macro image X](#flip-macro-image-x) | Boolean | OFF | Flip image horizontally |
| [Flip macro image Y](#flip-macro-image-y) | Boolean | OFF | Flip image vertically |
| [Inverted X stage](#inverted-x-stage) | Boolean | OFF | Stage X wiring is inverted |
| [Inverted Y stage](#inverted-y-stage) | Boolean | ON | Stage Y wiring is inverted |
| [Camera orientation](#camera-orientation) | Enum | NORMAL | How the displayed image is oriented relative to the sample frame |
| [Microscope Config File](#microscope-config-file) | File | - | Path to microscope YAML config |
| [Projects Folder](#projects-folder) | Directory | - | Root folder for slide projects |
| [Tissue Detection Script](#tissue-detection-script) | File | - | Optional Groovy script for tissue detection |
| [Data Bounds Classifier](#data-bounds-classifier) | File (.json) | - | Pixel classifier that finds the data region on acquired images (alignment workflow) |
| [Tissue Artifact Filter Enabled](#tissue-artifact-filter-enabled) | Boolean | ON | Use artifact-aware tissue detection |
| [Tissue Two-Pass Refine](#tissue-two-pass-refine) | Boolean | OFF | Apply second refinement pass |
| [Tissue Median Kernel](#tissue-median-kernel) | Integer | 17 | Median filter kernel size |
| [Tissue Morph Close Kernel](#tissue-morph-close-kernel) | Integer | 7 | Morphological closing kernel size |
| [Tissue Morph Close Iterations](#tissue-morph-close-iterations) | Integer | 3 | Morphological closing iterations |
| [High Bit Depth PPM Capture](#high-bit-depth-ppm-capture) | Boolean | OFF | Acquire PPM frames at higher camera bit depth |
| [Birefringence Minimum Intensity](#birefringence-minimum-intensity) | Integer | 10 | Dark region noise suppression threshold for PPM |
| [Enable time-lapse acquisition](#time-lapse-options) | Boolean | OFF | Repeat acquisition over multiple timepoints at fixed interval |
| [Timepoints](#time-lapse-options) | Integer | 1 | Number of times full acquisition is repeated |
| [Interval (s)](#time-lapse-options) | Double | 60.0 | Seconds between start of consecutive timepoints |
| [Tile Handling Method](#tile-handling-method) | Choice | None | How to handle intermediate tiles |
| [Tile Overlap Percent](#tile-overlap-percent) | Double | 10.0 | Overlap between adjacent tiles |
| [Compression type](#compression-type) | Choice | LZW | OME pyramid compression |
| [Stitching output format](#stitching-output-format) | Choice | OME_TIFF | Output format for stitched images |
| [Stitching concurrency](#stitching-concurrency) | Integer | 4 | Max angles/channels stitched at once per annotation |
| [Microscope Server Host](#microscope-server-host) | String | 127.0.0.1 | Server IP address |
| [Microscope Server Port](#microscope-server-port) | Integer | 5000 | Server port number |
| [Auto-connect to Server](#auto-connect-to-server) | Boolean | ON | Connect on QuPath startup |
| [No Manual Autofocus (Danger)](#no-manual-autofocus-danger) | Boolean | OFF | Skip manual focus dialogs |
| [Disable All Autofocus (Danger)](#disable-all-autofocus-danger) | Boolean | OFF | Send `--af-disabled` on the wire so server runs zero AF |
| Save Raw Tiles | Boolean | OFF | Save unprocessed tiles alongside corrected |
| Warn On Low Disk Space | Boolean | ON | Alert when disk space is low before acquisition |
| Enable Multi-Slide Workflow (experimental) | Boolean | OFF | Adds the MS-Existing Image menu entry for multi-slide carriers (e.g. quad_v) |
| [Reuse saved alignment (TESTING ONLY)](#reuse-saved-alignment-testing-only) | Boolean | OFF | Multi-slide batch alignment reuse (UNSAFE, testing only) |
| [Image name includes: Objective](#image-name-includes-objective) | Boolean | OFF | Add objective to filename |
| [Image name includes: Modality](#image-name-includes-modality) | Boolean | OFF | Add modality to filename |
| [Image name includes: Annotation](#image-name-includes-annotation) | Boolean | OFF | Add annotation name to filename |
| [Image name includes: Angle](#image-name-includes-angle) | Boolean | ON | Add angle to filename |
| [Metadata Propagation Prefix](#metadata-propagation-prefix) | String | OCR | Prefix for inherited metadata |
| [Live Viewer: Show Position Overlay](#live-viewer-show-position-overlay) | Boolean | OFF | Overlay the current XYZ(R) stage position on the live image |
| [Live Viewer: Position Overlay Text Size](#live-viewer-position-overlay-text-size) | Choice | (QuPath location size) | Text size for the position overlay |
| [Live Viewer: Dock Histogram Right](#live-viewer-dock-histogram-right) | Boolean | OFF | Dock the histogram + noise stats on the right (vertical) instead of below the image |

---

## Image Flipping & Stage Direction

These settings control the coordinate transformation between QuPath and the microscope.

### Flip macro image X

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | OFF |
| Requires Restart | No |

**Description:**
Flips the image horizontally for coordinate alignment. Enable when the optical light path creates a horizontally mirrored image relative to stage coordinates.

**When to Enable:**
- Stage moves right but feature moves left in image
- Scanned images appear horizontally mirrored
- After verifying with alignment workflow

---

### Flip macro image Y

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | OFF |
| Requires Restart | No |

**Description:**
Flips the image vertically for coordinate alignment. Enable when the optical light path creates a vertically mirrored image.

**When to Enable:**
- Stage moves up but feature moves down in image
- Scanned images appear vertically mirrored
- After verifying with alignment workflow

---

### Inverted X stage

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | OFF |
| Requires Restart | No |

**Description:**
Hardware stage wiring polarity for the X axis. When enabled, MicroManager's `+X` command moves the physical stage in the lab `-X` direction (i.e. the encoder or device adapter is wired backwards relative to the lab frame).

This is a pure hardware property — it does NOT describe how the image appears on screen. For image orientation, use [Camera orientation](#camera-orientation) instead.

**How to diagnose:**
This is a hardware-wiring property and must be verified by direct observation of the stage carrier, not by looking at the image. Issue `core.setXYPosition(core.getXPosition() + 100, core.getYPosition())` from a MicroManager script and watch which direction the physical stage actually moves. If positive commands move the carrier in the lab `-X` direction, enable this setting.

The **Calibrate Directions** tool (Live Viewer → Navigate tab → `Calibrate Directions...`, also a Setup Wizard step) **does not change this preference** -- it solves for `Camera orientation` under your current polarity. If the calibration tool reports "no camera orientation matches," that is a signal that polarity may be wrong; re-run the MicroManager-script check above.

**When to Enable:**
- You've verified via the hardware check above that positive X commands move the stage the wrong way.
- Do NOT enable this as a workaround for arrow buttons or stitched tiles looking flipped — that's a camera orientation issue; use the [Camera orientation](#camera-orientation) setting (or the Calibrate Directions tool) instead.

---

### Inverted Y stage

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | ON |
| Requires Restart | No |

**Description:**
Hardware stage wiring polarity for the Y axis. When enabled, MicroManager's `+Y` command moves the physical stage in the lab `-Y` direction.

As with the X polarity, this describes the stage wiring, NOT the image orientation on screen. For image orientation, use [Camera orientation](#camera-orientation) instead.

**How to diagnose:**
Same hardware check as X — set a new Y position via MM script and observe the physical stage direction. The **Calibrate Directions** tool does not change this preference.

**When to Enable:**
- You've verified via the hardware check that positive Y commands move the stage the wrong way.
- Default is ON because many common stages (e.g. Nikon Ti2) ship with this wiring convention.

---

### Camera orientation

| Property | Value |
|----------|-------|
| Type | Enum (choice) |
| Default | NORMAL |
| Requires Restart | No |

**Description:**

`Camera orientation` describes the **net optical relationship** between your sample and your displayed image. It composes with the two stage-polarity preferences above via `StageImageTransform` to form the complete stage ⇔ image relationship used by arrow buttons, the virtual joystick, double-click-to-center, and the stitcher's tile placement.

**⚠️ IMPORTANT — what this preference is NOT:**

- It is **not** a command to rotate or flip your displayed image.
- It does **not** change anything about what the Live Viewer shows. The Live Viewer keeps displaying exactly the same pixels regardless of this setting.
- It is **not** about whether you physically rotated your camera hardware.

**What it IS:**

A label describing how the displayed image is *already* related to the sample frame, so the software can do correct sign math for stage commands and tile placement. Your scope may have an image flip inherent to its optics (a dichroic, a mirror, a prism in the adapter, or just the way the sensor is wired) that you never installed — and this preference tells the software about that existing flip so it can compensate.

**Concrete example (OWS3, 2026-04-09):** The Nikon Ti2 body on OWS3 produces a horizontally-flipped image path even though the camera itself is mounted normally and nothing was physically rotated. The correct setting on OWS3 is `FLIP_H`. Changing from `NORMAL` to `FLIP_H` does not alter the Live Viewer image at all — but it fixes the X axis of the stitched output, which was previously mirrored.

**Values:**

Axis-aligned (fully supported by all subsystems including the stitcher):
- `NORMAL` — the simplest case. Sample `+X` appears at display right; sample `+Y` appears at display down. Start here for any new scope.
- `FLIP_H` — there's a *net* horizontal flip somewhere in the optical path. Sample `+X` appears at display **left**. This is the setting OWS3 turned out to need.
- `FLIP_V` — net vertical flip in the optical path. Sample `+Y` appears at display **up**.
- `ROT_180` — the image is upside-down relative to the stage frame. Equivalent to `FLIP_H` composed with `FLIP_V`.

Rotation / transpose cases (supported by Live Viewer controls but **not** by the stitcher — acquisitions will log an error and the stitched output will be mis-oriented until full rotation support lands):
- `ROT_90_CW` — image is rotated 90° clockwise relative to stage frame.
- `ROT_90_CCW` — image is rotated 90° counter-clockwise (= 270° CW).
- `TRANSPOSE` — diagonal transpose (swap X and Y axes).
- `ANTI_TRANSPOSE` — anti-diagonal transpose.

**How to Configure:**

**Easy path:** Live Viewer → Navigate tab → `Calibrate Directions...` (also a step in the Setup Wizard). The dialog jogs the stage in `+X` and `+Y`, asks which way the image panned, and solves for the `Camera orientation` that matches under your current `Inverted X/Y stage` polarity. It writes only `Camera orientation` -- polarity is treated as a hardware-wiring fact (see the [Inverted X/Y stage](#inverted-x-stage) sections above) and must be verified separately by direct stage observation. If the tool reports "no camera orientation matches," that is a signal your polarity may be wrong; verify via the MicroManager-script check before retrying. Manual override is available for users who need to edit both at once.

**Manual procedure (fallback):**

The goal is to pick whichever combination of `Inverted X/Y stage` + `Camera orientation` makes **all four of these** produce the correct visual result on your scope:

1. Arrow buttons (Sample Movement checked) — direction matches the button you press
2. Virtual joystick — direction matches the drag
3. Double-click-to-center — the clicked feature jumps to the centre of the view
4. A small stitched 2×2 acquisition — the corners are laid out as expected

Procedure:
1. Start with the `Inverted X/Y stage` settings you already had, and `Camera orientation = NORMAL`.
2. Test the four live gestures. If all four work, you may still need to verify stitching (step 4) — it's possible for gestures to appear correct but stitching to be wrong, as we found on OWS3.
3. Run a small 2×2 stitched acquisition.
4. If only the stitched X axis is wrong, try `FLIP_H`.
5. If only the stitched Y axis is wrong, try `FLIP_V`.
6. If both stitched axes are wrong, try `ROT_180`.
7. Only if none of the axis-aligned values work, and you genuinely have a physically rotated camera sensor, pick one of the rotation values — but be aware the stitcher won't fully support it yet.

Remember: changing `Camera orientation` will **not** make your Live Viewer image appear flipped or rotated. It only changes the software's interpretation of direction signs. If the Live Viewer looks correct with `NORMAL` but stitching is wrong, that's a legitimate reason to try `FLIP_H` or `FLIP_V` even though "nothing looks flipped".

**Debugging:** When QuPath starts, QPSC logs a `Live-view coordinate transform at startup` block to the log file (Help → Show Log). It shows the current stage/camera settings and the resulting mmDelta signs for each canonical gesture. If anything looks wrong, paste that block into a bug report.

---

## Server Connection

Settings for communicating with the microscope control server.

### Microscope Server Host

| Property | Value |
|----------|-------|
| Type | String |
| Default | 127.0.0.1 |
| Requires Restart | No |

**Description:**
IP address or hostname of the computer running the microscope control server.

**Common Values:**
- `127.0.0.1` - Local machine (same computer)
- `localhost` - Same as 127.0.0.1
- `192.168.x.x` - Remote machine on local network

---

### Microscope Server Port

| Property | Value |
|----------|-------|
| Type | Integer |
| Default | 5000 |
| Range | 1024-65535 |
| Requires Restart | No |

**Description:**
Port number the microscope server listens on. Must match the port configured in the Python server.

**Note:** If port 5000 is in use, choose another port and update both QuPath preferences and server configuration.

---

### Auto-connect to Server

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | ON |
| Requires Restart | Yes |

**Description:**
When enabled, QuPath automatically attempts to connect to the microscope server on startup.

**Recommendations:**
- **ON** if you always use microscope with QuPath
- **OFF** if you sometimes use QuPath without microscope access

---

## File & Path Configuration

Settings for file locations and paths.

### Microscope Config File

| Property | Value |
|----------|-------|
| Type | File |
| Default | Offline placeholder (auto-installed on first launch) |
| Requires Restart | Yes |
| **REQUIRED** | Yes |

**Description:**
Path to the YAML configuration file describing your microscope setup. This file defines available modalities, objectives, detectors, stage limits, and other hardware parameters.

**Offline / Analysis default:**
If this preference is empty on launch (a fresh install), QPSC auto-installs and
selects a bundled **Offline / Analysis** placeholder microscope at
`<QuPath user directory>/qpsc-offline-microscope/config_Offline.yml`. This lets
the extension load cleanly with no microscope and no command server: PPM analysis
and project utilities are available while hardware/acquisition workflows are
disabled. Point this preference at a real `config_<scope>.yml` (or run the Setup
Wizard) to enable acquisition. The placeholder is created once and never
overwritten.

**CRITICAL WARNING:**
Using the wrong configuration file could damage the microscope! Ensure this points to the correct configuration for your specific microscope system.

**Example:** `F:/QPScopeExtension/smartpath_configurations/microscopes/config_PPM.yml`

---

### Projects Folder

| Property | Value |
|----------|-------|
| Type | Directory |
| Default | (none) |
| Requires Restart | No |

**Description:**
Root folder where slide projects and acquisition data are stored. New projects are created as subdirectories within this folder.

**Structure:**
```
ProjectsFolder/
  +-- Sample001/
  |     +-- project.qpproj
  |     +-- SlideImages/
  +-- Sample002/
        +-- project.qpproj
        +-- SlideImages/
```

---

### Tissue Detection Script

| Property | Value |
|----------|-------|
| Type | File |
| Default | (none) |
| Requires Restart | No |

**Description:**
Optional Groovy script for automatic tissue detection before imaging. If specified, this script runs to identify tissue regions.

---

### Data Bounds Classifier

| Property | Value |
|----------|-------|
| Type | File (.json pixel classifier) |
| Default | (none) |
| Requires Restart | No |

**Description:**
A QuPath pixel classifier (`.json`) that segments the full-resolution acquired-slide image into "background/padding" vs "data" regions. The Microscope Alignment workflow uses this to compute the **bounding box of the actual data area** on the SVS/OME-TIFF; that box is paired with the green-box center detected on the macro image to build a safe macro-to-stage transform.

**Why this matters:** without an accurate data bounding box, the alignment math falls back to "use the entire image" -- which on a typical SVS includes the slide label and would let the stage drive into the label area. The workflow therefore refuses to save a transform when this preference is unset.

**Choosing a classifier per scanner / modality:** different sample classes have different "background" appearances. One classifier per sample class is fine; swap the preference when you switch sample classes. Examples:

| Sample class | Background appearance | Classifier intent |
|---|---|---|
| Ocus40 brightfield | White pyramid padding around tissue | Detect white -> inverse = tissue |
| Aperio SVS brightfield | White slide regions outside tissue | Same: white -> inverse = tissue |
| Hamamatsu mrxs brightfield | Black border around tissue | Detect black -> inverse = tissue |
| Widefield fluorescence | Dark/zero pixels outside lit area | Detect dark -> inverse = signal |
| Laser-scanning multi-channel | Dark zero-signal pixels | Same as widefield fluorescence |

**Building one:** in QuPath, train a 2-class pixel classifier (e.g. "Other" = background, anything else = data) on a representative full-resolution image, save as JSON, and point this preference at it. The alignment workflow runs `createAnnotationsFromPixelClassifier` then `makeInverseAnnotation()` -- so the classifier only needs to identify the background class accurately; the data class falls out by inversion.

**Behavior when the image has no padding:** if the classifier finds no background, the workflow uses the full image bounds (correct for already-cropped images).

**Note:** previously this functionality was hard-coded to `WhiteBackground.json` and inferred from the Tissue Detection Script's directory. As of 2026-04-29 it is a first-class preference. Existing rigs with `WhiteBackground.json` should point this preference directly at that file.

---

## Tissue Detection Parameters

These settings control the built-in artifact-aware tissue detection algorithm used by `MacroImageAnalyzer`. The algorithm identifies tissue regions on macro/overview images while filtering out non-tissue artifacts such as pen marks, dust, and slide edge debris. Algorithm inspired by LazySlide (Zheng et al. 2026, Nature Methods).

### Tissue Artifact Filter Enabled

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | ON |
| Requires Restart | No |

**Description:**
When enabled, tissue detection uses the ARTIFACT_FILTER method which computes `max(R-G, 0) * max(B-G, 0)` per pixel to identify non-tissue artifacts (pen marks, dust, colored debris). Pixels with high artifact scores are excluded before applying Otsu thresholding on grayscale intensity for tissue segmentation.

When disabled, tissue detection falls back to standard Otsu thresholding without artifact masking.

**When to Enable:**
- Slides with pen marks or colored labels near tissue
- Samples with dust or debris on the coverslip
- Any case where standard thresholding incorrectly includes non-tissue regions

**When to Disable:**
- Clean slides without artifacts
- Samples where the artifact filter incorrectly masks tissue (e.g., tissue with unusual color properties)

---

### Tissue Two-Pass Refine

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | OFF |
| Requires Restart | No |

**Description:**
When enabled, runs a second refinement pass after the initial tissue detection. The refinement pass can improve boundary accuracy for tissue regions with ambiguous edges.

**When to Enable:**
- Tissue regions have irregular or poorly defined boundaries
- Initial detection includes too much background at tissue edges
- High-precision region selection is required

---

### Tissue Median Kernel

| Property | Value |
|----------|-------|
| Type | Integer |
| Default | 17 |
| Range | 3-51 (must be odd) |
| Requires Restart | No |

**Description:**
Size of the median filter kernel applied to the binary tissue mask. The median filter removes salt-and-pepper noise (small isolated pixels) from the detection result. Larger values produce smoother masks but may lose fine tissue detail.

**Recommended Ranges:**
- **3-9**: Minimal smoothing, preserves small tissue fragments
- **11-21**: Moderate smoothing (default range), good balance
- **23-51**: Heavy smoothing, only large tissue regions retained

**Note:** The value must be odd. If an even number is provided, it will be rounded to the nearest odd value internally.

---

### Tissue Morph Close Kernel

| Property | Value |
|----------|-------|
| Type | Integer |
| Default | 7 |
| Range | 3-31 (must be odd) |
| Requires Restart | No |

**Description:**
Size of the structuring element for morphological closing (dilation followed by erosion). Closing fills small gaps and holes within detected tissue regions. Larger kernels bridge larger gaps but may merge separate tissue fragments.

**Recommended Ranges:**
- **3-5**: Fills tiny holes only
- **7-11**: Fills moderate gaps (default range), suitable for most tissue types
- **13-31**: Fills large holes, may merge nearby tissue regions

---

### Tissue Morph Close Iterations

| Property | Value |
|----------|-------|
| Type | Integer |
| Default | 3 |
| Range | 1-10 |
| Requires Restart | No |

**Description:**
Number of times the morphological closing operation is repeated. More iterations progressively fill larger gaps and smooth tissue region boundaries.

**Recommended Ranges:**
- **1-2**: Light cleanup, preserves tissue boundaries closely
- **3-5**: Moderate cleanup (default range), fills most internal holes
- **6-10**: Aggressive cleanup, produces very smooth regions

**Tip:** Increasing iterations has a similar effect to increasing the kernel size, but with finer control. Start with the default (3) and adjust if tissue regions appear fragmented.

---

## PPM Preferences

Settings specific to PPM (Polarization-Resolved Microscopy) acquisitions and image processing.

### High Bit Depth PPM Capture

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | OFF |
| Requires Restart | No |

**Description:**
When enabled, PPM angle frames are acquired at the camera's higher-bit PixelFormat (typically 12-bit or 16-bit instead of 8-bit) so the birefringence image is computed from genuinely high-precision inputs. This reduces quantization noise, especially visible in the dark crossed-polarizer regions where the denominator `I+ + I-` is small.

**Technical Detail:**
The `.biref` (birefringence) file has always been written as 16-bit, but was historically fed by 8-bit camera output, carrying only ~8 bits of real information. The normalized birefringence formula `|(I+ - I-)/(I+ + I-)|` is scale-invariant, so higher-bit inputs do **not** change the result's shape — they reduce quantization steps and noise. Autofocus remains 8-bit for speed; only the PPM angle-snap frames switch to the higher bit depth.

**Requirements:**
- The camera must have a `high_bit_depth` block configured in the detector entry of your microscope's YAML configuration (e.g., `resources_LOCI.yml`).
- If not configured, the preference is a safe no-op and the acquisition stays 8-bit.
- Existing white balance calibrations stay valid at high bit depth (WB is a set of ratios plus target level).

**When to Enable:**
- PPM acquisitions on thicker tissue where dark crossed-polarizer regions carry signal
- When birefringence images show excessive quantization noise
- When re-running PPM after commissioning the camera's high-bit-depth capability

**When to Keep OFF:**
- If your camera lacks the `high_bit_depth` YAML configuration (safe but pointless)
- During initial setup; switch on only after verifying high-bit frames are genuine higher precision (not left-shifted 8-bit)
- When bit-depth is not a limiting factor in your sample (thin tissue, bright birefringence)

---

### Birefringence Minimum Intensity

| Property | Value |
|----------|-------|
| Type | Integer |
| Default | 10 |
| Range | 0+ |
| Requires Restart | No |

**Description:**
Dark-region noise suppression threshold for birefringence computation on the server side. Pixels in the sum image `I+ + I-` below this threshold are zeroed during birefringence calculation (`(I+ - I-) / (I+ + I-)`), preventing division-by-small-number noise amplification.

The threshold is applied on the **bit-depth of the acquisition**:
- When **High Bit Depth PPM Capture** is OFF: threshold is on 8-bit scale (0-255)
- When **High Bit Depth PPM Capture** is ON: threshold is scaled to the camera's actual bit depth (typically 0-4095 for 12-bit)

**When to Adjust:**
- **Increase** (e.g., 20-30) if birefringence images show speckle noise in dark regions
- **Decrease** (e.g., 5-10) if tissue signal is being masked out in dim areas
- Start with the default (10) and adjust if image quality is poor

**Effect on Output:**
Thresholded pixels appear as zero (black) in the final birefringence image. The `.sum` (intensity) file is unaffected.

---

## Acquisition & Stitching

Settings that control image acquisition and stitching behavior.

### Z-Stack Options

Z-stack settings (enable, range, step, projection) are configured per-acquisition in the acquisition dialog under **Z-STACK OPTIONS**. These settings persist between sessions. See [Z-Stack / Time-Lapse](tools/z-stack-timelapse.md) for details.

---

### Time-Lapse Options

| Property | Type | Default |
|----------|------|---------|
| Enable time-lapse acquisition | Boolean | OFF |
| Timepoints | Integer | 1 |
| Interval (s) | Double | 60.0 |

**Description:**
Time-lapse settings are configured per-acquisition in the acquisition dialog under **TIME-LAPSE OPTIONS**. When enabled, the full region acquisition repeats over multiple timepoints at a fixed interval.

| Setting | Range | Purpose |
|---------|-------|---------|
| **Enable** | ON / OFF | Turns time-lapse loop on/off. When OFF, acquisition runs once (backward-compatible). |
| **Timepoints** | 1-10000 | Number of times the full acquisition is repeated. |
| **Interval (s)** | 0.0+ | Seconds between the *start* of consecutive timepoints. |

**Behavior:**
- If a timepoint takes longer than the requested interval, remaining timepoints start late and the acquisition continues.
- The first time a timepoint exceeds the interval, QPSC shows a one-time "falling behind" warning (modal dialog + push notification if configured).
- The warning is informational; the acquisition runs to completion regardless.
- The feature is backward-compatible: omitting time-lapse settings produces single-timepoint acquisitions identical to pre-time-lapse builds.

**Persistence:**
These settings persist globally across sessions and are restored when you open the acquisition dialog. See [Bounded Acquisition](tools/bounded-acquisition.md#time-lapse-options-collapsed-by-default) for UI details and how the preview folds timepoints into total-images and time estimates.

---

### Tile Handling Method

| Property | Value |
|----------|-------|
| Type | Choice |
| Default | None |
| Options | None, Zip, Delete |
| Requires Restart | No |

**Description:**
Controls what happens to intermediate tile images after stitching completes.

| Option | Behavior |
|--------|----------|
| **None** | Keep all tile images in the TempTiles folder |
| **Zip** | Compress tiles into a zip archive after stitching |
| **Delete** | Remove tile images after successful stitching |

**Important:** On acquisition or refinement failure, temporary tiles are always deleted regardless of this preference. The preference applies only to successful runs. This prevents incomplete or corrupt tile sets from accumulating on disk.

**Recommendations:**
- **None**: During debugging or when you might need to re-stitch
- **Zip**: For archival purposes with disk space management
- **Delete**: For production use to save disk space

---

### Tile Overlap Percent

| Property | Value |
|----------|-------|
| Type | Double |
| Default | 10.0 |
| Range | 0-50 |
| Requires Restart | No |

**Description:**
Percentage overlap between adjacent tiles during acquisition. Higher overlap improves stitching quality but increases acquisition time and storage.

| Overlap | Pros | Cons |
|---------|------|------|
| 0% | Fastest acquisition | May show seams at tile boundaries |
| 5% | Good balance | Slight seams possible |
| 10% | Recommended | Smooth stitching for most samples |
| 15%+ | Best quality | Longer acquisition time |

---

### Compression type

| Property | Value |
|----------|-------|
| Type | Choice |
| Default | DEFAULT |
| Requires Restart | No |
| **Visibility** | **Only shown when tiles-to-pyramid extension is installed** |

**Description:**
Compression algorithm for OME pyramid output files. This preference is only relevant when stitching is enabled.

| Option | Description |
|--------|-------------|
| **DEFAULT** | Platform default (usually LZW) |
| **UNCOMPRESSED** | No compression (fastest write, largest files) |
| **LZW** | Lossless compression (good balance) |
| **JPEG** | Lossy compression (smaller files, some quality loss) |
| **J2K** | JPEG2000 compression |
| **J2K_LOSSY** | Lossy JPEG2000 |
| **ZLIB** | Zlib compression |

**Dependencies:**
Like [Stitching output format](#stitching-output-format), this preference requires the `qupath-extension-tiles-to-pyramid` extension. If tiles-to-pyramid is not installed, this preference is hidden.

---

### Stitching output format

| Property | Value |
|----------|-------|
| Type | Choice |
| Default | OME_TIFF |
| Options | OME_TIFF, OME_ZARR |
| Requires Restart | No |
| **Visibility** | **Only shown when tiles-to-pyramid extension is installed** |

**Description:**
Output format for stitched images.

| Format | Pros | Cons |
|--------|------|------|
| **OME_TIFF** | Widely compatible, single file, standard format | Slower to write, larger files |
| **OME_ZARR** | 2-3x faster writing, 20-30% smaller, cloud-native | Directory format, less commonly used |

**Dependencies:**
This preference requires the `qupath-extension-tiles-to-pyramid` extension. If tiles-to-pyramid is not installed, this preference is hidden and stitching workflows are disabled. You can still use QPSC for analysis, utilities, and other non-stitching features. To enable stitching, install the tiles-to-pyramid extension JAR in your QuPath extensions folder and restart QuPath. See [Installation Guide](INSTALLATION.md) and [Troubleshooting](TROUBLESHOOTING.md) for more details.

**Note:** OME-ZARR is an emerging standard that offers significant performance benefits. To get a single-file OME-TIFF from a ZARR-backed project, use **Make Project Portable** (Extensions > QPSC > Utilities).

**Pyramid correctness:** OME-TIFF stitching uses a direct tiled-pyramidal writer that handles partial edge tiles correctly, so stitched mosaics whose dimensions are not a clean multiple of the tile size no longer produce corrupt (black) pyramid levels. The earlier `OME_TIFF_VIA_ZARR` format and its automatic retry/escalation were workarounds for that writer bug and have been removed.

---

### Stitching concurrency

| Property | Value |
|----------|-------|
| Type | Integer |
| Default | 4 |
| Minimum | 1 |
| Requires Restart | No |

**Description:**
Maximum number of angles (PPM) or channels (fluorescence) stitched **simultaneously within a single annotation**. Each angle/channel is an independent stitch writing its own output file, so they run concurrently up to this limit.

- Higher values stitch multi-angle and multi-channel acquisitions faster, at the cost of more memory and more concurrent writers running at once.
- `1` makes stitching fully sequential (one angle/channel at a time) -- useful if you hit memory pressure or want to isolate a stitching problem.
- This bounds parallelism **within** an annotation only. Different annotations still stitch one at a time (their acquisition is inherently sequential because the stage moves between them), so stitching of one annotation overlaps acquisition of the next without piling up unbounded work.

Applies to both OME-TIFF and OME-ZARR output. The default of 4 is a balance that speeds up the common 4-angle PPM and ~4-channel IF cases while keeping peak memory bounded.

---

### Enable Multi-Slide Workflow (experimental)

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | OFF |
| Requires Restart | No |

**Description:**
Adds an experimental Multi-Slide Existing Image entry to the QP Scope menu. The menu item appears and disappears **live** when you toggle this preference — **no restart required**. When enabled, the workflow assigns project macro images to slot positions in a multi-slide carrier (e.g. the 4-slide vertical holder) and guides you through acquisition on each slot. You can run slots one at a time manually, or use semi-automated or fully unattended two-pass batch modes.

**When to Enable:**
- You are using a multi-slide carrier (quad_v or similar)
- You have a project with macro entries (one per slide) that are ready to acquire from
- You want to run the same acquisition workflow on multiple slides in sequence

**When to Disable:**
- Hide the MS-Existing Image menu entry when you don't need multi-slide batch acquisition
- Return to single-slide workflows (the regular Bounded Acquisition and Acquire from Existing Image entries remain unaffected)

**See Also:** [MS-Existing Image (experimental)](WORKFLOWS.md#ms-existing-image-experimental) workflow documentation.

---

### Reuse saved alignment (TESTING ONLY)

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | OFF |
| Requires Restart | No |

**Description:**
**TESTING ONLY — UNSAFE for real acquisition.** When enabled, the multi-slide batch workflow reuses each slot's saved per-slide alignment JSON instead of re-aligning, skipping SIFT/manual alignment and single-tile refinement. A confirmation dialog appears at the start of each batch asking you to confirm the reuse decision; the same decision is cached so you are not prompted for every slot.

**Critical assumption:** This feature assumes every slide is **still physically mounted exactly as it was when its alignment was captured**. Any remount (removing and replacing a slide) invalidates the reused alignment, causing mis-positioned acquisitions.

**Behavior when enabled:**
1. A confirmation dialog appears at the start of each multi-slide batch run
2. Clicking "OK" reuses saved per-slide alignment (skips alignment / refinement)
3. Clicking "Cancel" runs fresh alignment on all slots (safe)
4. Slots with no valid saved alignment always align fresh (per-slot fallback)
5. The confirmation choice is cached for the batch, so you are not re-prompted per slot

**When to Enable:**
- Iterative on-scope testing where you know the holder has not been touched
- Debugging or validation runs on the same pre-aligned slides
- Speeding up repeated test acquisitions on a frozen holder configuration

**When to Keep OFF:**
- **Always OFF for real acquisition** — any slide remount invalidates the transforms
- Default production setting; only toggle ON for specific test scenarios

**See Also:** [MS-Existing Image (experimental)](WORKFLOWS.md#ms-existing-image-experimental) workflow documentation.

---

### Stitched output organization (multi-channel acquisitions)

| Property | Value |
|----------|-------|
| Type | Choice |
| Default | Single combined file |
| Options | Single combined file, Separate file per channel |
| Requires Restart | No |

**Description:**
Controls how stitched channels are grouped into files during multi-channel acquisitions (widefield fluorescence and BF+IF). This is a global per-acquisition preference and is selected via a dropdown in the acquisition dialog.

| Option | Behavior |
|--------|----------|
| **Single combined file** | All channels (except those marked "Split" in the channel picker) are merged into one multichannel OME-TIFF, imported to the project as a single entry. Channels marked "Split" are imported as separate entries. |
| **Separate file per channel** | Every channel is written as its own stitched OME-TIFF, imported as separate project entries. Per-channel "Split" checkboxes in the picker are ignored. |

**Per-channel override:** Individual channels can be marked "Split" in the channel picker (when the master "Customize" checkbox is enabled) to override this global setting — a split channel will be written as its own file even when "Single combined file" is selected.

**Merged vs split filenames:**
- **Merged files** use a modality prefix: `<sample>_<short-modality>_NNN.ome.tif` (e.g. `PollenIF_fl_001.ome.tif`)
- **Split files** use the channel id: one per-channel stitched pyramid, imported under its own entry

**Project import behavior:**
- When "Single combined file" is used with no split channels, only the merged file is imported; per-channel intermediates stay on disk as recovery artifacts.
- When "Separate file per channel" is used, or when channels are split individually, all stitched files are imported as separate entries.

---

## Filename Configuration

Settings that control what information appears in image filenames.

**Important:** ALL acquisition metadata is always stored in QuPath regardless of filename settings. These preferences only affect the visible filename.

### Image name includes: Objective

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | OFF |
| Requires Restart | No |

**Description:**
Adds objective/magnification to image filenames.

**Examples:**
- OFF: `SampleName_001.ome.tif`
- ON: `SampleName_20x_001.ome.tif`

---

### Image name includes: Modality

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | OFF |
| Requires Restart | No |

**Description:**
Adds imaging modality to image filenames.

**Examples:**
- OFF: `SampleName_001.ome.tif`
- ON: `SampleName_ppm_001.ome.tif`

---

### Image name includes: Annotation

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | OFF |
| Requires Restart | No |

**Description:**
Adds annotation name to filenames when acquiring specific regions.

**Examples:**
- OFF: `SampleName_001.ome.tif`
- ON: `SampleName_Tissue_001.ome.tif`

---

### Image name includes: Angle

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | ON |
| Requires Restart | No |

**Description:**
Adds polarization angle to filenames for multi-angle acquisitions. **Critical for PPM modality** to distinguish images at different angles.

**Examples:**
- OFF: `SampleName_001.ome.zarr` (ambiguous for multi-angle)
- ON: `SampleName_001_7.0.ome.zarr`, `SampleName_001_-7.0.ome.zarr`

**Warning:** Disabling this for PPM acquisitions makes it difficult to distinguish between angle images.

---

## Metadata Configuration

Settings for metadata handling across images.

### Metadata Propagation Prefix

| Property | Value |
|----------|-------|
| Type | String |
| Default | OCR |
| Requires Restart | No |

**Description:**
Prefix for metadata keys that should be automatically copied from parent images to acquired child images. Any metadata key starting with this prefix will be propagated.

**Use Case:**
If parent image has OCR-extracted text like:
- `OCR_PatientID`: "12345"
- `OCR_SlideLabel`: "H&E Stain"

These will be automatically copied to child images acquired from that parent.

**Examples:**
- `OCR` - Copies OCR_PatientID, OCR_SlideLabel, OCR_Date, etc.
- `LIMS_` - Copies LIMS_CaseNumber, LIMS_Accession, etc.
- `custom_` - Copies any custom_ prefixed metadata

---

## Safety Options

Settings that affect acquisition safety and reliability.

### No Manual Autofocus (Danger)

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | OFF |
| Requires Restart | No |

**Description:**
**WARNING:** Enabling this may result in out-of-focus imaging regions!

When enabled, the manual autofocus dialog never appears. If autofocus fails, the system automatically retries once and then continues with whatever focus level results.

**Only enable for:**
- Unattended overnight acquisitions
- Samples where some out-of-focus regions are acceptable
- Testing or debugging scenarios

**Risks:**
- Entire regions may be out of focus
- No user intervention possible during acquisition
- Quality control must be performed after acquisition

---

### Disable All Autofocus (Danger)

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | OFF |
| Requires Restart | No |

**Description:**
**WARNING:** Enabling this means *no* autofocus will run on the acquisition. Z drift will not be corrected. Use only when you've manually staged a known-good Z (or when running a fast scan over a sample whose drift over the acquisition window is negligible).

When enabled, the Java side emits `--af-disabled` on the ACQUIRE wire payload (in place of the `--af-tiles`/`--af-steps`/`--af-range` triplet). The server's `_configure_autofocus` short-circuits on this flag: no autofocus YAML load required, no AF positions scheduled, no pre-acquisition AF, no per-tile sweep autofocus, no manual-focus prompts. Server log shows a single `Autofocus DISABLED for this acquisition` line at workflow start.

This is broader than [No Manual Autofocus](#no-manual-autofocus-danger): No-Manual still runs AF and only suppresses the user prompt on failure; Disable-All-Autofocus skips AF entirely.

**This preference is the single on/off switch.** The Acquisition Wizard shows a read-only `Autofocus: enabled / DISABLED` status that reflects this preference and links you back here -- there is no separate wizard toggle. (A former wizard "Disable Autofocus" checkbox wrote this very preference; it was removed because it made operators think there were two independent settings.)

**Only enable for:**
- Test runs with a manually-set Z
- Samples where Z drift over the acquisition is known-small
- Diagnostic runs (debugging acquisition timing without AF noise)

**Risks:**
- No drift correction at all
- Sample drift over a long acquisition will gradually defocus
- No way for the system to recover from a Z mistake

---

## Live Viewer

Display options for the Live Viewer window. Both are also reachable from the Live
Viewer itself (the **XYZ** toolbar button toggles the overlay).

### Live Viewer: Show Position Overlay

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | OFF |
| Requires Restart | No |

**Description:**
Overlays the current stage position on a corner of the live image as a monospace
readout: `X ... Y ... Z ...`, and `R ...` on rotation scopes (PPM). The value
updates ~2 Hz from the shared stage-position poller (no extra hardware polling).
This preference is bound to the **XYZ** toggle button on the Live Viewer toolbar,
so toggling either one updates the other.

### Live Viewer: Position Overlay Text Size

| Property | Value |
|----------|-------|
| Type | Choice (Tiny / Small / Medium / Large / Huge) |
| Default | QuPath's viewer location-text size |
| Requires Restart | No |

**Description:**
Text size for the position overlay above. The default mirrors QuPath's own viewer
location-text size (Edit > Preferences > Viewer > "Location font size"), so the
readout matches the rest of the application; change it here to make the overlay
larger or smaller independently.

### Live Viewer: Dock Histogram Right

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | OFF |
| Requires Restart | No |

**Description:**
Controls where the Histogram & Contrast + Noise Stats group sits in the Live
Viewer. When OFF (default) the group is docked **below** the image as a horizontal
strip. When ON the group moves to the **far-right edge** of the window as a vertical
column -- the stage-control panel stays next to the image, and the histogram
reorients vertically and fills the full column height (the sliders run vertically;
labels and buttons stay horizontal for readability). This preference is bound to the
**Hist: Right** toggle button on the Live Viewer toolbar, so toggling either one
updates the other. Useful on wide monitors where vertical space below the image is
scarce.

---

## White Balance Mode

White balance mode is no longer a global preference. Instead, WB mode is selected per-acquisition from a dropdown in the acquisition dialog:

| WB Mode | Description |
|---------|-------------|
| **Off** | No white balance applied |
| **Camera AWB** | Camera auto white balance (one-shot at 90 degrees) |
| **Simple** | Uniform exposure adjustment across all angles |
| **Per-angle** | Per-angle calibrated exposures from WB calibration |

This dropdown appears in the Bounding Box acquisition dialog, Existing Image acquisition dialog, and Background Collection dialog. The selected mode flows through to the `--wb-mode` command parameter.

**Note:** The previous `JAI White Balance Mode` preference (SIMPLE/PPM) has been removed in v0.3.0.

---

## Loop Order (Persistent Preferences)

The per-tile snap-loop inner-axis preference stores the user's choice of loop nesting (Z-inner vs channel-inner for widefield, or angle-inner vs z-inner for PPM) on a per-modality-family basis. These keys are backed by QuPath's standard `PathPrefs` persistent-preference mechanism (the same store as `qpscZStackEnabled` and the rest of the Z-stack block) and are written whenever the user picks the alternative radio in the loop-order toggle of the Z-stack acquisition dialog.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `qpscAcqLoopOrder.widefield` | String | `"z"` | Loop nesting choice for widefield / fluorescence multi-channel acquisitions. Values: `"z"` (Z per channel, default -- channel-outer / z-inner nesting, fewer filter changes) or `"channel"` (channels per Z -- z-outer / channel-inner, slower but tightly z-registered). |
| `qpscAcqLoopOrder.ppm` | String | `"angle"` | Loop nesting choice for PPM multi-angle acquisitions. Values: `"angle"` (angle per Z, default -- z-outer / angle-inner, the historical PPM ordering) or `"z"` (z per angle -- angle-outer / z-inner, fewer rotation moves on thicker z-stacks). |

The loop-order toggle appears in both the Bounded Acquisition and Existing Image Acquisition dialogs whenever Z-stack is enabled AND the active modality is widefield (2+ channels selected) or PPM. The user's choice for each family is stored in these preferences and restored when that family is selected again, even across sessions. See [Z-Stack / Time-Lapse > Loop-order toggle](tools/z-stack-timelapse.md#loop-order-toggle-added-2026-05-14) for the user-facing rationale.

### Resetting

There is no in-app reset button specific to these keys. They live in QuPath's standard user-preferences XML alongside every other `PathPrefs`-backed preference (path varies per OS / QuPath version -- on Linux / WSL typically under `~/.java/.userPrefs/qupath/`). Deleting the `qpscAcqLoopOrder.widefield` and `qpscAcqLoopOrder.ppm` entries there resets both families to their per-modality defaults. Restart QuPath after editing the XML.

---

## Background Collection Exposure Mode (Persistent Preferences)

The Background Collection dialog's three-radio "Exposure mode" selector (visible for brightfield and PPM on monochrome cameras) stores the user's pick per modality family so the next opening of the dialog restores it. These keys are backed by the same `PathPrefs` mechanism as the loop-order block above.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `qpscBgExposureMode.brightfield` | String | `"PROFILE"` | Exposure mode for monochrome BF. Values: `"PROFILE"` (use the selected profile's exposure, no adaptive adjustment), `"TARGET"` (adaptive: server iterates from starting exposure to hit the target intensity; no profile binding), `"OVERRIDE"` (profile bound, but adaptive target overwrites the profile's nominal exposure -- confirmation required at Start). |
| `qpscBgExposureMode.ppm` | String | `"TARGET"` | Exposure mode for monochrome PPM. Same values as the brightfield key. PPM defaults to adaptive to preserve the existing behavior PPM monochrome relied on before this selector existed. |

The selector is hidden for RGB cameras (where WB mode drives exposure semantics), for PPM with WB Simple / Per-angle (where exposures live in the WB calibration), and for fluorescence modalities (where per-channel exposures come from the selected profile's channel table). On those paths the keys are not read.

### Resetting

No in-app reset; delete the `qpscBgExposureMode.brightfield` and `qpscBgExposureMode.ppm` entries from QuPath's user-preferences XML (same path as the loop-order keys above) and restart QuPath. Both families fall back to their per-modality defaults.

---

## Channel Picker (Persistent Preferences)

Preferences persisted by the multi-channel acquisition UI (`WidefieldChannelBoundingBoxUI`). These keys do **not** appear in QuPath's Preferences panel -- they are stored as dynamic string preferences under the QPSC extension's `dynamic` Java Preferences node and are written whenever the user ticks a checkbox or edits an exposure spinner in the channel picker. They are listed here for developers, for debugging, and for users who want to reset the picker to a clean state.

See [CHANNELS.md](CHANNELS.md) for how channels are configured in YAML, and [WORKFLOWS.md](WORKFLOWS.md#multi-channel-acquisition-widefield-if-bfif) for what the picker looks like.

### Key layout

All keys live under the prefix `widefield.channel.`:

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `widefield.channel.master_override_enabled` | String ("true" / "false") | `false` | State of the master **"Customize channel selection for this acquisition"** checkbox. When `false`, every library channel is acquired at its YAML default exposure and the per-channel rows below are ignored. When `true`, only checked channel rows are acquired and their per-channel spinner values are used. |
| `widefield.channel.<id>.selected` | String ("true" / "false") | `false` | Whether channel `<id>` is checked in the picker. Only consulted when `master_override_enabled` is `true`. |
| `widefield.channel.<id>.exposure_ms` | String (double) | Channel's library `exposure_ms` (from YAML, with profile overrides applied) | Per-channel exposure in milliseconds, as edited in the picker spinner. Only consulted when `master_override_enabled` is `true` and the channel is selected. |
| `widefield.channel.<id>.intensity` | String (double) | Channel's library `intensity_property` value | Per-channel intensity, as edited in the picker spinner (only present if the channel declares `intensity_property` in YAML). Only consulted when `master_override_enabled` is `true`. |
| `widefield.channel.<id>.split` | String ("true" / "false") | `false` | Whether channel `<id>` is marked for split output (written as its own stitched file instead of merging into the multichannel file). Only consulted when `master_override_enabled` is `true` and the channel is selected. Ignored when the "Stitched output" dropdown (persisted as `qpscStitchOrganization`) is set to "Separate file per channel" (all channels are split in that mode). |
| `widefield.channel.focus_channel` | String | (empty) | The channel id selected as the autofocus reference channel. Persisted across sessions and dialogs. |
| `widefield.channel.preset.names` | String (TAB-separated list) | (empty) | List of saved preset names, delimited by TAB characters. Used internally to populate the Preset dropdown. |
| `widefield.channel.preset.<safeKey>` | String (pipe-delimited blob) | (none) | Preset data for the preset named `<safeKey>`. Format: `v1\|focus=<id>\|<chId>=<sel>:<exp>:<int>\|...` where `sel` is true/false, `exp` and `int` are doubles. `<safeKey>` is the preset name lowercased and with non-alphanumerics replaced by underscores. |
| `widefield.channel.preset.last` | String | (empty) | The name of the last-selected preset, restored when the dialog reopens. |

`<id>` is the channel id declared in the YAML library (e.g. `DAPI`, `FITC`, `BF`).

### Resetting

There is currently no in-app "reset channel picker" button. To wipe channel picker state cleanly, delete the `widefield.channel.*` entries from the QPSC extension's Java Preferences store:

- **Windows:** `HKEY_CURRENT_USER\Software\JavaSoft\Prefs\qupath\ext\qpsc\preferences\dynamic`
- **Linux / WSL:** `~/.java/.userPrefs/qupath/ext/qpsc/preferences/dynamic/prefs.xml`
- **macOS:** `~/Library/Preferences/qupath.ext.qpsc.preferences.plist`

Delete the entries whose names start with `widefield.channel.` (or delete the whole `dynamic` node to reset every dynamic QPSC preference at once). Restart QuPath afterwards.

Alternatively, simply un-tick the master "Customize channel selection" checkbox before running an acquisition -- the per-channel keys stay on disk but are ignored, and the acquisition uses library defaults as if the picker had never been touched.

---

## SIFT Auto-Alignment

Preferences controlling the SIFT feature matching used for automated alignment refinement in single-tile and multi-tile refinement modes.

| Preference | Type | Default | Description |
|------------|------|---------|-------------|
| Trust SIFT alignment | Boolean | OFF | When enabled, SIFT runs automatically during single-tile or multi-tile refinement without showing the manual dialog. If confidence exceeds the threshold, the refined position is accepted automatically. Falls back to manual if SIFT fails or confidence is too low. |
| SIFT confidence threshold | Double | 0.5 | Minimum inlier ratio (0.0-1.0) required to auto-accept alignment when Trust SIFT is enabled. Higher values are stricter. 0.5 means at least 50% of matched features must be RANSAC inliers. |
| SIFT min pixel size (um) | Double | 1.0 | Minimum resolution for SIFT matching. Both images are downsampled to at least this pixel size before feature detection. Suppresses JPEG compression artifacts and speeds up matching. Lower values for high-magnification objectives (e.g., 63x oil where 1 um/px is a large fraction of the FOV). |
| SIFT coarse-to-fine enabled | Boolean | ON | When enabled, SIFT auto-align runs a two-pass search: a heavily downsampled coarse pass over the whole search region to find a rough offset, then a full-resolution fine pass over a small crop around the coarse result. Allows larger search margins without paying the cost of full-resolution matching over the whole region. Applies to all SIFT auto-align scopes. |
| SIFT coarse pixel size (um) | Double | 4.0 | Target resolution (um/pixel) for the coarse pass. Coarser than the fine target (SIFT min pixel size above) so the whole region matches quickly. Only used when coarse-to-fine is enabled and this value is coarser than the fine target. |

**Cross-modality preprocessing (16-bit camera vs 8-bit WSI):** Five additional knobs handle the case where the microscope camera produces 16-bit data (typically using only the lower 12-14 bits) and the reference is an 8-bit H&E or PPM scan. The legacy `/256` bit-shift would compress the useful camera range to a sliver of 8-bit and collapse SIFT matching against the brighter WSI. These prefs are surfaced in the SIFT Settings dialog (accessible from the refinement step's Auto-Align panel).

| Preference | Type | Default | Description |
|------------|------|---------|-------------|
| SIFT mono normalization | Enum | `PERCENTILE` | Mode used when converting >8-bit grayscale to 8-bit before SIFT. `PERCENTILE` stretches between the configured low/high percentiles (recommended). `MIN_MAX` stretches across the full per-image min/max range. `BIT_SHIFT` reproduces the legacy `>>8` behavior for back-compat. |
| SIFT percentile low | Double | 2.0 | Lower clip percentile (0-100) used by `PERCENTILE` mode. Pixels below this map to 0. |
| SIFT percentile high | Double | 98.0 | Upper clip percentile (0-100) used by `PERCENTILE` mode. Pixels above this map to 255. |
| SIFT CLAHE enabled | Boolean | ON | Apply Contrast-Limited Adaptive Histogram Equalisation to both microscope and WSI grayscale images before SIFT. Standard cross-modality robustness trick; dramatically improves matching of monochrome brightfield against H&E references. |
| SIFT CLAHE clip limit | Double | 2.0 | CLAHE `clipLimit`. Higher values produce more aggressive equalisation. |

**When to adjust these:**
- **Trust SIFT**: Enable when alignment is reliable and you want fully unattended acquisition
- **Confidence threshold**: Lower (e.g., 0.3) if tissue has few features; raise (e.g., 0.7) for critical alignment
- **Min pixel size**: Lower (e.g., 0.3) for 40x+ objectives; raise (e.g., 2.0) for 4x objectives or heavily compressed WSIs
- **Coarse-to-fine enabled**: Keep enabled for large WSI regions or wide search margins. Disable if you have a fast computer and want a single-pass search (marginally simpler debugging).
- **Coarse pixel size**: Raise (e.g., 8.0) to search faster and further, at the cost of a coarser initial estimate. Lower (e.g., 2.0) for more precision on smaller regions. The Stage Map shows the search range as a bright-orange dashed rectangle while the refinement dialog is open, updating live as you change the search margin.
- **Search margin**: Raise to enlarge the area SIFT searches around the predicted position (how far off the stage can be and still match). Coarse-to-fine keeps large margins fast. The bright-orange box on the Stage Map shows the resulting area; range 50-5000 um.
- **Mono normalization**: Leave on `PERCENTILE` unless you have a specific calibrated reason. Switch to `BIT_SHIFT` only when reproducing legacy alignment runs.
- **Percentile low/high**: Tighten (e.g., 5/95) to suppress speckle/noise; widen (e.g., 0.5/99.5) when matching very faint features against a bright reference.
- **CLAHE clip limit**: Raise (e.g., 3.0-4.0) for very low-contrast samples; lower (e.g., 1.0) when CLAHE is amplifying noise into spurious feature matches.

---

## Alerts (QuPath SCope Alerts)

Notification settings are in a separate preference category: **QuPath SCope Alerts**.

| Preference | Type | Default | Description |
|------------|------|---------|-------------|
| Play beep on completion | Boolean | ON | System beep when workflow finishes |
| Enable ntfy.sh notifications | Boolean | OFF | Push notifications to phone via ntfy.sh |
| ntfy.sh topic | String | (empty) | Topic name (must match phone app subscription) |
| ntfy.sh server | String | https://ntfy.sh | Server URL (change for self-hosted) |
| Notify on: Acquisition complete | Boolean | ON | Send notification on acquisition success |
| Notify on: Stitching complete | Boolean | ON | Send notification on stitching success |
| Notify on: Errors | Boolean | ON | Send notification on workflow errors |

### Setting Up Phone Notifications

1. Install the **ntfy** app on your phone ([Android](https://play.google.com/store/apps/details?id=io.heckel.ntfy) / [iOS](https://apps.apple.com/us/app/ntfy/id1625396347))
2. In the app, subscribe to a topic (e.g., `loci-ppm-a7f3x`)
3. In QuPath Preferences > QuPath SCope Alerts:
   - Enable ntfy.sh notifications
   - Enter the same topic name
4. Notifications arrive on your phone when workflows complete or fail

**Privacy note:** Use a random/unique topic name -- ntfy.sh topics are public by default. Only sample name, tile count, and error type are sent (never file paths or patient data).

---

## Configuration Files Reference

QPSC uses several YAML configuration files:

| File | Purpose | Location |
|------|---------|----------|
| `config_PPM.yml` | Main microscope configuration | Set in Microscope Config File preference |
| `resources_LOCI.yml` | Shared hardware definitions | Same folder as config file |
| `autofocus_PPM.yml` | Per-objective autofocus settings | Same folder as config file |
| `imageprocessing_PPM.yml` | White balance calibrations | Same folder as config file |

---

## Recommended Initial Setup

**Easiest path:** Use the **[Setup Wizard](tools/setup-wizard.md)** (Extensions > QP Scope > Setup Wizard). It creates all YAML configuration files and sets the Microscope Config File, Server Host, and Server Port preferences automatically.

**Manual setup:** Configure these preferences in order:

1. **Microscope Config File** - Point to your microscope's YAML config
2. **Projects Folder** - Set your preferred data storage location
3. **Microscope Server Host/Port** - Configure to match your server
4. **Tile Overlap Percent** - Start with 10% default
5. **Inverted Y stage** - Usually ON for most microscopes

Then verify settings using:
- Extensions > QP Scope > Communication Settings > Test Connection
- Extensions > QP Scope > Live Viewer (test camera feed and stage movement)
- Extensions > QP Scope > Microscope Alignment (verify coordinates)

---

## See Also

- [Utilities Reference](UTILITIES.md) - All utilities with options explained
- [Workflows Guide](WORKFLOWS.md) - Step-by-step workflow documentation
- [Troubleshooting Guide](TROUBLESHOOTING.md) - Common issues and solutions
