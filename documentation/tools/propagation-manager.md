# Propagation Manager

> Menu: Extensions > QP Scope > Utilities > Forward Propagation... / Back Propagation...
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

## How It Works

### Coordinate Transform

The alignment transform maps base image pixels to physical stage coordinates (microns). Each sub-image's XY offset records where it was acquired on the stage. The Propagation Manager chains these transforms:

**Forward** (base -> sub):
1. Base image annotation (pixels) -> stage microns (via alignment transform)
2. Stage microns -> sub-image pixels (subtract XY offset, divide by pixel size)
3. Apply flip correction if the sub-image has optical flip metadata

**Back** (sub -> base):
1. Sub-image annotation (pixels) -> stage microns (multiply by pixel size, add XY offset)
2. Stage microns -> base image pixels (via inverse alignment transform)

Objects whose centroid falls outside the target image bounds are automatically filtered out.

## Options

### Direction

| Option | Description |
|--------|-------------|
| Forward (Base -> Sub-images) | Copy objects from base images into their sub-images |
| Back (Sub-images -> Base) | Copy objects from sub-images back to their base image |

### Image Groups

The dialog shows a checkbox tree of image groups. Each group has a base image as the parent node and its sub-images as children. Check or uncheck individual images to control which ones participate in propagation.

- Checking/unchecking a parent toggles all its children
- You can selectively include/exclude individual sub-images

### Object Classes

Select which annotation/detection classes to propagate. The class list refreshes based on the current direction:
- **Forward**: Shows classes found in the selected base images
- **Back**: Shows classes found in the selected sub-images

Use the "Refresh Classes" button to rescan after changing image selections.

## Workflow

### Forward Propagation (Typical Use)

1. Draw region annotations on the base image (e.g., mark tumor regions, tissue boundaries)
2. Open **Forward Propagation** from the menu
3. Verify the image groups and classes are correctly selected
4. Click **Propagate**
5. Open a sub-image to verify the annotations landed in the correct positions

### Back Propagation (Typical Use)

1. Run analysis on sub-images (e.g., cell detection, classification)
2. Open **Back Propagation** from the menu
3. Select which sub-images to pull objects from
4. Click **Propagate**
5. Open the base image to see all results consolidated on the overview

## Output

- Objects are added to the target image's hierarchy and saved automatically
- The status area shows per-image results with object counts
- Failed propagations are reported with error messages

## Tips & Troubleshooting

- **Objects appear in the wrong position**: Check that the alignment transform is correct. Run Microscope Alignment if needed.
- **No sub-images found**: Sub-images must have `base_image` metadata. This is set automatically during acquisition workflows.
- **No alignment found**: The base image needs a slide-specific alignment file in the project's `alignmentFiles/` directory.
- **Objects missing after propagation**: Objects whose centroid falls outside the target image bounds are filtered out. This is expected -- only objects that overlap with the target image are propagated.
- Forward and Back propagation can be run multiple times. Duplicate objects may accumulate -- clear the target hierarchy first if you want a clean propagation.
- Both menu entries open the same Propagation Manager dialog; they just set the default direction differently.

## See Also

- [Microscope Alignment](microscope-alignment.md) -- Create the alignment transform needed for propagation
- [Bounded Acquisition](bounded-acquisition.md) -- Acquire sub-images with automatic metadata
- [Existing Image Acquisition](existing-image-acquisition.md) -- Acquire from existing images with coordinate tracking
