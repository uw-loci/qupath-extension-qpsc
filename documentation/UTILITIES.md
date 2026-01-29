# QPSC Utilities Reference

This document provides comprehensive documentation for all utilities available in the QPSC extension.

---

## Quick Reference

| Utility | Purpose | Menu Location |
|---------|---------|---------------|
| [Stage Control](#stage-control) | Manual microscope stage XYZ movement | Extensions > QP Scope > Stage Control |
| [Camera Control](#camera-control) | View/test camera exposure and gain settings | Extensions > QP Scope > Camera Control... |
| [Stage Map](#stage-map) | Visual map showing stage insert with slide positions | Extensions > QP Scope > Stage Map |
| [Server Connection Settings](#server-connection-settings) | Configure microscope server connection | Extensions > QP Scope > Server Connection Settings... |
| [Background Collection](#background-collection) | Capture flat-field correction images | Extensions > QP Scope > Collect Background Images |
| [White Balance Calibration](#white-balance-calibration) | Calibrate JAI 3-CCD camera white balance | Extensions > QP Scope > White Balance Calibration... |
| [Polarizer Calibration](#polarizer-calibration-ppm) | Calibrate polarizer rotation stage for PPM | Extensions > QP Scope > Polarizer Calibration (PPM)... |
| [PPM Reference Slide](#ppm-reference-slide-sunburst-calibration) | Hue-to-angle calibration from sunburst slide | Extensions > QP Scope > Utilities > PPM Reference Slide... |
| [Autofocus Editor](#autofocus-settings-editor) | Configure per-objective autofocus parameters | Extensions > QP Scope > Autofocus Settings Editor... |
| [Autofocus Benchmark](#autofocus-parameter-benchmark) | Find optimal autofocus settings systematically | Extensions > QP Scope > Autofocus Parameter Benchmark... |
| [PPM Sensitivity Test](#ppm-rotation-sensitivity-test) | Test rotation stage precision | Extensions > QP Scope > PPM Rotation Sensitivity Test... |
| [PPM Birefringence Optimization](#ppm-birefringence-optimization) | Find optimal polarizer angle for maximum contrast | Extensions > QP Scope > PPM Birefringence Optimization... |

---

## Stage Control

### Purpose
Manual control of the microscope stage for positioning and testing. Use this utility to verify communication with the microscope server and manually move to specific positions.

### Location
**Extensions > QP Scope > Stage Control**

### Dialog Fields

| Field | Type | Description |
|-------|------|-------------|
| Stage X (um) | Text Field | X coordinate in micrometers |
| Stage Y (um) | Text Field | Y coordinate in micrometers |
| Stage Z (um) | Text Field | Z coordinate (focus) in micrometers |
| Polarizer (deg) | Text Field | Polarizer rotation angle in degrees (PPM only) |

### Buttons

| Button | Action |
|--------|--------|
| **Move XY** | Move stage to the specified X and Y coordinates |
| **Move Z** | Move focus to the specified Z coordinate |
| **Move Polarizer** | Rotate polarizer to the specified angle |

### Usage Notes
- Current positions are displayed when the dialog opens
- Coordinates are validated against stage limits before movement
- Warning displayed if multiple viewers are open (may cause issues)
- Use this as a first test after setting up microscope connection

### Screenshot
`[Screenshot: documentation/images/stage-control.png]`

---

## Camera Control

### Purpose
View and test camera exposure and gain settings loaded from calibration profiles. This utility is particularly useful for JAI 3-CCD camera white balance troubleshooting and verifying calibration values before acquisition.

### Location
**Extensions > QP Scope > Camera Control...**

### Prerequisites
- Connected to microscope server (will prompt if not connected)

### Interface Sections

#### Camera Info
Displays the current camera name detected from the hardware.

#### Objective/Detector Selection

| Field | Type | Description |
|-------|------|-------------|
| Objective | ComboBox | Select objective to load calibration profile |
| Detector | ComboBox | Select camera/detector for calibration values |
| Reload from YAML | Button | Restore all values from YAML calibration file |

Changing the objective or detector automatically loads the corresponding calibration values.

#### Mode Toggles (JAI cameras only)

These options are only visible when a JAI 3-CCD camera is detected:

| Toggle | Options | Description |
|--------|---------|-------------|
| Exposure Mode | Individual / Unified | Individual: per-channel R/G/B exposures; Unified: single exposure for all channels |
| Gain Mode | Individual / Unified | Individual: per-channel R/G/B gains; Unified: single gain for all channels |

#### Per-Angle Settings

For PPM modality, displays calibration values for each polarizer angle:
- **Uncrossed (90 deg)**: Brightest angle
- **Crossed (0 deg)**: Darkest angle (extinction)
- **Positive (7 deg)**: Intermediate birefringence angle
- **Negative (-7 deg)**: Opposite intermediate angle

Each angle row shows:

| Field | Description |
|-------|-------------|
| Exposure All (ms) | Unified exposure time |
| Exposure R/G/B (ms) | Per-channel exposure times |
| Gain R/G/B | Per-channel analog gain values |
| Apply | Set camera settings AND move rotation stage to this angle |

### Live Mode Handling

When you click **Apply** for any angle, the system automatically:
1. Checks if live mode is currently running
2. Turns off live mode (if running)
3. Applies the exposure and gain settings
4. Moves the rotation stage to the target angle
5. Restores live mode (if it was running)

This ensures camera settings are applied cleanly without live mode interference.

### Important Notes
- Values can be edited for **testing purposes only**
- Changes are **NOT saved** to the YAML calibration file
- Use the [Background Collection](#background-collection) workflow for permanent calibration
- The dialog stays on top of other windows for convenience during testing

### Screenshot
`[Screenshot: documentation/images/camera-control.png]`

---

## Stage Map

### Purpose
Visual representation of the microscope stage insert showing slide positions. Helps users understand where slides are located on the stage and optionally overlay macro images.

### Location
**Extensions > QP Scope > Stage Map**

### Options

| Option | Type | Description |
|--------|------|-------------|
| Insert Configuration | ComboBox | Select the stage insert layout matching your hardware |
| Overlay Macro | CheckBox | Overlay the current macro image on the map display |

### Features
- Shows slide boundaries within the stage insert
- Displays current stage position
- Visual preview of accessible areas
- Helps prevent moving to positions outside the insert

### Screenshot
`[Screenshot: documentation/images/stage-map.png]`

---

## Server Connection Settings

### Purpose
Configure and test the connection between QuPath and the microscope control server. The server handles communication with Micro-Manager and the physical microscope hardware.

### Location
**Extensions > QP Scope > Server Connection Settings...**

### Connection Tab

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| Host | TextField | 127.0.0.1 | IP address or hostname of the microscope server |
| Port | Spinner | 5000 | Server port number |
| Auto-connect | CheckBox | ON | Automatically connect when QuPath starts |
| Auto-fallback | CheckBox | ON | Fall back to CLI mode if socket connection fails |

### Buttons

| Button | Action |
|--------|--------|
| **Test Connection** | Verify communication with server (returns stage position if successful) |
| **Connect Now** | Establish connection immediately |

### Advanced Tab

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| Connection timeout (ms) | Spinner | 5000 | Time to wait for initial connection |
| Read timeout (ms) | Spinner | 30000 | Time to wait for server responses |
| Max reconnect attempts | Spinner | 3 | Number of reconnection attempts on failure |
| Reconnect delay (ms) | Spinner | 1000 | Delay between reconnection attempts |
| Health check interval (ms) | Spinner | 30000 | How often to verify server is alive |

### Status Tab
- Displays current connection status
- Shows connection log with timestamps
- **Clear Log** button to reset the log display

### Usage Notes
- Run **Test Connection** first to verify setup
- If connection fails, check that Python server is running
- Check firewall settings if connecting to remote host
- Health checks keep the connection alive during long operations

### Screenshot
`[Screenshot: documentation/images/server-settings.png]`

---

## Background Collection

### Purpose
Acquire flat-field correction images for improved image quality. Background images correct for uneven illumination, dust on optics, and sensor artifacts.

### Location
**Extensions > QP Scope > Collect Background Images**

### Prerequisites
- Microscope positioned at clean, blank area (empty slide or uniform background)
- For JAI cameras: Run [White Balance Calibration](#white-balance-calibration) first

### Options

| Option | Type | Description |
|--------|------|-------------|
| Modality | ComboBox | Select imaging modality (e.g., ppm_20x) |
| Objective | ComboBox | Select objective lens |
| Detector | ComboBox | Select camera/detector |
| Output Folder | Directory Picker | Where to save background images |
| Use Per-Channel WB | CheckBox | Use per-channel white balance calibration (JAI cameras) |

### Angle Configuration
For multi-angle modalities like PPM, configure each angle:

| Column | Description |
|--------|-------------|
| Angle | Polarizer angle in degrees |
| Target Intensity | Target grayscale value (e.g., 245 for bright, 125 for medium) |
| Initial Exposure | Starting exposure time in milliseconds |

### What Happens
1. For each angle, the system captures a test image
2. Exposure is automatically adjusted to reach target intensity
3. Final background image is saved with metadata
4. Process repeats for all configured angles

### Important Notes
- Background exposures MUST match acquisition exposures for proper correction
- Backgrounds are specific to objective/detector/modality combination
- Recollect after changing illumination settings or lamp replacement
- JAI cameras automatically load per-channel calibration when available

### Screenshot
`[Screenshot: documentation/images/background-collection.png]`

---

## White Balance Calibration

### Purpose
Calibrate per-channel (R, G, B) exposure times for JAI 3-CCD prism cameras. This ensures neutral white balance at each polarization angle without digital gain artifacts.

### Location
**Extensions > QP Scope > White Balance Calibration...**

### Prerequisites
- Microscope positioned at clean, white reference area
- JAI or other prism-based camera selected

### Options

| Option | Type | Description |
|--------|------|-------------|
| Mode | ComboBox | **Simple** (single angle) or **PPM** (4 angles) |
| Target Intensity | Spinner | Target grayscale value to achieve (default: 200) |
| Tolerance | Spinner | Acceptable deviation from target (default: 5) |
| Max Analog Gain | Spinner | Maximum allowed analog gain setting |
| Gain Threshold Ratio | Spinner | Ratio at which to start using analog gain |
| Calibrate Black Level | CheckBox | Also calibrate black level offsets |

### PPM Mode Per-Angle Targets
When PPM mode is selected, configure individual targets for each angle:
- **Crossed (0 deg)**: Typically higher target (~245) as this is the darkest angle
- **Uncrossed (90 deg)**: Lower target (~125) as this is the brightest angle
- **Positive/Negative (±7 deg)**: Intermediate targets

### What Happens
1. System iteratively adjusts R, G, B exposure times
2. Each channel is adjusted independently to achieve neutral gray
3. Calibration is saved to `imageprocessing_{microscope}.yml`
4. Subsequent background collection and acquisitions use these settings

### Output
Calibration is stored in YAML format:
```yaml
imaging_profiles:
  ppm:
    LOCI_OBJECTIVE_20X:
      LOCI_DETECTOR_JAI:
        angles:
          crossed:
            exposures_ms: {r: 45.2, g: 38.1, b: 52.3}
```

### Important Notes
- Run this BEFORE background collection for JAI cameras
- Each objective/detector combination needs separate calibration
- Recalibrate after hardware changes affecting light path

### Screenshot
`[Screenshot: documentation/images/white-balance.png]`

---

## Polarizer Calibration (PPM)

### Purpose
Determine the exact hardware offset (`ppm_pizstage_offset`) for the PPM rotation stage. This calibration finds the encoder position corresponding to crossed polarizers (0 degrees optical).

### Location
**Extensions > QP Scope > Polarizer Calibration (PPM)...**

### Prerequisites
- Microscope positioned at uniform, bright background
- PPM rotation stage connected and working

### Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Coarse Step Size | Spinner | 5 deg | Step size for initial 360-degree sweep |
| Exposure Time | Spinner | 10 ms | Exposure for calibration images (keep short) |

### Two-Stage Calibration Process

**Stage 1 - Coarse Sweep (~90 seconds):**
- Full 360-degree rotation in hardware encoder counts
- Identifies approximate locations of crossed polarizer minima (180 degrees apart)

**Stage 2 - Fine Sweep (~40 seconds):**
- Narrow sweep around each detected minimum
- Very small steps (0.1 degrees) for precise positioning
- Determines exact hardware position of each intensity minimum

### Output Report Includes
- Exact hardware positions (encoder counts) for crossed polarizers
- Recommended `ppm_pizstage_offset` value
- Optical angles relative to recommended offset
- Validation data and statistics

### After Calibration
Update `config_PPM.yml` with the recommended offset:
```yaml
ppm_pizstage_offset: 50228.7
```

### Important Notes
- This calibration is only needed after hardware installation/repositioning
- NOT needed between routine imaging sessions
- The offset value is specific to your rotation stage

### Screenshot
`[Screenshot: documentation/images/polarizer-calibration.png]`

---

## Autofocus Settings Editor

### Purpose
Configure per-objective autofocus parameters in an easy-to-use GUI. Allows customization of focus search behavior for different objectives.

### Location
**Extensions > QP Scope > Autofocus Settings Editor...**

### Options

| Parameter | Type | Typical Range | Description |
|-----------|------|---------------|-------------|
| Objective | ComboBox | - | Select objective to configure |
| n_steps | Spinner | 5-20 | Number of Z positions to sample during autofocus |
| search_range_um | Spinner | 10-50 | Total Z range to search in micrometers |
| n_tiles | Spinner | 3-10 | Autofocus runs every N tiles during acquisition |

### Buttons

| Button | Action |
|--------|--------|
| **Write to File** | Save all settings to YAML file |
| **OK** | Save and close dialog |
| **Cancel** | Discard unsaved changes |

### Parameter Guidelines

| Objective | n_steps | search_range_um | n_tiles |
|-----------|---------|-----------------|---------|
| 10X | 9 | 15 | 5 |
| 20X | 11 | 15 | 5 |
| 40X | 15 | 10 | 7 |

### Usage Notes
- Higher magnification objectives need more steps and smaller search range
- Lower n_tiles = more frequent autofocus = slower but more reliable
- Thick samples may need larger search_range_um
- Settings are stored in `autofocus_{microscope}.yml`

### Screenshot
`[Screenshot: documentation/images/autofocus-editor.png]`

---

## Autofocus Parameter Benchmark

### Purpose
Systematically find optimal autofocus settings by testing multiple parameter combinations. Useful for optimizing performance on new sample types or after hardware changes.

### Location
**Extensions > QP Scope > Autofocus Parameter Benchmark...**

### Options

| Option | Type | Description |
|--------|------|-------------|
| Reference Z Position | Spinner | Known good focus position to use as reference |
| Output Directory | Directory Picker | Where to save benchmark results |
| Test Distances | Text Field | Comma-separated list of defocus distances to test |
| Quick Mode | CheckBox | Faster but less comprehensive testing |

### What Happens
1. System defocuses by specified amounts from reference position
2. Runs autofocus with various parameter combinations
3. Measures how accurately focus is recovered
4. Generates report with optimal parameter recommendations

### Output
- CSV file with all test results
- Summary report with best parameter combinations
- Graphs showing performance vs. parameters (if quick mode disabled)

### Usage Notes
- Position microscope on representative sample area first
- Ensure reference Z position is correctly focused
- Full benchmark may take 10-30 minutes depending on parameter ranges
- Quick mode tests fewer combinations but finishes faster

### Screenshot
`[Screenshot: documentation/images/autofocus-benchmark.png]`

---

## PPM Rotation Sensitivity Test

### Purpose
Test the precision and repeatability of the rotation stage. Verifies that the stage can reliably reach and maintain specified angles.

### Location
**Extensions > QP Scope > PPM Rotation Sensitivity Test...**

### What It Tests
- Stage positioning accuracy at target angles
- Repeatability over multiple movements
- Backlash compensation effectiveness
- Position stability over time

### Output
- Position accuracy statistics
- Repeatability measurements
- Recommendations for hardware adjustment if needed

### Usage Notes
- Run after rotation stage installation or service
- Can help diagnose mechanical issues
- Not needed for routine operation

### Screenshot
`[Screenshot: documentation/images/ppm-sensitivity.png]`

---

## PPM Birefringence Optimization

### Purpose
Find the optimal polarizer angle that maximizes birefringence contrast for your sample. Different samples may have optimal imaging at slightly different angles from the standard positions.

### Location
**Extensions > QP Scope > PPM Birefringence Optimization...**

### Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Output Folder | Directory Picker | - | Where to save optimization results |
| Min Angle | Spinner | -15 | Starting angle for sweep |
| Max Angle | Spinner | 15 | Ending angle for sweep |
| Angle Step | Spinner | 0.5 | Degrees between test positions |
| Exposure Mode | ComboBox | Interpolate | How to handle exposure at each angle |

### Exposure Modes

| Mode | Description |
|------|-------------|
| **Interpolate** | Calculate exposure based on neighboring calibrated angles |
| **Calibrate** | Run auto-exposure at each angle (slower but more accurate) |
| **Fixed** | Use single fixed exposure for all angles |

### What Happens
1. Rotates through specified angle range
2. Captures image at each position
3. Calculates birefringence contrast metrics
4. Identifies angle(s) with maximum contrast

### Output
- Images at each test angle
- Contrast metrics plot
- Recommended optimal angles for imaging

### Usage Notes
- Position on representative sample region first
- Wider angle range = more comprehensive but slower
- Smaller step size = more precision but slower
- Results may vary between sample types

### Screenshot
`[Screenshot: documentation/images/ppm-birefringence.png]`

---

## PPM Reference Slide (Sunburst Calibration)

### Purpose
Create a hue-to-angle calibration from a PPM reference slide with a sunburst pattern. This calibration is used by PPM image processing to accurately map observed colors to fiber orientation angles.

### Location
**Extensions > QP Scope > Utilities > PPM Reference Slide...**

### Prerequisites
- PPM reference slide (sunburst pattern with colored rectangles at known orientations)
- Microscope positioned and focused on the calibration slide
- Camera settings configured for PPM imaging

### Workflow Overview

1. Acquire an image using current camera settings
2. Detect oriented rectangles in the sunburst pattern
3. Extract hue values from each detected rectangle
4. Create a linear regression mapping hue to orientation angle (0-180 degrees)
5. Save calibration data for use in PPM analysis

### Dialog Options

#### Calibration Folder

| Field | Description |
|-------|-------------|
| Calibration Folder | Root folder for calibration files |
| Browse... | Select output folder |

Files are organized as: `{folder}/{modality}/calibration_files`

#### Camera Setup

**Important:** Use low-angle PPM settings (7, 0, or -7 degrees) for best color saturation during calibration.

| Button | Action |
|--------|--------|
| Open Camera Control... | Opens [Camera Control](#camera-control) dialog to set polarizer angle and verify camera settings |

This integration allows you to set the rotation stage to the optimal angle before capturing the calibration image.

#### Detection Settings

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| Expected Rectangles | 16 | 4-32 | Number of rectangles on your calibration slide |
| Saturation Threshold | 0.1 | 0.01-0.5 | Minimum HSV saturation for foreground detection. In the debug mask: WHITE = detected, BLACK = background |
| Value Threshold | 0.1 | 0.01-0.5 | Minimum HSV brightness for foreground detection. In the debug mask: WHITE = detected, BLACK = background |

**Common slide configurations:**
- 16 rectangles: Standard sunburst slides (22.5 degree spacing)
- 12 rectangles: Some older slides (30 degree spacing)
- 8 rectangles: Simplified slides (45 degree spacing)

#### Calibration Name
Optional custom name for calibration files. If empty, an auto-generated timestamp name is used.

**Allowed characters:** letters, numbers, underscores, hyphens

### Output Files

| File | Description |
|------|-------------|
| `{name}_image.tif` | Acquired calibration image |
| `{name}.npz` | Calibration data (used by PPM analysis) |
| `{name}_plot.png` | Visual verification of calibration fit |

### Result Dialogs

**Success dialog** shows R-squared value, rectangles detected, calibration file path, and the calibration plot image. An "Open Folder" button navigates to the output directory.

**Failure dialog** shows the error message, debug images (segmentation mask if available), and troubleshooting tips. An "Open Folder" button navigates to the debug/output directory for inspecting saved images and masks.

**Debug mask interpretation:**
- **WHITE pixels** = detected foreground (pixels above both saturation and value thresholds)
- **BLACK pixels** = background (pixels below threshold)
- All black mask: thresholds too high -- lower saturation/value thresholds
- All white mask: thresholds too low -- raise saturation/value thresholds

### Troubleshooting

**Rectangles not detected:**
- Check the debug mask in the failure dialog to understand what the detector sees
- If mask is all BLACK: lower saturation and/or value thresholds (try 0.05)
- If mask is all WHITE: raise saturation and/or value thresholds (try 0.2-0.3)
- Ensure slide is properly illuminated
- Check that polarizer is at a low angle (7 deg) for best color contrast

**Wrong rectangle count:**
- Verify the expected rectangles matches your slide
- Adjust thresholds to exclude noise or include faint rectangles
- Use the "Open Folder" button in the result dialog to inspect debug images

**Poor calibration fit:**
- Ensure slide is flat and in focus
- Re-acquire at a different polarizer angle
- Check for dust or damage on the calibration slide

### Screenshot
`[Screenshot: documentation/images/ppm-reference-slide.png]`

---

## See Also

- [Workflows Guide](WORKFLOWS.md) - Step-by-step workflow documentation
- [Preferences Reference](PREFERENCES.md) - All settings explained
- [Troubleshooting Guide](TROUBLESHOOTING.md) - Common issues and solutions
