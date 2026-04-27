# Camera Control

> Menu: Extensions > QP Scope > Camera Control...
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md)

## Purpose

View and test camera exposure, gain, binning, and illumination settings. The dialog renders dynamically from the server's capability response (`GETCAP`) so a new camera, modality, or illumination source declared in YAML appears with no Java-side changes. Changes made here are for testing/tuning only and are not saved to the YAML calibration file unless explicitly stored as a preset.

![Camera Control dialog](../images/Docs_CameraControl.png)

## Prerequisites

- Connected to microscope server (will prompt if not connected)

## Options

### Camera Info

Displays the current camera name and type (`jai`, `hamamatsu`, `laser_scanning`, `generic`) detected from the hardware.

### Objective/Detector Selection

| Option | Type | Description |
|--------|------|-------------|
| Objective | ComboBox | Select objective to load its calibration profile |
| Detector | ComboBox | Select camera/detector for calibration values |
| Reload from YAML | Button | Restore all values from the YAML calibration file |

Changing the objective or detector automatically loads the corresponding calibration values.

### Binning

ComboBox populated from the camera's allowed binning factors (`GETBIN`). Hidden when the camera reports only `[1]`. Selecting a value and clicking Apply stops live mode, writes MM's `Binning` property, and restarts live mode. Cameras that snap to nearest supported value have their post-write binning re-read so the ComboBox reflects what the hardware actually accepted.

### Exposure Mode Toggle (per-channel-capable cameras only)

Visible when the active camera reports `supports_per_channel_exposure: true` (today, JAI 3-CCD).

| Option | Values | Description |
|--------|--------|-------------|
| Exposure Mode | Individual / Unified | Individual: per-channel R/G/B exposures. Unified: single exposure for all channels. |

Gain is always unified. The gain row shows unified gain plus analog Red and Blue gain values.

### Per-Angle Cards (multi-angle modalities)

Visible when the active acquisition profile reports `is_multi_angle: true` from `GETCAP` -- specifically the *currently-selected* profile, not "any modality has rotation" (the old conservative heuristic). Switching from a multi-angle profile (e.g. `ppm_20x`) to a single-shot one (e.g. `Brightfield_10x`) hides the per-angle card without reopening the dialog.

For PPM-style modalities, each rotation angle declared in `modalities.<name>.rotation_angles` is displayed as a card with exposure, gain, and an Apply button that writes settings AND drives the rotation stage to that angle.

### Illumination Sources

One Apply row per declared illumination source (from `cap.illumination[]`). 1 source -> 1 row, 7 sources -> 7 rows -- no per-device branches in the UI.

Row format: `<Label>:  <input widget>  <range hint>  [Apply]  [ON|OFF]`

Input widget swaps based on the source's `value_type`:
- **`binary`** -- CheckBox (`On`). Only valid powers are 0 and `power_range[1]`. Used for sources whose MM device exposes only an enumerated `State` property (e.g. OWS3 Epi LED via `LappMainBranch1`, where `state_property == intensity_property`).
- **`continuous`** -- TextField with prompt-text-typed range. Any value in `power_range` is valid.
- **`discrete`** -- reserved for future use (small enumerated set; would render as radio buttons).

Apply on a row sends `SETILLMD` for that row's device, independent of which source the active profile selected. So you can drive Lumencor while in Brightfield mode (and vice versa) without first APPLYPRing. Note: if the row's optical path is not currently selected (e.g. the cube turret routes light away from this device), the value is staged but no light reaches the sample until you APPLYPR the matching profile.

### Acquisition Profile selector

ComboBox of all profiles in `acquisition_profiles`, optionally filtered by modality. Apply runs `APPLYPR` (cube + light path + per-modality illumination + Z/F stage positions). On change, the dialog re-queries `GETCAP` so per-angle visibility, illumination rows, and the active source's input-widget type all update without a dialog reopen.

### Save/Load Preset

Saves the current exposure / gain / illumination state as a named preset, keyed by modality + objective + detector. Saved format includes the active profile name (`profile|exp|gain|illum`) so on Load the dialog runs `APPLYPR` for the saved profile *before* `SETCAM`/`SETILLM`. This eliminates the previous failure mode where loading a Brightfield preset after a Fluorescence acquisition routed power for DiaLamp into the Epi LED and surfaced as `ERR_ILLM`. Legacy presets (no leading profile name) still load and auto-resolve to the first profile matching the section's modality.

## Workflow

1. Open Camera Control from the menu
2. Select the objective and detector combination to load calibration values
3. (Optional) Select an acquisition profile and click Apply to switch the hardware to that mode
4. Tune the values per source / per channel using each row's Apply button
5. The system automatically pauses live mode around camera-state changes and restores it after
6. (Optional) Save the tuned values as a preset for quick recall later
7. Use **Reload from YAML** to revert to saved calibration values after testing

## Output

No persistent output. Camera Control is a testing and verification tool. Changes to exposure and gain values are applied to the hardware temporarily but are not saved to the YAML calibration file.

## Tips & Troubleshooting

- Values can be edited for **testing purposes only** -- changes are not saved
- Use [Background Collection](background-collection.md) for permanent calibration changes
- The dialog stays on top of other windows for convenience during testing
- When clicking Apply, live mode is automatically paused and restored to prevent interference
- If exposure values look wrong, click **Reload from YAML** to restore saved calibration
- For JAI cameras, verify both unified and per-channel modes work as expected

## See Also

- [Live Viewer](live-viewer.md) - Real-time camera feed to see the effect of settings changes
- [Background Collection](background-collection.md) - Collect flat-field correction images with calibrated settings
- [Bounded Acquisition](bounded-acquisition.md) - Uses the calibration profiles tested here
- [Existing Image Acquisition](existing-image-acquisition.md) - Uses the calibration profiles tested here
