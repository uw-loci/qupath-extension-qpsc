# Existing Image Acquisition

> Menu: Extensions > QP Scope > Acquire from Existing Image
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md) | [All Workflows](../WORKFLOWS.md)

## Purpose

Acquire high-resolution images of specific regions annotated on an existing macro/overview image. Annotations drawn in QuPath define the regions to scan, and a coordinate transform maps image positions to physical stage positions. Use this when you have a macro image of your slide and want to acquire targeted regions of interest.

The acquisition dialog consolidates all options in a single scrollable panel. You may need to scroll to see all options on smaller screens.

![Existing Image Acquisition dialog -- top section](../images/Docs_ExistingImage_ConsolidatedDialog.png)

![Annotation class selection dialog](../images/Docs_AnnotationAcquisition.png)

## Prerequisites

- QuPath project open with a macro/overview image
- Annotations drawn on the image defining regions to acquire
- Valid coordinate alignment between image and stage (see [Microscope Alignment](microscope-alignment.md))
- Python microscope server running

## Options

### Sample Setup

| Option | Type | Required | Description |
|--------|------|----------|-------------|
| Sample Name | TextField | Yes | Name for acquired images. Defaults to current image name. |
| Use Existing Project | Auto | - | If a project is open, images are added to the current project. If no project is open, you will be prompted to create or select one. |

### Alignment Selection

| Option | Type | Description |
|--------|------|-------------|
| Use Saved Transform | ComboBox | Select from previously saved coordinate transforms |
| Create New Transform | Button | Opens the alignment workflow to create a new transform |
| Last Used Transform | Button | Uses the most recently used transform |

Selected transforms are validated before use. Invalid transforms show a warning and allow reselection.

### Refinement Options

| Option | Description |
|--------|-------------|
| No Refinement | Use saved transform directly (fastest). Best when transform is known to be accurate. |
| Single-Tile Refinement | Refine alignment using one reference tile. Quick adjustment for minor drift. |
| Full Manual Alignment | Create new transform with multiple points. Use the first time or after hardware changes. |

### Annotation Class Selection

| Column | Type | Description |
|--------|------|-------------|
| Select | CheckBox | Include/exclude this annotation class |
| Class Name | Label | Annotation classification name |
| Count | Label | Number of annotations with this class |
| Preview | Color Swatch | Color of the annotation class |

Select All / Deselect All buttons are available for convenience.

### Hardware Configuration

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Modality | ComboBox | From config | Imaging modality (e.g., ppm_20x, bf_10x) |
| Objective | ComboBox | From config | Objective lens for this modality |
| Detector | ComboBox | From config | Camera/detector combination |
| Angle Overrides | Various | - | Modality-specific angle controls (if applicable) |

## Workflow

### Step 1: Sample Setup

Enter a sample name and confirm the project location. If a project is already open, images will be added to it automatically.

### Step 2: Alignment Selection

Choose how to align QuPath image coordinates with physical stage positions. You can use a previously saved transform, create a new one, or reuse the last transform.

### Step 3: Refinement Options

Select the level of alignment refinement needed. No Refinement is fastest; Single-Tile Refinement corrects for minor drift; Full Manual Alignment is most thorough.

If you choose Single-Tile Refinement, the workflow first generates tile detections from the **annotation classes you selected for collection** (not from every annotation in the image). The tile-selection dialog then lets you pick one of those tiles as the reference. This avoids the noisy/overlapping tile grids that used to appear when unrelated annotations got tiled alongside the ones you actually wanted to acquire. The dialog reports the count and class names so you can confirm the right set was tiled.

The Refine Alignment dialog shown after the move encourages you to click **Auto-Align (SIFT)** to refine the position automatically. The "Save Refined Position" button is disabled until SIFT has run successfully, preventing accidental skipping of refinement. If SIFT fails, use the microscope controls (joystick or Stage Control dialog) to nudge the stage closer, then try SIFT again. To keep the predicted position without refining, click **Skip Refinement** instead of Save. SIFT works best on tissue with visible features and only succeeds when the live view already overlaps the selected tile by at least a few hundred microns. See [Microscope Alignment > Step 4](microscope-alignment.md#step-4-refinement-manual-or-automatic) for the full SIFT walkthrough, and [Preferences > SIFT Auto-Alignment](../PREFERENCES.md#sift-auto-alignment) for the cross-modality bit-depth normalization knobs.

### Step 4: Annotation Class Selection

Choose which annotation classes to include in the acquisition. The dialog shows all classes present on the current image with their counts and color previews. The same selection drives tiling for the Single-Tile Refinement step (Step 3).

### Step 5: Hardware Configuration

Select the modality, objective, and detector combination. Only valid hardware combinations are shown.

### Step 6: Acquisition Progress

The progress monitor shows:

- Current region / total regions
- Tile progress within current region
- Overall progress bar

For each annotation, the system:

1. Navigates to the annotation location
2. Runs autofocus
3. Acquires all tiles covering the annotation
4. Stitches tiles for this annotation
5. Moves to the next annotation

### Step 7: Completion

Each annotation's acquisition is added to the project as it completes. Metadata includes:

- Parent image reference
- Annotation name
- XY offset from parent image
- All hardware settings used

![Acquisition progress dialog](../images/Docs_AcquisitionWorkflowProgress.png)

## Output

- One stitched image per annotation, added to the QuPath project
- Acquisition metadata linking each image to its parent and annotation
- XY offset information for coordinate mapping back to the macro image

## Tips & Troubleshooting

| Issue | Cause | Solution |
|-------|-------|----------|
| "No annotations found" | No annotations on image | Draw annotations on the macro image before starting |
| "Transform validation failed" | Alignment is off | Run refinement or create a new transform |
| Images appear shifted | Coordinate mismatch | Check flip/invert settings in Preferences |
| "Pixel size not set" | Missing calibration | Set image pixel size in QuPath image properties |
| Poor alignment at edges | Too few alignment points | Add more calibration points spread across the image |
| Auto-Align (SIFT) fails near correct tile | Bit-depth mismatch (16-bit camera vs 8-bit H&E WSI) | SIFT Settings should default to `PERCENTILE` 2/98 + CLAHE on. If still failing, raise CLAHE clip to 4.0 or widen percentile to 0.5/99.5. See [Preferences > SIFT Auto-Alignment](../PREFERENCES.md#sift-auto-alignment). |
| Auto-Align (SIFT) fails far from tile | Stage too far from target | Drive the stage roughly close (live view should partially overlap the tile) before clicking SIFT. SIFT is a refinement, not a search. |

## See Also

- [Bounded Acquisition](bounded-acquisition.md) - Acquire by defining stage coordinates directly
- [Microscope Alignment](microscope-alignment.md) - Create the coordinate transform needed for this workflow
- [Camera Control](camera-control.md) - Verify camera settings before acquisition
- [Background Collection](background-collection.md) - Collect flat-field correction images
- [Live Viewer](live-viewer.md) - Verify positioning and focus before acquisition
- [Communication Settings](server-connection.md) - Configure server connection and alerts
