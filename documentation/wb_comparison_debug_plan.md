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

## Remaining Questions (for next test run)

1. **Are backgrounds expected to have WB applied?**
   With camera_awb, AWB "Once" is triggered then mode set to "Off" before background capture.
   If the camera doesn't persist AWB gains when mode is Off, backgrounds will always be unbalanced.
   The new fallback (computing WB from backgrounds) handles this for the acquisition tiles,
   but the background images themselves will still show the raw camera color cast.

2. **Autofocus at tissue position**: Why did AF fail? Possibly the 90 deg / 0.68ms exposure
   is too dim for focus scoring. May need to verify tissue position or adjust AF angle/exposure.

3. **Stitching**: Not yet tested. The scan type warning fix may have been blocking it.

4. **Gain_AnalogGreen warning**: Non-fatal. Server logs
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
