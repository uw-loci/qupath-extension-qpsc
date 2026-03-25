# Quick Start: Your First Acquisition

> Get from "everything is installed" to "I have my first scanned image" in 15 minutes.

> [Back to README](../README.md) | [Workflows Guide](WORKFLOWS.md)

---

## Step 1: Start the Software (2 minutes)

QPSC uses three programs that work together. Start them in this order:

| Order | Program | What to Do |
|-------|---------|------------|
| 1st | **Micro-Manager** | Launch it and load your hardware configuration. Make sure the camera and stage are recognized. |
| 2nd | **Python Server** | Run `start_server.bat` (or `Launch-QPSC.ps1` if your system has it). Wait until you see "Server ready." in the console window. |
| 3rd | **QuPath** | Open QuPath. The "QP Scope" menu should appear in the menu bar. |

**Why three programs?** Micro-Manager talks to your microscope hardware. The Python server translates between QuPath and Micro-Manager. QuPath is where you control everything and view results.

> **Tip:** If someone set up your system with the PPM-QuPath installer, look for a `Launch-QPSC.ps1` shortcut that starts the server and QuPath together. You still need to open Micro-Manager first.

---

## Step 2: Verify Your Setup with the Live Viewer (2 minutes)

Open the Live Viewer to confirm everything is connected:

**Menu: Extensions -> QP Scope -> Acquisition Wizard...**

The Acquisition Wizard opens the Live Viewer and Stage Map automatically. You can also open the Live Viewer directly from Extensions -> QP Scope -> Live Viewer.

![Live Viewer window](images/Docs_LiveViewer.png)

**What you should see:** A live camera feed that updates several times per second. If illumination is on and a slide is loaded, you should see an image of your sample.

**Quick checks:**
- Click the arrow buttons in the Movement tab -- the image should shift as the stage moves.
- Scroll the Z control up and down -- the image should go in and out of focus.

**If something is wrong:**

| You see... | Check this |
|------------|------------|
| Black screen | Is the lamp turned on? Is Micro-Manager running? |
| "Connection refused" | Start the Python server (Step 1, row 2) |
| Nothing moves | Is the stage initialized in Micro-Manager? |

---

## Step 3: Navigate to Your Sample (2 minutes)

Now find the area you want to image:

- **Stage Map:** If a macro image is available, the Stage Map window shows a bird's-eye view of your slide. Double-click a spot to move there.
- **Arrow buttons:** Use the arrow buttons in the Live Viewer to pan across the slide. Pick a step size from the FOV dropdown (start with 1 FOV for big moves, then switch to 1/4 FOV for fine positioning).
- **Focus:** Use the Z controls to bring your sample into sharp focus.

When you can see the tissue you want to scan, you are ready for the next step.

---

## Step 4: Run a Small Bounded Acquisition (5 minutes)

This is the simplest acquisition workflow -- it scans a rectangular area around your current position.

**Menu: Extensions -> QP Scope -> Bounded Acquisition**

![Bounded Acquisition dialog](images/Docs_BoundedAcquisition.png)

1. **Sample Name:** Type a name (e.g., "TestScan01"). Letters, numbers, underscores, and hyphens only.
2. **Hardware dropdowns:** Modality, Objective, and Detector should be pre-filled from your configuration. Leave them as-is for your first test.
3. **Set the center position:** Click **"Use Current Position as Center"** -- this grabs the stage coordinates from where you navigated in the Live Viewer.
4. **Set the region size:** Enter a small area for your first test. Try **Width: 1000** and **Height: 1000** (in micrometers). This produces roughly a 3x3 grid of tiles (small overlapping images that get stitched together).
5. **Check the preview:** The Acquisition Preview panel at the bottom shows tile count, estimated time, and storage. For a 1000x1000 um test, this should be fast.
6. **Click Start Acquisition.**

A progress dialog appears showing the current tile, a progress bar, and elapsed time. The stage moves to each tile position, captures an image, and moves on.

![Acquisition progress](images/Docs_AcquisitionWorkflowProgress.png)

After all tiles are captured, stitching runs automatically. Do not interact with QuPath during stitching -- it typically takes under a minute for a small scan.

---

## Step 5: View Your Result (1 minute)

When stitching finishes, the image opens automatically in your QuPath project. You can:

- **Zoom in** to see full-resolution detail.
- **Draw annotations** to mark regions of interest.
- **Measure features** using QuPath's built-in analysis tools.

For a larger acquisition, repeat Step 4 with a bigger region (e.g., 5000 x 5000 um). The Acquisition Preview will show you how many tiles and how long it will take before you commit.

---

## What's Next?

Now that you have your first image, explore these guides for more advanced workflows:

- **[Workflows Guide](WORKFLOWS.md)** -- Choose between Bounded, Existing Image, and Alignment workflows
- **[Autofocus Editor](tools/autofocus-editor.md)** -- Tune focus quality for your objectives
- **[Background Collection](tools/background-collection.md)** -- Flat-field correction for even illumination
- **[White Balance Calibration](tools/white-balance-calibration.md)** -- Color calibration for JAI cameras

---

## Troubleshooting

| Problem | Quick Fix |
|---------|-----------|
| Black screen in Live Viewer | Check that Micro-Manager is running and the lamp is on |
| Stage does not move | Verify hardware is initialized in Micro-Manager |
| "Connection refused" error | Start the Python server before opening QuPath |
| Stitching fails | Check disk space and output directory permissions |
| Focus looks wrong | Open the [Autofocus Editor](tools/autofocus-editor.md) and adjust the search range for your objective |

For more help, see the full [Troubleshooting Guide](TROUBLESHOOTING.md).
