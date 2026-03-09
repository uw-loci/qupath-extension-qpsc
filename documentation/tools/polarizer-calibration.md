# Polarizer Calibration (PPM)

> Menu: Extensions > QP Scope > PPM > Polarizer Calibration (PPM)...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Determine the exact hardware offset (`ppm_pizstage_offset`) for the PPM rotation
stage. This calibration finds the encoder position corresponding to crossed
polarizers (0 degrees optical) by performing a two-stage sweep of the rotation stage
and identifying the intensity minima.

Use this tool after initial hardware installation, after repositioning the rotation
stage, or if PPM images show incorrect polarization alignment.

## Prerequisites

- Microscope positioned at a uniform, bright background (blank slide recommended)
- PPM rotation stage connected and functional
- Connected to microscope server

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Coarse Step Size | Spinner | 5 deg | Step size for initial 360-degree sweep |
| Exposure Time | Spinner | 10 ms | Exposure for calibration images (keep short to avoid saturation) |

## Workflow

The calibration uses a two-stage process for precision:

### Stage 1 -- Coarse Sweep (~90 seconds)

1. Performs a full 360-degree rotation in hardware encoder counts.
2. Captures an image at each step position.
3. Measures mean intensity at each position.
4. Identifies the approximate locations of crossed polarizer minima (two minima,
   180 degrees apart).

### Stage 2 -- Fine Sweep (~40 seconds)

1. Performs a narrow sweep around each detected minimum from Stage 1.
2. Uses very small steps (0.1 degrees) for precise positioning.
3. Determines the exact hardware encoder position of each intensity minimum.
4. Calculates the recommended offset value.

## Output

The calibration report includes:

| Data | Description |
|------|-------------|
| Exact hardware positions | Encoder counts for crossed polarizer positions |
| Recommended offset | The `ppm_pizstage_offset` value to use in configuration |
| Optical angles | Angles relative to the recommended offset |
| Validation statistics | Data quality metrics |

After calibration, update `config_PPM.yml` with the recommended offset:

```yaml
ppm_pizstage_offset: 50228.7
```

## Tips & Troubleshooting

- **One-time calibration** -- this is only needed after hardware installation or
  repositioning. It is NOT needed between routine imaging sessions.
- **Keep exposure short** -- use a low exposure time (e.g., 10 ms) to avoid
  saturation at the uncrossed position while still getting measurable signal at
  the crossed position.
- **Uniform background** -- position on a blank, bright area. Sample features
  will add noise to the intensity measurements and reduce accuracy.
- **Two minima expected** -- crossed polarizers produce two intensity minima
  180 degrees apart. If only one is found, check that the rotation stage is
  completing the full sweep.
- The offset value is specific to your rotation stage hardware and does not
  change unless the stage is physically moved.

## See Also

- [PPM Sensitivity Test](ppm-sensitivity-test.md) -- Verify rotation stage precision after calibration
- [PPM Birefringence Optimization](ppm-birefringence-optimization.md) -- Find optimal imaging angles
- [PPM Reference Slide](ppm-reference-slide.md) -- Hue-to-angle calibration from sunburst slide
- [White Balance Calibration](white-balance-calibration.md) -- Calibrate camera white balance for PPM
- [All Tools](../UTILITIES.md) -- Complete utilities reference
