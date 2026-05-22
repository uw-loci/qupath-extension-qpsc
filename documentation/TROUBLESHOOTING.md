# QPSC Troubleshooting Guide & FAQ

**Last Updated:** May 10, 2026
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

#### Q: "Read timed out" / "Acquisition failed" / `WinError 10053` mid-acquisition with multi-annotation runs

**A:** This was a known failure mode through 2026-04-25. Three independent issues stacked:

- The Java client's primary `getAcquisitionStatus` poll used a 5s read timeout. During a 3-angle JAI tile capture (~10-15s on PPM 20x), the server-side STAT handler queued behind the workflow command on the same client thread, so the poll timed out even though the server was healthy.
- The auxiliary socket's commands (GETXY/GETZ/etc.) shared the server's hardware lock with the primary's tile capture and hit the same 5s timeout, triggering `cleanupAuxiliary` and a reconnect storm from StageMap, LiveViewer-FramePoller, and StagePositionManager-Poller.
- The server's same-IP CONFIG takeover guard checked only the most recent CONFIG-sender's acquisition state. With the dual-socket layout, the auxiliary often became the "active" addr while acquisition ran on the primary -- a takeover could then proceed and kill the in-flight workflow.

**As of 2026-04-26, all three are fixed:**
- Primary `getAcquisitionStatus` per-call timeout bumped to 30s.
- Auxiliary socket read timeout bumped to 30s.
- Server CONFIG takeover guard now scans every connection from the same IP for an active workflow before allowing a takeover; rejected attempts come back as `CFG_BLCK: BLOCKED: Active acquisition on (...)`.

If you still see the symptom:
1. Make sure both the QPSC JAR and the Python server are at or after these changes (server commit `a980062`, QPSC `2208ef0` and `9e5cc48`).
2. Check the server log for `CONFIG: Refusing same-IP takeover ... actively running acquisition` lines -- those indicate the guard is doing its job and the workflow is *not* being interrupted; the Java side may still log retry warnings until the next status poll succeeds.

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

#### Q: "Alignment Flip Frame Unverified"

**A:** This dialog appears when you load a slide alignment that was saved before flip-frame verification was implemented (May 19, 2026). The alignment JSON has `flipMacroX` and `flipMacroY` fields but lacks the `flipFrameVerified` indicator, and the active microscope's saved presets require a flipped sibling for at least one source scanner.

**What this means:**
- The alignment was saved between May 2026 (when flip-frame metadata was first introduced) and May 19, 2026 (when verification stamping was added)
- During that window, alignments saved the flip metadata but always hardcoded it to `false`, regardless of whether the transform was actually built on the flipped-sibling image
- If the alignment was built on a flipped-sibling image but recorded as `(false, false)`, downstream workflows will apply an extra flip and send the stage to the X/Y mirror of the intended target
- The 2026-05-18 OWS3 acquisition log shows this exact failure: annotation appearance flipped, partially overlapped with slide tissue

**Why this matters:** On a flip-needing scope, frame mismatch produces coordinates that are mirrored horizontally. Your annotations will appear to disagree by X, and acquired tiles will land at X-mirrored positions.

**To fix:**
1. **Recommended:** Cancel the workflow, run **Microscope Alignment** on this slide to rebuild the alignment JSON with the new flip-frame verification stamping, then retry. The new JSON will record `flipFrameVerified: true` and downstream workflows will compose it correctly.
2. **At your own risk:** Continue anyway if you are confident this alignment was built on the unflipped base entry (not the flipped sibling).

#### Q: "Legacy Alignment -- Flip Frame Unknown"

**A:** This dialog appears when you load a slide alignment saved before flip-frame metadata was introduced (before May 2026), and the active microscope's saved presets require a flipped sibling for at least one source scanner.

**What this means:**
- The alignment JSON predates Phase 3 (May 2026) and has no `flipMacroX` / `flipMacroY` fields at all
- The active microscope has at least one preset pair where flipping matters (e.g., flipped and unflipped macro images for the same source scanner)
- We cannot determine which frame the saved transform was built in
- Reusing it risks applying a flipped-frame transform to unflipped pixel coordinates (or vice versa), causing an X-mirror effect

**Why this matters:** On a flip-needing scope, frame mismatch produces coordinates that are mirrored horizontally. Your annotations will appear to disagree by X, and acquired tiles will land at X-mirrored positions.

**To fix:**
1. **Recommended:** Cancel the workflow, run **Microscope Alignment** on this slide to rebuild the alignment JSON with flip-frame metadata, then retry.
2. **At your own risk:** Continue anyway if you are confident the saved alignment's frame matches what the workflow will use now.

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

#### Q: Auto-Align (SIFT) fails even though the live view is on the correct tile

**A:** The most common cause is a bit-depth mismatch between a 16-bit monochrome microscope camera (e.g. OWS3) and an 8-bit reference WSI (typical H&E or PPM scan). Most cameras only use 12-14 of their 16 bits, so the legacy `gray / 256` bit-shift compresses the camera's useful range to a sliver of 8-bit, leaving SIFT with one near-black image and one full-contrast reference. The fix landed 2026-05-06 (Java commit `0f13ca2`, server commit `59c8d41`).

