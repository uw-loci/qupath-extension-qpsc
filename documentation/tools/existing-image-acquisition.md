# Existing Image Acquisition

> Menu: Extensions > QP Scope > Acquire from Existing Image
> [Back to README](../../README.md) | [All Tools](../UTILITIES.md) | [All Workflows](../WORKFLOWS.md)

## Purpose

Acquire high-resolution images of specific regions annotated on an existing macro/overview image. Annotations drawn in QuPath define the regions to scan, and a coordinate transform maps image positions to physical stage positions. Use this when you have a macro image of your slide and want to acquire targeted regions of interest.

The acquisition dialog consolidates all options in a single scrollable panel. You may need to scroll to see all options on smaller screens.

![Existing Image Acquisition dialog -- top section](../images/Docs_ExistingImage_ConsolidatedDialog.png)

![Annotation class selection dialog](../images/Docs_AnnotationAcquisition.png)

## Prerequisites

- QuPath project open with an image entry and annotations defining regions to acquire
- Valid coordinate alignment between image and stage (see [Microscope Alignment](microscope-alignment.md))
- Python microscope server running

### Macro Image Requirement for Scanner-Preset Alignment

The **"Use existing alignment"** option with saved scanner presets requires a macro image to be reachable from the current entry. The scanner-preset path runs green-box detection on the macro to localize the whole-slide image on the microscope slide. Sub-images acquired by prior QPSC workflows (Bounded Acquisition, etc.) typically do not have a reachable macro, so in those cases the scanner-preset combo is disabled.

When a macro is not available, you can still use:
- **Slide-specific alignment** (if available) — QPSC-acquired images automatically carry alignment metadata from their acquisition stage coordinates. Even without an explicit saved JSON alignment file, QPSC stitches are recognized and can be used directly. For other images, manual alignment creates a JSON file that remains available for future acquisitions on the same slide.
- **Manual alignment** — create a new alignment or re-run the Microscope Alignment tool on the current image.

### Sub-Image Requirements

When acquiring from a sub-image (an image created by a prior Bounded Acquisition or Acquire from Existing Image workflow), the sub-image entry must have **objective metadata**. This records which microscope objective was used when the sub-image was originally acquired, and is critical for accurate coordinate transformation on subsequent acquisitions.

If you see a dialog titled "Sub-image Missing Objective -- Workflow Cancelled," the opened sub-image lacks this metadata. To fix, either:
1. **Re-acquire the sub-image** using the current workflow, which will stamp the objective on import.
2. **Hand-edit the project entry metadata** to add the correct objective name (advanced; not recommended unless you know the original objective).

If you see a dialog titled "Sub-image Objective Mismatch," the sub-image was acquired at a different objective than the wizard's current setting. The mismatch shifts every tile by half the field-of-view difference between the two objectives. Recommended: cancel, switch the wizard to the entry's objective, or re-acquire the sub-image at the desired objective.

## Options

### Sample Setup

| Option | Type | Required | Description |
|--------|------|----------|-------------|
| Sample Name | TextField | Yes | Name for acquired images. Defaults to current image name. |
| Use Existing Project | Auto | - | If a project is open, images are added to the current project. If no project is open, you will be prompted to create or select one. |

### Alignment Selection

The workflow offers three paths to align the image with the microscope stage:

| Option | Type | Description |
|--------|------|-------------|
| Use Existing Alignment | RadioButton | Select from previously saved scanner-preset transforms. **Requires a macro image** — if unavailable, slide-specific alignments (below) are used instead. If neither scanner presets nor slide-specific alignment is available, you must choose manual alignment. |
| Manual Alignment | RadioButton | Create a new alignment using the Microscope Alignment workflow. Does not require a macro image. |
| Refinement Options | - | After selecting an alignment method, choose whether to refine it with zero, one, or multiple reference tiles before acquiring the full region. |

When scanning from a sub-image that lacks a macro, the "Use Existing Alignment" option may display available scanner transforms but the combo will be disabled (greyed out). Slide-specific alignments saved during prior QPSC acquisitions remain available as alternatives to manual alignment.

Selected transforms are validated before use. Invalid transforms show a warning and allow reselection.

