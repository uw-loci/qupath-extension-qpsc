# PPM Birefringence Optimization

> Menu: Extensions > QP Scope > PPM > PPM Birefringence Optimization...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Find the optimal polarizer angle that maximizes birefringence contrast for your
sample. Different sample types may have optimal imaging at slightly different angles
from the standard positions, and this tool sweeps through a range of angles to
identify the best contrast.

Use this tool when working with a new sample type or when the standard PPM angles
do not produce satisfactory birefringence contrast.

## Prerequisites

- Microscope positioned on a representative area of the sample
- PPM rotation stage connected, calibrated, and functional
- Connected to microscope server
- Sample in focus

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Output Folder | Directory Picker | - | Where to save optimization results and images |
| Min Angle | Spinner | -15 | Starting angle for the sweep (degrees) |
| Max Angle | Spinner | 15 | Ending angle for the sweep (degrees) |
| Angle Step | Spinner | 0.5 | Degrees between test positions |
| Exposure Mode | ComboBox | Interpolate | How to handle exposure at each angle |

### Exposure Modes

| Mode | Description |
|------|-------------|
| **Interpolate** | Calculate exposure based on neighboring calibrated angles (fastest) |
| **Calibrate** | Run auto-exposure at each angle (slowest but most accurate) |
| **Fixed** | Use a single fixed exposure for all angles (fast, less accurate at extremes) |

## Workflow

1. Position the microscope on a representative region of your sample.
2. Ensure the sample is in focus.
3. Open the PPM Birefringence Optimization dialog.
4. Select an output folder for results.
5. Configure the angle range and step size:
   - Default range (-15 to +15 deg) covers both positive and negative birefringence
     angles around crossed position.
   - Smaller step size gives more precision but takes longer.
6. Select the exposure mode.
7. Click Start.
8. The system rotates through the specified angle range, capturing an image at each
   position and calculating birefringence contrast metrics.
9. Results are displayed with the optimal angle(s) identified.

## Output

| Output | Description |
|--------|-------------|
| Images | Captured image at each test angle |
| Contrast plot | Graph of birefringence contrast vs. polarizer angle |
| Optimal angles | Recommended angle(s) for maximum contrast on this sample |

## Tips & Troubleshooting

- **Sample positioning matters** -- choose an area with clear birefringent features.
  A blank or isotropic region will not produce useful contrast data.
- **Wider range = more comprehensive** -- if unsure where the optimal angle is,
  use a wider sweep range (e.g., -30 to +30 deg).
- **Smaller step = more precision** -- use 0.5 deg steps for initial survey, then
  narrow the range and use 0.1 deg steps to refine.
- **Exposure mode trade-offs**:
  - Interpolate is fastest and works well when calibrated angles bracket the sweep.
  - Calibrate is most accurate but significantly slower.
  - Fixed is fast but may under/overexpose at angles far from the fixed setting.
- **Results vary by sample type** -- collagen, bone, and other birefringent tissues
  may have different optimal angles. Run this for each new sample type.

## See Also

- [Polarizer Calibration](polarizer-calibration.md) -- Calibrate the rotation stage offset (prerequisite)
- [PPM Sensitivity Test](ppm-sensitivity-test.md) -- Verify rotation stage precision
- [PPM Reference Slide](ppm-reference-slide.md) -- Hue-to-angle calibration from sunburst slide
- [All Tools](../UTILITIES.md) -- Complete utilities reference