Defaults now handle this correctly: `Bit-depth normalization` = `PERCENTILE` 2/98 with `CLAHE` on (clipLimit 2.0). Both knobs live in the SIFT Settings dialog (open via the **Settings...** button next to **Auto-Align (SIFT)**).

If matching still fails on a tile that visually overlaps the camera's live view:

1. Confirm the camera and server are running the post-fix versions. The server log line `_to_gray: mode=PERCENTILE, ...` should appear during a SIFT call. If you see `BIT_SHIFT applied`, the legacy mode is still selected -- update the dropdown.
2. Raise CLAHE clipLimit to 4.0 (Settings dialog) for very low-contrast samples.
3. Widen the percentile range to 0.5/99.5 if the camera produces very faint features that are being clipped.
4. Lower the SIFT contrast threshold to 0.02 (Preferences > SIFT) for pale tissue.
5. As a last resort, switch normalization mode to `MIN_MAX`. Note: this is more sensitive to a few saturated pixels than `PERCENTILE`.

#### Q: Auto-Align (SIFT) fails when the live view is far from the target tile

**A:** SIFT is a *refinement*, not a search. The WSI region pulled around the target tile extends only ~160 um beyond the tile (configurable via `Search margin (um)` in the SIFT Settings dialog). If the live view doesn't overlap the WSI region at all, there's nothing to match against and SIFT will report `insufficient features` or `matching failed`.

Drive the stage roughly close (a few hundred microns is enough) using the joystick, Live Viewer click-to-center, or the initial transform estimate before clicking SIFT. If you genuinely need a wider capture range, raise the search margin -- but matching cost grows with margin squared, so 300-400 um is a reasonable upper bound.

### Acquisition Problems

#### Q: "Sub-image Missing Objective -- Workflow Cancelled" error

**A:** The open sub-image entry has no `objective` metadata, and the workflow cannot safely proceed with acquisition.

**What this means:**
- The sub-image was acquired without recording which microscope objective was used
- The objective is critical for computing the half-FOV correction applied to the tile-grid origin
- Without it, the system would fall back to whatever objective Micro-Manager happens to be on right now, which may not match the saved sub-image's original objective
- This fallback could shift all tiles by half a field of view with no warning

**To fix:**
1. **Recommended:** Re-acquire the sub-image using the current workflow. The new acquisition will stamp the objective metadata on import.
2. **Alternative:** If you know the original objective, hand-edit the project entry's metadata to add it (not recommended unless you are certain of the value).

#### Q: "Sub-image Objective Mismatch" Continue/Cancel dialog

**A:** The open sub-image's recorded objective differs from the wizard's current objective setting.

**What this means:**
- The sub-image was acquired at objective A (recorded in entry metadata)
- The wizard is configured for objective B
- The entry's objective drives the half-FOV correction; the wizard's objective drives the tile grid size
- A mismatch shifts every tile by half the FOV difference between the two objectives (may be hundreds of microns)

**To fix:**
1. **Recommended:** Cancel and either:
   - Switch the wizard to the entry's objective (safest)
   - Re-acquire the sub-image at the wizard's desired objective (if you want to keep that objective)
2. **At your own risk:** Continue anyway (you accept the shift risk)

#### Q: "Camera ROI Mismatch -- Workflow Cancelled" error

**A:** The live camera frame dimensions do not match the configured sensor dimensions. The camera is cropped to a sub-region of the full sensor — historically left behind by a prior streaming Autofocus call that did not restore the camera ROI on exit. MicroManager remembers the last ROI across sessions, so the cropped state persists.

**Now mostly a safety net.** As of microscope_command_server commit `7f40a47` (2026-05-11), the streaming-AF code path anchors absolutely on the full sensor: every entry calls `clear_roi()` first to read the canonical full-sensor baseline, every exit calls `clear_roi()` first to return there. The path that historically leaked a cropped ROI is unreachable from streaming AF. This dialog should now essentially never fire from streaming AF; if it does, the camera was cropped by something else (operator-set MM Property Browser, an unrelated handler, a future regression).

**What happens if you continue:** Every acquired tile captures only the cropped portion of the planned field of view. The stitched mosaic ends up with empty space between tiles and alignment lands at the wrong stage position.

**What the dialog shows:**
- Configured sensor dimensions (from microscope resources YAML)
- Live camera dimensions (queried from MicroManager)
- The percentage difference on each axis (threshold is 5%)
- Step-by-step instructions to reset the ROI in MicroManager

**To fix:**
1. Open MicroManager
2. Find the camera's ROI / SubROI property (or use the "Clear ROI" / "Reset ROI" button in the toolbar if available)
3. Set ROI to full sensor dimensions or clear it completely
4. Restart the workflow in QuPath

**Why this matters:** A cropped ROI is invisible in the Live Viewer but has catastrophic effects on acquisition geometry. The tile grid is planned for the full sensor's field of view, but each tile captures only the cropped portion. The resulting mosaic has ~50% empty space between tiles (if cropped to 50%) and alignment calculations are completely wrong. This gate catches the condition before any data is corrupted.

