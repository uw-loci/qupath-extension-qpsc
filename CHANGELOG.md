# Changelog

All notable changes to the QPSC QuPath Extension will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

*No unreleased changes.*

## [0.5.0] - 2026-05-07

### Added

**SIFT auto-alignment**
- Auto-Align (SIFT) button in the 3-point Microscope Alignment workflow per-tile confirm step (was previously only available in single-tile refinement). Reuses the same dialog and Settings, minus the "save / skip / new alignment" buttons.
- Configurable bit-depth normalization (`PERCENTILE` / `MIN_MAX` / `BIT_SHIFT`) and CLAHE preprocessing for cross-modality matching (16-bit monochrome camera vs 8-bit H&E WSI). Defaults `PERCENTILE` 2/98 + CLAHE on with clipLimit 2.0 fix the OWS3 + H&E "fails near correct tile" failure mode.
- Wire-protocol flags `--mono-norm`, `--pct-low`, `--pct-high`, `--clahe`, `--clahe-clip` on SIFTAL.

**Live Viewer**
- "Always Auto-Scale" checkbox replaces the forced per-tile rescale; drag the Min/Max sliders to opt out of auto-scale.
- Camera Control tab restores last-used Exposure + Intensity on open.
- Focus buttons recover when `restartStreaming` fails (no more deadlocked spinner).

**Autofocus Editor**
- Test Streaming Autofocus button with live curve plots; chart axes auto-fit.
- Surfaced `valid_modalities` and `min_magnification` constraints from the focus manifest.

**Propagation Manager**
- Ground-truth source ROI auto-stamped from parent tile detections; "Stamp source ROI from tiles" retrofit button for older sub-acquisitions.
- Cross-scope back-prop swaps in the source-scope alignment so the xy_offset is interpreted in its own scope's stage frame.
- "Remove existing objects of copied classes" checkbox: when set, deletes existing objects on each target whose class is in the selected set (and unclassified objects when "Unclassified" is also selected) before adding the propagated copies. Use case: refining annotations on the source image and re-propagating without overlapping the old shapes. FORWARD removes per-sub right before each `propagateForward`; BACK removes once per group across all base siblings before any sub fans out (otherwise multiple subs back-prop'ing into the same base would each delete and re-add, dropping earlier subs' contributions).
- Comprehensive diagnostics for back-prop; multi-group UI; "Save & reload viewer" handling.
- Visible warning when a source-scope config is missing instead of silently producing a wrong xy_offset.