**Cross-Scope Alignment:** If no per-slide alignment exists for the active microscope but one was built for another microscope on the same sample, the system can compose an alignment through the shared macro frame. When successful, a modal dialog appears explaining that cross-scope alignment is approximate and asking you to confirm before proceeding. The refinement options are automatically disabled for cross-scope acquisitions (see Refinement Options below). After acquisition, run Microscope Alignment on this microscope to build a native target-scope alignment that future acquisitions can reuse without composition.

If alignment records from other microscopes exist but *cannot* be composed through a shared scanner preset to the active scope, a non-modal info dialog appears listing how many records were considered. This typically means the microscopes use incompatible scanner-preset bridges, or the macro-frame alignment was built on a scope whose presets do not overlap with the active scope. In this case, run Microscope Alignment on the active microscope to build a native alignment for this slide.

### Refinement Options

| Option | Description |
|--------|-------------|
| No Refinement | Use saved transform directly (fastest). Best when transform is known to be accurate. |
| Single-Tile Refinement | Refine alignment using one reference tile. Quick adjustment for minor drift. |
| Full Manual Alignment | Create new transform with multiple points. Use the first time or after hardware changes. |

**Saved Alignment Objective Mismatch Advisory:** If you load a saved alignment that was created at a different objective than your wizard's current setting (e.g., alignment refined at 10x, but wizard set to 20x), a modal dialog appears. The dialog explains that refinement translations are tied to the objective's tile geometry; reusing at a different objective preserves the scale and rotation but loses per-tile precision. You can continue with the loaded alignment or cancel to adjust the wizard's objective or re-align. (Note: this is distinct from a sub-image entry's objective mismatch, which is checked separately and has its own advisory.)

**Legacy Alignment Flip-Frame Advisory:** If you load an alignment JSON that was saved before flip-frame tracking was introduced (no `flipMacroX` / `flipMacroY` fields in the JSON file), and the active microscope has any saved preset requiring a flipped sibling (meaning flip matters for some scanner-on-this-scope pairing), a modal dialog appears. The dialog explains that we cannot determine whether the saved transform was built in a flipped or unflipped frame, and reusing it risks applying the wrong frame to your coordinates. Recommended: cancel and re-run Microscope Alignment for this slide to rebuild the JSON with flip-frame metadata. You can continue with the legacy alignment at your own risk if you are confident the frame is correct.

**Cross-Scope Alignment Refinement:** When a cross-scope alignment is composed (see Alignment Selection above), refinement options are automatically disabled. Single-tile and full manual refinement on the target scope would mis-frame the composed transform that was built in the source scope's coordinate system. If you need refinement after a cross-scope acquisition, the recommended approach is to run Microscope Alignment on the target microscope to build a native alignment that is properly framed in that scope's coordinates. Future acquisitions using that native alignment can then use refinement normally.

### Annotation Class Selection

| Column | Type | Description |
|--------|------|-------------|
| Select | CheckBox | Include/exclude this annotation class |
| Class Name | Label | Annotation classification name |
| Count | Label | Number of annotations with this class |
| Preview | Color Swatch | Color of the annotation class |

Select All / Deselect All buttons are available for convenience.

### Hardware Configuration

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| Modality | ComboBox | From config | Imaging modality (e.g., ppm_20x, bf_10x) |
| Objective | ComboBox | From config | Objective lens for this modality |
| Detector | ComboBox | From config | Camera/detector combination |
| Angle Overrides | Various | - | Modality-specific angle controls (if applicable) |

## Workflow

### Step 1: Sample Setup

Enter a sample name and confirm the project location. If a project is already open, images will be added to it automatically.

### Step 2: Alignment Selection

Choose how to align QuPath image coordinates with physical stage positions. You can use a previously saved transform, create a new one, or reuse the last transform.

### Step 3: Refinement Options

Select the level of alignment refinement needed. No Refinement is fastest; Single-Tile Refinement corrects for minor drift; Full Manual Alignment is most thorough.

If you choose Single-Tile Refinement, the workflow first generates tile detections from the **annotation classes you selected for collection** (not from every annotation in the image). The tile-selection dialog then lets you pick one of those tiles as the reference. This avoids the noisy/overlapping tile grids that used to appear when unrelated annotations got tiled alongside the ones you actually wanted to acquire. The dialog reports the count and class names so you can confirm the right set was tiled.