**If the dialog fires after `7f40a47` is on the server:** capture the server log and grep for `STREAM_AF:entry camera ROI` — if the first AF run after MM startup logs a cropped entry, the camera was already cropped before the Live Viewer was opened (operator-set, or an external Python session). If you see a `STREAM_AF:cropped` line without a matching `STREAM_AF:restored ... [full sensor]` line at the end of the same run, file an issue: the absolute anchoring has been defeated.

#### Q: "Source Microscope Missing -- Workflow Cancelled" error

**A:** The open entry has no `source_microscope` metadata, and the active microscope requires a flipped sibling for visual-UX during alignment.

**What this means:**
- The image was imported without recording which microscope (scanner) it came from
- The active microscope has saved alignment presets with `flipMacroX` or `flipMacroY` set (meaning the optical path is flipped)
- The workflow cannot determine which preset's flip state applies to this entry, so it refuses to proceed

**Why this matters:** Without knowing the source microscope, the workflow can't resolve which preset to use. On a flip-needing scope, proceeding with the wrong flip assumption would cause the live camera view to disagree with the annotations by a mirror, breaking alignment and driving the stage to the wrong physical location.

**To fix:**
1. Go to **Microscope → Stage Map**
2. Click **Stamp Source Microscope** button
3. Select the microscope (scanner) that originally captured the overview image
4. Restart the workflow

**Alternative:** If you don't know the original source microscope, open the parent macro entry (overview image) instead of a sub-image and re-run the workflow on that.

#### Q: "Objective Pixel-Size Mismatch -- Workflow Cancelled" error

**A:** The dialog shows that MicroManager's active objective does not match the wizard's selection. The tile grid is planned for the wizard's objective; if the actual objective in MicroManager is different, tiles will be spaced incorrectly and the mosaic will not be usable.

**What the dialog shows:**
- Wizard objective and expected pixel size
- MicroManager's reported pixel size
- The closest configured objective that matches MicroManager's value (so you can see what MM probably has active)
- The difference percent (threshold is 5%)

**To fix:**
1. **Option A (recommended):** Change the objective in MicroManager to match what the wizard selected, then restart the workflow.
2. **Option B:** Change the wizard's objective dropdown to match what is physically on the microscope, then restart the workflow.

**Why this matters:** The previous version allowed a "Continue anyway?" but this produced gap-mosaics (empty space between tiles at 10x spacing while acquiring at 20x FoV) or silent calibration mismatch (White Balance and Background Collection write files under the wrong magnification key). The 5% threshold is tight enough to catch any user-induced mismatch (like a turret change) but wide enough to absorb calibration drift. A mismatch always cancels now; fixing it and restarting is the safe path.

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

#### Q: Live Viewer shows only the cropped center after streaming Autofocus refused

**A:** Two iterations:

- **First fix (2026-05-08, microscope_command_server `44b0ab0`):** the two pre-flight `UNAVAILABLE` early returns (blur-budget exceeded, saturation > threshold) sat outside the try/finally that called `_restore_roi`, so the centered 50% scan crop leaked past the refusal. Both branches now restore the ROI explicitly before returning.
- **Second fix (2026-05-11, microscope_command_server `7f40a47`):** `_apply_crop_roi` and `_restore_roi` were *relative* — they read whatever ROI the camera was in on entry and restored to that, not to full sensor. If the camera entered AF in an already-cropped state (from any pre-`44b0ab0` leak, an MM Property Browser action, or an external code path), every "restore" preserved the crop instead of recovering. After `7f40a47` both functions anchor *absolutely* on full sensor: entry calls `clear_roi()` first to establish the canonical baseline, exit calls `clear_roi()` first to return there. The camera unconditionally ends streaming AF at full sensor regardless of entry state.

Combined, the symptom (live image stuck at the central 50% after Autofocus reports "NO FOCUS" or shows a saturation dialog) is unreachable on a current build. Diagnostic: server log now contains `STREAM_AF:entry camera ROI = (X, Y, AxB)` at the start of every AF run and `STREAM_AF:restored camera ROI to (X, Y, AxB) [full sensor]` at the end. If those two lines disagree, the absolute anchoring has been defeated -- file an issue.

#### Q: Streaming Autofocus refuses with "metric range X% of peak is within noise"

**A:** This is the `metric_flat` refusal, raised when the focus metric varies by less than 4% of the peak across the scanned Z range AND no clean Gaussian shape is detected AND no monotonic slope is detected. Three distinct failure modes share this message:

