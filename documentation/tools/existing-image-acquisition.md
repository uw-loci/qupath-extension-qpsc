# Existing Image Acquisition

> Menu: Extensions > QP Scope > Acquire from Existing Image
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md) | [All Workflows](../WORKFLOWS.md)

## Purpose

Acquire high-resolution images of specific regions annotated on an existing macro/overview image. Annotations drawn in QuPath define the regions to scan, and a coordinate transform maps image positions to physical stage positions. Use this when you have a macro image of your slide and want to acquire targeted regions of interest.

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

### Step 4: Annotation Class Selection

Choose which annotation classes to include in the acquisition. The dialog shows all classes present on the current image with their counts and color previews.

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

## See Also

- [Bounded Acquisition](bounded-acquisition.md) - Acquire by defining stage coordinates directly
- [Microscope Alignment](microscope-alignment.md) - Create the coordinate transform needed for this workflow
- [Camera Control](camera-control.md) - Verify camera settings before acquisition
- [Background Collection](background-collection.md) - Collect flat-field correction images
- [Live Viewer](live-viewer.md) - Verify positioning and focus before acquisition
- [Communication Settings](server-connection.md) - Configure server connection and alerts
