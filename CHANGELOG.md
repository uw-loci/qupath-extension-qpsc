# Changelog

All notable changes to the QPSC QuPath Extension will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

*No unreleased changes.*

## [0.3.0] - 2026-03-02

### Added

**Live Camera Viewer**
- Real-time camera feed window with streaming from MicroManager circular buffer
- 256-bin luminance histogram with per-channel saturation percentage (turns red when any channel exceeds 1%)
- Min/max contrast sliders with auto-scale, Fit mode, display scale selector
- RGB readouts for calibration diagnostics
- Double-click-to-center feature for stage navigation
- Integrated Stage Control panel (see below)

**Stage Control (integrated into Live Viewer)**
- Virtual joystick with quadratic response curve
- FOV-based step size dropdown for intuitive stage movement
- Two-tab layout: Movement tab and Saved Points tab
- Double-step arrows for coarse/fine movement
- Saved stage positions (named, persisted via JSON preferences)
- Sample movement mode with correct axis conventions
- Rate-limited joystick commands (prevents command flooding)

**White Balance System (JAI/Prism Cameras)**
- Full per-angle white balance calibration with iterative R/G/B exposure adjustment
- Four explicit WB modes: Off, Camera AWB, Simple, Per-angle
- Per-angle target intensity editing
- Advanced settings: max analog gain, gain threshold ratio, black level calibration
- Objective/detector selection with automatic calibration profile loading
- WB Comparison Test workflow for side-by-side WB mode comparison
- Gain values displayed in calibration results with Open Folder button

**Camera Control Dialog**
- Card-based layout with per-angle cards and color-coded exposure/gain rows
- Real-time gain validation with range tooltips
- Unified gain model (R/G/B shows analog gains, unified gain for all channels)
- Singleton dialog (brings to front if already open)

**Sunburst Calibration (PPM Reference Slide)**
- Hue-to-angle mapping workflow with radial spoke detection
- Advanced radial settings (inner/outer radius)
- Manual center selection UI for retry on detection failure
- Debug mask display in failure dialog
- All dialog settings persisted between sessions
- Redo callback and Go Back button in result dialogs

**JAI Noise Characterization**
- Quick/Full/Custom presets for noise measurement
- NoiseStatsPanel in Live Viewer (R/G/B Mean/StdDev/SNR grid)
- Persistent preferences, non-modal progress display

**CONFIG Command Handshake**
- Extension sends microscope configuration path to server on connection
- Server validates configuration before accepting commands
- User-friendly error handling for CONFIG failures

**Other New Features**
- Auxiliary socket for non-blocking Live Viewer and stage control operations during acquisition
- Objective pixel size mismatch warning before acquisition (queries MicroManager active pixel size)
- "No Manual Autofocus (Danger)" preference for unattended overnight acquisition
- CI workflow and Dependabot for automated build/dependency tracking
- Acquisition preview tile count now matches actual tiling logic

### Changed

**Menu Structure**
- Utilities menu reorganized by function (camera calibration, autofocus, PPM)
- "Starburst Calibration" renamed to "PPM Reference Slide..."
- "rectangles" renamed to "spokes" in all calibration dialogs
- Conditional "JAI Camera" submenu appears only when JAI camera detected
- Stage Control removed as separate menu item (integrated into Live Viewer)

**White Balance UI**
- Old boolean checkboxes ("White Balance" + "Per-Angle WB") replaced with single WB Mode ComboBox (Off / Camera AWB / Simple / Per-angle)
- WB mode applies on combo box selection directly (removed Apply button)
- Target Intensity moved from Shared Settings to Simple WB section
- Objective selection moved to Shared Settings (both Simple and PPM need it)

**Camera/Gain Model**
- Unified gain model replaces per-channel gain throughout
- Camera Control redesigned with card-based layout
- Gain mode radio buttons removed (gain is always unified; R/G/B shows analog gains)

**Dialog Behavior**
- Multiple dialogs made non-modal: calibration, birefringence progress, workflow progress, stage move
- Camera Control and Stage Control dialogs made singleton
- Exposure time fields removed from PPM angle selection dialog (exposures now auto-determined)
- Stage Map window doubled in size (840x760) with hollow crosshair circle and ScrollPane

**Configuration**
- Default calibration folder name changed to "ppm_reference_slide"
- Project logs moved to `<project>/logs/` subdirectory with config-based fallback
- Macro image crop bounds are now fully config-driven

### Fixed

**Stage Map / Macro Overlay**
- Fix macro overlay misplacement on flipped (inverted XY) image entries
- Fix macro overlay positioning, scale, aspect ratio, and flip handling (extensive series of fixes)
- Backward-compatible format marker for old vs new alignment files

**Autofocus**
- Fix focus Z position persistence across WB comparison modes
- Fix manual focus cancel deadlock (sends SKIPAF before CANCEL to unblock server)
- Fix manual focus skip preventing stitching in BoundedAcquisitionWorkflow

**Camera / White Balance**
- Fix camera_awb calibration lifecycle and gain handling
- Fix Camera Control showing stale WB values from cached config
- Fix per-angle WB not loading calibrated exposures
- Fix WB output path and singleton socket client usage
- Fix Continuous WB mode not applying in Camera Control

**Stitching**
- Fix birefringence stitching with directory isolation to prevent angle cross-matching
- Fix WB Comparison stitching (tile paths, Z position, monitoring, bounds nesting)

**Stage Movement**
- Fix stage movement to match MicroManager conventions
- Fix joystick direction in sample movement mode
- Fix joystick command flooding with rate limiting and latest-target-wins

**Annotations**
- CRITICAL: Fix annotation class filtering removing ALL annotations during alignment when no classes selected

**Live Viewer**
- Fix canvas expanding and frame poller race condition
- Fix camera mode changes failing when Live Viewer is streaming
- Fix Live Viewer image display sizing and display scale dropdown

**Other**
- Fix UI freeze when stage controls used during long operations
- Fix init-order NPE risk in ExistingImageAcquisitionController
- Fix birefringence optimization socket timeout during calibration
- Guard against setting BRIGHTFIELD_H_E on non-RGB images

### Removed
- "Apply & Go Snap Test" workflow and RAWSNAP command
- Standalone Stage Control menu item (integrated into Live Viewer)
- Legacy WB mode preferences: jaiWhiteBalanceMode, PPMPerAngleWBEnabled
- Gain mode radio buttons from Camera Control (gain always unified)

### Breaking Changes
- **CONFIG handshake required**: Extension now sends CONFIG command on connection; requires microscope-command-server v1.1.0+
- **WB mode parameter changed**: Old `--white-balance` and `--wb-per-angle` boolean flags replaced with `--wb-mode` enum (camera_awb|simple|per_angle|off)
- **Unified gain model**: GainsResult and CameraModeResult records changed; `setGains()` with count=3 now means [unified, analog_red, analog_blue]

## [0.2.0] - 2026-01-12

First release of a mostly working version, with code dedicated to working on one particular polychromatic polarization microscope. Expect significant changes as we migrate to more instruments.

## Guidelines for Release Notes

When creating a new release, add a section above with the version number and date:

```markdown
## [1.0.0] - 2026-01-XX

### Added
- Feature descriptions

### Changed
- Modification descriptions

### Fixed
- Bug fix descriptions

### Removed
- Removed feature descriptions
```

### Categories

- **Added** - New features
- **Changed** - Changes in existing functionality
- **Deprecated** - Soon-to-be removed features
- **Removed** - Now removed features
- **Fixed** - Bug fixes
- **Security** - Security improvements