1. **The scan window is genuinely inside one depth-of-field.** Widen the range from the Live Viewer dropdown (the dropdown now repopulates per-objective: 10x up to 100 um, 20x up to 50 um, 40x up to 20 um, 60x+ up to 10 um -- see [tools/live-viewer.md](tools/live-viewer.md)) or fall back to Sweep Focus.
2. **The stage is not actually moving at the configured slow speed.** As of 2026-05-05 (commit `a5e2b87`) the server logs `STREAM_AF:post-scan stage Z=... (expected z_end=...); achieved X% of planned Y um` after the scan loop. If `X%` is below 50% or above 150%, the warning line names the YAML key to fix: `stage.streaming_af.slow_speed_value` and `stage.streaming_af.slow_speed_um_per_s`. The same commit dumps the per-sample (t, z, metric) trace on the refusal path so you can confirm whether all samples are at one Z (stuck stage) or actually swept (featureless sample).
3. **The metric is the wrong tool for this objective / sample.** As of 2026-05-12 (commits `401f014` through `8d89d9b`), three layered edge-detection checks fire BEFORE this refusal so a peak just past the scan boundary or a clean monotonic slope across the scan gets a retry walk instead of UNAVAILABLE. If you still see `metric_flat` after those checks, the metric genuinely has no signal in this Z range -- consider changing `score_metric` per objective in `autofocus_<scope>.yml` (see "Streaming Autofocus keeps focusing on dust" below).

#### Q: Streaming Autofocus commits the wrong Z when I start far from focus

**A:** Fixed 2026-05-12 (commit `8d89d9b`). Previously, when streaming AF started 10-30 um from focus, the gaussian fit converged at the edge of the sampled samples (where `_gaussian_peak` clamps `mu` to `[z_arr.min(), z_arr.max()]`), and the algorithm committed that boundary-pinned peak instead of walking past it to find the real peak. The legacy edge-of-window check used the commanded `z_start`/`z_end` with a 10%-of-range tolerance, but `HEAD_DISCARD_MS=600` removed the first ~7 um of every scan, so a peak at the actual sampled boundary sat 7 um inside the commanded boundary and was mis-classified as interior.

The fix adds three layered checks before the success commit:
1. **mu-at-sampled-boundary**: when the gaussian fits a clean peak (R^2 >= 0.70, sigma < 0.45*span) and `mu` is within `sigma_fit` of the first/last in-motion sample's z, classify as `edge_low`/`edge_high` and walk one full range in that direction. Returns `best_z = mu_fit` as a fallback.
2. **Pearson-correlation slope detector**: when the gaussian fit is degenerate (sigma at upper bound), check Pearson r between z and metric; if `|r| >= 0.5` and amplitude >= 0.5%, classify as `edge_low`/`edge_high` per sign of r.
3. **Post-loop mu-fallback**: when retries exhaust without a `success`, commit to the best `mu` from an earlier mu-at-boundary attempt instead of UNAVAILABLE.

Diagnostic: a successful walk-and-recover logs `STREAM_AF:gaussian mu=... pinned within ... um of sampled ... boundary` on attempt 1, followed by `STREAM_AF:committed final Z=...` after attempt 2 finds the real peak. See `claude-reports/2026-05-12_streaming-af-edge-detection-layers.md` for the full design and verification protocol.

#### Q: Streaming Autofocus keeps focusing on dust / commits to a non-tissue Z

**A:** The default per-objective `score_metric` in `autofocus_<scope>.yml` is `laplacian_variance` for PPM modalities. This metric is `var(scipy.ndimage.laplace(gray))` -- variance of squared Laplace responses -- which means a handful of sharp dust pixels at a non-tissue focus plane can outvote broader tissue contrast (the squared per-pixel responses blow up for high-curvature outliers). The AF then walks toward the dust speck, not the tissue.

Workaround: change `score_metric` for the affected objective to `brenner_gradient`. Brenner is `sum((I[x+2] - I[x])^2)` -- still squared, but a directional first difference where broader tissue features contribute area-proportionally. Confirmed 2026-05-12 on OWS3 PPM 10x: laplacian_variance committed Z=-16.7 (dust) when true tissue focus was at Z=-26; switching to brenner_gradient found Z=-26 on the same FOV.

Steps:
1. Open the Autofocus Editor (Extensions > QP Scope > Autofocus Editor)
2. Select the affected objective (e.g. `LOCI_OBJECTIVE_OLYMPUS_10X_001`)
3. Change `score_metric` from `laplacian_variance` to `brenner_gradient`
4. Save and retry.

The packaged manifest's default per-modality is still `laplacian_variance`; per-rig YAML override is the current recommendation. If multiple rigs hit this, consider promoting `brenner_gradient` to the modality default (out of scope for now -- need more evidence across scopes/scenes).

#### Q: Autofocus is too slow

**A:** Reduce the number of autofocus positions:
1. Edit `autofocus_PPM.yml`
2. Increase `n_tiles` value (e.g., from 2 to 5)
3. `n_tiles=5` means autofocus every 5 tiles instead of every 2 tiles
4. Faster acquisition but less frequent focus updates

#### Q: I checked "Disable Autofocus" but the manual focus dialog still appeared

**A:** Fixed 2026-04-26. The Java side previously dropped the `--af-tiles`/`--af-steps`/`--af-range` triplet but didn't tell the server "no AF at all", so the server fell back to YAML defaults and ran AF normally (including the manual-focus prompt on the first failure). Now the Java side sends an explicit `--af-disabled` flag the server short-circuits on. If you still see manual focus prompts after this fix, confirm: (1) the QuPath build is current (commit `98edcf2` or later), (2) the server is current (commit `b5ae861` or later), (3) "Disable Autofocus" is actually checked in Preferences (Extensions > QP Scope > Preferences > "Disable All Autofocus (Danger)"). Server log should show a single `Autofocus DISABLED for this acquisition` line at workflow start.

