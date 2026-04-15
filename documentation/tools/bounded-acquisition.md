# Bounded Acquisition

> Menu: Extensions > QP Scope > Bounded Acquisition
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md) | [All Workflows](../WORKFLOWS.md)

## Purpose

Acquire high-resolution images by defining stage coordinates directly. This workflow creates a new QuPath project and acquires a rectangular region of the slide. Use this when starting fresh without an existing overview image, when you know the stage coordinates of the region you want, or when setting up a new sample for imaging.

![Bounded Acquisition dialog](../images/Docs_BoundedAcquisition.png)

## Prerequisites

- Python microscope server running
- Microscope hardware initialized in Micro-Manager
- Valid microscope configuration file loaded
- Background images collected (recommended)

## Options

### Project & Sample

| Option | Type | Required | Description |
|--------|------|----------|-------------|
| Sample Name | TextField | Yes | Name for this acquisition (e.g., "MySample01"). Validated for cross-platform compatibility; invalid characters and Windows reserved names (CON, NUL, etc.) are blocked. |
| Projects Folder | Directory Picker | Yes* | Root folder for projects. Only required if no project is currently open. |

### Hardware Configuration

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Modality | ComboBox | From config | Imaging modality (e.g., ppm_20x, bf_10x) |
| Objective | ComboBox | From config | Objective lens. List updates based on selected modality. |
| Detector | ComboBox | From config | Camera/detector. List updates based on selected objective. |

Only valid hardware combinations are shown via cascading selection.

### Acquisition Region

Two input modes are available:

**Center Point + Size Mode:**

The center position defines the midpoint of the acquisition area. Tiles extend equally in all directions from this point.

| Option | Type | Description |
|--------|------|-------------|
| Center X (um) | Text | Center X coordinate of the acquisition region in micrometers |
| Center Y (um) | Text | Center Y coordinate of the acquisition region in micrometers |
| Width (um) | Text | Total width of the acquisition region in micrometers |
| Height (um) | Text | Total height of the acquisition region in micrometers |
| Use Current Position as Center | Button | Set center coordinates from the current stage position (Live Viewer field of view center) |

**Two Corners Mode:**

| Option | Type | Description |
|--------|------|-------------|
| Corner 1 X/Y | Spinner | First corner coordinates |
| Corner 2 X/Y | Spinner | Opposite corner coordinates |
| Get Stage Position | Button | Populate each corner from current stage position |

### Advanced Options (Collapsed by Default)

| Option | Type | Description |
|--------|------|-------------|
| WB Mode | ComboBox | White balance mode (JAI cameras only): Off, Camera AWB, Simple, or Per-angle calibrated exposures. Applies immediately on selection. |
| Angle Overrides | Various | Modality-specific options (e.g., PPM angle overrides) |

### Acquisition Preview

Real-time preview showing calculated acquisition details:

| Field | Description |
|-------|-------------|
| Region | Calculated size in millimeters |
| Field of View | FOV dimensions for selected hardware |
| Tile Grid | Number of tiles (X x Y) and overlap percentage |
| Angles | Number of angles for the modality |
| Total Images | Total images to be acquired |
| Est. Time | Rough time estimate |
| Est. Storage | Rough storage estimate |

## Workflow

### Step 1: Unified Acquisition Dialog

All configuration is presented in a single screen with collapsible sections. Fill in sample name, select hardware configuration, define the acquisition region, and review the acquisition preview.

### Step 2: Acquisition Progress

After clicking **Start Acquisition**, the progress monitor appears showing:

- Current tile number / total tiles
- Progress bar
- Current stage position
- Elapsed time

During acquisition:

- Stage moves to each tile position
- Camera captures image(s) at each position
- For PPM: rotates through all angles at each position
- Autofocus runs periodically based on settings
- Cancel is available (partial tiles will be saved)

### Step 3: Stitching

After all tiles are captured, automatic stitching begins:

- Tiles are aligned using overlap regions
- Pyramidal image is created for efficient viewing
- Output format determined by preferences (OME-TIFF or OME-ZARR)
- Do not interact with QuPath during stitching
- Stitching time depends on tile count (typically 2-10 minutes)
- Temporary tiles may be kept, zipped, or deleted per preferences

### Step 4: Completion

- Stitched image is automatically added to the project
- Image opens in QuPath viewer
- Metadata is populated with acquisition details
- Temporary tiles are handled per preferences
- **A per-slide stage alignment is registered automatically** (see below)

![Acquisition progress dialog](../images/Docs_AcquisitionWorkflowProgress.png)

## Output

- A new QuPath project (if none was open)
- Stitched pyramidal image (OME-TIFF or OME-ZARR) added to the project
- Acquisition metadata stored in project
- Temporary tile images (handled per preference settings)
- Per-slide alignment JSON in `{project}/alignmentFiles/{imageName}_alignment.json`

## Automatic Stage Alignment

Because you supplied the stage bounds directly, QPSC already knows how every pixel in the stitched image maps to a physical stage position — no manual 3-point alignment is required. When the stitched file is imported, the workflow writes a pixel -> stage affine transform keyed by the stitched image's file name.

**What you can do with it immediately:**

- Open the stitched image and draw an ROI anywhere.
- Click **Go to centroid** in the Live Viewer (Navigate tab). The button is enabled as soon as the new image is opened — you do not have to run Microscope Alignment first.
- Double-click any point in the Live Viewer to center the stage there.

The alignment is scoped to the current project. If you re-open the same image in a different project you will need to either re-register it (e.g. via a new BoundingBox acquisition over the same region) or use the Microscope Alignment workflow to add a manual one.

**When auto-alignment does NOT fire:**

- Annotation-based acquisitions from the **Existing Image Acquisition** workflow — those inherit their parent macro image's alignment and do not need a standalone registration.
- Multi-channel widefield IF / BF+IF acquisitions where the channel merge fails: in the fallback path each per-channel pyramid is registered independently under its own file name, so Go-to-centroid still works when you open any one of them.

## Tips & Troubleshooting

| Issue | Cause | Solution |
|-------|-------|----------|
| "Connection refused" | Server not running | Start Python microscope server |
| Stage does not move | Hardware not initialized | Check Micro-Manager |
| Black images | Exposure too short or lamp off | Check illumination and camera settings |
| Stitching fails | Insufficient disk space | Free disk space and retry |
| Invalid sample name | Special characters or reserved names | Use only letters, numbers, underscores, and hyphens |

## See Also

- [Existing Image Acquisition](existing-image-acquisition.md) - Acquire from annotated regions on an existing image
- [Microscope Alignment](microscope-alignment.md) - Create coordinate transforms for image-to-stage mapping
- [Camera Control](camera-control.md) - Verify camera settings before acquisition
- [Background Collection](background-collection.md) - Collect flat-field correction images
- [Live Viewer](live-viewer.md) - Verify microscope communication and positioning
- [Communication Settings](server-connection.md) - Configure server connection and alerts
