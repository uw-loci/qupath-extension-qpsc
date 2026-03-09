# PPM Polarity Plot
> Menu: Extensions > QP Scope > PPM > PPM Polarity Plot...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

The PPM Polarity Plot generates a polar histogram (rose diagram) showing the
distribution of fiber orientation angles within a selected annotation. It
provides quantitative circular statistics (mean angle, standard deviation,
alignment strength) that characterize fiber organization patterns in PPM images.

Use this tool to:
- Quantify the angular distribution of fibers within a region of interest.
- Determine if fibers are randomly oriented or strongly aligned.
- Identify the dominant fiber orientation direction.
- Compare fiber alignment between different tissue regions or samples.

## Prerequisites

- A PPM **sum image** must be open in QuPath.
- An **annotation** must be selected in the viewer (drawn by the user or
  imported from another source).
- A **PPM calibration** (sunburst calibration file) must be available. The tool
  searches in the same order as the Hue Range Filter:
  1. PPM analysis set metadata.
  2. Image entry metadata.
  3. Active calibration path in PPM preferences.
- **Python** must be available on the system PATH with `ppm_library` installed.

## Options

The polarity plot does not have a configuration dialog -- it operates on the
currently selected annotation using default analysis parameters:

| Parameter | Value | Description |
|-----------|-------|-------------|
| Histogram bins | 18 | Number of angular bins (each bin covers 10 deg of the 0-180 deg range). |
| Birefringence threshold | 100.0 | Minimum birefringence intensity for a pixel to be included (when a sibling birefringence image is available). |

## Workflow

1. Open a PPM sum image in QuPath.
2. Draw or select an annotation on the region you want to analyze.
3. Launch the tool from the menu.
4. The tool extracts the annotated image region, creates a binary mask of the
   annotation shape, and calls the Python `ppm_library.analysis.cli` module.
5. If a sibling birefringence image exists in the same PPM analysis set, it is
   also extracted and used to filter by birefringence intensity.
6. Results are displayed in a plot window.

You can select a different annotation and run the tool again -- the plot window
updates with the new results.

## Output

### Polar Histogram (Rose Diagram)

A semi-circular chart (0-180 deg) where:
- Each **wedge** represents an angular bin.
- Wedge **radius** is proportional to the number of pixels in that bin.
- Wedge **color** is mapped by angle (HSV rainbow across 0-180 deg).
- A **red line** shows the circular mean angle.
- Axis labels show 0 deg (right), 90 deg (top), and 180 deg (left).
- Tick marks appear at 30 deg intervals.

### Statistics Panel

| Statistic | Description |
|-----------|-------------|
| Valid pixels | Number of pixels that passed saturation/value/birefringence filters. |
| Circular mean | The mean fiber orientation angle (0-180 deg), computed using circular statistics. |
| Circular std | Circular standard deviation in degrees. Lower values indicate tighter alignment. |
| Alignment (R) | Mean resultant length (0-1). Quantifies alignment strength: >0.8 = highly aligned, 0.5-0.8 = moderately aligned, 0.2-0.5 = weakly aligned, <0.2 = nearly random. |
| Dominant bin | The angular bin with the most pixels, showing the range (e.g., "80-90 deg") and pixel count. |

### Window Title

The plot window title updates to include the annotation name (e.g.,
"PPM Polarity Plot - Annotation 1").

## Tips & Troubleshooting

- **"Please select an annotation first"**: Click on an annotation in the
  viewer before running the tool. Detection objects are not supported -- only
  annotations.
- **"No PPM calibration found"**: Run the sunburst calibration workflow first,
  or set the active calibration path in PPM preferences.
- **Python errors**: Ensure `ppm_library` is installed in the Python environment
  accessible from the system PATH. Check the QuPath log for the full error
  message from the Python process.
- **All pixels filtered out (0 valid pixels)**: The region may contain only
  background or low-signal areas. Try a different annotation or check that the
  image is a valid PPM sum image.
- **Results differ from Hue Range Filter**: The Polarity Plot uses Python
  (`ppm_library`) for computation, while the Hue Range Filter uses a Java-side
  linear approximation. Small differences are expected; the Python implementation
  is considered the reference.
- **Birefringence filtering**: If a birefringence sibling image is found in the
  PPM analysis set, pixels below the birefringence threshold (100.0) are excluded.
  This helps remove low-retardance (isotropic) regions from the analysis.

## See Also

- [PPM Hue Range Filter](ppm-hue-range-filter.md) -- Interactive angle overlay
- [Surface Perpendicularity](surface-perpendicularity.md) -- Fiber orientation
  relative to boundaries
- [Batch PPM Analysis](batch-ppm-analysis.md) -- Run polarity analysis across
  multiple images