#### Q: Time-remaining estimate is wildly off after a slow start

**A:** Fixed 2026-04-26 (commit `df1092f`). The mid-run estimator previously computed `(now - workflowStartTime) / totalTilesCompleted`, which folded everything that happened *before* the first tile finished -- pre-acquisition AF, blocking modals, manual-focus dialog wait time -- into the per-tile mean. A 30-second pause at the start could turn a 12-tile / 4 s-per-tile acquisition into "5 hours remaining" once the first tile finished. Now the estimator uses the rolling `allTileTimes` mean (already collected with tile 1 excluded for this exact reason). The first-tile fallback keeps the legacy formula because there's no sample yet, but from tile 2 onward the estimate reflects steady-state cadence only.

#### Q: "Saturation Limit Exceeded" dialog appears during acquisition (PPM)

**A:** This dialog appears when the birefringence saturation guard (PPM modalities) detects that the initial monitoring tiles are saturated. The acquisition pauses and offers two options:

**What each choice does:**

- **Continue anyway** (red button) -- Acquire despite saturation. Useful for faint signal that needs a higher exposure. This choice disables the saturation guard for the rest of this acquisition run. Saturated angles cannot be background-corrected, but the data will be acquired and stitched normally.

- **Cancel acquisition** (default button) -- Abort the acquisition as you would in the old hard-abort behavior. You can lower settings and retry.

**Why saturation happens and how to avoid it:**

The saturation guard monitors the initial tiles to detect overexposed data before committing to a full multi-angle scan. Saturation typically occurs when the White Balance Target Intensity is too high for your sample.

**To fix:**
1. Click Cancel to abort the current acquisition
2. Lower the White Balance Target Intensity in the next acquisition attempt (try reducing by 10-20 units)
3. Retry the acquisition

Alternatively, if you are confident the faint signal genuinely requires the higher exposure, click Continue anyway.

**Server compatibility:** Older versions of `microscope_command_server` (before 2026-05-22) hard-abort on saturation instead of prompting. The interactive dialog requires a matching server build. If your server is older and saturation is detected, the acquisition will abort without showing this dialog.

#### Q: Z-stack with fluorescence channels saves only one image per tile (no Z planes)

**A:** Fixed 2026-04-26 (commit `e8e3799`). The widefield IF tile path was snapping once per channel and ignoring `ctx.z_offsets` entirely, so Z-stack settings on fluorescence acquisitions silently dropped to single-plane (logs showed "264 total images" but only 24 saved when channels=2, planes=11). Now the channel loop iterates Z planes per channel, accumulates, and applies the configured projection. Output: one projected file per `<output>/<channel>/<tile>.tif`; with `--save-raw` also `<output>/<channel>/z000/`, etc.

#### Q: Z-stack progress dialog shows 34/12 positions

**A:** Fixed 2026-04-26 (commit `e86ae06`). `AcquisitionManager.monitorAcquisition` was computing `stepsPerPosition` as `max(channels, angles)` and ignoring Z-planes entirely. With FITC + Cy5 + 11 Z-planes the server reported 264 progress increments but Java divided by 2 instead of 22, overshooting the 12-position denominator. Now the multiplier includes `ceil(zRange/zStep) + 1` when Z-stack is enabled, matching the existing logic in `BoundedAcquisitionWorkflow`.

### Micro-Manager MDA Export

