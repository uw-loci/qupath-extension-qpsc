# Background Collection

> Menu: Extensions > QP Scope > Collect Background Images
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Acquire flat-field correction images for improved image quality. Background images correct for uneven illumination, dust on optics, and sensor artifacts. These correction images are essential for producing high-quality stitched acquisitions.

## Prerequisites

- Microscope positioned at a clean, blank area (empty slide or uniform background)
- Connected to microscope server
- For JAI cameras: Run White Balance Calibration first

## Options

### General Settings

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Modality | ComboBox | From config | Select imaging modality (e.g., ppm_20x) |
| Objective | ComboBox | From config | Select objective lens |
| Detector | ComboBox | From config | Select camera/detector |
| Output Folder | Directory Picker | - | Where to save background images |
| Use Per-Channel WB | CheckBox | OFF | Use per-channel white balance calibration (JAI cameras) |

### Angle Configuration (Multi-Angle Modalities)

For multi-angle modalities like PPM, configure each angle:

| Column | Type | Description |
|--------|------|-------------|
| Angle | Label | Polarizer angle in degrees |
| Target Intensity | Spinner | Target grayscale value (e.g., 245 for bright angles, 125 for medium) |
| Initial Exposure | Spinner | Starting exposure time in milliseconds |

## Workflow

1. Position the microscope at a clean, blank area with no tissue or debris
2. Open Collect Background Images from the menu
3. Select the modality, objective, and detector matching your acquisition setup
4. Choose the output folder for background images
5. Configure target intensities for each angle (if multi-angle modality)
6. Click **Start** to begin collection

For each configured angle:

1. The system captures a test image
2. Exposure is automatically adjusted to reach the target intensity
3. The final background image is saved with metadata
4. The process repeats for all configured angles

## Output

- One background image per angle, saved to the output folder
- Each image includes metadata recording exposure, gain, and angle settings
- Images are named with the modality, objective, detector, and angle information

## Tips & Troubleshooting

- **Background exposures MUST match acquisition exposures** for proper flat-field correction
- Backgrounds are specific to each objective/detector/modality combination -- collect separately for each
- Recollect backgrounds after changing illumination settings or replacing the lamp
- JAI cameras automatically load per-channel calibration when available
- If images show uneven patterns after correction, the background may be contaminated -- reposition to a cleaner area
- Ensure the blank area is truly uniform -- dust, scratches, or tissue remnants will contaminate the background
- For best results, collect backgrounds at the beginning of each imaging session
- If auto-exposure does not converge to the target intensity, check that the lamp is on and the illumination path is clear

## See Also

- [Camera Control](camera-control.md) - Verify camera settings before collecting backgrounds
- [Live Viewer](live-viewer.md) - Navigate to a clean blank area before collection
- [Bounded Acquisition](bounded-acquisition.md) - Uses background images for flat-field correction
- [Existing Image Acquisition](existing-image-acquisition.md) - Uses background images for flat-field correction
