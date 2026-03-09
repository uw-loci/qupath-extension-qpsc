# PPM Rotation Sensitivity Test

> Menu: Extensions > QP Scope > PPM > PPM Rotation Sensitivity Test...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Test the precision and repeatability of the PPM rotation stage. This tool verifies
that the stage can reliably reach and maintain specified angles by performing
repeated movements and measuring positioning accuracy.

Use this tool after rotation stage installation or service, to diagnose suspected
mechanical issues, or to verify that backlash compensation is working correctly.

## Prerequisites

- PPM rotation stage connected and functional
- Polarizer calibration completed (offset value configured)
- Connected to microscope server

## Options

This tool runs a predefined test sequence. No user-configurable options are required
beyond confirming the test should proceed.

## Workflow

1. Open the PPM Rotation Sensitivity Test from the menu.
2. Confirm to start the test.
3. The system automatically:
   - Moves the rotation stage to multiple target angles.
   - Repeats movements to measure repeatability.
   - Tests backlash by approaching targets from both directions.
   - Measures position stability over brief hold periods.
4. Results are displayed when the test completes.

## Output

| Metric | Description |
|--------|-------------|
| Position accuracy | How close the stage gets to each target angle |
| Repeatability | Variation across multiple movements to the same target |
| Backlash | Difference in position when approaching from opposite directions |
| Stability | Position drift during hold periods |
| Recommendations | Hardware adjustment suggestions if performance is outside spec |

## Tips & Troubleshooting

- **Not needed for routine operation** -- this is a diagnostic tool for hardware
  verification, not part of the normal imaging workflow.
- **Run after service** -- if the rotation stage has been serviced, replaced, or
  repositioned, run this test to verify performance before imaging.
- **Diagnosing drift** -- if PPM images show inconsistent polarization across a
  tiled acquisition, this test can reveal whether the rotation stage is the cause.
- **Backlash issues** -- if the test shows significant backlash, the rotation
  stage may need mechanical adjustment or the backlash compensation parameters
  in the configuration may need updating.

## See Also

- [Polarizer Calibration](polarizer-calibration.md) -- Calibrate the rotation stage offset (run first)
- [PPM Birefringence Optimization](ppm-birefringence-optimization.md) -- Find optimal imaging angles
- [PPM Reference Slide](ppm-reference-slide.md) -- Hue-to-angle calibration from sunburst slide
- [All Tools](../UTILITIES.md) -- Complete utilities reference
