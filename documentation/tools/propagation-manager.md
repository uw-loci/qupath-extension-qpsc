# Propagation Manager

> Menu: Extensions > QP Scope > Utilities > Propagation Manager...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Transfer annotations and detections between base images (whole-slide scans) and their acquired sub-images (stitched acquisitions). Supports both directions:

- **Forward Propagation**: Copy objects FROM the base image TO sub-images. Use this to push tissue region annotations, landmarks, or classification labels drawn on the overview into each acquired sub-image.
- **Back Propagation**: Copy objects FROM sub-images TO the base image. Use this to consolidate analysis results (cell detections, classified regions) from multiple sub-images back onto the overview for a unified view.

## Prerequisites

- A QuPath project with at least one base image and one or more sub-images
- Sub-images must have `base_image` metadata (automatically set during acquisition)
- An alignment transform must exist for the base image (created by Microscope Alignment)
- Sub-images must have `xy_offset_x_microns` and `xy_offset_y_microns` metadata (automatically set during acquisition)
- Microscope server should be connected for accurate half-FOV offset correction (works offline with slightly reduced precision)

## How It Works

### Coordinate Transform

The alignment transform maps base image pixels to physical stage coordinates (microns). Each sub-image's XY offset records where it was acquired on the stage. The Propagation Manager chains these transforms with a half-FOV correction (the tile grid starts half a camera field-of-view before the annotation edge):

**Forward** (base -> sub):
1. Base image annotation (pixels) -> stage microns (via alignment transform)
2. Stage microns -> sub-image pixels (subtract corrected XY offset, divide by pixel size)
3. Apply flip correction if the sub-image has optical flip metadata

**Back** (sub -> base):
1. Sub-image annotation (pixels) -> stage microns (multiply by pixel size, add corrected XY offset)
2. Undo flip correction if sub-image is flipped
3. Stage microns -> base image pixels (via inverse alignment transform)

Objects whose centroid falls outside the target image bounds are automatically filtered out.

## Options

### Direction

| Option | Description |
|--------|-------------|
| Forward (Base -> Sub-images) | Copy objects from base images into their sub-images |
| Back (Sub-images -> Base) | Copy objects from sub-images back to their base image |

### Base Image Selection

When multiple variants of the base image exist (e.g., original and flipped XY), a dropdown lets you choose which one to use as the propagation source or target. This is important because:

- The **flipped version** matches the alignment coordinate space (recommended for most cases)
- The **original version** is useful when working with the unflipped WSI directly

The dropdown defaults to the flipped variant (if available) since the alignment was typically calibrated on the flipped view.

### Sub-Image Selection

A checkbox tree shows all sub-images grouped under their base image. Check or uncheck individual sub-images to control which ones participate in propagation.

### Object Classes

Select which annotation/detection classes to propagate. The class list refreshes based on the current direction:
- **Forward**: Shows classes found in the selected base image
- **Back**: Shows classes found in the selected sub-images

Use the "Refresh Classes" button to rescan after changing image or direction selections.

## Workflow

### Forward Propagation (Typical Use)

1. Draw region annotations on the base image (e.g., mark tumor regions, tissue boundaries)
2. Open **Propagation Manager** from the QP Scope menu
3. Select **Forward** direction
4. Choose the base image variant (flipped or original) from the dropdown
5. Verify sub-images and classes are correctly selected
6. Click **Propagate**
7. Open a sub-image to verify the annotations landed in the correct positions

### Back Propagation (Typical Use)

1. Run analysis on sub-images (e.g., cell detection, classification)
2. Open **Propagation Manager** from the QP Scope menu
3. Select **Back** direction
4. Choose which base image variant to write to
5. Select which sub-images to pull objects from
6. Click **Propagate**
7. Open the base image to see all results consolidated on the overview

## Output

- Objects are added to the target image's hierarchy and saved automatically
- The status area shows per-image results: `source_image -> target_image: N objects`
- Failed propagations are reported with error messages

## Tips & Troubleshooting

- **Objects appear in the wrong position**: Check that the alignment transform is correct and that you selected the correct base image variant (flipped vs original). Run Microscope Alignment if needed.
- **Half-tile offset**: If objects are shifted by approximately half a tile, ensure the microscope server is connected when running propagation. The half-FOV correction requires the camera FOV from the server.
- **No sub-images found**: Sub-images must have `base_image` metadata. This is set automatically during acquisition workflows.
- **No alignment found**: The base image needs a slide-specific alignment file in the project's `alignmentFiles/` directory.
- **Objects missing after propagation**: Objects whose centroid falls outside the target image bounds are filtered out. This is expected.
- **Empty class list on first open**: Toggle the direction and toggle back, or click "Refresh Classes" to rescan.
- Propagation can be run multiple times. Duplicate objects may accumulate -- clear the target hierarchy first if you want a clean propagation.

## See Also

- [Microscope Alignment](microscope-alignment.md) -- Create the alignment transform needed for propagation
- [Bounded Acquisition](bounded-acquisition.md) -- Acquire sub-images with automatic metadata
- [Existing Image Acquisition](existing-image-acquisition.md) -- Acquire from existing images with coordinate tracking
