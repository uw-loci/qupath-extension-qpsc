# Back-Propagate Annotations to Parent
> Menu: Extensions > QP Scope > PPM > Back-Propagate Annotations to Parent...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Back-Propagate Annotations transfers annotations drawn on high-resolution
sub-images (acquired via the Existing Image workflow) back to the parent/base
image's coordinate space. This allows you to view annotations from multiple
sub-acquisitions overlaid on the original macro image, preserving classification,
measurements, and spatial relationships.

Use this tool to:
- Aggregate annotations from multiple sub-image acquisitions onto a single
  parent image.
- Visualize the spatial distribution of annotated features across the full
  tissue section.
- Combine analysis results from different acquisition regions into one view.
- Prepare consolidated annotation maps for publication figures.

## Prerequisites

- A **QuPath project** must be open.
- The project must contain **image collections** with:
  - A **flipped parent image** (created during the Existing Image workflow) with
    flip metadata (`flip_x` and/or `flip_y` set).
  - One or more **sub-images** with `annotation_name` metadata (set during
    acquisition).
- Sub-images must have **classified annotations** (at least one annotation class
  must be present).
- An **alignment transform** must exist for the sample (created during the
  Microscope Alignment workflow).
- Sub-images must have valid **pixel size calibration** and **XY offset metadata**.

## Options

### Target Image

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Original base image | Radio button | Selected | Propagates annotations to the original (un-flipped) base image. Applies the flip transform to map from the flipped parent's coordinate space to the original. This is the default and recommended option. |
| Flipped XY parent | Radio button | -- | Propagates annotations to the flipped parent image directly, without applying the flip transform. Use when you want annotations in the flipped coordinate space. |

If the original base image is missing for a collection, the tool falls back to
the flipped parent automatically.

### Annotation Classes to Propagate

A checklist of all annotation classes found across the sub-images:

| Control | Description |
|---------|-------------|
| Individual checkboxes | Select/deselect individual annotation classes. All are selected by default. |
| Check All | Select all classes. |
| Check None | Deselect all classes. |

### Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Include measurements | Checkbox | Checked | Copies measurement values (e.g., PPM analysis results) from the original annotation to the propagated annotation. |
| Lock propagated annotations | Checkbox | Checked | Locks the propagated annotations to prevent accidental editing on the parent image. |

## Workflow

1. Complete one or more Existing Image acquisitions, creating sub-images in the
   project.
2. Draw and classify annotations on the sub-images (or run batch analysis to
   add measurements).
3. Launch the tool from the menu.
4. Review the summary (number of collections and sub-images found).
5. Select the target image type (original base or flipped parent).
6. Check/uncheck annotation classes to propagate.
7. Configure options (measurements, locking).
8. Click OK to confirm.
9. The tool processes each collection:
   - Loads the alignment transform for the sample.
   - For each sub-image, computes the coordinate transformation chain.
   - Transforms matching annotations to the target image's coordinate space.
   - Names propagated annotations with the acquisition region prefix.
   - Adds all propagated annotations to the target image and saves.

## Output

- **Propagated annotations** appear on the target image (original base or
  flipped parent) with:
  - The same **classification** as the source annotation.
  - The same **measurements** (if "Include measurements" is checked).
  - A **name prefix** indicating the source acquisition region (e.g.,
    "region_1: Tumor boundary" or "region_1: (1234, 5678)").
  - **Locked** state (if "Lock propagated annotations" is checked).
- A **notification** reports the total number of annotations propagated.

### Coordinate Transform Chain

The transformation from sub-image pixels to target image pixels involves:

```
sub-image pixel  --(pixel size + XY offset)-->  stage microns
    --(inverse alignment transform)-->  flipped parent pixel
    --(flip transform)-->  original base pixel
```

1. **Sub-image pixel to stage microns**: Multiply pixel coordinates by the
   sub-image pixel size and add the XY offset (physical stage position where
   the sub-image was acquired).
2. **Stage microns to flipped parent pixel**: Apply the inverse of the
   alignment transform (which maps parent pixels to stage coordinates).
3. **Flipped parent pixel to original base pixel** (if targeting original):
   Apply the flip transform based on the parent's flip_x/flip_y metadata.

## Tips & Troubleshooting

- **"No image collections with sub-images and parent images found"**: The
  project must contain images from the Existing Image workflow. Sub-images need
  `annotation_name` metadata, and the parent must have flip metadata.
- **"No classified annotations found on sub-images"**: Annotations must have
  a class assigned. Unclassified annotations are not propagated.
- **"No alignment transform for sample"**: Run the Microscope Alignment
  workflow for the sample before attempting back-propagation. The alignment
  transform is required to map between microscope stage coordinates and image
  pixels.
- **Annotations appear in wrong location**: This usually indicates a stale or
  incorrect alignment transform. Re-run microscope alignment and try again.
- **"Invalid pixel size" warning**: The sub-image must have valid pixel
  calibration metadata. This is normally set automatically during acquisition.
- **Some collections missing original base**: If the original (un-flipped) base
  image was not imported into the project, the tool falls back to the flipped
  parent. A warning appears in the radio button text.
- **Locked annotations**: Propagated annotations are locked by default to
  prevent accidental editing. Unlock them manually in QuPath if needed (select
  annotation, right-click, toggle Lock).
- **Programmatic API**: The `propagateForCollection()` method provides a
  programmatic interface for use from scripts or the batch analysis workflow.

## See Also

- [Batch PPM Analysis](batch-ppm-analysis.md) -- Run analysis across multiple
  images before back-propagating results
- [Surface Perpendicularity](surface-perpendicularity.md) -- Analyze fiber
  orientation relative to boundaries
- [PPM Polarity Plot](ppm-polarity-plot.md) -- Analyze fiber orientation
  within annotations
