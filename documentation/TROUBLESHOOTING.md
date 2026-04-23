# QPSC Troubleshooting Guide & FAQ

**Last Updated:** March 16, 2026
**Target Audience:** Pathologists, researchers, and microscopy users
**Skill Level:** All levels (clear explanations provided)

---

## Table of Contents

1. [Quick Diagnostic Checklist](#quick-diagnostic-checklist)
2. [Getting Started Troubleshooting](#getting-started-troubleshooting)
3. [FAQ by Category](#faq-by-category)
4. [Error Messages Reference](#error-messages-reference)
5. [Component-by-Component Troubleshooting](#component-by-component-troubleshooting)
6. [Configuration Validation](#configuration-validation)
7. [Log Files and Diagnostics](#log-files-and-diagnostics)

---

## Quick Diagnostic Checklist

**Before starting any acquisition, verify ALL of these are working:**

- [ ] **Micro-Manager is running** and shows live camera view
- [ ] **Python server is running** (command window should be open with "Server started" message)
- [ ] **QuPath is open** with QPSC extension loaded (check Extensions menu)
- [ ] **Microscope hardware is initialized** in Micro-Manager (stage, camera, rotation stage if using PPM, laser/PMT/scan engine if using SHG)
- [ ] **Configuration files exist** in the correct location (config_<name>.yml, resources_LOCI.yml)
- [ ] **Project folder exists** or you have permission to create one

**Quick Test:** Use Extensions > QPSC > Utilities > Communication Settings to verify communication between all components.

---

## Getting Started Troubleshooting

### Where to Look First

**1. Check the error dialog** - QuPath will show clear error messages with recommended actions

**2. Check QuPath logs** - Most helpful information:
   - Location: `<QuPath-Directory>/logs/qpsc/qpsc-acquisition.log`
   - Look for ERROR and WARN messages near the time of the problem

**3. Check Python server console** - If you see errors there, the server crashed or has issues

**4. Check Micro-Manager** - If Micro-Manager is frozen or showing errors, restart it first

### Most Common Issues (90% of problems)

1. **Python server not running** - Start it before using QPSC
2. **Micro-Manager not started** - Launch Micro-Manager first
3. **Hardware not initialized** - Load hardware configuration in Micro-Manager
4. **Wrong configuration file** - Check file paths in microscope config
5. **Missing pixel calibration** - Image metadata lacks pixel size information

---

## FAQ by Category

### Installation & Setup

#### Q: Where do I install the QPSC extension?

**A:** Copy `qupath-extension-qpsc-0.3.0-all.jar` to your QuPath extensions folder:
- **Windows:** `C:\Users\<YourName>\QuPath\extensions\`
- **Mac:** `~/Library/Application Support/QuPath/extensions/`
- **Linux:** `~/.qupath/extensions/`

Then restart QuPath.

#### Q: How do I know if the extension loaded correctly?

**A:** Check the Extensions menu - you should see "QPSC" with options like "Bounded Acquisition", "Acquire from Existing Image", etc. If not:
1. Check the extensions folder contains the .jar file
2. Look at QuPath console for error messages on startup
3. Verify you're using QuPath 0.6.0-rc4 or newer

#### Q: The extension won't load - says "dependency error"

**A:** You need both extensions:
1. `qupath-extension-tiles-to-pyramid-0.1.0-all.jar` (install this FIRST)
2. `qupath-extension-qpsc-0.3.0-all.jar` (install after)

Both must be in the extensions folder. The tiles-to-pyramid extension handles image stitching.

### Setup Wizard

#### Q: How do I run the Setup Wizard?

**A:** The Setup Wizard appears automatically as the first menu item ("Setup Wizard (Start Here)...") when no valid microscope configuration is found. Once configured, it moves to Extensions > QP Scope > Utilities > Setup Wizard.

#### Q: The Setup Wizard created config files but QPSC still says "no valid configuration"

**A:** Restart QuPath after the wizard completes. The config file preference is set automatically, but some components require a restart to reload.

#### Q: Can I re-run the Setup Wizard to modify my configuration?

**A:** Yes. After initial setup, find it under Extensions > QP Scope > Utilities > Setup Wizard. It will create new config files -- back up your existing ones first if you want to preserve them.

#### Q: The wizard doesn't show my hardware in the catalog

**A:** The catalog contains known LOCI hardware. Use "Add Custom" to manually enter your hardware IDs, names, and specifications. The LOCI IDs you assign should match your Micro-Manager device names.

### Configuration Files

#### Q: Where are the configuration files located?

**A:** Default location depends on your installation. Common locations:
- Windows: `C:\Users\<YourName>\QPSC\configurations\`
- Linux/Mac: `~/QPSC/configurations/`

Files needed:
- `config_ppm.yml` - Main microscope configuration
- `resources_LOCI.yml` - Hardware component definitions
- `autofocus_PPM.yml` - Autofocus settings (optional, can be in YAML or config)

#### Q: How do I know which config file QuPath is using?

**A:** Check the QuPath preferences:
1. Edit > Preferences > QPSC
2. Look at "Microscope configuration file" field
3. This shows the currently loaded config file path

If the field is empty or shows a missing file, use "Browse" to select your config file.

#### Q: I changed the config file but nothing happened

**A:** Configuration files are loaded once at startup. To apply changes:
1. Save your changes to the YAML file
2. Restart QuPath completely
3. Verify changes in Edit > Preferences > QPSC

Some settings (like exposure times for backgrounds) are saved separately and need to be updated via the background collection workflow.

### Python Server

#### Q: How do I start the Python server?

**A:** Open a command window and run:
```bash
cd /path/to/microscope_command_server
python -m microscope_command_server.server
```

You should see:
```
Server started on localhost:5000
Waiting for connections...
```

Keep this window open while using QPSC.

#### Q: Server won't start - says "Address already in use"

**A:** Another program is using port 5000. Two options:

**Option 1 (Recommended):** Stop the other server
```bash
# Find what's using the port (Windows)
netstat -ano | findstr :5000

# Kill the process (use PID from above)
taskkill /PID <process_id> /F
```

**Option 2:** Use a different port
- Start the server with a different port: `python -m microscope_command_server.server --port 5001`
- Update QuPath preferences to match the new port

#### Q: Server starts but QuPath can't connect

**A:** Check these:
1. **Firewall:** Windows Firewall might be blocking Python - allow it
2. **Port number:** Verify config file has same port as server (default: 5000)
3. **Server running:** Make sure server console is still open and not showing errors
4. **Test connection:** Use Extensions > QPSC > Utilities > Communication Settings

#### Q: "Not connected to microscope server" but Live Viewer works fine

**A:** QPSC uses two independent socket connections to the Python server:

- **Primary socket:** Acquisition commands, configuration, and other long-running operations
- **Auxiliary socket:** Live Viewer frames, stage control, and position polling

The Live Viewer and stage control operate exclusively through the auxiliary socket, which auto-connects when first used. Workflow dialogs (Z-Stack, Acquisition, etc.) check overall connection status, and the primary socket auto-reconnects when needed.

If you see this error despite Live Viewer working:
1. Open **Communication Settings** (Extensions > QP Scope > Utilities)
2. Check the status line -- it shows both socket states
3. Click **Connect Now** to reconnect the primary socket (the auxiliary stays running)
4. If the status shows "Auxiliary only," the primary will reconnect automatically the next time a command is sent

**Technical detail:** The Connection dialog shows four possible states:
- **Green** "Connected (primary + auxiliary)" -- fully operational
- **Green** "Primary connected" -- auxiliary will connect on first Live Viewer use
- **Orange** "Auxiliary only" -- Live Viewer works; primary reconnects on demand
- **Gray** "Disconnected" -- no connection to server

#### Q: Server crashes during acquisition with "UnicodeEncodeError"

**A:** This is a Windows encoding issue. The fix is already in the code, but if you see it:
- Your Python code has non-ASCII characters (like arrows, degrees symbols)
- Update to the latest version of microscope-command-server
- If editing code yourself, use only ASCII characters in logging statements

### Micro-Manager Connection

#### Q: Error says "Hardware not initialized" or "MicroManager not running"

**A:** Start Micro-Manager first:
1. Launch Micro-Manager application
2. Load your hardware configuration (.cfg file)
3. Verify you can see live camera feed
4. Move the stage manually to confirm it responds
5. Now start QuPath and QPSC will connect

#### Q: Micro-Manager crashes when QPSC tries to acquire

**A:** This usually means:
1. **Stage limits exceeded:** Check if your acquisition region is within stage travel range
2. **Hardware conflict:** Close any other software controlling the microscope
3. **Micro-Manager needs restart:** It sometimes gets stuck - restart helps
4. **Not enough memory:** Check if Micro-Manager is set to circular buffer mode with reasonable size

#### Q: Stage doesn't move even though everything else works

**A:** Check in this order:
1. Can you move stage manually in Micro-Manager? If NO - hardware problem
2. Are stage limits set correctly in config? If NO - see configuration validation section
3. Is stage control enabled in Micro-Manager device properties? If NO - enable it
4. Look at Python server console for error messages about stage movement

### Coordinate System & Alignment

#### Q: My acquired images don't line up with the QuPath annotations

**A:** This is a coordinate alignment issue. Check:

1. **Image flip settings:** In the sample setup dialog, make sure "Invert X" / "Invert Y" match your microscope orientation
2. **Pixel size calibration:** Verify your image has correct pixel size metadata (check image properties in QuPath)
3. **Stage coordinate system:** Some microscopes have inverted stage coordinates - adjust flip settings to compensate

**To fix:**
- Use Extensions > QPSC > Microscope Alignment to calibrate coordinate transformation
- Mark reference points in QuPath and on microscope stage
- System will calculate the correct transformation automatically

#### Q: What does "Inverted X stage" / "Inverted Y stage" mean?

**A:** These describe the hardware wiring polarity of the stage — whether a positive MicroManager stage command moves the physical stage in the lab's positive or negative direction on each axis.

- **Inverted X stage = ON:** MM `+X` command moves the stage in lab `-X` (i.e. the stage encoder / device adapter is wired backwards on the X axis).
- **Inverted Y stage = ON:** Same for the Y axis.

These are NOT the same as macro image flip (which is about how the loaded macro image looks on screen) or camera orientation (which is about how the live image is oriented).

**To diagnose:** run this in a MicroManager script:

```java
core.setXYPosition(core.getXPosition() + 100, core.getYPosition());
```

Observe which direction the physical stage actually moves. If it moves in the lab `-X` direction (from the operator's neutral viewpoint), enable "Inverted X stage". Same procedure for Y.

#### Q: Arrow buttons, joystick, or double-click-to-center move the wrong way

**A:** This is the "stage-vs-image sign math" problem. Starting with commit `e3cb133` (2026-04-09), all three of these — plus the stitcher's tile placement — go through a single `StageImageTransform` that composes two independent settings:

1. **Stage polarity** (Inverted X / Y stage) — hardware wiring
2. **Camera orientation** — how the camera mounts / optical path flips the image

**Diagnosis procedure:**

1. Open the QuPath log (Help → Show Log) and find the startup line:
   ```
   Live-view coordinate transform at startup:
     StageImageTransform[stage=..., camera=...]
       Arrow right (pan right) -> mmDelta (..., ...)
       Arrow up    (pan up)    -> mmDelta (..., ...)
       ...
   ```
   This tells you exactly what the extension thinks your scope is configured as.

2. In the Live Viewer, test the four gestures:
   - Arrow right button (with "Sample Movement" checked)
   - Arrow up button
   - Joystick drag upward
   - Double-click on a feature that's currently visible but off-centre

3. Each gesture should produce the "obvious" visual direction. If they don't:
   - **Only Live Viewer X gesture is wrong** → toggle "Inverted X stage" in preferences
   - **Only Live Viewer Y gesture is wrong** → toggle "Inverted Y stage"
   - **Both Live Viewer axes are wrong** → toggle both
   - **Live Viewer gestures all work, BUT the stitched output has wrong corners** → change "Camera orientation", see below

4. Acquire a small 2×2 bounding box test. Place it on a recognizable feature, run the acquisition, and look at the stitched output. Compare corner-by-corner against what you see in the live view at each stage position. **Do this step even if the Live Viewer gestures all look correct** — it's possible for gestures to look right while stitching is wrong (OWS3 showed exactly this during the 2026-04-09 refactor test).

5. If the stitched output is wrong, the fix is in `Camera orientation`:
   - **Only stitched X mirrored** → set `Camera orientation` to `FLIP_H`
   - **Only stitched Y mirrored** → set `Camera orientation` to `FLIP_V`
   - **Both axes mirrored (180° rotation)** → set `Camera orientation` to `ROT_180`

**CRITICAL misconception to avoid:** Setting `Camera orientation` to `FLIP_H` does **not** change what the Live Viewer shows. It does not flip your camera image on screen. It only tells the software that your scope's optical path already produces a horizontally-flipped relationship between stage coordinates and displayed pixels — and the software should do sign math accordingly. If your live image looks correct but your stitched output has mirrored corners, that's a legitimate reason to pick `FLIP_H` even though "nothing looks flipped" in the live view.

**Real example from OWS3 (Nikon Ti2 + Hamamatsu Orca):** Live Viewer arrows, joystick, and double-click all worked correctly with `Inverted Y stage` = ON and `Camera orientation` = NORMAL. But a 2×2 stitched acquisition showed tiles with their X corners mirrored. The correct setting turned out to be `Camera orientation` = `FLIP_H`, because the Ti2 body's optical path produces a horizontally-flipped image. Nobody physically modified the camera; the flip is inherent to the scope. Setting `FLIP_H` did not alter the Live Viewer image at all — but it fixed the stitched output.

**Axis-aligned vs rotation values:**
- `NORMAL`, `FLIP_H`, `FLIP_V`, `ROT_180` are the four axis-aligned values. These work everywhere, including the stitcher.
- `ROT_90_CW`, `ROT_90_CCW`, `TRANSPOSE`, `ANTI_TRANSPOSE` are the rotation/transpose cases for scopes whose camera sensor is physically rotated 90°. The Live Viewer gestures work with them, but the stitcher does NOT fully support them — stitched output will be mis-oriented and a warning will be logged. If you actually have a rotated camera, file an issue and request full rotation support.

**Design doc:** `claude-reports/2026-04-09_stage-image-transform-refactor.md`

#### Q: Coordinate transformation fails - "No valid transformation found"

**A:** The alignment calibration needs more data points:
1. You need at least 3 reference points for reliable transformation
2. Points should be spread across the slide (not clustered)
3. Points must be on the same image (can't mix images)
4. Reference points must have both QuPath and stage coordinates recorded

#### Q: Go to Centroid button is disabled on a BoundingBox acquisition output

**A:** Every BoundingBox acquisition registers its own stage alignment automatically at stitch-import time, so the button should enable as soon as the new image is opened. If it does not, check in this order:

1. **Open the image first.** The button state is re-evaluated on image change. Click the stitched image in the project tab.
2. **Session log** (QuPath menu: View → Show log). Look for a line beginning `Auto-registered stage alignment for '<file>' from BoundingBox metadata`. If that line is missing, the registration never ran -- most likely because the stitched file was not readable by the image server at import time (Windows file lock, partial write, or merged file was never produced). Check the lines above it for a `ChannelMerger` / `PyramidImageWriter` warning.
3. **Alignment file on disk.** Look in `{project}/alignmentFiles/` for a file named `{your-image}_alignment.json`. If it exists, the registration succeeded and the button should be enabled -- restart QuPath to force `StageControlPanel` to re-initialize. If it does not exist, re-run the BoundingBox acquisition after fixing any merge warnings.
4. **Wrong lookup key.** The alignment is keyed by the on-disk file name returned by `QPProjectFunctions.getActualImageFileName(imageData)`. If you renamed the image in the project browser, the key no longer matches and the button will show `No alignment for: ...` -- either revert the rename or run Microscope Alignment to register a new one under the current name.

For multi-channel IF / BF+IF acquisitions where the merge failed and each per-channel file was imported individually, every channel gets its own alignment. Opening any one of them should enable the button.

### Acquisition Problems

#### Q: Acquisition starts but no images appear

**A:** Check Python server console for errors. Common causes:

1. **Camera not triggering:** Check Micro-Manager can acquire images manually
2. **Wrong save location:** Python server might be saving to unexpected folder - check console messages
3. **Permissions error:** Python server can't write to the output directory
4. **Out of disk space:** Check available space on target drive

#### Q: First few tiles are black or very dark

**A:** Camera needs time to stabilize after starting acquisition:
1. Increase "settling time" in microscope config (default: 100ms, try 500ms)
2. Check camera exposure time is reasonable for your sample
3. Verify illumination is on and stable

#### Q: Images are overexposed (too bright/white)

**A:** Exposure time too long:
1. Reduce exposure time in angle selection dialog
2. For background collection, use lower exposures
3. Check if adaptive exposure is enabled (it should auto-adjust, but initial value might be wrong)

#### Q: Images are underexposed (too dark)

**A:** Exposure time too short or illumination problem:
1. Increase exposure time in angle selection dialog
2. Check microscope illumination is on at correct intensity
3. Verify camera gain settings in Micro-Manager

#### Q: Autofocus isn't working - images out of focus

**A:** Several possibilities:

**Tissue Detection Issue:**
- Autofocus might think there's no tissue at that position
- Check logs for "Tissue stats: texture=X" messages
- If texture values are low (< 0.005), tissue detection thresholds might need adjustment
- Edit `autofocus_PPM.yml` and lower `texture_threshold` value

**Autofocus Hardware:**
- Verify Z-stage works in Micro-Manager
- Check autofocus search range is reasonable (default 30um)
- Try manual focus workflow to verify Z-control works

**Configuration:**
- Check `n_steps` (number of focus steps) - should be 15-25 for most applications
- Verify `search_range_um` covers your sample thickness variation

#### Q: Autofocus is too slow

**A:** Reduce the number of autofocus positions:
1. Edit `autofocus_PPM.yml`
2. Increase `n_tiles` value (e.g., from 2 to 5)
3. `n_tiles=5` means autofocus every 5 tiles instead of every 2 tiles
4. Faster acquisition but less frequent focus updates

### Stitching Issues

#### Q: Stitching takes forever - QuPath freezes

**A:** This is expected behavior - stitching can take several minutes for large images:

1. **Don't click anything in QuPath** during stitching - this can cause crashes
2. A blocking dialog appears to prevent accidental interference
3. You can dismiss the dialog "at your own risk" but this may crash QuPath
4. Just wait - stitching time depends on number of tiles (2-10 minutes is normal)

**Stitching time guide:**
- 10 tiles: ~30 seconds
- 50 tiles: ~2 minutes
- 150 tiles: ~5-8 minutes
- 500+ tiles: ~10-20 minutes

#### Q: Stitched image has visible tile boundaries

**A:** This is normal for zero-overlap acquisitions:
1. Add overlap percentage in acquisition settings (try 10%)
2. Overlap helps stitching algorithm blend tiles smoothly
3. More overlap = better stitching but longer acquisition time

#### Q: Stitching fails - "TileConfiguration.txt not found"

**A:** The tile coordinate file is missing:
1. Check the temp tiles folder still exists (QuPath might have cleaned it up early)
2. Look for Python server errors during acquisition (acquisition might have failed silently)
3. Verify Python server completed acquisition successfully before stitching started

#### Q: Stitching failed but my tiles were acquired - how do I re-stitch?

**A:** This is fully recoverable. Your tiles and TileConfiguration.txt files are preserved in the TempTiles folder after a stitching failure.

**Recommended method - QPSC Re-stitch utility (adds images to project):**

1. Open the QuPath project where you want the stitched images
2. Go to **Extensions > QPSC > Utilities > Re-stitch Tiles**
3. Browse to your tile directory (the folder containing angle subdirectories or TileConfiguration.txt)
4. Verify the pixel size (auto-populated from your microscope config)
5. Set compression and output format
6. For **Matching String**: use `"."` to stitch all subdirectories, or a specific angle like `"0.0"`
7. Click **Stitch & Import**

The stitched images will be created in a SlideImages folder and automatically imported into your project.

**Alternative method - standalone stitching (file only, no project import):**

If you just need the stitched file without project integration:
1. Go to **Extensions > Basic Stitching > Stitch Images**
2. Browse to the tile directory and configure parameters
3. After stitching, manually add the file to your project via **File > Open** or drag-and-drop

**Tile directory structure for reference:**
```
TempTiles/
  annotationName/           (e.g., "Tissue_12345_67890" or "bounds")
    0.0/                    (angle subdirectory - PPM only)
      tile_001_001.tif
      tile_001_002.tif
      ...
      TileConfiguration.txt
    5.0/
      ...
    90.0/
      ...
```

**If you see `_temp_` folders (e.g., `_temp_90_0`):**

During multi-angle stitching, each angle is temporarily isolated into a `_temp_<angle>` directory. If stitching crashes before cleanup completes, tiles for that angle get stuck there:
```
annotationName/
  _temp_90_0/              <-- angle 90.0 stuck here after crash
    90.0/
      tile_001_001.tif
      TileConfiguration.txt
  0.0/                     <-- other angles may be fine
    ...
```

To recover stuck angles:
- **Option A (recommended):** Point the Re-stitch utility at the `_temp_90_0` folder and use matching string `90.0` (or `.`)
- **Option B:** Manually move the angle folder (e.g., `90.0/`) back up to the annotation directory, then delete the empty `_temp_*` folder. This restores the normal layout for any stitching approach.

Only the angle that was being processed when stitching failed will be stuck -- angles that completed before the crash were already restored to their normal location.

**Tips:**
- If the original stitching failed due to memory, try closing other images first or increasing QuPath memory (Edit > Preferences > General)
- For very large acquisitions (1000+ tiles), stitching may take 5-15 minutes -- do not interact with QuPath during stitching
- The tiles-to-pyramid extension must be installed (it should be, as QPSC depends on it)

#### Q: Stitched image is in wrong location in QuPath

**A:** Pixel size calibration issue:
1. Check if the original image has pixel size metadata
2. Verify microscope config has correct `pixel_size_microns` value
3. For annotation-based acquisitions, pixel size MUST be set - check logs for warnings

### PPM-Specific Issues

#### Q: PPM angle selection dialog freezes when clicking OK

**A:** This was a bug that's been fixed. Make sure you have the latest version. If still happening:
1. Update to latest qupath-extension-qpsc
2. Check QuPath logs for "JavaFX Application Thread" errors
3. This shouldn't happen anymore, but if it does, force-quit QuPath and report the bug

#### Q: Warning says "exposure settings don't match background"

**A:** This is informational, not an error:
- You selected different exposure times than what's in your saved background images
- You can proceed anyway (background correction won't be applied)
- Or collect new background images with matching exposures

To fix permanently:
1. Run Extensions > QPSC > Utilities > Background Collection
2. Use the same angles and exposures you plan to use for acquisitions
3. System will use these backgrounds automatically in future acquisitions

#### Q: Some tiles in stitched image are very bright (saturated)

**A:** This was a rotation stage bug that's been fixed, but if you see it:

**Cause:** Wrong rotation angle during acquisition (likely 90 deg instead of intended angle)

**Fix:**
1. Update smart-wsi-scanner to latest version (includes rotation fixes)
2. Check Python server console during acquisition for "Angle sequence issue" warnings
3. If you see these warnings, the rotation stage is having trouble - may need hardware reset

**Workaround:** Restart Micro-Manager and Python server between acquisitions

#### Q: Birefringence images show tile-to-tile brightness variations

**A:** This was a normalization bug that's been fixed. Make sure you have latest version:
- Update smart-wsi-scanner to latest version
- Old version normalized each tile separately causing brightness mismatch
- New version uses consistent scaling across entire mosaic

### PPM Reference Slide Calibration

#### Q: Calibration fails with "no spokes detected"

**A:** The detection thresholds may not match your camera output. Check the debug mask image in the failure dialog:
- **All BLACK mask:** Thresholds are too high. Lower saturation threshold (try 0.05) and/or value threshold (try 0.05).
- **All WHITE mask:** Thresholds are too low. Raise saturation threshold (try 0.2) and/or value threshold (try 0.2).
- **Partial mask but wrong shapes:** Slide may not be properly positioned or focused.

Use the "Open Folder" button in the failure dialog to inspect the saved debug images.

#### Q: Detection works on sample data but fails on live camera images

**A:** Camera output characteristics may differ from test data:
1. Check camera exposure -- overexposed or underexposed images produce poor hue detection
2. Verify you are using a low polarizer angle (7, 0, or -7 degrees) for best color saturation
3. Camera white balance or gain settings may shift hue values
4. Try saving the camera image and running detection offline to isolate the issue

#### Q: Where are calibration files saved?

**A:** Files are saved to: `{calibration_folder}/ppm/{name}_*`

Default calibration folder is set in Edit > Preferences > QPSC under "Default calibration folder". The default location is a `ppm_reference_slide` subfolder under your microscope configuration directory.

Output files:
- `{name}_image.tif` -- Acquired calibration image
- `{name}.npz` -- Calibration data for PPM analysis
- `{name}_plot.png` -- Visual verification plot
- `{name}_mask.png` -- Debug segmentation mask (on failure)

#### Q: R-squared value is low (below 0.95)

**A:** A low R-squared indicates poor calibration fit:
1. Ensure the slide is flat and properly focused
2. Try a different polarizer angle
3. Check for dust, scratches, or damage on the calibration slide
4. Verify the expected spoke count matches your slide
5. Check the calibration plot for outlier points

### SHG / Laser Scanning Issues

#### Q: Live Viewer shows a completely black image with the scan engine

**A:** Check these in order:
1. **PMT is enabled** -- verify in Micro-Manager that the PMT controller (e.g., DCC100) is powered on and the gain is set above 0%
2. **Pockels cell voltage is above 0** -- a Pockels cell at 0V blocks all laser light
3. **Laser shutter is open** -- check both the physical shutter and any digital IO shutter state
4. **Laser is on and at the correct wavelength** -- verify in Micro-Manager
5. **Correct camera is selected** -- the scan engine (e.g., OSc-LSM) must be the active camera, not the brightfield camera

#### Q: SHG image is very noisy or has hot pixels

**A:** This is typically a signal-to-noise issue:
- **Increase averaging** -- set averaging to 2-4 frames in the acquisition profile
- **Reduce scan speed** -- lower the pixel rate (longer dwell time per pixel improves SNR)
- **Reduce PMT gain** if the image is saturated; increase if too dim
- **Check for ambient light** -- PMTs are extremely sensitive; room lights, monitor screens, or indicator LEDs can contribute noise

#### Q: Image resolution or pixel size seems wrong

**A:** For laser scanning microscopes, pixel size depends on scan resolution:
- Pixel size = `base_pixel_size_um * 256 / current_resolution`
- At 256px: base pixel size (e.g., 0.509 um)
- At 512px: half the base (e.g., 0.255 um)
- At 1024px: quarter of base (e.g., 0.127 um)
- Verify the `LSM-Resolution` property in Micro-Manager matches your expectation
- Check that the zoom factor matches `zoom.default` in your config

#### Q: Autofocus fails on SHG tissue

**A:** SHG signal is often sparser than brightfield -- not all tissue generates second harmonic:
- Lower `texture_threshold` (try 0.003-0.005)
- Lower `tissue_area_threshold` (try 0.05)
- The `normalized_variance` and `laplacian_variance` metrics both work well for SHG
- If the sample has very sparse SHG signal, consider using `n_tiles=1` (autofocus every tile)

#### Q: PMT overload warning

**A:** The PMT has been exposed to excessive light. This is a safety concern:
1. **Immediately reduce gain** or disable the PMT
2. Check for stray light sources (room lights, brightfield lamp still on)
3. Verify the modality switching sequence correctly disables the PMT before turning on the brightfield lamp
4. The DCC100 controller has a hardware overload latch -- clear it after resolving the light source

### White Balance Calibration (JAI Camera)

#### Q: Calibration fails at 40x -- channels stuck at max exposure

**A:** At higher magnifications (40x, 60x), less light reaches the sensor, particularly at polarizer angles far from uncrossed. The calibration algorithm may hit the configured max exposure without reaching target intensity.

**What happens:** The algorithm detects channels stuck at the exposure ceiling and takes corrective action:
1. **Gain boost:** If analog gain has headroom, gain is increased and exposure reduced proportionally.
2. **Exposure extension:** If gain is already at hardware max (4.0x for R/B, 64.0x for green), the max exposure limit is automatically extended up to the hardware ceiling (7900ms).
3. **Early termination:** If channels remain stuck at both hardware max gain AND hardware max exposure for 3 consecutive checks, calibration stops early with a clear message.

**If you see the warning "Extending max exposure to Xms":** This is normal behavior. The algorithm is adapting to low-light conditions.

**If you see "hardware limits reached":** The scene is too dim for the camera to achieve target intensity even at maximum settings. Try:
- Using a brighter light source or increasing lamp intensity
- Moving to an area with more signal (tissue vs. empty glass)
- Lowering the target intensity value in the calibration dialog
- Reducing the base_gain setting if R/B channels are clamped (check logs for "clamped" warnings)

#### Q: What do "base gain clamped" warnings mean?

**A:** The JAI camera has different analog gain ranges per channel:
- Red: 0.47x - 4.0x
- Green: 1.0x - 64.0x
- Blue: 0.47x - 4.0x

If `base_gain` is set higher than 4.0, the R/B channels are clamped to 4.0x while green uses the full requested value. This is expected behavior. The calibration algorithm tracks the actual clamped values and compensates accordingly.

#### Q: Where are white balance calibration files saved?

**A:** Calibration diagnostics are saved to the output path specified in the dialog. Files include:
- `convergence_log.csv` -- Per-iteration data showing exposure, gain, and intensity values
- `white_balance_settings.yml` -- Final calibration settings (can be reloaded)
- `white_balance_verification.tif` -- Image captured with final calibrated settings
- `intensity_histograms.png` -- Visual histogram and convergence plots

### Background Collection

#### Q: What are background images and do I need them?

**A:** Background images correct for uneven illumination:

**What they do:** Divide your sample images by background images to remove illumination variations

**When to collect:**
- When you change objectives
- When you change modality (e.g., PPM to brightfield)
- When illumination settings change
- Periodically (recommended monthly or after lamp changes)

**How to collect:**
1. Remove your slide (focus on empty stage or coverslip)
2. Extensions > QPSC > Utilities > Background Collection
3. Select modality and objective
4. Set exposure times (use same as your sample acquisitions)
5. Acquire - takes 30-60 seconds

#### Q: Background collection fails with timeout

**A:** The Python server took too long to respond:
1. Check Python server console for errors
2. Verify camera is working (test in Micro-Manager)
3. Check rotation stage isn't stuck (for PPM backgrounds)
4. Increase timeout in config if you have a slow camera

#### Q: Should I use the same exposure for backgrounds and samples?

**A:** **YES - this is critical:**
- Background and sample exposures MUST match for proper correction
- If they don't match, background correction is automatically disabled
- System will warn you if exposures don't match
- Use the same exposure times you plan to use for your samples

### File Management

#### Q: Where are my acquired images saved?

**A:** Location depends on workflow:

**Bounded Acquisition:**
- Creates new QuPath project in: `<Projects Folder>/<Sample Name>/`
- Stitched images in: `<Sample Name>/SlideImages/`
- Temporary tiles in: `<Sample Name>/TempTiles/` (auto-deleted after stitching)

**Acquire from Existing Image:**
- Uses currently open project
- Stitched images added to existing project
- Check project folder in QuPath (Project > Show project location)

#### Q: Can I delete the TempTiles folder?

**A:** **After stitching completes - YES**
- QuPath automatically deletes temp tiles after successful stitching
- If stitching failed, tiles remain so you can re-stitch (see "Stitching failed but my tiles were acquired" above)
- You can manually delete TempTiles folder after confirming stitched images are correct
- Keep tiles if you need to re-run stitching with different parameters

#### Q: Stitched images are huge - can I reduce file size?

**A:** Yes, several options:

**Option 1: Use OME-ZARR format (recommended)**
1. Edit > Preferences > QPSC
2. Change "Stitching output format" to OME_ZARR
3. 20-30% smaller files, 2-3x faster to create

**Option 2: Reduce overlap**
- Less overlap = fewer total tiles = smaller file
- But too little overlap hurts stitching quality

**Option 3: Increase compression**
- Edit tiles-to-pyramid config if you know how
- Default LZW compression is already pretty good

#### Q: ZARR files won't open in QuPath

**A:** ZARR is a directory format:
- The `.ome.zarr` folder is the complete image
- Don't move or delete files inside the folder
- QuPath should recognize ZARR automatically
- If not, check QuPath has bio-formats extension installed
- Fallback: Switch to OME-TIFF format in preferences

### Workflow-Specific

#### Q: What's the difference between "Bounded Acquisition" and "Acquire from Existing Image"?

**A:**

**Bounded Acquisition:**
- For new acquisitions starting from scratch
- You specify physical coordinates or region size
- Creates a new QuPath project
- Use when you don't have an existing overview image

**Acquire from Existing Image:**
- For targeted acquisition on loaded images
- You draw annotations in QuPath on an existing image
- Adds acquisitions to current project
- Use when you have a macro/overview image and want to acquire specific regions

**Simple rule:** If you have an overview image already, use Acquire from Existing Image. If starting fresh, use Bounded Acquisition.

#### Q: "Acquire from Existing Image" says "No macro image available"

**A:** This workflow needs an overview/macro image to define coordinates:

**Fix:**
1. Acquire or load a macro image of your entire sample first
2. Open it in QuPath before starting the workflow
3. The macro image provides the coordinate reference system

**Alternative:** Use Bounded Acquisition instead, which doesn't need a macro image

#### Q: Can I run multiple acquisitions in one session?

**A:** Yes, with some notes:

**Bounded Acquisition:**
- Creates new project each time by default
- Or if project already open, adds to existing project
- Each sample gets its own folder structure

**Acquire from Existing Image:**
- Always adds to currently open project
- Can acquire multiple annotations in one workflow run
- Best for batch processing regions of interest

**Best practice:** Keep same project open to add multiple samples, or close/reopen between completely different experiments

### Multi-Channel Acquisition (Widefield IF, BF+IF)

Quick fixes for the most common multi-channel failure modes. For the full reference on how channels are configured in YAML, how profile-level overrides work, and more exotic failures, see [CHANNELS.md](CHANNELS.md).

#### Q: The channel picker is empty

**A:** The acquisition dialog's Fluorescence Channels panel shows "No fluorescence channels configured for this microscope" instead of a channel grid.

**Possible causes:**
1. The selected profile's modality has no `channels:` library in YAML. The channel path only activates for widefield (`type: widefield`) and BF+IF (`type: bf_if`) modalities that declare a non-empty library.
2. The YAML parsed but every entry was rejected (e.g. missing or non-numeric `exposure_ms`). Check the log for `Skipping channel '<id>' in modalities.<modality>.channels` warnings on startup.
3. The Microscope Config File preference is pointing at the wrong YAML, or the config failed to load.

**Fix:** Add a `channels:` block under the intended modality, fix any parse warnings, and verify `Edit > Preferences > QuPath SCope > Microscope Config File` points at the right YAML. See [CHANNELS.md](CHANNELS.md#2-channel-library-schema) for the schema.

#### Q: "Acquisition refused -- no channels selected"

**A:** The picker's master "Customize channel selection" checkbox is on, but every individual channel checkbox is off. The workflow blocks the acquisition rather than silently falling back to the full library.

**Fix:** Either check at least one channel row, or un-tick the master checkbox to acquire every library channel at its default exposure.

#### Q: One channel is missing from the merged OME-TIFF

**A:** The per-channel stitch for that channel failed, so its single-channel pyramid never existed when `ChannelMerger` ran.

**Fix:** Open the acquisition log and search for warnings around `stitchChannelDirectories` that mention the missing channel id. Common root causes: no tiles were written for that channel (filter wheel timed out, server dropped the channel mid-acquisition), the `TileConfiguration.txt` references a missing tile, or the channel subdirectory is empty. The raw per-tile TIFFs under `{projectsFolder}/{sample}/<profile>/{annotation}/{channel_id}/` survive stitch failures, so you can re-run stitching with [Stitching Recovery](WORKFLOWS.md#utility-tools) once the underlying issue is fixed.

#### Q: BF channel is washed out or too dark on BF+IF

**A:** Transmitted-lamp intensity is wrong for the objective. The BF library entry has a `DiaLamp Intensity` (or equivalent) set for a different objective and has not been overridden for this profile.

**Fix:** Add a `channel_overrides.BF.device_properties` entry to the profile, tuning the right property for this objective. The extended merge rule replaces the matching `(device, property)` in the library entry and leaves everything else alone -- you do not have to redeclare the BF channel. Example for a low-magnification profile:

```yaml
BF_IF_10x:
  modality: BF_IF
  channels: [BF, DAPI, FITC, TRITC, Cy5]
  channel_overrides:
    BF:
      exposure_ms: 20
      device_properties:
        - { device: DiaLamp, property: Intensity, value: 70 }
```

See [CHANNELS.md](CHANNELS.md#3-profile-level-selection-and-overrides) for the full extended override schema.

#### Q: Channel transitions race the snap (first tile of a channel looks dim or wrong)

**A:** Some hardware (filter wheels, reflector turrets, certain light paths) reports `isBusy() = false` before the LED intensity or filter position has actually settled. Micro-Manager's `waitForDevice` cannot detect the remaining settling time, so the camera snaps too early.

**Fix:** Add `settle_ms: <N>` to the offending channel in its YAML library entry. Start with 50-100 ms and tune down. This is a dumb sleep applied after all presets and property writes have been issued and `waitForDevice` has returned, immediately before the exposure is set and the image is snapped.

### Widefield IF / BF+IF Gotchas (2026-04-13/14 session)

This section documents failure signatures that were shaken out during the OWS3 widefield IF and BF+IF bring-up. Each item is: symptom -> what the log looks like -> root cause -> where it was fixed. If you are running an older build and see one of these, upgrading to the listed commit (or newer) is the real fix.

#### Q: I picked two channels but the result only has one

**A:** The merged OME-TIFF opens in QuPath with a single channel even though two (or more) channels were checked in the picker and acquisition appeared to succeed.

**Log signature:**
```
StitchingHelper: Stitching region: bounds (single pass, no rotation or channel axis)
...
Updating server metadata for 1 channel
```

**Root cause:** The channel-library lookup used the base modality name (e.g. `widefield`) instead of the enhanced profile key (e.g. `Fluorescence_10x`) when deciding whether this was a channel-based run. The lookup returned no library, so the channel branch silently fell through to the single-pass stitcher, which flattened all the per-tile TIFFs it found into a single pancaked image.

**Resolved in:** `b40c98e` + `0a67083` -- the channel branch is now wired into `performRegionStitching` as well as `performAnnotationStitching`, and both lookups use the enhanced profile key.

#### Q: Merge said success but the output OME-TIFF is still 1 channel

**A:** The stitching log clearly reports a multichannel merge, but when you open the file in QuPath (or read the OME metadata) it has one channel.

**Log signature:**
```
ChannelMerger: Channel-merged (2 sources, 2 channels)
PyramidImageWriter: Writing plane 1/1
...
1 channel
```

**Root cause:** `PyramidImageWriter.writeOMETIFF` unconditionally called `channelsInterleaved()` on the OME builder. Under JPEG-2000 compression this packs channels as samples-per-pixel, which the downstream writer then truncates to the first sample, dropping every channel after the first.

**Resolved in:** tiles-to-pyramid `b60b689` -- `channelsInterleaved()` is now gated on `isRGB()` so only RGB images use interleaved samples. Grayscale multichannel OME-TIFFs write channels as separate planes and survive the round-trip.

#### Q: Acquisition succeeds but all tile channels look identical

**A:** Every channel subdirectory has the same number of tiles and they line up, but opening a DAPI tile and a FITC tile from the same position shows essentially the same image. Nothing is switching between channels at the hardware level.

**Log signature (server side):**
```
'mmcorej_CMMCore' object has no attribute 'setConfig'
'mmcorej_CMMCore' object has no attribute 'setProperty'
'mmcorej_CMMCore' object has no attribute 'waitForDevice'
```
These warnings appear once per channel per tile.

**Root cause:** `apply_channel_hardware_state` in the Python server used Java-style camelCase method names (`setConfig`, `setProperty`, `waitForDevice`) instead of pycromanager's snake_case equivalents (`set_config`, `set_property`, `wait_for_device`). Every hardware call raised an AttributeError, was caught by the guard clause, and was logged as a warning. The camera kept snapping, but whatever state the previous tile had left on the scope was what actually got captured -- so every "channel" came from the same hardware configuration.

**Resolved in:** server commit `2498383` -- all pycromanager calls in `apply_channel_hardware_state` now use the correct snake_case API.

#### Q: "Cannot set property State to 1.000000" on LappMainBranch1 at acquisition start

**A:** Acquisition fails immediately on the first tile (or sometimes during mode setup) with a property rejection from `LappMainBranch1`.

**Log signature:**
```
Cannot set property "State" to "1.000000" [ LappMainBranch1: Invalid property value: State (3) ]
```

**Root cause:** `apply_mode_setup` step 5 called `illumination.set_power(1.0)` using the profile's legacy `illumination_intensity` field. OWS3's `Fluorescence` modality declares its legacy illumination device as `LappMainBranch1` with `intensity_property: State`, which is a discrete integer property (0/1/2/3) and rejects float strings like `"1.000000"`. For channel-based profiles the per-channel library already handles illumination, so the profile-level `set_power` call is both wrong and unnecessary.

**Resolved in:** `microscope_control/8e04254` -- `apply_mode_setup` now skips profile-level illumination for any profile whose modality has a `channels:` library. The per-channel `intensity_property` writes are still honored.

#### Q: "Acquisition refused -- no channels selected" even though I opened the picker

**A:** You checked "Customize channel selection for this acquisition" but did not tick any individual channel rows, then clicked OK.

**Root cause:** The master override checkbox and the individual row checkboxes are independent. Turning on the master checkbox means "I am going to explicitly pick the channels for this run." An empty channel list in that mode is a different thing from leaving the master checkbox off, and the workflow refuses to run rather than silently falling back to the full library (which would be an invisible surprise downstream).

**Fix:** Either tick at least one row, or turn the master checkbox off and rerun. See [WORKFLOWS.md Multi-Channel section](WORKFLOWS.md#multi-channel-acquisition-widefield-if-bfif) for the full picker behavior.

#### Q: FITC / TRITC entries flicker into my QuPath project before a merged entry appears

**A:** During acquisition you see per-channel project entries pop up (`..._FITC`, `..._TRITC`, etc.), then disappear and get replaced by a single merged entry. QuPath also prompts "Save changes to <entry>.qpdata?" as the old entries are removed.

**Root cause:** `processAngleWithIsolation` imported every per-channel stitched OME-TIFF to the QuPath project as it was produced. The merge step then imported the merged file as a new entry and removed the per-channel ones -- but not before they had already appeared in the project tree and triggered auto-save dialogs on switch.

**Resolved in:** `882367d` -- per-channel stitched files are now written with `skipProjectImport: true`. They still land on disk as recovery artifacts in the channel subdirectories, but the merged multichannel OME-TIFF is the only thing the project ever sees. No flicker, no qpdata save prompt.

#### Q: Autofocus fails on sparse samples (beads, pollen, seeds)

**A:** Autofocus refuses to run on a clearly-visible sample. The tissue texture gate passes but the area gate fails.

**Log signature:**
```
Tissue stats: texture=0.0499 (threshold=0.0050)  PASS
Tissue stats: area=0.009 (threshold=0.200)  FAIL
```

**Root cause:** The area gate ("fraction of the tile that is tissue") is a dense-sample assumption baked into the default autofocus strategy. Sparse samples -- fluorescent beads, pollen grains, individual seeds, single cells on a clean coverslip -- legitimately cover 1% of the field of view, so the gate fires every time.

**Current workaround:** `autofocus_OWS3.yml` now ships with a v2 schema that has per-modality strategies (`dense`, `sparse`, `dark-field`, `manual_only`, ...). The loader reads them at acquisition start and logs which one was chosen. **The AF call-site swap that actually consumes the strategy object is deferred**, so today the way to tune the area threshold for a sparse sample is to hand-edit `autofocus_OWS3.yml`:

```yaml
strategies:
  sparse:
    texture_threshold: 0.003
    area_threshold: 0.005
    n_steps: 21
    search_range_um: 40
```

and pick it (or `manual_only`) as the modality's default. The GUI dropdown for strategy selection is a follow-up feature -- see the [Autofocus Strategy Override note in WORKFLOWS.md](WORKFLOWS.md#autofocus-strategy-override-partial-feature).

### Performance & Optimization

#### Q: Acquisition is very slow - how can I speed it up?

**A:** Several factors affect speed:

**1. Autofocus frequency:**
- Edit `autofocus_PPM.yml`
- Increase `n_tiles` from 2 to 5 or even 10
- Autofocus less often = faster acquisition
- But risk of defocusing if sample isn't flat

**2. Overlap percentage:**
- Reduce overlap from 10% to 5% or even 0%
- Less overlap = fewer total tiles
- But stitching quality may suffer

**3. Camera exposure:**
- Shorter exposures = faster acquisition
- But images might be too dark
- Use adaptive exposure to find optimal balance

**4. Camera cooling/settling:**
- Reduce settling time in config (try 100ms instead of 500ms)
- Only if your camera stabilizes quickly

**5. Stitching format:**
- Use OME-ZARR instead of OME-TIFF
- 2-3x faster to create stitched images

#### Q: QuPath is using too much memory

**A:**
- Close unused projects
- Don't open multiple large stitched images simultaneously
- Increase QuPath memory limit (Edit > Preferences > Memory)
- Consider using lower resolution overview images

---

## Error Messages Reference

### Socket/Connection Errors

#### "Connection refused"

**Meaning:** QuPath can't reach the Python server

**Likely causes:**
- Python server not running
- Wrong port number in config
- Firewall blocking connection

**Solution:**
1. Start Python server
2. Verify port 5000 in both server and config
3. Check Windows Firewall settings

#### "Read timed out"

**Meaning:** Python server didn't respond in time

**Likely causes:**
- Micro-Manager crashed or frozen
- Python server busy with long operation
- Network issue (rare for localhost)

**Solution:**
1. Check if Micro-Manager is responsive
2. Check Python server console for errors
3. Restart both if necessary

#### "Hardware error: XY stage not initialized"

**Meaning:** Micro-Manager doesn't have stage control

**Solution:**
1. Launch Micro-Manager
2. Load hardware configuration
3. Verify stage shows in device list
4. Test stage movement manually

#### "CONFIG handshake failed" / "CFG_FAIL"

**Meaning:** The microscope server rejected the configuration file sent during connection

**Likely causes:**
- Microscope config preference not set in QuPath
- Config file path is invalid or file does not exist
- Server version too old (requires microscope-command-server v1.1.0+)
- Server cannot read the configuration file

**Solution:**
1. Verify microscope config is set: Edit > Preferences > QPSC > Microscope Config File
2. Ensure the config file exists at the specified path
3. Update microscope-command-server to v1.1.0 or later (CONFIG handshake required since v0.3.0)
4. Check Python server console for the specific rejection reason

### Configuration Errors

#### "Pixel size must be set for annotation-based workflows"

**Meaning:** The image lacks pixel calibration data

**Likely causes:**
- Image imported without metadata
- Incorrect import settings
- Microscope config missing pixel_size_microns

**Solution:**
1. Check image properties in QuPath (right-click image > Image > Show metadata)
2. If pixel size is missing, add it manually (Image > Set pixel size)
3. Or ensure microscope config has correct pixel_size_microns value

#### "Configuration file not found"

**Meaning:** QuPath can't find the YAML config

**Solution:**
1. Edit > Preferences > QPSC
2. Use Browse button to select config_ppm.yml
3. Verify file exists at the specified path

#### "Missing required configuration key: exposures"

**Meaning:** YAML file structure is incorrect

**Solution:**
1. Check YAML file formatting (indentation matters!)
2. Compare with example config files
3. Look for missing or misspelled keys in the modalities section

### Acquisition Errors

#### "Stage position exceeds limits"

**Meaning:** Requested position is outside stage travel range

**Solution:**
1. Check stage_limits in microscope config
2. Reduce acquisition region size
3. Verify you're not trying to acquire off the slide

#### "Autofocus failed at position"

**Meaning:** Couldn't find focus at one or more positions

**Likely causes:**
- No tissue at that position (normal - acquisition continues)
- Z-stage not responding
- Autofocus settings inappropriate for sample

**Solution:**
- If only a few positions: Normal, tissue detection working correctly
- If all positions: Check Z-stage in Micro-Manager, verify autofocus config

#### "Background acquisition error"

**Meaning:** Couldn't collect background images

**Likely causes:**
- Camera not responding
- Rotation stage stuck (for PPM)
- Server timeout

**Solution:**
1. Test camera in Micro-Manager
2. For PPM: verify rotation stage moves manually
3. Check Python server console for details

### Stitching Errors

#### "TileConfiguration.txt not found"

**Meaning:** Missing coordinate file for stitching

**Likely causes:**
- Acquisition failed before completion
- Temporary folder was deleted prematurely
- File permissions issue

**Solution:**
1. Check Python server completed acquisition successfully
2. Verify temp tiles folder exists and contains TileConfiguration.txt
3. Re-run acquisition if file is truly missing

#### "Failed to create OME-ZARR directory"

**Meaning:** Can't write ZARR output

**Solution:**
1. Check disk space
2. Verify write permissions on output folder
3. Check path length isn't too long (Windows limit: 260 characters)

### Other Errors

#### "No project is currently open"

**Meaning:** Some workflows require an open QuPath project

**Solution:**
- Bounded Acquisition: Creates project automatically
- Acquire from Existing Image: Open or create project first

#### "No annotations found in current image"

**Meaning:** Acquire from Existing Image workflow needs annotations

**Solution:**
1. Draw annotations with rectangle or polygon tool
2. Select annotations before starting workflow
3. Or use Bounded Acquisition if you don't have annotations

---

## Component-by-Component Troubleshooting

### QuPath Extension

**Problem:** Extension doesn't appear in menu

**Check:**
1. Extension .jar file in extensions folder
2. QuPath version 0.6.0-rc4 or newer
3. No error messages on QuPath startup
4. Dependencies installed (tiles-to-pyramid extension)

**Solution:**
- Reinstall both extensions in correct order
- Check console for Java exceptions
- Verify .jar files aren't corrupted (should be ~10MB each)

**Problem:** Menu items are grayed out

**Check:**
1. Configuration file loaded (Edit > Preferences > QPSC)
2. Python server running
3. Project open (for workflows that need it)

**Solution:**
- Load valid configuration file
- Start Python server
- Create or open project as needed

### Python Server (microscope-command-server)

**Problem:** Server won't start

**Check:**
1. Python version (3.8 or newer required)
2. Dependencies installed (`pip install -e .` from microscope_command_server directory)
3. Port 5000 not in use
4. Micro-Manager running

**Solution:**
```bash
# Check Python version
python --version

# Install dependencies
cd microscope_command_server
pip install -e .

# Start server with explicit port
python -m microscope_command_server.server --port 5000
```

**Problem:** Server crashes during acquisition

**Check:**
1. Python console for exception traceback
2. Micro-Manager still responsive
3. Hardware errors (stage, camera, etc.)

**Solution:**
- Read error message in console carefully
- Check if Micro-Manager device is disconnected
- Restart server and try again
- Check logs for hardware communication errors

### Micro-Manager

**Problem:** Micro-Manager won't control hardware

**Check:**
1. Hardware configuration loaded
2. Devices initialized (green lights in device list)
3. No error messages in Micro-Manager console
4. Hardware physically connected and powered

**Solution:**
- Reload hardware configuration
- Reinitialize devices in Tools > Device Property Browser
- Check physical connections
- Restart Micro-Manager

**Problem:** Stage moves to wrong positions

**Check:**
1. Stage calibration in Micro-Manager
2. Origin set correctly
3. No hardware errors

**Solution:**
- Re-home stage
- Verify stage coordinates in property browser match physical position
- Check for mechanical binding

### Hardware (Microscope Components)

**Problem:** Camera not acquiring

**Check:**
1. Camera power
2. Camera driver loaded in Micro-Manager
3. Camera settings (binning, exposure, etc.)
4. USB/connection cable

**Solution:**
- Power cycle camera
- Reload camera driver in Micro-Manager
- Test with Micro-Manager Snap Image button
- Check device manager (Windows) for camera recognition

**Problem:** Rotation stage not moving (PPM)

**Check:**
1. Rotation stage controller powered
2. Stage position readable in Micro-Manager
3. No mechanical binding
4. Controller communication

**Solution:**
- Power cycle controller
- Check cable connections
- Manually move stage via controller
- Verify Micro-Manager can query position

**Problem:** Laser scanning hardware not responding (SHG/multiphoton)

**Check:**
1. Scan engine (e.g., OSc-LSM) recognized in Micro-Manager
2. PMT controller powered and connected
3. Pockels cell analog output device configured
4. Laser is on and at correct wavelength

**Solution:**
- Verify scan engine is listed as a camera device in Micro-Manager
- Check PMT controller status -- clear overload latch if triggered
- Test Pockels cell by setting voltage manually in Micro-Manager property browser
- Verify NI DAQ analog output channels are correctly assigned

---

## Configuration Validation

### Microscope Config File (config_<name>.yml)

**Essential settings to verify:**

```yaml
microscope_settings:
  name: "Your_Microscope_Name"
  socket_port: 5000                    # Must match Python server

stage_limits:
  x_min_um: 0                          # Adjust to your stage
  x_max_um: 200000
  y_min_um: 0
  y_max_um: 150000

camera:
  width_pixels: 2064                   # Match your camera
  height_pixels: 1544
  pixel_size_um: 0.325                 # CRITICAL for coordinate calculation

modalities:
  ppm_20x:                             # Must match objective in GUI
    detector: "LOCI_DETECTOR_..."      # Must exist in resources
    objective: "LOCI_OBJECTIVE_..."    # Must exist in resources
    angles: [90.0, 0.0, 7.0, -7.0]    # Your PPM angle sequence
    exposures: [50, 50, 50, 50]        # Match angles list length
```

**Common mistakes:**
- Mismatched angles and exposures list lengths
- Wrong pixel_size_um (causes coordinate errors)
- Misspelled detector/objective references
- Port mismatch between config and server

### Resources File (resources_LOCI.yml)

**Structure:**
```yaml
detectors:
  LOCI_DETECTOR_ANDOR_ZYLA:
    name: "Andor Zyla"
    fov_width_um: 1416.1424            # Width of camera FOV
    fov_height_um: 1060.1104           # Height of camera FOV

objectives:
  LOCI_OBJECTIVE_OLYMPUS_20X_POL_001:
    name: "20x Polarization"
    magnification: 20
    na: 0.45
```

**Common mistakes:**
- FOV dimensions don't match camera + objective combination
- Missing entries referenced in main config
- Incorrect magnification values

### Autofocus Config (autofocus_PPM.yml)

**Per-objective settings:**
```yaml
- objective: LOCI_OBJECTIVE_OLYMPUS_20X_POL_001
  n_steps: 20                          # Number of Z-steps in search
  search_range_um: 30.0                # Total Z search range
  n_tiles: 2                           # Autofocus every N tiles
  texture_threshold: 0.005             # Tissue detection sensitivity
  tissue_area_threshold: 0.2           # Minimum tissue coverage
```

**Common mistakes:**
- n_tiles too low (autofocus too often, slow)
- search_range_um too small (doesn't find focus)
- texture_threshold too high (skips tissue positions)

### Validation Checklist

Run through this before first use:

- [ ] All YAML files have valid syntax (use YAML validator online)
- [ ] Detector and objective references match between files
- [ ] Camera FOV calculated correctly: pixel_size x width/height
- [ ] Stage limits set to physical range
- [ ] Port 5000 available and not firewalled
- [ ] Modality names match what you select in GUI
- [ ] Exposure times reasonable for your samples (50-1000ms typical)
- [ ] Autofocus parameters tested on sample slides

---

## Log Files and Diagnostics

### Where to Find Logs

**QuPath Logs:**
- Primary: `<QuPath-Directory>/logs/qpsc/qpsc-acquisition.log`
- Older logs: Same folder, dated files kept for 7 days
- Project-specific: `<Project-Folder>/logs/acquisition.log` (when workflows enable it)

**Python Server Logs:**
- Console output (not saved by default)
- To save: redirect output when starting server
  ```bash
  python -m smart_wsi_scanner.qp_server > server.log 2>&1
  ```

**Micro-Manager Logs:**
- Micro-Manager console window (not saved by default)
- Enable logging in Tools > Options if needed

### Reading QuPath Logs

**Log format:**
```
14:23:45.123 [Thread] LEVEL  Logger.Class - Message
```

**Important levels:**
- **ERROR:** Critical failures - read these first
- **WARN:** Problems that don't stop execution - check these second
- **INFO:** Normal operation - helpful for timing and progress

**What to look for:**

1. **Error messages** with stack traces (Java exceptions)
2. **WARN messages** before failures (often indicate root cause)
3. **Timestamp gaps** (system waiting or hung)
4. **Repeated messages** (stuck in loop or retry)

**Common log patterns:**

**Successful acquisition:**
```
14:23:45 INFO  BoundedAcquisitionWorkflow - Starting acquisition for SampleName
14:23:46 INFO  TilingUtilities - Generated 150 tile positions
14:25:30 INFO  UIFunctions - Acquisition completed successfully
14:25:35 INFO  StitchingHelper - Stitching 150 tiles...
14:28:12 INFO  TileProcessingUtilities - Successfully imported stitched image
```

**Failed connection:**
```
14:23:45 INFO  MicroscopeController - Connecting to localhost:5000
14:23:55 ERROR MicroscopeSocketClient - Connection refused
```
**Fix:** Start Python server

**Hardware error:**
```
14:25:30 INFO  MicroscopeSocketClient - Querying XY position
14:25:30 ERROR MicroscopeHardwareException - XY stage not initialized
```
**Fix:** Initialize stage in Micro-Manager

**Coordinate transformation:**
```
14:26:15 INFO  TilingUtilities - Pixel size: 0.325 um/pixel
14:26:15 INFO  TilingUtilities - Converting QuPath pixels to stage microns
14:26:15 INFO  TilingUtilities - QuPath (1000, 2000) -> Stage (325.0, 650.0) um
```
Verify pixel size is correct for your setup

### Enabling Debug Logging

**For more detailed logs:**

1. Find QuPath's `logback.xml` (in extensions .jar or QuPath installation)
2. Change log level:
   ```xml
   <logger name="qupath.ext.qpsc" level="DEBUG" additivity="false">
   ```
3. Restart QuPath

**Debug logs show:**
- Detailed socket communication
- Coordinate calculations step-by-step
- Configuration file parsing
- Intermediate workflow states

**Warning:** Debug logs are VERY verbose - use only for troubleshooting specific issues

### Common Log Messages Explained

#### "Stage XY position: (x, y)" [repeated many times]

**Meaning:** Health check heartbeats - normal operation

**This appears during:** Long acquisitions while checking server is alive

**Action:** None needed - this is filtered from main log but might appear in debug log

#### "Tissue stats: texture=X, area=Y"

**Meaning:** Autofocus tissue detection results

**If texture very low (< 0.005):** No tissue detected, autofocus deferred

**Action:** Normal if occasional. If frequent, lower texture_threshold in autofocus config

#### "Found X existing OME-TIFF files before stitching"

**Meaning:** Tracking files in output folder before adding new stitched images

**Action:** None - informational only

#### "Adjusting exposure: Xms -> Yms"

**Meaning:** Adaptive exposure changing camera exposure

**Action:** Normal during background collection or adaptive acquisition

---

## Getting Help

If this guide doesn't solve your problem:

1. **Collect information:**
   - What were you trying to do?
   - What happened instead?
   - Any error messages (exact text)?
   - QuPath log file (last 50-100 lines around the error)
   - Python server console output
   - Screenshot of error dialog if applicable

2. **Check versions:**
   - QuPath version
   - QPSC extension version
   - Python version
   - Micro-Manager version

3. **Try these first:**
   - Restart QuPath
   - Restart Python server
   - Restart Micro-Manager
   - Reboot computer (really - Windows USB/driver issues sometimes need this)

4. **Report issues:**
   - GitHub: https://github.com/uw-loci/qupath-extension-qpsc/issues
   - Include all info from step 1
   - Steps to reproduce the problem
   - What you've already tried

---

## Appendix: Quick Reference

### Essential Commands

**Start Python Server:**
```bash
cd /path/to/microscope_command_server
python -m microscope_command_server.server
```

**Test Microscope Connection:**
QuPath: Extensions > QPSC > Utilities > Communication Settings

**Check Logs:**
QuPath: Help > Show log file location

**Reload Configuration:**
QuPath: Edit > Preferences > QPSC > Browse to config file, restart QuPath

### File Locations Summary

| Item | Location |
|------|----------|
| QuPath extensions | `<QuPath>/extensions/` |
| QPSC logs | `<QuPath>/logs/qpsc/` |
| Config files | Your configured location |
| Project data | `<ProjectsFolder>/<SampleName>/` |
| Stitched images | `<Project>/SlideImages/` |
| Temp tiles | `<Project>/TempTiles/` (auto-deleted) |

### Common Port Numbers

| Service | Port | Configurable? |
|---------|------|---------------|
| Python server | 5000 | Yes (in config) |
| Micro-Manager | N/A | N/A |
| QuPath | N/A | N/A |

### Workflow Decision Tree

```
Do you have an overview image of your sample?
+-- YES -> Use "Acquire from Existing Image"
|          Draw annotations on overview
|          Acquire high-res at annotated regions
|
+-- NO  -> Use "Bounded Acquisition"
           Specify region by coordinates or size
           Creates new project automatically
```

---

**Document Version:** 1.2
**Last Updated:** March 16, 2026
**GitHub:** https://github.com/uw-loci/qupath-extension-qpsc
