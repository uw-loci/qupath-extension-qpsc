# QPSC Workflows Guide

This document provides an overview of all QPSC acquisition workflows. Click any workflow name for full step-by-step documentation including all dialog options, screenshots, and troubleshooting.

---

## First-Time Setup

**New to QPSC?** Before running any acquisition workflow, you need a valid microscope configuration. Use the **[Setup Wizard](tools/setup-wizard.md)** -- it appears automatically when no configuration is found and creates all required YAML files step by step. Once configured, proceed to the workflows below.

---

## Table of Contents

1. [Bounding Box Acquisition](#bounding-box-acquisition)
2. [Existing Image Acquisition](#existing-image-acquisition)
3. [Microscope Alignment](#microscope-alignment)

---

## Bounding Box Acquisition

**[Full Documentation](tools/bounded-acquisition.md)**

**Menu:** Extensions > QP Scope > Bounded Acquisition

Acquire high-resolution images by defining stage coordinates directly. Creates a new QuPath project and acquires a rectangular region of the slide.

**When to Use:**
- Starting fresh without an existing overview image
- You know the stage coordinates of the region you want
- Setting up a new sample for imaging

**Prerequisites:**
- Python microscope server running
- Microscope hardware initialized in Micro-Manager
- Valid microscope configuration file loaded
- Background images collected (recommended)

**Workflow Steps:**
1. **Unified Acquisition Dialog** - Configure sample name, hardware (modality/objective/detector), acquisition region (start+size or two-corner mode), white balance mode, and angle overrides. Real-time preview shows tile grid, estimated time, and storage.
2. **Acquisition Progress** - Stage moves to each tile position, captures images (rotating through angles for PPM), runs periodic autofocus.
3. **Stitching** - Tiles are automatically aligned and assembled into a pyramidal OME-TIFF or OME-ZARR image.
4. **Completion** - Stitched image is added to the QuPath project with full acquisition metadata.

| Common Issue | Solution |
|-------|----------|
| "Connection refused" | Start Python server |
| Stage doesn't move | Check Micro-Manager hardware initialization |
| Black images | Check illumination and exposure settings |
| Stitching fails | Verify sufficient disk space |

---

## Existing Image Acquisition

**[Full Documentation](tools/existing-image-acquisition.md)**

**Menu:** Extensions > QP Scope > Acquire from Existing Image

Acquire high-resolution images of specific regions annotated on an existing macro/overview image. Annotations drawn on the macro image define the regions to scan at high resolution.

**When to Use:**
- You have a macro/overview image of your slide
- You want to acquire specific regions of interest at higher magnification
- Adding acquisitions to an existing project

**Prerequisites:**
- QuPath project open with a macro image
- Annotations drawn on the image defining regions to acquire
- Valid coordinate alignment between image and stage
- Python microscope server running

**Workflow Steps:**
1. **Sample Setup** - Name the acquisition and select or create a project.
2. **Alignment Selection** - Choose a saved coordinate transform or create a new one.
3. **Refinement Options** - Select no refinement (fastest), single-tile refinement (quick verify), or full manual alignment (most accurate).
4. **Annotation Class Selection** - Choose which annotation classes to include.
5. **Hardware Configuration** - Select modality, objective, detector, and angle overrides.
6. **Acquisition Progress** - Each annotation is acquired, stitched, and added to the project.
7. **Completion** - All acquired images include metadata linking back to the parent image.

| Common Issue | Solution |
|-------|----------|
| "No annotations found" | Draw annotations on the image before starting |
| "Transform validation failed" | Run refinement or create new transform |
| Images appear shifted | Check flip/invert settings in preferences |
| "Pixel size not set" | Set image pixel size in QuPath |

---

## Microscope Alignment

**[Full Documentation](tools/microscope-alignment.md)**

**Menu:** Extensions > QP Scope > Microscope Alignment

Create or update the coordinate transformation between QuPath image coordinates and physical microscope stage positions. Required for accurate positioning when using Existing Image Acquisition.

**When to Use:**
- First time setting up a slide/scanner combination
- After hardware changes affecting positioning
- When acquired images don't align with annotations

**Prerequisites:**
- Macro image loaded in QuPath with pixel size calibration
- Microscope server connected
- Stage can move to known positions

**Workflow Steps:**
1. **Macro Image Selection** - Select the image to use for alignment.
2. **Point Marking** - Click on recognizable features in QuPath, then navigate the stage to the same physical location. Record at least 3 matched point pairs spread across the image.
3. **Transform Calculation** - Compute best-fit affine transformation with error statistics (mean error, max error, R-squared).
4. **Validation** - Test the transform by navigating to a validation point and verifying visual alignment.
5. **Save Transform** - Name and save the transform for reuse in Existing Image workflows.

**Understanding Flip and Invert Settings:**
- **Image Flipping** (optical property): Corrects for optical inversions in the light path
- **Stage Inversion** (coordinate direction): Corrects for stage coordinate conventions
- These are independent settings -- see [full documentation](tools/microscope-alignment.md) for details.

| Common Issue | Solution |
|-------|----------|
| Large mean error | Re-mark points with more precision |
| Stage moves wrong direction | Toggle Invert X or Y in preferences |
| Image appears mirrored | Toggle Flip X or Y in preferences |
| Points don't form consistent pattern | Verify each point pair matches correctly |

---

## See Also

- [Utilities Reference](UTILITIES.md) - All utilities with options explained
- [Preferences Reference](PREFERENCES.md) - All settings explained
- [Troubleshooting Guide](TROUBLESHOOTING.md) - Common issues and solutions
