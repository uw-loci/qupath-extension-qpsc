# Background Collection

> Menu: Extensions > QP Scope > Collect Background Images
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

Acquire flat-field correction images for improved image quality. Background images correct for uneven illumination, dust on optics, and sensor artifacts. These correction images are essential for producing high-quality stitched acquisitions.

![Background Collection dialog](../images/Docs_BackgroundCollection.png)

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
| Acquisition Profile | ComboBox | Auto-resolved | (Optional) Acquisition profile whose illumination intensity the server applies during collection. Shown when the modality declares one or more acquisition profiles. The lamp intensity for the selected profile is displayed below. |
| Lamp Intensity | Label | From profile | (Read-only) Illumination intensity applied during collection, sourced from the selected acquisition profile. Visible only when the modality has adjustable illumination. Scopes without a lamp (e.g., PPM) do not show this field. |
| Output Folder | Directory Picker | - | Where to save background images |
| Use Per-Channel WB | CheckBox | OFF | Use per-channel white balance calibration (JAI cameras) |

### Angle Configuration (Multi-Angle Modalities)

For multi-angle modalities like PPM, configure each angle:

| Column | Type | Description |
|--------|------|-------------|
| Angle | Label | Polarizer angle in degrees |
| Target Intensity | Spinner | Target grayscale value (e.g., 245 for bright angles, 125 for medium) |
| Initial Exposure | Spinner | Starting exposure time in milliseconds |

### Channel Configuration (Fluorescence Modalities)

For fluorescence profiles (e.g., DAPI, FITC, TRITC), the collection uses a per-channel mode instead of angle-based configuration:

| Column | Type | Description |
|--------|------|-------------|
| Channel | Label | Channel identifier (e.g., DAPI, FITC) |
| Exposure | Label | Default exposure time for the channel (read-only, from profile) |
| Intensity | Label | Illumination intensity for the channel (read-only, from profile) |
| Status | Label | "In use" for channels with positive intensity, "Skipped (unused)" for channels with 0 intensity |

The **unused-channel rule** applies: a channel is collected only if it has a positive illumination intensity. Channels with 0 intensity are skipped, and no background is required for them at acquisition time. This means the number of backgrounds collected depends on how many channels in the profile have non-zero intensity.

## Workflow

1. Position the microscope at a clean, blank area with no tissue or debris
2. Open Collect Background Images from the menu
3. Select the modality, objective, and detector matching your acquisition setup
4. Choose the output folder for background images
5. (Optional) Select an acquisition profile if the modality has multiple profiles
6. Configure target intensities for each angle (if angle-based modality) or verify channel coverage (if fluorescence)
7. Click **Start** to begin collection

### For Angle-Based Modalities (PPM, Brightfield)

For each configured angle:

1. The system captures a test image
2. Exposure is automatically adjusted to reach the target intensity
3. The final background image is saved with metadata
4. The process repeats for all configured angles

### For Channel-Based Modalities (Fluorescence)

For each in-use channel in the selected profile:

1. The system configures the channel's exposure and illumination intensity
2. A test image is captured
3. Exposure may be adjusted if adaptive exposure is enabled
4. The final background image is saved as `<channelId>.tif` in a profile-keyed folder
5. The process repeats for all in-use channels (channels with 0 intensity are skipped)

## Output

### Angle-Based Modalities

- One background image per angle, saved to the output folder
- Each image includes metadata recording exposure, gain, and angle settings
- Images are named with the modality, objective, detector, and angle information
- Settings saved to `background_settings.yml` with all angle-exposure pairs

### Channel-Based Modalities

- One background image per in-use channel, named `<channelId>.tif`
- Saved in a profile-keyed subfolder (e.g., `.../<detector>/Fluorescence/20x/Fluorescence_20x/`)
- Each channel background records that channel's exposure and illumination intensity
- Settings saved to `background_settings.yml` (v2.0) with per-channel metadata and lamp intensity tracking

## Tips & Troubleshooting

- **Background exposures MUST match acquisition exposures** for proper flat-field correction
- Backgrounds are specific to each objective/detector/modality combination -- collect separately for each
- Recollect backgrounds after changing illumination settings or replacing the lamp
- **Lamp intensity tracking** (v2.0): The server records the lamp intensity used during background collection. The Acquisition Wizard will warn if that intensity no longer matches the active profile's setting; re-collect backgrounds to clear the warning
- JAI cameras automatically load per-channel white balance calibration when available (independent of per-channel background collection for fluorescence)
- **Per-channel backgrounds (fluorescence)** are collected only for channels marked as "In use" (positive illumination intensity). Channels with 0 intensity are skipped; no background is required for them at acquisition time
- If images show uneven patterns after correction, the background may be contaminated -- reposition to a cleaner area
- Ensure the blank area is truly uniform -- dust, scratches, or tissue remnants will contaminate the background
- For best results, collect backgrounds at the beginning of each imaging session
- If auto-exposure does not converge to the target intensity, check that the lamp is on and the illumination path is clear

## See Also

- [Camera Control](camera-control.md) - Verify camera settings before collecting backgrounds
- [Live Viewer](live-viewer.md) - Navigate to a clean blank area before collection
- [Bounded Acquisition](bounded-acquisition.md) - Uses background images for flat-field correction
- [Existing Image Acquisition](existing-image-acquisition.md) - Uses background images for flat-field correction
