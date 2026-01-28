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
| [JAI White Balance Mode](#jai-white-balance-mode) | Choice | SIMPLE | White balance calibration mode |

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

## Acquisition & Stitching

Settings that control image acquisition and stitching behavior.

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

## Camera-Specific Settings

### JAI White Balance Mode

| Property | Value |
|----------|-------|
| Type | Choice |
| Default | SIMPLE |
| Options | SIMPLE, PPM |
| Requires Restart | No |

**Description:**
White balance calibration mode for JAI 3-CCD prism cameras.

| Mode | Description | Use Case |
|------|-------------|----------|
| **SIMPLE** | Same per-channel exposures for all polarization angles | Faster calibration, good for most samples |
| **PPM** | Separate calibration for each polarization angle | More accurate for samples with strong birefringence variation |

**Requirements:**
- Requires running White Balance Calibration before acquisition
- Only affects JAI and similar prism-based cameras
- Standard Bayer cameras ignore this setting

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

For first-time setup, configure these preferences in order:

1. **Microscope Config File** - Point to your microscope's YAML config
2. **Projects Folder** - Set your preferred data storage location
3. **Microscope Server Host/Port** - Configure to match your server
4. **Tile Overlap Percent** - Start with 10% default
5. **Inverted Y stage** - Usually ON for most microscopes

Then verify settings using:
- Extensions > QP Scope > Server Connection Settings > Test Connection
- Extensions > QP Scope > Stage Control (test movement)
- Extensions > QP Scope > Microscope Alignment (verify coordinates)

---

## See Also

- [Utilities Reference](UTILITIES.md) - All utilities with options explained
- [Workflows Guide](WORKFLOWS.md) - Step-by-step workflow documentation
- [Troubleshooting Guide](TROUBLESHOOTING.md) - Common issues and solutions
