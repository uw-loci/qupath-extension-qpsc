# QPSC Workflows Guide

This document provides detailed step-by-step documentation for all QPSC acquisition workflows.

---

## Table of Contents

1. [Bounding Box Acquisition](#bounding-box-acquisition)
2. [Existing Image Acquisition](#existing-image-acquisition)
3. [Microscope Alignment](#microscope-alignment)

---

## Bounding Box Acquisition

### Overview
Acquire high-resolution images by defining stage coordinates directly. Creates a new QuPath project and acquires a rectangular region of the slide.

### When to Use
- Starting fresh without an existing overview image
- You know the stage coordinates of the region you want
- Setting up a new sample for imaging

### Prerequisites
- [x] Python microscope server running
- [x] Microscope hardware initialized in Micro-Manager
- [x] Valid microscope configuration file loaded
- [x] Background images collected (recommended)

### Location
**Extensions > QP Scope > Bounded Acquisition**

---

### Step 1: Unified Acquisition Dialog

The unified dialog presents all configuration in a single screen with collapsible sections.

`[Screenshot: documentation/images/bounded-unified-dialog.png]`

#### PROJECT & SAMPLE Section

| Field | Required | Description |
|-------|----------|-------------|
| Sample Name | Yes | Name for this acquisition (e.g., "MySample01") |
| Projects Folder | Yes* | Root folder for projects (*only if no project is open) |

**Validation:**
- Sample names are validated for cross-platform compatibility
- Invalid characters are automatically flagged
- Windows reserved names (CON, NUL, etc.) are blocked

#### HARDWARE CONFIGURATION Section

| Field | Options | Description |
|-------|---------|-------------|
| Modality | From config | Imaging modality (e.g., ppm_20x, bf_10x) |
| Objective | From config | Objective lens for this modality |
| Detector | From config | Camera/detector combination |

**Cascading Selection:**
- Objective list updates based on selected modality
- Detector list updates based on selected objective
- Only valid hardware combinations are shown

#### ACQUISITION REGION Section

**Two input modes available:**

**Start Point + Size Mode:**

| Field | Description |
|-------|-------------|
| Start X (um) | Starting X coordinate in micrometers |
| Start Y (um) | Starting Y coordinate in micrometers |
| Width (um) | Region width in micrometers |
| Height (um) | Region height in micrometers |
| Get Stage Position | Button to populate from current stage position |

**Two Corners Mode:**

| Field | Description |
|-------|-------------|
| Corner 1 X/Y | First corner coordinates |
| Corner 2 X/Y | Opposite corner coordinates |
| Get Stage Position | Buttons to populate each corner from stage |

#### ADVANCED OPTIONS Section (Collapsed by Default)

**JAI Camera White Balance** (visible only for JAI cameras):

| Option | Default | Description |
|--------|---------|-------------|
| Enable white balance correction | ON | Apply white balance calibration during acquisition |
| Use per-angle white balance (PPM) | OFF | Use different WB settings per polarizer angle |

**Modality-Specific Options:**
- PPM modality shows angle override controls
- Other modalities show relevant parameters

#### ACQUISITION PREVIEW Section

Real-time preview showing:
- **Region**: Calculated size in millimeters
- **Field of View**: FOV dimensions for selected hardware
- **Tile Grid**: Number of tiles (X x Y) and overlap percentage
- **Angles**: Number of angles for the modality
- **Total Images**: Total images to be acquired
- **Est. Time**: Rough time estimate
- **Est. Storage**: Rough storage estimate

---

### Step 2: Acquisition Progress

After clicking **Start Acquisition**, the progress monitor appears.

`[Screenshot: documentation/images/bounded-progress.png]`

**Progress Display:**
- Current tile number / total tiles
- Progress bar
- Current stage position
- Elapsed time

**During Acquisition:**
- Stage moves to each tile position
- Camera captures image(s) at each position
- For PPM: rotates through all angles at each position
- Autofocus runs periodically based on settings

**You Can:**
- Watch progress in the dialog
- The dialog blocks to prevent accidental interference
- Cancel acquisition (partial tiles will be saved)

---

### Step 3: Stitching

After all tiles are captured, automatic stitching begins.

`[Screenshot: documentation/images/bounded-stitching.png]`

**Stitching Process:**
- Tiles are aligned using overlap regions
- Pyramidal image is created for efficient viewing
- Output format determined by preferences (OME-TIFF or OME-ZARR)

**Important:**
- Do not interact with QuPath during stitching
- Stitching time depends on tile count (typically 2-10 minutes)
- Temporary tiles may be kept, zipped, or deleted per preferences

---

### Step 4: Completion

`[Screenshot: documentation/images/bounded-complete.png]`

**On Completion:**
- Stitched image is automatically added to the project
- Image opens in QuPath viewer
- Metadata is populated with acquisition details
- Temporary tiles are handled per preferences

---

### Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| "Connection refused" | Server not running | Start Python server |
| Stage doesn't move | Hardware not initialized | Check Micro-Manager |
| Black images | Exposure too short or lamp off | Check illumination |
| Stitching fails | Insufficient disk space | Free space and retry |

---

## Existing Image Acquisition

### Overview
Acquire high-resolution images of specific regions annotated on an existing macro/overview image. Annotations define the regions to scan.

### When to Use
- You have a macro/overview image of your slide
- You want to acquire specific regions of interest
- Adding acquisitions to an existing project

### Prerequisites
- [x] QuPath project open with a macro image
- [x] Annotations drawn on the image defining regions to acquire
- [x] Valid coordinate alignment between image and stage
- [x] Python microscope server running

### Location
**Extensions > QP Scope > Acquire from Existing Image**

---

### Step 1: Sample Setup

`[Screenshot: documentation/images/existing-sample-setup.png]`

| Field | Required | Description |
|-------|----------|-------------|
| Sample Name | Yes | Name for acquired images (defaults to current image name) |
| Use Existing Project | Auto | If project is open, uses current project |

**Project Behavior:**
- If a project is open: images added to current project
- If no project: prompted to create/select one

---

### Step 2: Alignment Selection

`[Screenshot: documentation/images/existing-alignment.png]`

| Option | Description |
|--------|-------------|
| Use Saved Transform | Select from previously saved coordinate transforms |
| Create New Transform | Opens alignment workflow to create new transform |
| Last Used Transform | Uses the most recently used transform |

**Transform Validation:**
- Selected transform is validated before use
- Invalid transforms show warning and allow reselection

---

### Step 3: Refinement Options

`[Screenshot: documentation/images/existing-refinement.png]`

| Option | Description |
|--------|-------------|
| No Refinement | Use saved transform directly (fastest) |
| Single-Tile Refinement | Refine alignment using one reference tile |
| Full Manual Alignment | Create new transform with multiple points |

**When to Use Each:**
- **No Refinement**: Transform is known to be accurate
- **Single-Tile**: Quick adjustment for minor drift
- **Full Manual**: First time or after hardware changes

---

### Step 4: Annotation Class Selection

`[Screenshot: documentation/images/existing-class-selection.png]`

| Column | Description |
|--------|-------------|
| Select | Checkbox to include/exclude class |
| Class Name | Annotation classification name |
| Count | Number of annotations with this class |
| Preview | Color swatch for the class |

**Selection Options:**
- Select All / Deselect All buttons
- Filter by specific annotation classes
- Preview shows which annotations will be scanned

---

### Step 5: Hardware Configuration

`[Screenshot: documentation/images/existing-hardware.png]`

Same as Bounding Box Acquisition:
- Modality selection
- Objective selection
- Detector selection
- Angle overrides (if applicable)

---

### Step 6: Acquisition Progress

`[Screenshot: documentation/images/existing-progress.png]`

**Multi-Region Progress:**
- Shows current region / total regions
- Tile progress within current region
- Overall progress bar

**Per-Annotation Processing:**
1. Navigate to annotation location
2. Run autofocus
3. Acquire all tiles covering annotation
4. Stitch tiles for this annotation
5. Move to next annotation

---

### Step 7: Completion

Each annotation's acquisition is added to the project as it completes.

**Metadata Includes:**
- Parent image reference
- Annotation name
- XY offset from parent
- All hardware settings used

---

### Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| "No annotations found" | No annotations on image | Draw annotations before starting |
| "Transform validation failed" | Alignment is off | Run refinement or create new transform |
| Images appear shifted | Coordinate mismatch | Check flip/invert settings |
| "Pixel size not set" | Missing calibration | Set image pixel size in QuPath |

---

## Microscope Alignment

### Overview
Create or update the coordinate transformation between QuPath image coordinates and physical microscope stage positions. Required for accurate positioning.

### When to Use
- First time setting up a slide/scanner combination
- After hardware changes affecting positioning
- When acquired images don't align with annotations

### Prerequisites
- [x] Macro image loaded in QuPath
- [x] Microscope server connected
- [x] Stage can move to known positions

### Location
**Extensions > QP Scope > Microscope Alignment**

---

### Step 1: Macro Image Selection

`[Screenshot: documentation/images/alignment-image-select.png]`

**If Multiple Images:**
- Select the image to use for alignment
- Should be the macro/overview image
- Must have visible features for point matching

**Requirements:**
- Image must have pixel size calibration
- Should cover area of interest

---

### Step 2: Point Marking Interface

`[Screenshot: documentation/images/alignment-point-marking.png]`

**Process:**
1. **In QuPath**: Click on a recognizable feature
2. **Move Stage**: Navigate stage to same physical location
3. **Record Point**: Click "Record Point" to capture both coordinates
4. **Repeat**: Mark at least 3 points (more = better accuracy)

**Point Distribution:**
- Spread points across the image
- Cover corners and center if possible
- Don't cluster points in one area

**Per-Point Display:**

| Column | Description |
|--------|-------------|
| # | Point number |
| Image X | X coordinate in QuPath pixels |
| Image Y | Y coordinate in QuPath pixels |
| Stage X | X coordinate in micrometers |
| Stage Y | Y coordinate in micrometers |
| Delete | Remove this point |

---

### Step 3: Transform Calculation

`[Screenshot: documentation/images/alignment-calculate.png]`

**After marking points:**
- Click **Calculate Transform**
- System computes best-fit affine transformation
- Error statistics are displayed

**Quality Metrics:**

| Metric | Good Value | Description |
|--------|------------|-------------|
| Mean Error | < 50 um | Average positioning error |
| Max Error | < 100 um | Worst case error |
| R-squared | > 0.99 | Fit quality (1.0 = perfect) |

---

### Step 4: Validation

`[Screenshot: documentation/images/alignment-validation.png]`

**Validation Test:**
1. Select a validation point (not used in calculation)
2. Click **Go To Point** to move stage
3. Verify stage arrives at expected location
4. Check visual alignment through microscope

**If Validation Fails:**
- Add more calibration points
- Check for systematic errors (flip/invert)
- Ensure points were accurately marked

---

### Step 5: Save Transform

`[Screenshot: documentation/images/alignment-save.png]`

| Field | Description |
|-------|-------------|
| Transform Name | Descriptive name for this transform |
| Scanner | Scanner type this applies to (if applicable) |
| Notes | Optional notes about conditions |

**Saved Transforms:**
- Stored in configuration folder
- Can be selected in Existing Image workflow
- Multiple transforms can exist for different conditions

---

### Understanding Flip and Invert Settings

**Image Flipping (Optical Property):**
- Corrects for optical inversions in the light path
- Affects how annotations are displayed
- Set in Preferences: "Flip macro image X/Y"

**Stage Inversion (Coordinate Direction):**
- Corrects for stage coordinate conventions
- Affects movement direction commands
- Set in Preferences: "Inverted X/Y stage"

**Typical Settings:**

| Configuration | Flip X | Flip Y | Invert X | Invert Y |
|---------------|--------|--------|----------|----------|
| Standard upright | OFF | OFF | OFF | ON |
| Inverted microscope | OFF | ON | OFF | ON |
| Flipped slide scanner | ON | OFF | OFF | ON |

---

### Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| Large mean error | Points inaccurately marked | Re-mark with more precision |
| Stage moves wrong direction | Invert settings wrong | Toggle Invert X or Y |
| Image appears mirrored | Flip settings wrong | Toggle Flip X or Y |
| Points don't form pattern | Incorrect point pairs | Verify each point matches |

---

## See Also

- [Utilities Reference](UTILITIES.md) - All utilities with options explained
- [Preferences Reference](PREFERENCES.md) - All settings explained
- [Troubleshooting Guide](TROUBLESHOOTING.md) - Common issues and solutions
