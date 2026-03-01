# WB Comparison Workflow - Debug Status

## What Works
- Dialog with Z position persistence (blank + tissue XYZ saved across sessions)
- Tile grid generation (20 tiles, 4x5 grid)
- WB calibration itself (verification images show R/G/B ~177 -- near-perfect balance)
- Background acquisition command sends and returns exposures
- Path construction now matches Python server expectation: `{projects}/{sample}/{scan_type}/{region}/`
- Menu item, workflow routing, mode iteration with continue-on-failure
- Manual focus auto-skip during acquisition (prevents hang)

## Resolved Issues

### Issue 1: Background images have no white balance applied (FIXED)
All three modes produced identical backgrounds with strong red cast (R>>G>>B).
Verification images showed perfect WB, proving calibration works but backgrounds don't use it.

**Root cause**: `workflow.py` loaded background images and computed per-angle WB coefficients
(e.g., [-7 deg: [0.65, 1.0, 2.14]]) but immediately *discarded* them via `_` unpacking.
It then fell back to `get_angles_wb_from_settings()` which looked for `settings['white_balance']['ppm']`
in the YAML config -- not present -- and returned neutral [1,1,1] for all angles.

**Fix**: Capture `background_wb_coeffs` from `load_background_images()` and use them as fallback
when config has no WB settings. Changed in `microscope_command_server/acquisition/workflow.py`.

### Issue 2: Acquisition hung on manual focus (FIXED)
Autofocus ran at 90 deg with 0.68ms exposure (very dim), failed with "No focus gradient detected",
and server requested manual focus. `WBComparisonWorkflow` passed `null` for the manual focus
callback to `monitorAcquisition()`, so the server blocked waiting for acknowledgment indefinitely
(67 minutes until QuPath was closed).

**Fix**: Added auto-skip callback that sends `SKIPAF` when server requests manual focus.
Changed in `WBComparisonWorkflow.java`.

### Issue 3: Only camera_awb attempted acquisition (EXPLAINED)
camera_awb hung on manual focus, so the workflow never progressed to simple/per_angle modes.
With the manual focus fix, all three modes should now complete.

### Issue 4: Scan type "ppm_40x" warning (FIXED)
`get_modality_from_scan_type()` expected 3-part format "ppm_40x_1" but WB Comparison sends
2-part "ppm_40x". Function returned correct value but logged a spurious warning.

**Fix**: Added handling for 2-part scan types in `ppm_library/imaging/background.py`.

## Resolved Issues (2026-03-01 AWB Fixes)

### Issue 5: Camera AWB not applying color corrections (FIXED)
AWB was running at the client's default exposure (10ms) which fully saturated the sensor at
uncrossed/90-deg. The camera couldn't determine color ratios from clipped pixels. Also, only
3 frames were delivered during AWB equilibration (insufficient for convergence).

**Fix:** `run_auto_white_balance()` now uses a safe calibration exposure (0.5ms default) with
38 Hz frame rate and 3-second equilibration with active buffer draining (~100+ frames).
AWB adjusts the camera's internal Temperature property, not the analog gain registers.

### Issue 6: AWB drain loop overshoot causing error 11018 (FIXED)
The drain loop ran for 11+ seconds due to no time check in the inner loop. During this time,
subsequent WB calibration commands arrived and collided with the still-active streaming.

**Fix:** Added `time.time() < end_time` check in the inner drain loop.

### Issue 7: Java socket timeout too short for AWB (FIXED)
Default 5-second read timeout caused Java to time out during AWB (5-6 seconds), sending
subsequent commands while streaming was still active.

**Fix:** `setWhiteBalanceMode(2)` now uses 15-second timeout.

### Issue 8: Background exposures starting too high (FIXED)
Client default 10ms needed ~20 proportional-reduction iterations to reach 0.5ms for 90-deg.
Fully saturated images (median=255) gave 13.5% reduction per step.

**Fix:** Two-part: (1) Load saved exposures from prior run as starting points, (2) Halve
exposure when fully clipped (median >= 254) instead of proportional reduction.

## Remaining Questions (for next test run)

1. **Stitching**: Not yet tested. The scan type warning fix may have been blocking it.

2. **Gain_AnalogGreen warning**: Non-fatal. Server logs
   `"Could not configure camera AWB mode: Failed to set property 'Gain_AnalogGreen' to '1.0'"`
   but `disable_individual_gain()` succeeded. Likely a camera firmware side-effect of
   transitioning from individual to unified gain mode. No code path explicitly sets
   Gain_AnalogGreen to 1.0.

## Key Files (Java side)
- `WBComparisonWorkflow.java` - Main orchestration (processMode loop)
- `WBComparisonDialog.java` - Config dialog with Z position support
- `MicroscopeSocketClient.java` - startBackgroundAcquisition(), startAcquisition(), monitorAcquisition()
- `AcquisitionCommandBuilder.java` - Socket message construction

## Key Files (Python side)
- `microscope_command_server/acquisition/workflow.py` - Acquisition workflow, WB coefficient loading
- `microscope_command_server/server/qp_server.py` - Server command routing
- `ppm_library/imaging/background.py` - Background loading, WB coefficient computation, modality parsing

## WB Coefficient Flow (post-fix)
```
1. Background capture (BGACQUIRE)
   - Camera captures raw backgrounds at each angle
   - Backgrounds saved as .tif files

2. Acquisition (ACQUIRE) - background loading
   - load_background_images() reads backgrounds and computes WB coefficients
     per angle from mean R/G/B channel values (normalize to green channel)
   - Returns: (background_images, scaling_factors, wb_coefficients)

3. Acquisition - WB settings resolution
   - Try: get_angles_wb_from_settings(ppm_settings) from YAML config
   - Fallback: if config returns all neutral [1,1,1], use background_wb_coeffs
   - Result: per-angle [R_mult, G_mult, B_mult] applied during tile capture
```

## Commits So Far
- `f0f3f6c` - Initial WB Comparison implementation
- `a6647fa` - Fix double-nested bounds dir and YAML angle key
- `408b8b9` - Use monitorAcquisition() for retry on EOFException
- `28abdd9` - Add Z position support, fix missing stage move, fresh connection before acquire
- `c716892` - Fix tile path missing scan_type subdirectory
- (pending) - Auto-skip manual focus, use background WB coefficients, handle 2-part scan types

### AWB Fix Commits (2026-03-01)
- `f8df128` (microscope_control) - AWB calibration exposure + consistent Off write
- `53af6aa` (microscope_control) - Fix drain loop overshooting with time check
- `e3c9777` (microscope_control) - AWB Temperature readback diagnostics
- `7c3cb72` (microscope_command_server) - Aggressive exposure halving for saturated backgrounds
- `4788117` (microscope_command_server) - Load saved exposures as starting points
- `3e412c1` (qupath-extension-qpsc) - 15-second socket timeout for AWB mode 2