See [UTILITIES.md -- Exporting to Micro-Manager MDA](UTILITIES.md#exporting-to-micro-manager-mda) for the full feature overview.

#### Q: MM MDA file loaded, but autofocus fails to run.

**A:** Expected. QPSC ran its own per-tile server-side streaming autofocus during the original acquisition, so the exported MDA writes `useAutofocus: false` to keep MM's `AutofocusManager` from fighting that focus. If you want MM to autofocus when re-running the plan, re-tick the **Autofocus** checkbox in the MDA window and pick an MM AF method.

#### Q: Stage Position List "Load..." button shows the .pos file but positions don't appear.

**A:** The `.pos` file references stage devices by name. Confirm the `mm_stage_devices:` block in the microscope YAML (`xy_stage` and `z_stage`) names the same device labels as your MM hardware config (default `XYStage` and `ZStage`). If they differ, edit the YAML and re-export, or hand-edit the `stageName` entries in `MDA_<region>.pos` to match the MM device labels. If the YAML block was omitted, the writer falls back to `("XYStage", "ZStage")` and logs a WARN to that effect.

#### Q: Live progress shows "Dimension counters out of sync".

**A:** The client-side decomposer detected a tile index past its expected total, so it cannot map the index back to a single channel / Z / position. The aggregate progress bar is still accurate. Check the latest WARN log line (it carries `k`, the plan counts, and the observed indices) and report if this is reproducible -- it most likely indicates the server's per-position loop order changed and `LiveDimensionDecomposer` needs an update. See [SOCKET_PROTOCOL.md -- Client-side dimension inference](developer/SOCKET_PROTOCOL.md#client-side-dimension-inference-no-protocol-change).

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

#### Q: Fluorescence existing-image acquisition is rotated 180 degrees relative to its parent brightfield image

**A:** Fixed 2026-04-26 (commit `d94ea94`). The channel-merge import path (`StitchingHelper.importMergedImageOnly`) was passing `metadata.flipX, metadata.flipY` to `addImageToProjectWithMetadata`, but the per-channel pyramids were already written with the stitcher's `flipStitchingX/Y` baked in. The result was a second `TransformedServerBuilder` flip on top of the already-flipped merged file -- a 180° net rotation on any scope whose `stitcherFlipFlags` are non-trivial (e.g. OWS3 with inverted stage polarity). The BF single-pass path was always correct because it imported with `false, false`. Now both paths agree. If you still see rotated FL output after the fix, check that you're on commit `d94ea94` or later and re-run the affected acquisition.

#### Q: Camera Control preset Load fails with `ERR_ILLM` after a different-modality acquisition

**A:** Fixed 2026-04-26 (commit `d88fdd8`). The preset format previously stored `exp|gain|illum` with no modality binding, so loading a Brightfield preset after a Fluorescence acquisition routed power calibrated for DiaLamp into the Epi LED (which raised in `set_power` and surfaced as `ERR_ILLM`). The new format prepends the active profile name (`profile|exp|gain|illum`) and the Load path runs `APPLYPR` for the saved profile *before* `SETCAM`/`SETILLM`. Legacy presets still load (the leading segment is detected by whether it parses as a float) and auto-resolve to the first profile matching the section's modality.

#### Q: Camera Control "Set intensity" field accepted any number but Apply gave `Cannot set property "State" to "1.000000"`

**A:** Fixed 2026-04-26 (commits `f5be41e` / `e010910` / `698e487`). Some MM device adapters expose only an enumerated `State` property with discrete values like `"0"` / `"1"` and no continuous intensity knob. Configs declare these by pointing `state_property` and `intensity_property` at the same MM property name. The old `set_power` flow wrote `State="1"` (string, OK) then `State=1.0` (float, serialized by pycromanager as `"1.000000"`) which MM rejected. Now `DevicePropertyIllumination._is_binary()` detects this case and skips the float intensity write entirely. The Camera Control dialog also reads `value_type` from `GETCAP` and renders a checkbox instead of a TextField for binary sources, so users can't type invalid values in the first place.

#### Q: Stitching fails - "TileConfiguration.txt not found"

**A:** The tile coordinate file is missing:
1. Check the temp tiles folder still exists (QuPath might have cleaned it up early)
2. Look for Python server errors during acquisition (acquisition might have failed silently)
3. Verify Python server completed acquisition successfully before stitching started

#### Q: Stitching Recovery says "could not be stitched even after retries and the ZARR fallback" - what does this mean?

**A:** The base (full-resolution) image wrote successfully, but tile writes failed at one or more of the downsampled pyramid levels. When this happens, the Stitching Recovery workflow **automatically retries** the stitching (up to 3 times for OME-TIFF format). If retries fail, the system **automatically escalates to OME_TIFF_VIA_ZARR** format (writes to ZARR, then queues a background conversion to OME-TIFF). If both retry and escalation fail, this error appears.

**Why it happens (root cause):**
This is a QuPath / OMEPyramidWriter bug, not a disk-space or permissions problem. At pyramid levels whose dimensions are not a clean multiple of the tile size (512 px), the writer's internal tile-iteration count disagrees with the per-level dimensions stored in the underlying TiffWriter. Tiles get queued past the right or bottom edge, the TiffWriter rejects them with `FormatException: X:1024 must be < 854` (or similar), and the writer occasionally NPEs on `this.initialized` when multiple resolution levels' tile-write workers race. The base level wrote OK, hence "image opens"; the upper levels did not, hence "upper levels are black."

**What you'll see:**
- **First tile-write error detected:** A push notification alerts you that tile-write errors occurred. During normal post-acquisition stitching a non-blocking dialog also offers you the choice to switch the remaining attempts to the ZARR format (which avoids the problematic `OMEPyramidWriter` code path). Stitching Recovery runs non-interactively -- it auto-retries and escalates without a prompt.
- **Auto-retry:** The system automatically retries the stitching. Tiles are preserved between attempts, so re-tries are cheap.
- **Auto-escalation:** If OME-TIFF retries all fail, the system automatically switches to OME_TIFF_VIA_ZARR (writes to ZARR, queues background TIFF conversion).
- **Final failure dialog:** If all retries and escalation fail: "N failed angles could not be stitched even after retries and the ZARR fallback and were NOT imported"
- **Log (Scripting > Show log)** contains `Error writing Tile: level=N, bounds=(...)` entries from `qupath.lib.images.writers.ome.OMEPyramidWriter`, with cause `FormatException: ... must be <` or `NullPointerException: ... this.initialized is null`

**How to recover:**
1. The tiles in `TempTiles/` are preserved. Since automatic retry and escalation have already been attempted, re-running Stitching Recovery with a different output format (e.g., **OME-ZARR** if the previous attempt used OME-TIFF) may help if the failure was transient.
2. If OME-ZARR also fails, the issue may be environmental (e.g., disk space, file permissions, or a corrupted tile set). Check your disk space and verify the tiles folder is readable.
3. As a last resort, re-acquire the region. The bug is in the OME-TIFF pyramid writer, not the acquisition.

The underlying writer issue is in QuPath core (`qupath.lib.images.writers.ome.OMEPyramidWriter`) and is being tracked there; the recovery workflow's job here is to make sure broken outputs never silently land in the project.

#### Q: Stitching failed but my tiles were acquired - how do I re-stitch?

**A:** This is fully recoverable. Your tiles and TileConfiguration.txt files are preserved in the TempTiles folder after a stitching failure.

**Recommended method - QPSC Re-stitch utility (adds images to project):**

1. Open the QuPath project where you want the stitched images
2. Go to **Extensions > QPSC > Utilities > Re-stitch Tiles**
3. Browse to your tile directory (the folder containing angle subdirectories or TileConfiguration.txt)
4. Verify the pixel size (auto-populated from your microscope config)
5. Select **Output format**: OME_TIFF (standard), OME_ZARR (faster, directory format), or OME_TIFF_VIA_ZARR (ZARR speed with automatic TIFF conversion)
6. Select **Compression**: available options are filtered by format. TIFF allows all compression types (LZW, JPEG, J2K, zstd, etc.), while ZARR-based formats are restricted to LZW, ZLIB, Uncompressed, and Default
7. For **Matching String**: use `"."` to stitch all subdirectories, or a specific angle like `"0.0"`
8. Click **Stitch & Import**

The stitched images will be created in `<projectDir>/SlideImages` (project-anchored, matching the regular acquisition path) and automatically imported into your project. Filenames follow your configured naming pattern (respecting Objective, Annotation, Angle, and other preferences).

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

#### Q: Acquisition Wizard shows an orange "Background lamp ... != profile ..." warning

**A:** The background images were collected at a lamp (illumination) intensity
that no longer matches the active acquisition profile's `illumination_intensity`.
Flat-field division would then correct for the wrong illumination level.

This is a **non-blocking warning** -- the Background step turns orange and the
pre-acquisition confirmation lists it, but you can still proceed. To clear it:
- Re-collect backgrounds (Extensions > QPSC > Utilities > Background Collection)
  with the current profile selected, **or**
- Restore the profile's `illumination_intensity` to the value the backgrounds
  were collected at.

Lamp intensity is sourced from the acquisition profile and is shown read-only in
the Background Collection dialog. Scopes with no adjustable lamp (e.g. PPM) never
show this warning -- there is nothing to be inconsistent.

#### Q: Fluorescence Background step says "Missing backgrounds for channel(s): ..."

**A:** A fluorescence profile collects one background per **in-use** channel
(keyed by profile). A channel is in use when it has a non-zero exposure and a
non-zero illumination intensity; channels with 0 intensity are treated as unused
and need no background. The warning means an in-use channel has no background
image yet. Re-run Background Collection with that profile selected -- the
per-channel table in the dialog shows which channels will be collected and which
are skipped as unused.

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
- QuPath automatically deletes temp tiles after successful stitching (or zips them if you selected that preference)
- **On acquisition/stitching failure, tiles are always deleted** regardless of your tile-handling preference (see PREFERENCES > Tile Handling Method). This prevents incomplete/corrupt tile sets from accumulating. Tile recovery is only possible if you selected "Zip" in preferences -- the zip archive will be created even on error
- You can manually delete TempTiles folder after confirming stitched images are correct
- To keep tiles for re-stitching on a successful run, set your preference to "None" (keep tiles) or "Zip" (compress them)

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

#### Q: "Sub-image Acquired on a Different Microscope -- Workflow Cancelled"

**A:** This error appears when you try to acquire from a sub-image using the Existing Image workflow, but the sub-image was acquired on a different microscope than the one currently active.

**Why this matters:** Sub-images are acquired at a specific stage position on a specific microscope. When you use the Existing Image workflow, the sub-image's `xy_offset` metadata is interpreted as stage coordinates **on the acquiring microscope's stage frame**. If you try to use those same coordinates on a different microscope, the stage will move to the wrong physical location.

**What you see:**
- A dialog explaining the problem
- The name of the microscope that acquired the sub-image
- The name of the currently active microscope
- Two options to fix it

**To fix:**

**Option 1 (Recommended if possible):** Open the sub-image on the microscope that originally acquired it
1. Switch Micro-Manager or the scope selector to the original acquiring microscope
2. Restart QuPath or reconnect to the microscope
3. Open the sub-image entry
4. Run Acquire from Existing Image on that microscope

**Option 2 (More flexible):** Open the parent macro entry instead
1. Find and open the parent macro image (usually the overview image that contains this sub-image)
2. Draw a new annotation on the parent for the region you want
3. Run Acquire from Existing Image on the parent
4. The cross-scope alignment path will compose a fresh transform for the current microscope

**When this constraint applies:**
- You are using **Acquire from Existing Image** on a sub-image (an image that was created from a prior Bounded Acquisition or Acquire from Existing Image workflow)
- The active microscope name differs from the one that acquired the sub-image
- The system blocks the acquisition to prevent silent stage position errors

**Legacy note:** Sub-images acquired before 2026-05-14 may not have the microscope-name metadata. The system falls back to parsing the microscope name from the derived alignment JSON filename. If neither source exists, the gate logs a warning and proceeds (preserving pre-2026-05-14 behavior).

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

#### "Microscope server is not responding" (accepts connections but not responding to commands)

**Meaning:** The Python microscope server is accepting TCP connections but not responding to configuration or workflow commands. New operations will hang, but existing Live Viewer connections may still work.

**Likely causes:**
- A stuck server connection thread, usually from a streaming autofocus abort that left an operation mid-flight (most common)
- True MicroManager crash or loss of camera connection (less common)
- Server process hung due to unresponsive hardware

**Solution (try in order):**
1. **Restart the Python microscope server first** - This resolves the stuck-thread issue in most cases and is faster than restarting MicroManager
   - Stop the server process
   - Start it again (e.g., `python microscope_command_server/server.py`)
   - Click **Retry** on the dialog once the server is back up
2. **If Python server restart doesn't help, also restart MicroManager**
   - Close MicroManager completely
   - Reload your hardware configuration
   - Verify Live Viewer shows frames again
   - Click **Retry** on the dialog

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

#### "Target position is outside stage limits" -- every Stage Map move rejected

**Meaning:** The bounds check rejects *every* position, not just out-of-range ones.

**Cause:** `stage.limits` in the microscope YAML must use **MicroManager
coordinates** -- the values MicroManager's `getXYPosition` / Z reports -- not
the microscope's own stage readout. On some scopes (e.g. OWS3) MicroManager's
frame is mirrored relative to the physical stage. If `x_um.low` is *greater*
than `x_um.high` (or the same for `y`/`z`), older builds rejected all moves
because the check was a literal `pos >= low && pos <= high`.

**Solution:**
1. Make sure `stage.limits` values are MicroManager coordinates.
2. Prefer `low` = the most negative MM coordinate, `high` = the most positive.
3. Builds from 2026-05-22 onward normalize `low`/`high` with min/max, so
   either ordering works -- update the extension if you cannot reorder them.

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

### Stage Map

**Problem:** Cursor lands outside the green box on the macro overlay even when the stage is inside the alignment annotation; cursor appears mirrored from the actual physical position.

**Check:**
1. Alignment 3-tile prediction was perfect when you ran `Microscope Alignment` (sanity check that the underlying transform is correct).
2. Open the QuPath log (Help -> Show Log) and watch the Stage Map render lines:
   - `Macro overlay (anchor-based): macro pixel (...) at screen (...); sign=(sX=..., sY=...) m00=... m11=... dirX=... dirY=...` -- when this fires the Stage Map is using the alignment build anchor, not corner extrapolation.
   - `Macro overlay anchor: preset has no anchor metadata (legacy preset) -- 4-corner placement will be used` -- the saved preset predates anchor metadata; re-run `Microscope Alignment` to regenerate it.

**Solutions:**

- **Cursor and green box agree at one point but disagree elsewhere:** the saved preset is on the legacy 4-corner path. Re-run `Microscope Alignment` -- the new preset will have anchor metadata and the Stage Map will pin on the build point.
- **Saved preset isn't picked up after running alignment without clicking Reload:** older builds (before commit `2c5d7c1`, 2026-05-01) cached `activePreset` in memory. Pull the latest extension; the post-save image-change handler now re-resolves from disk.
- **Macro overlay Y orientation disagrees with the Live Viewer:** known cosmetic mismatch on instruments without an explicit `slide_holder` / `inserts:` block in the YAML. The cursor positions correctly across the slide, but the macro IMAGE itself may render Y-mirrored from what the camera shows. This does not affect alignment correctness or stage moves; it's a display-only discrepancy between the scanner's macro Y direction and the camera's Y direction. Adding a measured `inserts:` block to the YAML lets `fromConfigMap` detect axis inversion from aperture corners and resolves it.

**Background:** Three things must agree on stage Y direction for an instrument's overlay to look right: (1) hardware stage polarity (`stageInvertedY` preference -- which way the stage moves on positive commands); (2) macro image display orientation (driven by the slide-holder `inserts:` block when measured, otherwise unspecified and assumed normal); (3) the saved `macro->stage` alignment transform. The cursor uses (1)+(3) and is therefore correct regardless of (2). The macro image rendering uses (2). When the YAML has no measured `inserts:` block, (2) defaults to "no flip" while the actual scanner Y direction may differ -- producing a cosmetic Y mirror in the macro image while cursor and stage moves remain correct. Adding a measured `inserts:` block lets the system detect axis inversion from aperture corners and resolves the cosmetic mismatch.

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
**Last Updated:** May 10, 2026
**GitHub:** https://github.com/uw-loci/qupath-extension-qpsc
