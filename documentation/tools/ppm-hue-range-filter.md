# PPM Hue Range Filter
> Menu: Extensions > QP Scope > PPM > PPM Hue Range Filter...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

The PPM Hue Range Filter displays an interactive overlay on the current image
that highlights pixels whose fiber orientation angle falls within a
user-specified range. This allows rapid visual exploration of fiber alignment
patterns across a PPM (Polychromatic Polarization Microscopy) image.

Use this tool to:
- Visually identify regions where fibers are oriented in a specific direction.
- Explore the angular distribution of fibers across the image.
- Filter out low-signal pixels using saturation and value thresholds.
- Create visual documentation of fiber orientation patterns.

## Prerequisites

- A PPM **sum image** must be open in QuPath.
- A **PPM calibration** (sunburst calibration file) must be available. The tool
  searches in this order:
  1. Calibration stored in the image's PPM analysis set metadata.
  2. Calibration stored in the image entry's metadata.
  3. The active calibration path set in PPM preferences.
- A QuPath project is recommended (for automatic calibration discovery).

## Options

The control panel provides the following adjustable parameters:

### Angle Range

| Option | Type | Range | Default | Description |
|--------|------|-------|---------|-------------|
| Low | Slider | 0-180 | 0 | Lower bound of the fiber angle range to highlight (degrees). |
| High | Slider | 0-180 | 180 | Upper bound of the fiber angle range to highlight (degrees). |

Angles are in the range 0-180 degrees, representing axial fiber orientation
(fibers at 0 deg and 180 deg are equivalent).

### Validity Thresholds

| Option | Type | Range | Default | Description |
|--------|------|-------|---------|-------------|
| Saturation | Slider | 0.0-1.0 | 0.20 | Minimum HSV saturation for a pixel to be considered valid. Filters out low-color/white areas. |
| Value | Slider | 0.0-1.0 | 0.20 | Minimum HSV value (brightness) for a pixel to be considered valid. Filters out dark/background areas. |

### Overlay Appearance

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Color | Color Picker | Lime green | Color used to highlight matching pixels. |
| Opacity | Slider (0.0-1.0) | 0.50 | Transparency of the overlay. 0 = fully transparent, 1 = fully opaque. |

### Controls

| Control | Description |
|---------|-------------|
| Clear Overlay | Removes the overlay from the viewer and closes the control window. |

## Workflow

1. Open a PPM sum image in QuPath.
2. Launch the tool from the menu.
3. The overlay appears immediately on the viewer, and a control panel window opens.
4. Adjust the angle range sliders to highlight fibers in a specific orientation.
5. Adjust saturation and value thresholds to filter out background and
   low-signal pixels.
6. Change the overlay color and opacity as needed for visibility.
7. The stats display updates with the number of matching pixels and their
   percentage of all valid pixels.
8. Click "Clear Overlay" or close the control window when done.

## Output

The tool produces a **live overlay** on the QuPath viewer. No files are saved.

The stats display shows:
- Number of matching pixels out of total valid pixels.
- Percentage of valid pixels within the selected angle range.
- The current angle range in degrees.

## How It Works

1. The overlay reads the current viewport region from the image server.
2. Each pixel's RGB color is converted to HSV.
3. The hue is mapped to a fiber angle (0-180 deg) using the PPM calibration.
4. Pixels with saturation and value above the thresholds are considered valid.
5. Valid pixels whose angle falls within the specified range are highlighted.
6. The overlay is computed at a moderate downsample for performance (capped at
   ~4 million pixels per computation).
7. Changes to sliders are **debounced** (400ms delay) to avoid excessive
   recomputation while dragging.

## Tips & Troubleshooting

- **"No PPM calibration found"**: Run the sunburst calibration workflow first,
  or set the active calibration path in PPM preferences.
- **Overlay looks blank**: Widen the angle range (try 0-180) and lower the
  saturation/value thresholds to see if any pixels qualify. If the image is
  not a PPM sum image, the angle computation will not produce meaningful results.
- **Overlay is slow to update**: The computation is limited to ~4 million pixels.
  At very high zoom levels with large images, there may be a slight delay.
  Pan or zoom the view to trigger recomputation for the current viewport.
- **Only one overlay at a time**: Opening the filter on a new image automatically
  removes the previous overlay.
- **Closing QuPath**: The overlay is automatically cleaned up when the control
  window is closed or when QuPath exits.

## See Also

- [PPM Polarity Plot](ppm-polarity-plot.md) -- Quantitative angle histogram
  for a selected annotation
- [Surface Perpendicularity](surface-perpendicularity.md) -- Fiber orientation
  relative to annotation boundaries
- [Batch PPM Analysis](batch-ppm-analysis.md) -- Automated analysis across
  multiple images