**Acquisition Wizard**
- Calibration status pills (white balance, backgrounds, alignment) auto-refresh after sub-workflows complete via explicit completion notifications -- no more closing+reopening to clear yellow warnings after running WB / Background Collection / Microscope Alignment / Save Transform. Replaces the earlier 3-second polling Timeline (which worked but was a constant 6-file disk read for the wizard's entire lifetime). `AcquisitionWizardDialog.notifyCalibrationChanged()` is the public hook; `WhiteBalanceWorkflow`, `BackgroundCollectionWorkflow`, and `MicroscopeAlignmentWorkflow` call it on success. Refresh All button stays as the user-driven backup path.
- Unified `ObjectiveSelector` component that auto-detects from MicroManager pixel size (priority: MM pixel size match -> last-used pref -> first config entry).

**White Balance**
- R/B analog gain ceiling spinner exposed in the WB dialog and plumbed to the server.
- Objective/detector combos seeded from live MicroManager pixel size when opening WB / Background dialogs.

### Fixed

**Flip handling (Step B refactor + 2026-05-07 follow-ups)**
- Step B (2026-05-04): flip source-of-truth moved from per-entry `FLIP_X`/`FLIP_Y` metadata to `TransformPreset.flipMacroX/Y` (per `(source_scanner, target_microscope)` preset) and the per-slide alignment JSON. `AlignmentHelper.checkForSlideAlignment` bakes the alignment-frame flip into the loaded transform so all downstream callers consume unflipped-base pixel coords.
- 2026-05-04: per-slide JSONs auto-registered at BoundingBox stitch import + saved by ExistingImageWorkflow refinement now record `flipMacroX/Y` truthfully (the legacy 5-arg overload wrote `null,null` and was source of an XY-mirror bug on PPM existing-image acquisitions).
- 2026-05-07 (this release): `(flipped X|Y|XY)` companion entries are still created on demand by `ImageFlipHelper.validateAndFlipIfNeeded` for the visual-UX of operator alignment (the unflipped base and the flipped live camera view disagree by a mirror on PPM and cannot be aligned by eye). The 2026-05-04 stub-out of this method was reverted and replaced with a real preset-driven implementation.
- 2026-05-07: `ManualAlignmentPath` now records `flipMacroX/Y` on the per-slide JSON from the active preset (was via `FlipResolver(null,null,null)` which post-Step-B always returned `(false, false)`, mis-stamping the JSON as "no flip" when the alignment was actually done in the flipped sibling's frame).
- 2026-05-07: `StageControlPanel.handleGoToCentroid` (Move to Centroid button) now loads via `loadSlideAlignmentWithFrame` and bakes the flip on the unflipped base entry, mirroring `AlignmentHelper.checkForSlideAlignment`. The legacy raw-load + manual-compensation block was producing XY-mirror motion on PPM.
- 2026-05-07: tile detections in single-tile refinement now overlay the (correctly flipped) annotations on the flipped sibling instead of landing at the XY-mirror -- caused by `state.annotations` being captured against the unflipped base at config-dialog return and never re-fetched after the flip switch. `processSlideSpecificAlignment` now clears `state.annotations` after `validateAndFlipIfNeeded` so `ensureAnnotationsExist` re-reads from the now-current hierarchy.
- 2026-05-09: `ImageFlipHelper.switchOpenEntry` always defers the entry switch via `Platform.runLater` instead of running synchronously when already on the FX thread. The synchronous path threw `IllegalStateException: showAndWait is not allowed during animation or layout processing` followed by an NPE because the open call chain hit `QuPathGUI.checkSaveChanges` -> `Dialogs.showAndWait`, and the caller arrived inside a JavaFX animation pulse (synchronous continuation of `ProjectHelper.setupProject`'s post-creation Timeline). Hit any new user on their first existing-image acquisition with a flip-required preset (e.g. PPM).
- 2026-05-07: forward-prop now resolves the alignment-frame flip from the sub's parent entry's `FLIP_X`/`FLIP_Y` metadata (priority: sub-parent metadata > per-slide JSON > active preset). The previous "load flip from per-slide JSON" path produced 0 propagated objects on projects whose JSON was written by the pre-`45ca489` `ManualAlignmentPath` -- those JSONs claimed `hasFlipFrame=true` while actually storing `(false, false)`, so no pre-flip ran and source pixels mapped through `baseToStage` in the wrong frame. Sub-parent metadata is set at import and was unaffected by the JSON-save bug, so it's a more reliable signal. When the JSON disagrees with the parent metadata a warning is logged recommending re-alignment.

**Stage Map**
- Macro overlay flip now resolves from the active `(source, target)` preset, not per-entry metadata.
- Recovers when polling fails instead of hiding the overlay; logs the real failure cause.
- Recovers from stale `acquisitionActive` after cancellation.

**Connection stability**
- User-triggered Reconnect clears the server-unresponsive latch.
- Unified server-unresponsive recovery path across acquisition + heartbeat.
- Cancel runs off the JavaFX thread; CANC read timeout bumped so cancellation doesn't time out under server load.

**Class-filtered alignment tiling**
- `MicroscopeAlignmentWorkflow` (standalone) now prefers `PersistentPreferences.getSelectedAnnotationClasses()` for tiling, with Tissue / valid-class fallback chain. Removed the silent "all annotations regardless of class" fallback that produced noisy / overlapping tile grids.
- Existing Image Workflow's single-tile refinement prompt now reports the count and class names of annotations being tiled.

**Other**
- Tile-handling preference (Delete / Zip / Keep) now actually runs after the Existing Image Workflow finishes stitching. The cleanup was previously only wired into `BoundedAcquisitionWorkflow`, so existing-image runs left their `tempTileDirectory` in place regardless of the preference. Both workflows now share `TileCleanupHelper.performCleanup`, and the Zip path only deletes originals if zipping succeeded. Background-correction tiles live in a separate config-specified folder and are unaffected.
- Live Viewer focus range dropdown options 6-40um (was truncated 1-20um).
- Sweep Focus rejects boundary peaks and requires 2 flanking samples for parabolic interpolation; suppresses early-stop on flat metric / peak-at-start.
- Test Standard/Adaptive Autofocus stops live view before running (no more "AF metric is whatever the camera last streamed" artifacts).
- Multichannel merged filenames follow the standard naming scheme.
- Camera tab Apply Profile rebuilds the panel and restores saved exposure/illumination prefs to hardware so UI matches.

### Documentation

- New `documentation/developer/COORDINATE_TRANSFORMS.md` is now authoritative for the flip pipeline.
- `documentation/developer/SOCKET_PROTOCOL.md` includes a full SIFTAL flag reference.
- `documentation/PREFERENCES.md` covers all SIFT bit-depth normalization knobs.
- `documentation/TROUBLESHOOTING.md` adds entries for SIFT failure modes (close-but-failing on PPM/H&E + stage-too-far-from-tile).
- `documentation/tools/microscope-alignment.md` and `documentation/tools/existing-image-acquisition.md` updated for SIFT-in-3-point alignment + class-filtered tiling.

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
