# QPSC Utilities Reference

This document provides an overview of all utilities available in the QPSC extension. Click any tool name for full documentation including all options, workflow details, and troubleshooting.

![QP Scope main menu](images/Docs_mainmenu.png)

The **QP Scope** entry in the menu bar shows a coloured dot so it stands out when multiple QuPath extensions are installed. The dot colour and visibility are configured in `Preferences > QuPath Scope > Show menu colour dot` and `Menu dot colour`.

---

## Quick Reference

| Utility | Purpose | Menu Location |
|---------|---------|---------------|
| **Top-Level Menu** | | |
| [Acquisition Wizard](tools/acquisition-wizard.md) | Guided setup for new acquisitions | Extensions > QP Scope > Acquisition Wizard... |
| Bounded Acquisition | Direct bounding-box acquisition from stage coordinates | Extensions > QP Scope > Bounded Acquisition |
| Existing Image Acquisition | Targeted acquisition from annotations on existing images | Extensions > QP Scope > Acquire from Existing Image |
| [Live Camera Viewer](tools/live-viewer.md) | Real-time camera feed with integrated stage control | Extensions > QP Scope > Live Viewer |
| [Camera Control](tools/camera-control.md) | View/test camera exposure and gain settings | Extensions > QP Scope > Camera Control... |
| [Stage Map](tools/stage-map.md) | Visual map with slide positions and macro overlay | Extensions > QP Scope > Stage Map |
| [Report a Bug...](#report-a-bug) | Submit a bug report directly to the issue tracker | Extensions > QP Scope > Report a Bug... |
| **Utilities Submenu** | | |
| Microscope Alignment | Semi-automated alignment between QuPath and microscope | Extensions > QP Scope > Utilities > Microscope Alignment... |
| [Background Collection](tools/background-collection.md) | Capture flat-field correction images | Extensions > QP Scope > Utilities > Collect Background Images |
| [WB Comparison Test](tools/wb-comparison-test.md) | Compare white balance modes side-by-side | Extensions > QP Scope > Utilities > WB Comparison Test... |
| [Autofocus Editor](tools/autofocus-editor.md) | Configure per-objective autofocus parameters | Extensions > QP Scope > Utilities > Autofocus Configuration Editor... |
| [Autofocus Benchmark](tools/autofocus-benchmark.md) | Find optimal autofocus settings systematically | Extensions > QP Scope > Utilities > Autofocus Parameter Benchmark... |
| [Parfocality Calibration](tools/parfocality-calibration.md) | Capture per-profile Z offsets so the stage refocuses when switching modality | Extensions > QP Scope > Utilities > Calibrate Parfocality... |
| [Z-Stack / Time-Lapse](tools/z-stack-timelapse.md) | Single-tile Z-stack or time-lapse acquisition | Extensions > QP Scope > Utilities > Z-Stack / Time-Lapse... |
| [Propagation Manager](tools/propagation-manager.md) | Transfer objects between base and sub-images | Extensions > QP Scope > Utilities > Propagation Manager... |
| Re-stitch Tiles | Re-stitch tiles from a failed or incomplete acquisition | Extensions > QP Scope > Utilities > Re-stitch Tiles... |
| Stitch MicroManager Folder | Standalone stitching of MicroManager OME-TIFF tiles (no project required) | Extensions > QP Scope > Utilities > Stitch MicroManager Folder... |
| [Setup Wizard](tools/setup-wizard.md) | Create microscope config files (first-time setup) | Extensions > QP Scope > Utilities > Setup Wizard... |
| [Communication Settings](tools/server-connection.md) | Configure server connection and notification alerts | Extensions > QP Scope > Utilities > Communication Settings... |
| Make Project Portable | Convert or zip ZARR-backed images and clean up raw tile folders for portability | Extensions > QP Scope > Utilities > Make Project Portable... |
| **JAI Camera Submenu** (conditional -- only when JAI detected) | | |
| [White Balance Calibration](tools/white-balance-calibration.md) | Calibrate JAI 3-CCD camera white balance | Extensions > QP Scope > Utilities > JAI Camera > White Balance... |
| [JAI Noise Characterization](tools/noise-characterization.md) | Measure camera noise statistics | Extensions > QP Scope > Utilities > JAI Camera > Noise Characterization... |
| **PPM Modality Submenu** (conditional -- only with PPM modality) | | |
| [Polarizer Calibration](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/polarizer-calibration.md) | Calibrate polarizer rotation stage | Scope > PPM > Polarizer Calibration (PPM)... |
| [PPM Reference Slide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-reference-slide.md) | Hue-to-angle calibration from sunburst slide | Scope > PPM > PPM Reference Slide... |
| [PPM Sensitivity Test](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-sensitivity-test.md) | Test rotation stage precision | Scope > PPM > PPM Rotation Sensitivity Test... |
| [PPM Birefringence Optimization](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-birefringence-optimization.md) | Find optimal polarizer angle for maximum contrast | Scope > PPM > PPM Birefringence Optimization... |
| **PPM Analysis Extension** (separate extension) | | |
| [PPM Hue Range Filter](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-hue-range-filter.md) | Interactive HSV filtering for PPM images | Extensions > PPM Analysis > PPM Hue Range Filter... |
| [PPM Polarity Plot](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-polarity-plot.md) | Polar histogram visualization of fiber orientations | Extensions > PPM Analysis > PPM Polarity Plot... |
| [Surface Perpendicularity](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/surface-perpendicularity.md) | Fiber orientation relative to annotation boundaries | Extensions > PPM Analysis > Surface Perpendicularity... |
| [Batch PPM Analysis](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/batch-ppm-analysis.md) | Batch processing of PPM image sets | Extensions > PPM Analysis > Batch PPM Analysis... |

---

## Imaging & Stage Control

### [Live Camera Viewer](tools/live-viewer.md)

Real-time camera feed with integrated stage control, histogram, noise statistics, and **snapshot capture** (save frames as OME-TIFF with optional flat-field correction). The primary tool for verifying microscope communication, positioning the stage, and checking camera settings before acquisition. Includes a virtual joystick, saved stage positions, and per-channel saturation monitoring.

### [Camera Control](tools/camera-control.md)

View and test camera exposure and gain settings loaded from calibration profiles. Particularly useful for JAI 3-CCD camera white balance troubleshooting. Displays per-angle cards with exposure, gain, and an Apply button that sets camera settings and rotates the polarizer.

### [Stage Map](tools/stage-map.md)

Visual representation of the microscope stage insert showing slide positions and current stage location. Supports configurable macro image overlay for navigation context. Includes a scanner preset selector for choosing which scanner-to-stage alignment to use for the overlay (e.g., "Ocus40 to PPM").

If the microscope config has no `stage.inserts` block, the Stage Map synthesizes a single-slide insert at the center of `stage.limits` using `slide_size_um` for the slide footprint. This lets scopes with only basic stage-limits calibration get a working Stage Map without an explicit per-instrument insert block.

---

## Connection & Configuration

### [Setup Wizard](tools/setup-wizard.md)

Step-by-step wizard that creates all required microscope YAML configuration files. Appears automatically as the first menu item when no valid configuration is found. Guides users through hardware selection (objectives, detectors, stage), pixel size calibration, modality setup, and server connection. Uses a bundled catalog of known LOCI hardware for quick selection.

### Register Current Objective

Adds the objective currently in the light path to the microscope configuration without re-running the full Setup Wizard -- handy when objectives are swapped on the scope frequently. Reads the pixel size from MicroManager (the only optical fact MM exposes; it does not know magnification, NA, or a human name), then prompts for that missing metadata (id, display name, magnification, NA, working distance, detector). It writes skeleton entries to `hardware.objectives`, `autofocus_settings` (`calibrated: false`), `imaging_profiles` (placeholder per modality), and `id_objective_lens`, then reloads so the objective is immediately selectable with the correct pixel size. The calibration data itself (autofocus, white balance, exposures, background) stays a placeholder -- run the corresponding calibration utilities afterwards to make the objective acquisition-ready. Requires a live microscope connection; comment-preserving and idempotent.

### [Communication Settings](tools/server-connection.md)

Configure and test the connection between QuPath and the microscope control server, and manage notification alerts. Includes connection, advanced timeout, status, and alerts tabs with push notification support via ntfy.sh.

### [Acquisition Wizard](tools/acquisition-wizard.md)

Guided setup wizard that walks new users through the complete first-time configuration process. Checks prerequisites (server connection, configuration files, calibrations) and launches the appropriate setup tools.

---

## Calibration Tools

### [White Balance Calibration](tools/white-balance-calibration.md)

Calibrate per-channel (R, G, B) exposure times for JAI 3-CCD prism cameras. Supports Simple (single angle) and PPM (4 angles) modes. Results are saved to YAML and used automatically by background collection and acquisition.

### [WB Comparison Test](tools/wb-comparison-test.md)

Acquire and display side-by-side images using different white balance modes. Helps users visually compare Off, Camera AWB, Simple, and Per-angle WB modes to select the best option for their sample.

### [Background Collection](tools/background-collection.md)

Acquire flat-field correction images with adaptive exposure control. Automatically adjusts exposure to reach target intensity at each angle. Supports per-channel white balance for JAI cameras.

### [Polarizer Calibration](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/polarizer-calibration.md)

Two-stage calibration (coarse sweep + fine sweep) to find the exact hardware offset for the PPM rotation stage. Determines the encoder position corresponding to crossed polarizers. Only needed after hardware installation or repositioning.

### [PPM Reference Slide (Sunburst Calibration)](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-reference-slide.md)

Create a hue-to-angle calibration from a reference slide with a sunburst pattern. Samples hue values along radial spokes and creates a linear regression for PPM image processing. Includes manual center selection and debug mask visualization.

### [Autofocus Configuration Editor](tools/autofocus-editor.md)

Configure per-objective autofocus parameters (n_steps, search_range_um, n_tiles) in an intuitive GUI. Supports editing multiple objectives before saving to YAML.

### [Autofocus Parameter Benchmark](tools/autofocus-benchmark.md)

Systematically find optimal autofocus settings by testing multiple parameter combinations at various defocus distances. Generates CSV results and performance recommendations.

### [JAI Noise Characterization](tools/noise-characterization.md)

Measure camera noise statistics (mean, standard deviation, SNR) with Quick, Full, or Custom presets. Useful for characterizing camera performance and detecting hardware issues.

---

## PPM Tools

PPM functionality is split across two extensions:
- **QPSC** (this extension): Hardware calibration and acquisition workflows under **Scope > PPM**
- **[qupath-extension-ppm](https://github.com/uw-loci/qupath-extension-ppm)**: Image analysis workflows under **Extensions > PPM Analysis** (no microscope needed)

PPM computations use the [ppm_library](https://github.com/uw-loci/ppm_library) Python package.

### Calibration & Hardware Validation (QPSC, Scope > PPM)

| Tool | Purpose | Requires | Full Docs |
|------|---------|----------|-----------|
| Polarizer Calibration | Calibrate rotation stage to find crossed polarizer positions | Connected microscope | [Guide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/polarizer-calibration.md) |
| PPM Reference Slide | Hue-to-angle calibration from sunburst reference slide | Connected microscope | [Guide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-reference-slide.md) |
| PPM Sensitivity Test | Test rotation stage precision and repeatability | Connected microscope | [Guide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-sensitivity-test.md) |
| PPM Birefringence Optimization | Find optimal polarizer angle for maximum contrast | Connected microscope, sample slide | [Guide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-birefringence-optimization.md) |

### Analysis Tools (PPM extension, Extensions > PPM Analysis)

**All analysis tools below require a completed [Sunburst Calibration](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-reference-slide.md)** (.npz file) and operate on PPM birefringence or sum images.

#### Interactive Analysis

| Tool | Purpose | Requires | Full Docs |
|------|---------|----------|-----------|
| PPM Hue Range Filter | Real-time overlay highlighting pixels whose fiber angle falls within a user-specified range | Sunburst calibration, open PPM image | [Guide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-hue-range-filter.md) |
| PPM Polarity Plot | Rose diagram of fiber orientation distribution with circular statistics (mean, std, resultant length) | Sunburst calibration, open PPM image, selected annotation | [Guide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/ppm-polarity-plot.md) |

### Boundary & Perpendicularity Analysis

| Tool | Purpose | Requires | Full Docs |
|------|---------|----------|-----------|
| Surface Perpendicularity (PS-TACS) | Score fiber orientation relative to annotation boundaries using the PS-TACS method | Sunburst calibration, open PPM birefringence image, boundary annotation (tissue interface) + region annotation (analysis zone), pixel classifier or thresholder for foreground segmentation | [Guide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/surface-perpendicularity.md) |

### Batch & Project-Wide

| Tool | Purpose | Requires | Full Docs |
|------|---------|----------|-----------|
| Batch PPM Analysis | Run polarity and/or perpendicularity analysis across all annotations in a project; exports CSV | Sunburst calibration, project with annotated PPM images | [Guide](https://github.com/uw-loci/qupath-extension-ppm/blob/master/documentation/batch-ppm-analysis.md) |
| [Propagation Manager](tools/propagation-manager.md) | Bidirectional transfer of annotations/detections between base images and sub-images | Multi-sample project with sub-images, alignment transform | [Guide](tools/propagation-manager.md) |
| [Z-Stack / Time-Lapse](tools/z-stack-timelapse.md) | Single-tile Z-stack or time-lapse at current position | Microscope connection, positioned at area of interest | [Guide](tools/z-stack-timelapse.md) |

---

## Internal Utilities

### MacroImageAnalyzer (Tissue Detection)

`MacroImageAnalyzer` provides automatic tissue detection on macro/overview images. It is called internally by the Existing Image workflow when no annotations are present and the user chooses "Run tissue detection."

**Available Threshold Methods:**

| Method | Description |
|--------|-------------|
| Otsu | Standard Otsu thresholding on grayscale |
| Mean | Mean intensity threshold |
| Percentile | Percentile-based threshold |
| Fixed | User-specified fixed threshold value |
| H&E Eosin / H&E Dual / Color Deconvolution | Color-space methods for stained tissue |
| **Artifact-aware (ARTIFACT_FILTER)** | **Otsu with artifact masking and morphological cleanup** |

**ARTIFACT_FILTER Method (default):**

The artifact-aware method combines artifact masking with Otsu thresholding to handle slides that have pen marks, dust, or other non-tissue debris. The algorithm is inspired by LazySlide (Zheng et al. 2026, Nature Methods).

**How it works:**
1. For each pixel, compute an artifact score: `max(R-G, 0) * max(B-G, 0)`. Tissue is typically green-dominant (in grayscale terms), so pixels where both red and blue significantly exceed green are flagged as potential artifacts.
2. Apply Otsu thresholding to the artifact score image to classify artifact vs. non-artifact pixels.
3. Apply standard Otsu thresholding on the grayscale image for tissue vs. background segmentation.
4. Combine: a pixel is classified as tissue only if it passes the grayscale threshold AND is not flagged as an artifact.
5. Apply morphological cleanup (median filter + morphological closing) to remove noise and fill gaps.

**Configuration:** The artifact filter parameters (median kernel size, closing kernel size, closing iterations) are configurable via Preferences. See [Tissue Detection Parameters](PREFERENCES.md#tissue-detection-parameters) for details.

**No additional dependencies:** The artifact filter uses QuPath's bundled OpenCV -- no external libraries are required.

---

## Exporting to Micro-Manager MDA

Every QPSC acquisition writes a Micro-Manager 2.0 compatible Multi-Dimensional Acquisition (MDA) plan alongside the tiles. This lets you re-acquire the same geometry (positions, channels, Z-stack) directly from Micro-Manager's MDA window -- useful for cross-checking, repeat runs after a hardware change, or handing the plan to a collaborator who acquires through MM rather than QPSC.

**File locations** (per region, in the per-region folder beside `TileConfiguration.txt`):

- `<projects>/<sample>/<enhancedModality>/<region>/MDA_<region>.txt` -- MM `SequenceSettings` JSON. MM's MDA "Load..." filter shows `.txt` by default.
- `<projects>/<sample>/<enhancedModality>/<region>/MDA_<region>.pos` -- MM `PositionList` JSON.
- `<projects>/<sample>/<enhancedModality>/<region>/MDA_NOTES.txt` -- plaintext provenance, the autofocus caveat, and any dropped multi-group presets or fallback device names.

**How to load in Micro-Manager 2.0:**

1. Open the **Multi-Dimensional Acquisition** window. Click **Load...** and select `MDA_<region>.txt`.
2. Open the **Stage Position List** window. Click **Load...** and select `MDA_<region>.pos`.
3. Review channels, Z range and step, and positions in the MM dialogs -- they should match the QPSC plan.

**Triggers.** The MDA files are written automatically at the start of every acquisition (auto-save). They can also be exported without acquiring via the **Save as MicroManager MDA...** button on the widefield and PPM acquisition setup dialogs; the button opens a confirmation alert with the saved path so you can jump straight to the folder.

**Autofocus.** QPSC runs per-tile, server-side streaming autofocus. MM 2.0 uses its own per-position `AutofocusManager`, which is a different mechanism. To keep MM from fighting our AF after the fact, the exported MDA sets `useAutofocus: false`. If you want MM to autofocus when re-running the plan, re-enable the "Autofocus" checkbox in the MDA window and pick an MM AF method.

**PPM caveat.** PPM rotation angles do not map to MM `ConfigGroup` channels. PPM MDA files are written positions-only (`useChannels: false`); the angle list and rationale are recorded in `MDA_NOTES.txt`.

**Multi-group channels caveat.** A QPSC channel can carry presets across more than one MM `ConfigGroup`. MM's `SequenceSettings` allows only one channel group per channels list, so the writer keeps the first preset's group and lists the dropped presets in `MDA_NOTES.txt`. If you need the dropped presets active in MM, apply them manually (or future-edit the MDA file) before running.

**Live progress panel.** During acquisition the existing per-annotation progress bar gains a small dimension panel beneath it: a static summary line (tiles, channels or angles, Z, T, total images, estimated duration) plus live counters (`Channel: FITC`, `Z step 3/9`, `Tile 47/84`). A time-lapse progress bar slot is reserved and lights up when `timepoints > 1`. The MDA auto-save path is shown as an `MDA: <path>` label so you can find the saved files without leaving the dialog. If the counters ever drift from server reality, the per-axis labels collapse to a single "Dimension counters out of sync; showing aggregate only" note and the aggregate bar continues unaffected.

---

## Stitch MicroManager Folder

Standalone stitching utility for tile folders acquired with MicroManager 2.0. Unlike the project-based **Re-stitch Tiles**, this tool operates independently without requiring a QuPath project or existing QPSC acquisition metadata.

**Input:** A folder containing MicroManager OME-TIFF tiles (`*_MMStack_*.ome.tif`) with `*_metadata.txt` sidecars that record tile positions and other acquisition parameters.

**Output:** A single stitched OME-TIFF or OME-ZARR image, plus a `<stem>.mm-metadata.json` sidecar preserving channel names, exposure, pixel size, grid dimensions, and other MicroManager metadata.

**Key features:**
- Reads tile positions directly from MMStack `*_metadata.txt` files (no `TileConfiguration.txt` required).
- Auto-detects pixel size from MMStack metadata or TIFF resolution tags; fallback default is 0.5 µm.
- Preserves channel names, MicroManager version, user/computer name, and acquisition comments in the metadata sidecar.
- Selectable output format: OME-TIFF (single file) or OME-ZARR (directory-based).
- Selectable compression (LZW, JPEG, etc.) with format-specific options.

**Access:** Extensions > QP Scope > Utilities > Stitch MicroManager Folder...

---

## Make Project Portable

Cleans up a project's ZARR-backed images and raw tile folders so the project is easy to copy off the acquisition workstation. ZARR images arise when stitching uses the **OME_ZARR** output format (either as the acquisition preference or via **Re-stitch Tiles** recovery run in OME_ZARR mode).

The tool works in two scenarios:

- **With ZARR entries:** Lists every ZARR-backed image with its OME-TIFF status (`READY`, `CONVERTING...`, or `MISSING`), offers ZARR conversion/archival options, and allows tile cleanup.
- **Without ZARR entries (tiles only):** If a project has already been fully converted to OME-TIFF, the tool still surfaces any remaining raw tile folders for cleanup. The dialog simplifies to show only the tile-cleanup option.

**ZARR handling** (when ZARR entries are present; choose one):

| Choice | What it does |
|---|---|
| **Convert ZARR to OME-TIFF** (recommended) | Swaps each project entry from ZARR to a sibling single-file `.ome.tif` via the QuPath `updateURIs()` mechanism, then deletes the ZARR. ZARR files with **no `.ome.tif` yet** (status `MISSING`) are converted on the spot -- no need to re-stitch. Conversion can take several minutes per file depending on size. Images still mid background-conversion (`CONVERTING...`) are skipped; click **Refresh** later. |
| **Zip ZARR to .ome.zarr.zip archive** | Zips each `.ome.zarr` directory into a sibling `.ome.zarr.zip` and deletes the directory. Use this when conversion is too slow or you want a lossless single-file archive. **Important:** QuPath's image reader cannot open a zipped ZARR -- you must extract each `.ome.zarr.zip` back to a `.ome.zarr` directory before reopening the project. The archive expands in place, so the project entry works again once extracted. |
| **Leave ZARR untouched** | Does not touch the ZARR files at all. Combine with tile deletion to *only* clean up raw tiles. |

**Tile images:** A **Keep individual tile images** checkbox (unchecked by default) controls whether the raw, un-stitched per-mode acquisition tile folders are deleted. This is independent of the ZARR choice -- e.g. *Leave ZARR untouched* + delete tiles cleans up only the raw tiles, or run the tool on a fully-converted project to delete tiles with no ZARR conversion involved. The dialog shows the tile count and total size.

**What Gets Preserved:**

- For the Convert path, all annotations, detections, image metadata, and thumbnails remain unchanged (`updateURIs()` only repoints the backing file).
- Acquisition metadata files inside the tile folders are preserved -- only raw tile images are removed.

Individual tiles are only needed if you plan to **re-stitch** the acquisition. The dialog shows a permanent-deletion warning that changes with the selected options and requires confirmation before deleting anything.

**When You Need It:**

- You've acquired images using the OME_ZARR output format and want single-file OME-TIFFs instead
- You want to archive or share a project with minimal file size (only single-file OME-TIFFs, no ZARR directories or raw tiles)
- Your project has already been fully converted to OME-TIFF but raw tile folders remain (the tool can clean those up independently)
- You're copying the project from the acquisition workstation to storage or a shared server

---

## Report a Bug

Easily submit bug reports directly from QuPath without needing a GitHub account or manually creating an issue.

**How to use:**
1. Go to **Extensions > QP Scope > Report a Bug...**
2. Enter a one-line **Summary** (8–80 characters, required) — this becomes the GitHub issue title
3. Write a **Description** of what went wrong (minimum 20 characters)
4. Optionally include:
   - **System info** (OS version, Java version, QuPath version)
   - **QPSC session log** (activity from the current session)
   - **Microscope server log** (the Python command server's session log, fetched over the socket; only available when connected to the server)
   - **QuPath log** (QuPath's own application log) — see below for enabling if unavailable
   - **Screenshot** (of the QuPath window; you will be shown a preview before sending)
5. Click **Submit**

**If "Include QuPath log" is disabled:**

QuPath only writes a log file when logging is enabled. To enable and attach QuPath's application log:

1. Go to **Edit > Preferences > General** (in QuPath)
2. Check **"Create log files"**
3. QuPath may prompt you to set a user directory — if so, choose or create a location and confirm
4. Restart QuPath — the log file is created on next startup
5. When you reopen the Report a Bug dialog, "Include QuPath log" will be enabled and ready to attach

Large logs are trimmed to fit GitHub's issue size limit: the version/startup banner is always kept (for provenance) along with the most recent lines (where errors usually appear), with an `[N chars omitted]` note in between.

Your report will be filed as a GitHub Issue in the QPSC issue tracker with all the information you provided. You don't need a GitHub account, and the issue URL will be shown once submission completes.

**Screenshot note:** The screenshot is not automatically redacted. You will see a mandatory preview dialog before anything is sent — close any sensitive windows first (passwords, personal info, etc.).

**What happens to your data:**
- Reports are submitted to a Cloudflare Worker that holds a GitHub authentication token server-side (your JAR never contains a token)
- The Worker files the issue in the public QPSC GitHub repository
- No data is stored on Cloudflare or any third party beyond GitHub

---

## See Also

- [Workflows Guide](WORKFLOWS.md) - Step-by-step workflow documentation
- [Preferences Reference](PREFERENCES.md) - All settings explained
- [Troubleshooting Guide](TROUBLESHOOTING.md) - Common issues and solutions
