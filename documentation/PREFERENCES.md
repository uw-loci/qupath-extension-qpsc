# QPSC Preferences Reference

This document provides comprehensive documentation for all QPSC preferences available in QuPath's Preferences panel.

**Location:** Edit > Preferences > QuPath SCope

---

## Quick Reference Table

| Preference | Type | Default | Description |
|------------|------|---------|-------------|
| [Flip macro image X](#flip-macro-image-x) | Boolean | OFF | Flip image horizontally |
| [Flip macro image Y](#flip-macro-image-y) | Boolean | OFF | Flip image vertically |
| [Inverted X stage](#inverted-x-stage) | Boolean | OFF | Stage X axis is inverted |
| [Inverted Y stage](#inverted-y-stage) | Boolean | ON | Stage Y axis is inverted |
| [Microscope Config File](#microscope-config-file) | File | - | Path to microscope YAML config |
| [Projects Folder](#projects-folder) | Directory | - | Root folder for slide projects |
| [Extension Location](#extension-location) | Directory | - | QPSC extension installation directory |
| [Tissue Detection Script](#tissue-detection-script) | File | - | Optional Groovy script for tissue detection |
| [Tissue Artifact Filter Enabled](#tissue-artifact-filter-enabled) | Boolean | ON | Use artifact-aware tissue detection |
| [Tissue Two-Pass Refine](#tissue-two-pass-refine) | Boolean | OFF | Apply second refinement pass |
| [Tissue Median Kernel](#tissue-median-kernel) | Integer | 17 | Median filter kernel size |
| [Tissue Morph Close Kernel](#tissue-morph-close-kernel) | Integer | 7 | Morphological closing kernel size |
| [Tissue Morph Close Iterations](#tissue-morph-close-iterations) | Integer | 3 | Morphological closing iterations |
| [Tile Handling Method](#tile-handling-method) | Choice | None | How to handle intermediate tiles |
| [Tile Overlap Percent](#tile-overlap-percent) | Double | 10.0 | Overlap between adjacent tiles |
| [Compression type](#compression-type) | Choice | DEFAULT | OME pyramid compression |
| [Stitching output format](#stitching-output-format) | Choice | OME_TIFF | Output format for stitched images |
| [Microscope Server Host](#microscope-server-host) | String | 127.0.0.1 | Server IP address |
| [Microscope Server Port](#microscope-server-port) | Integer | 5000 | Server port number |
| [Auto-connect to Server](#auto-connect-to-server) | Boolean | ON | Connect on QuPath startup |
| [No Manual Autofocus (Danger)](#no-manual-autofocus-danger) | Boolean | OFF | Skip manual focus dialogs |
| [Image name includes: Objective](#image-name-includes-objective) | Boolean | OFF | Add objective to filename |
| [Image name includes: Modality](#image-name-includes-modality) | Boolean | OFF | Add modality to filename |
| [Image name includes: Annotation](#image-name-includes-annotation) | Boolean | OFF | Add annotation name to filename |
| [Image name includes: Angle](#image-name-includes-angle) | Boolean | ON | Add angle to filename |
| [Metadata Propagation Prefix](#metadata-propagation-prefix) | String | OCR | Prefix for inherited metadata |

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
Indicates the stage X axis moves in the opposite direction from standard convention. When enabled, positive X commands move the stage left instead of right.

**When to Enable:**
- Your microscope's X axis convention is inverted
- Stage control shows correct position but moves wrong direction in X
- Typically determined during microscope alignment

---

### Inverted Y stage

| Property | Value |
|----------|-------|
| Type | Boolean |
| Default | ON |
| Requires Restart | No |

**Description:**
Indicates the stage Y axis moves in the opposite direction from standard convention. Most microscopes have inverted Y because stage coordinates increase downward while image coordinates increase upward.

**When to Enable:**
- Default is ON for most microscope configurations
- Stage moves down when you expect up
- Typically required for standard microscope setups

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
| Default | (none) |
| Requires Restart | Yes |
| **REQUIRED** | Yes |

**Description:**
Path to the YAML configuration file describing your microscope setup. This file defines available modalities, objectives, detectors, stage limits, and other hardware parameters.

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

### Extension Location

| Property | Value |
|----------|-------|
| Type | Directory |
| Default | (none) |
| Requires Restart | No |

**Description:**
Directory where the QPSC extension is installed. Used to locate built-in scripts and resources.

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

## Acquisition & Stitching

Settings that control image acquisition and stitching behavior.

### Z-Stack Options

Z-stack settings (enable, range, step, projection) are configured per-acquisition in the acquisition dialog under **Z-STACK OPTIONS**. These settings persist between sessions. See [Z-Stack / Time-Lapse](tools/z-stack-timelapse.md) for details.

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

**Description:**
Compression algorithm for OME pyramid output files.

| Option | Description |
|--------|-------------|
| **DEFAULT** | Platform default (usually LZW) |
| **UNCOMPRESSED** | No compression (fastest write, largest files) |
| **LZW** | Lossless compression (good balance) |
| **JPEG** | Lossy compression (smaller files, some quality loss) |
| **J2K** | JPEG2000 compression |
| **J2K_LOSSY** | Lossy JPEG2000 |
| **ZLIB** | Zlib compression |

---

### Stitching output format

| Property | Value |
|----------|-------|
| Type | Choice |
| Default | OME_TIFF |
| Options | OME_TIFF, OME_ZARR |
| Requires Restart | No |

**Description:**
Output format for stitched images.

| Format | Pros | Cons |
|--------|------|------|
| **OME_TIFF** | Widely compatible, single file, standard format | Slower to write, larger files |
| **OME_ZARR** | 2-3x faster writing, 20-30% smaller, cloud-native | Directory format, less commonly used |

**Note:** OME-ZARR is an emerging standard that offers significant performance benefits but may have compatibility issues with older software.

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

## SIFT Auto-Alignment

Preferences controlling the SIFT feature matching used for automated alignment refinement.

| Preference | Type | Default | Description |
|------------|------|---------|-------------|
| Trust SIFT alignment | Boolean | OFF | When enabled, SIFT runs automatically during single-tile refinement without showing the manual dialog. If confidence exceeds the threshold, the refined position is accepted automatically. Falls back to manual if SIFT fails or confidence is too low. |
| SIFT confidence threshold | Double | 0.5 | Minimum inlier ratio (0.0-1.0) required to auto-accept alignment when Trust SIFT is enabled. Higher values are stricter. 0.5 means at least 50% of matched features must be RANSAC inliers. |
| SIFT min pixel size (um) | Double | 1.0 | Minimum resolution for SIFT matching. Both images are downsampled to at least this pixel size before feature detection. Suppresses JPEG compression artifacts and speeds up matching. Lower values for high-magnification objectives (e.g., 63x oil where 1 um/px is a large fraction of the FOV). |

**When to adjust these:**
- **Trust SIFT**: Enable when alignment is reliable and you want fully unattended acquisition
- **Confidence threshold**: Lower (e.g., 0.3) if tissue has few features; raise (e.g., 0.7) for critical alignment
- **Min pixel size**: Lower (e.g., 0.3) for 40x+ objectives; raise (e.g., 2.0) for 4x objectives or heavily compressed WSIs

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
