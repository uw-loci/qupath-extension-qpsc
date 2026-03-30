# Z-Stack / Time-Lapse

> Menu: Extensions > QP Scope > Utilities > Z-Stack / Time-Lapse...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Acquire single-tile Z-stacks or time-lapse sequences at the current stage position. These are basic acquisition modes for capturing 3D depth information or temporal dynamics without the complexity of multi-tile stitching.

## Prerequisites

- Connected to the microscope server
- Stage positioned at the area of interest (use the Live Viewer to navigate)
- For Z-stacks: focus set to the approximate center of the desired Z range

## Z-Stack

Acquires images at multiple Z positions centered on the current focus.

| Parameter | Description | Default |
|-----------|-------------|---------|
| Total range (um) | Full Z range to sweep (centered on current Z) | 20 |
| Step size (um) | Distance between Z planes | 1.0 |
| Output folder | Directory for saved images | `<project>/zstack/` |

The info label shows the computed Z range and number of planes. For example, 20 um range with 1 um step = 21 planes centered on current Z.

### Output

Individual TIFF files named `z0000_Z<position>.tif` with metadata including Z position and plane index.

## Time-Lapse

Acquires images at the current position at regular intervals.

| Parameter | Description | Default |
|-----------|-------------|---------|
| Timepoints | Number of acquisitions | 10 |
| Interval (sec) | Seconds between time points (0 = as fast as possible) | 5.0 |
| Output folder | Directory for saved images | `<project>/timelapse/` |

The info label shows total acquisition duration.

### Output

Individual TIFF files named `t00000_T<elapsed>s.tif` with metadata including timepoint index and elapsed time.

## PPM Support

Both Z-stack and time-lapse support PPM multi-angle acquisition. When the modality is set to PPM, all configured rotation angles are acquired at each Z plane or time point. Files are suffixed with the angle: `z0000_Z10.0_angle90.tif`.

## Future Expansion

The current implementation acquires a single tile. The architecture is designed for future multi-tile expansion:
- Multi-tile Z-stacks: iterate an XY grid, run Z-stack at each position
- Multi-tile time-lapse: iterate an XY grid at each time point
- Combined: XY grid x Z-stack x time points (requires careful ordering and stitching per Z/time)

## See Also

- [Live Viewer](live-viewer.md) -- Navigate to the position of interest
- [Camera Control](camera-control.md) -- Adjust exposure and gain before acquisition