The Refine Alignment dialog shown after the move encourages you to click **Auto-Align (SIFT)** to refine the position automatically. The "Save Refined Position" button is disabled until SIFT has run successfully, preventing accidental skipping of refinement. If SIFT fails, use the microscope controls (joystick or Stage Control dialog) to nudge the stage closer, then try SIFT again. To keep the predicted position without refining, click **Skip Refinement** instead of Save. SIFT works best on tissue with visible features and only succeeds when the live view already overlaps the selected tile by at least a few hundred microns. See [Microscope Alignment > Step 4](microscope-alignment.md#step-4-refinement-manual-or-automatic) for the full SIFT walkthrough, and [Preferences > SIFT Auto-Alignment](../PREFERENCES.md#sift-auto-alignment) for the cross-modality bit-depth normalization knobs.

### Step 4: Annotation Class Selection

Choose which annotation classes to include in the acquisition. The dialog shows all classes present on the current image with their counts and color previews. The same selection drives tiling for the Single-Tile Refinement step (Step 3).

### Step 5: Hardware Configuration

Select the modality, objective, and detector combination. Only valid hardware combinations are shown.

### Step 6: Acquisition Progress

The progress monitor shows:

- Current region / total regions
- Tile progress within current region
- Overall progress bar

For each annotation, the system:

1. Navigates to the annotation location
2. Runs autofocus
3. Acquires all tiles covering the annotation
4. Stitches tiles for this annotation
5. Moves to the next annotation

### Step 7: Completion

Each annotation's acquisition is added to the project as it completes. Metadata includes:

- Parent image reference
- Annotation name
- XY offset from parent image
- All hardware settings used

![Acquisition progress dialog](../images/Docs_AcquisitionWorkflowProgress.png)

## Output

- One stitched image per annotation, added to the QuPath project
- Acquisition metadata linking each image to its parent and annotation
- XY offset information for coordinate mapping back to the macro image

## Tips & Troubleshooting

| Issue | Cause | Solution |
|-------|-------|----------|
| "No annotations found" | No annotations on image | Draw annotations on the macro image before starting |
| "Transform validation failed" | Alignment is off | Run refinement or create a new transform |
| Scanner-preset combo is greyed out / unavailable | Image has no reachable macro (common for sub-images from prior QPSC acquisitions) | Use manual alignment, or if a slide-specific alignment was created during a prior acquisition, select "Use Existing Alignment" to access it directly without the scanner-preset combo. |
| Images appear shifted | Coordinate mismatch | Check flip/invert settings in Preferences |
| "Pixel size not set" | Missing calibration | Set image pixel size in QuPath image properties |
| Poor alignment at edges | Too few alignment points | Add more calibration points spread across the image |
| Auto-Align (SIFT) fails near correct tile | Bit-depth mismatch (16-bit camera vs 8-bit H&E WSI) | SIFT Settings should default to `PERCENTILE` 2/98 + CLAHE on. If still failing, raise CLAHE clip to 4.0 or widen percentile to 0.5/99.5. See [Preferences > SIFT Auto-Alignment](../PREFERENCES.md#sift-auto-alignment). |
| Auto-Align (SIFT) fails far from tile | Stage too far from target | Drive the stage roughly close (live view should partially overlap the tile) before clicking SIFT. SIFT is a refinement, not a search. |
| "Sub-image Acquired on a Different Microscope -- Workflow Cancelled" | Sub-image opened on a different scope than the one that acquired it | See [TROUBLESHOOTING.md: Sub-image Cross-Scope Mismatch](../TROUBLESHOOTING.md#q-sub-image-acquired-on-a-different-microscope--workflow-cancelled) for two ways to resolve this. |

## See Also

- [Bounded Acquisition](bounded-acquisition.md) - Acquire by defining stage coordinates directly
- [Microscope Alignment](microscope-alignment.md) - Create the coordinate transform needed for this workflow
- [Camera Control](camera-control.md) - Verify camera settings before acquisition
- [Background Collection](background-collection.md) - Collect flat-field correction images
- [Live Viewer](live-viewer.md) - Verify positioning and focus before acquisition
- [Communication Settings](server-connection.md) - Configure server connection and alerts
