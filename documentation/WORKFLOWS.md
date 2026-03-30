# QPSC Data Collection Workflows

QPSC connects QuPath to your microscope via Pycro-Manager and Micro-Manager. You draw annotations or define coordinates in QuPath, and QPSC moves the stage, captures tiles, stitches them, and imports the result back into your project -- all without leaving QuPath. This page helps you choose the right workflow and understand how the tools fit together. For installation instructions, see the [main README](../README.md).

> **First time?** See the **[Quick Start Guide](QUICKSTART-BF.md)** to get your first image in 15 minutes.

---

## Quick Reference

| I want to... | Workflow | Menu Path |
|--------------|----------|-----------|
| Scan a rectangular region by stage coordinates | [Bounded Acquisition](#workflow-1-bounded-acquisition) | Extensions -> QP Scope -> Bounded Acquisition |
| Acquire high-res images of annotated regions on an overview slide | [Acquire from Existing Image](#workflow-2-acquire-from-existing-image) | Extensions -> QP Scope -> Acquire from Existing Image |
| Calibrate the coordinate link between a scanner image and the microscope | [Microscope Alignment](#workflow-3-microscope-alignment) | Extensions -> QP Scope -> Utilities -> Microscope Alignment |
| Get guided help through the full setup-to-acquisition process | [Acquisition Wizard](#acquisition-wizard) | Extensions -> QP Scope -> Acquisition Wizard... |

---

## Before You Begin

### Platform Requirements

QPSC requires **Windows 10+** for microscope control. Most microscope hardware drivers (stages, cameras, rotation stages) are Windows-only, and Micro-Manager's device adapter ecosystem is most complete on Windows. Development and testing are done on Windows; Linux and macOS are not supported for acquisition.

### Startup Order (Every Session)

The three components must be started in this order:

| Step | Application | What to Do |
|------|-------------|------------|
| 1 | **Micro-Manager** | Launch and load your hardware configuration. Verify the camera and stage respond. |
| 2 | **Python Server** | Run `start_server.bat` (or `python -m microscope_command_server.server.qp_server`). Wait for "Server ready." |
| 3 | **QuPath** | Launch QuPath. The QP Scope menu will auto-connect if enabled in preferences. |

> **Tip:** If you installed QPSC with the [PPM-QuPath.ps1 installer](https://github.com/uw-loci/QPSC), it creates a `Launch-QPSC.ps1` script in your install directory that starts the Python server (and optionally QuPath) with package verification. You still need to start Micro-Manager manually first.
>
> For a simpler approach, create desktop shortcuts for all three applications arranged left-to-right in startup order. The `start_server.bat` script (in the microscope_command_server folder) activates the virtual environment and launches the server -- ideal as a desktop shortcut.

### First-Time Setup Checklist

If this is your first time, complete these additional steps. The **Acquisition Wizard** (Extensions -> QP Scope -> Acquisition Wizard...) checks each prerequisite and launches the right tool for you.

1. **Install QPSC and its dependencies.**
   The extension JAR, the tiles-to-pyramid extension JAR, and the Python microscope server must all be installed. See the [main README](../README.md) and [Installation Guide](INSTALLATION.md) for details.

2. **Create a microscope configuration file.**
   The **Setup Wizard** appears automatically on first launch and walks you through hardware selection, pixel size calibration, and server connection. See [Setup Wizard docs](tools/setup-wizard.md).

3. **Collect background images.**
   Used for flat-field correction. See [Background Collection docs](tools/background-collection.md).

4. **Calibrate white balance (JAI cameras only).**
   If using a JAI 3-CCD prism camera. See [White Balance Calibration docs](tools/white-balance-calibration.md).

5. **Configure autofocus (recommended).**
   Set search range, step size, and scoring method per objective. See [Autofocus Editor docs](tools/autofocus-editor.md).

---

## Workflow 1: Bounded Acquisition

### What It Does

Bounded Acquisition scans a rectangular region of the slide defined by stage coordinates. QPSC creates a tile grid over the region, acquires every tile through the microscope, stitches them into a single high-resolution image, and adds that image to a QuPath project. Everything is configured in a single dialog.

### When You Need It

- You want to scan a region and do not have an existing overview/macro image of the slide.
- You know the approximate stage coordinates of the area you want (or you can read them from the Live Viewer or Stage Map).
- You are setting up a new sample and want a quick initial scan.

See [full documentation](tools/bounded-acquisition.md) for step-by-step instructions, all options, and troubleshooting.

---

## Workflow 2: Acquire from Existing Image

### What It Does

This workflow lets you target specific regions on an existing macro or overview image for high-resolution acquisition. You draw annotations on the overview image in QuPath, and QPSC transforms those annotation coordinates into physical stage positions, acquires tiles covering each annotated region, stitches them, and adds the results to your project.

### When You Need It

- You have a macro or overview image of your slide (from a slide scanner, low-magnification scan, or previous Bounded Acquisition).
- You want to acquire specific regions at higher magnification.
- You want to use tissue detection or manual annotations to define what to scan.

### Variations

**No annotations on the image:** If you start the workflow without annotations, QPSC offers three options: run automatic tissue detection (artifact-aware filtering is applied by default -- see [Preferences](PREFERENCES.md#tissue-detection-parameters) for tuning), draw annotations manually, or cancel.

**First-time alignment:** If no saved transform exists, the workflow prompts you to create one via the Microscope Alignment process. Once saved, the workflow continues from where it left off.

**Single-tile refinement:** When selected, QPSC acquires one reference tile and shows you the result so you can verify alignment before scanning all annotations. This catches minor drift without a full re-alignment.

See [full documentation](tools/existing-image-acquisition.md) for step-by-step instructions, all options, and troubleshooting.

---

## Workflow 3: Microscope Alignment

### What It Does

Microscope Alignment creates a coordinate transformation (affine transform) that maps pixel positions in your overview/macro image to physical stage positions on the microscope. Without it, the Existing Image workflow cannot navigate the stage to the correct locations.

### When You Need It

- **First time with a new scanner/microscope combination.** Each slide scanner produces images with different coordinate systems.
- **After hardware changes.** If the microscope stage, scanner, or optical path has been modified.
- **When acquired images do not line up with annotations.** This indicates the saved transform is no longer accurate.

You do *not* need to re-run alignment every time you load a new slide from the same scanner, as long as the slides are loaded consistently and the hardware has not changed.

### SIFT Auto-Alignment

During refinement, an **Auto-Align (SIFT)** button can automatically match the microscope view to the WSI tile using feature detection, eliminating the need for manual stage adjustment. This works best on tissue with visible structural features and handles different pixel sizes between the WSI and microscope automatically. Falls back to manual alignment if SIFT cannot find enough matching features.

See [full documentation](tools/microscope-alignment.md) for step-by-step instructions, point distribution guidelines, flip/invert settings, and troubleshooting.

---

## Calibration & Setup Tools

Configure these before your first acquisition. Most only need to be run once (or when hardware changes). Click each link for full documentation.

| Tool | When to Run | Docs |
|------|-------------|------|
| **Acquisition Wizard** | Use for guided setup -- checks all prerequisites and launches the right tools. | [Acquisition Wizard](tools/acquisition-wizard.md) |
| **Communication Settings** | Configure and test the socket connection to the microscope server. Set up push notifications for long acquisitions. | [Communication Settings](tools/server-connection.md) |
| **Background Collection** | Collect flat-field correction images. Run after changing objectives, detectors, or illumination. | [Background Collection](tools/background-collection.md) |
| **White Balance Calibration** | Calibrate per-channel R/G/B exposures for JAI 3-CCD cameras. Run before background collection. | [White Balance Calibration](tools/white-balance-calibration.md) |
| **Autofocus Editor** | Configure per-objective autofocus parameters (search range, step count, frequency). | [Autofocus Editor](tools/autofocus-editor.md) |
| **Setup Wizard** | First-time microscope configuration. Creates YAML config files. Appears automatically when no config is found. | [Setup Wizard](tools/setup-wizard.md) |

---

## Utility Tools

These tools help you monitor and control the microscope during your work session.

| Tool | What It Does | Docs |
|------|-------------|------|
| **Live Viewer** | Real-time camera feed with stage controls, histogram, and noise statistics. Use to navigate, verify focus, and check exposure. | [Live Viewer](tools/live-viewer.md) |
| **Stage Map** | Bird's-eye view of the stage insert showing slide positions and current objective location. Double-click to navigate. | [Stage Map](tools/stage-map.md) |
| **Camera Control** | View and test camera exposure/gain settings from calibration profiles. Testing only -- changes are not saved to YAML. | [Camera Control](tools/camera-control.md) |
| **Stitching Recovery** | Re-run stitching on previously acquired tiles if stitching failed. | -- |
| **[Propagation Manager](tools/propagation-manager.md)** | Transfer annotations/detections between base images and sub-images. Supports forward (base->sub) and back (sub->base) with base variant selection. | [Guide](tools/propagation-manager.md) |
| **[Z-Stack / Time-Lapse](tools/z-stack-timelapse.md)** | Single-tile Z-stack or time-lapse acquisition at the current stage position. | [Guide](tools/z-stack-timelapse.md) |

---

## Putting It All Together: Typical Session

A typical data collection session looks like this:

1. **Start the microscope server** on the microscope computer.
2. **Open QuPath** and verify the connection via the Acquisition Wizard or Communication Settings.
3. **Load your slide** on the microscope stage.
4. **Quick look around** -- Open the Live Viewer to navigate the slide and find the area of interest. Use the Stage Map for orientation.
5. **Choose your workflow:**
   - If you have an overview/macro image -> [Acquire from Existing Image](#workflow-2-acquire-from-existing-image)
   - If you want to scan by coordinates -> [Bounded Acquisition](#workflow-1-bounded-acquisition)
6. **Run the acquisition.** Configure the dialog, click OK, and monitor the progress bar.
7. **Review results** in the QuPath project. Inspect stitched images, overlay with annotations, and proceed to analysis.

---

## See Also

- [Utilities Reference](UTILITIES.md) -- All tools with full option documentation
- [Preferences Reference](PREFERENCES.md) -- Every setting explained
- [Troubleshooting Guide](TROUBLESHOOTING.md) -- Common issues and solutions
- [Main README](../README.md) -- Installation and system overview
