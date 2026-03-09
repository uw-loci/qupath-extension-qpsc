# PPM Reference Slide (Sunburst Calibration)

> Menu: Extensions > QP Scope > PPM > PPM Reference Slide...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Create a hue-to-angle calibration from a PPM reference slide with a sunburst
pattern. This calibration maps observed colors (hue values) to fiber orientation
angles (0-180 degrees) and is used by PPM image processing to accurately interpret
birefringence color data.

Use this tool whenever you need to establish or update the color-to-orientation
mapping for PPM analysis, such as after optical changes or when using a new
reference slide.

## Prerequisites

- PPM reference slide with a sunburst pattern (colored spokes at known orientations)
- Microscope positioned and focused on the calibration slide
- Camera settings configured for PPM imaging (low-angle PPM settings recommended)
- Connected to microscope server
- Polarizer calibration completed

## Options

### Calibration Folder

| Field | Description |
|-------|-------------|
| Calibration Folder | Root folder where calibration files will be saved |
| Browse... | Button to select the output folder |

Files are saved directly into this folder.

### Camera Setup

**Important:** Use low-angle PPM settings (7, 0, or -7 degrees) for best color
saturation during calibration.

| Button | Action |
|--------|--------|
| Open Camera Control... | Opens Camera Control dialog to set polarizer angle and verify camera settings before capture |

### Detection Settings

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| Expected Spokes | 16 | 4-32 | Number of spokes on your calibration slide |
| Saturation Threshold | 0.1 | 0.01-0.5 | Minimum HSV saturation for foreground detection |
| Value Threshold | 0.1 | 0.01-0.5 | Minimum HSV brightness for foreground detection |

**Common slide configurations:**

| Spokes | Spacing | Notes |
|--------|---------|-------|
| 16 | 11.25 deg | Standard sunburst slides |
| 12 | 15 deg | Some older slides |
| 8 | 22.5 deg | Simplified slides |

### Advanced Radial Detection Settings

Expand "Advanced Radial Detection Settings" for fine-tuning:

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| Inner Radius (px) | 30 | 10-200 | Inner radius for radial sampling from pattern center. Increase to skip noisy pixels near center |
| Outer Radius (px) | 150 | 50-500 | Outer radius for radial sampling. Should reach into colored spokes but not extend past them |

### Calibration Name

Optional custom name for calibration files. If left empty, an auto-generated
timestamp name is used.

**Allowed characters:** letters, numbers, underscores, hyphens.

## Workflow

1. Mount the PPM reference slide and focus on the sunburst pattern.
2. Open the PPM Reference Slide dialog.
3. Select a calibration output folder.
4. Click **Open Camera Control...** to set the polarizer to a low angle (7 deg
   recommended) for best color contrast.
5. Verify the expected spoke count matches your slide.
6. Adjust saturation and value thresholds if needed (defaults work for most slides).
7. Optionally set a calibration name.
8. Click Start to acquire and process.
9. The system:
   - Acquires an image using current camera settings.
   - Detects the sunburst pattern center (or uses manually selected center).
   - Samples hue values along radial spokes from the pattern center.
   - Creates a linear regression mapping hue to orientation angle (0-180 deg).
   - Saves calibration data.
10. Review the result dialog.

### Result Dialogs

**Success dialog** shows:
- R-squared value (quality of fit)
- Number of spokes detected
- Calibration file path
- Calibration plot image
- "Open Folder" button to navigate to output

**Failure dialog** shows:
- Error message
- Debug images (segmentation mask if available)
- Troubleshooting tips
- "Open Folder" button for inspecting debug images

**Debug mask interpretation:**
- WHITE pixels = detected foreground (above both saturation and value thresholds)
- BLACK pixels = background (below threshold)
- All black mask: thresholds too high -- lower them
- All white mask: thresholds too low -- raise them

**Manual center selection:** Both success and failure dialogs include a
"Manual Center Selection" section showing the captured image. Click on the
center of the sunburst pattern to specify the center point, then press
"Retry with Selected Center" to re-run calibration without re-acquiring.

**"Go Back and Redo" button:** Re-opens the parameter dialog to adjust settings
and re-acquire a new image.

## Output

| File | Description |
|------|-------------|
| `{name}_image.tif` | Acquired calibration image |
| `{name}.npz` | Calibration data (used by PPM analysis pipeline) |
| `{name}_plot.png` | Visual verification of calibration fit |

## Tips & Troubleshooting

**Spokes not detected:**
- Check the debug mask in the failure dialog to see what the detector sees.
- If mask is all BLACK: lower saturation and/or value thresholds (try 0.05).
- If mask is all WHITE: raise saturation and/or value thresholds (try 0.2-0.3).
- Ensure the slide is properly illuminated.
- Check that the polarizer is at a low angle (7 deg) for best color contrast.

**Wrong spoke count:**
- Verify the expected spokes setting matches your physical slide.
- Adjust thresholds to exclude noise or include faint spokes.
- Use the "Open Folder" button to inspect debug images.

**Poor calibration fit (low R-squared):**
- Ensure the slide is flat and in focus.
- Re-acquire at a different polarizer angle.
- Check for dust or damage on the calibration slide.
- Try manually selecting the pattern center if auto-detection is off.

**Center detection inaccurate:**
- Use the manual center selection feature in the result dialog.
- Increase inner radius to skip noisy center pixels.

## See Also

- [Polarizer Calibration](polarizer-calibration.md) -- Calibrate rotation stage offset (run before this)
- [PPM Birefringence Optimization](ppm-birefringence-optimization.md) -- Find optimal imaging angles for samples
- [PPM Sensitivity Test](ppm-sensitivity-test.md) -- Verify rotation stage precision
- [White Balance Calibration](white-balance-calibration.md) -- Calibrate camera white balance for PPM
- [All Tools](../UTILITIES.md) -- Complete utilities reference
