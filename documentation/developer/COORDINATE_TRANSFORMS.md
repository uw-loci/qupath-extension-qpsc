# Coordinate Transform System

Developer reference for how QPSC transforms coordinates between QuPath's pixel space and the physical microscope stage.

## The Problem

A user draws annotations on a whole-slide image (WSI) in QuPath, measured in pixels. The microscope stage moves in micrometers. The two coordinate systems differ in:
- **Scale**: pixels vs micrometers (pixel size varies by objective)
- **Origin**: QuPath is top-left; stage origin is hardware-dependent
- **Orientation**: the WSI may be optically flipped relative to stage coordinates
- **Axis direction**: stage axes may be inverted (positive = left instead of right)

## Transform Chain

```mermaid
graph LR
    QP["QuPath Full-Res<br/>pixels, origin top-left"]
    MF["Macro Flipped<br/>pixels, flip applied"]
    MO["Macro Original<br/>pixels, native orientation"]
    ST["Stage<br/>micrometers, hardware origin"]

    QP -->|"downsample"| MF
    MF -->|"flip transform<br/>(optical path correction)"| MO
    MO -->|"affine transform<br/>(from alignment calibration)"| ST

    ST -->|"inverse affine"| MO
    MO -->|"inverse flip"| MF
    MF -->|"upsample"| QP
```

### Step 1: QuPath Full-Res to Macro (downsample)

QuPath displays images at various zoom levels. Annotations are stored in full-resolution pixel coordinates. The macro/overview image is a downsampled version. The downsample factor is the ratio of full-res to macro pixel sizes.

### Step 2: Flip Correction (optical path)

The microscope's light path may produce images that are mirrored relative to the physical slide. This is corrected by an affine flip transform:

```java
// TransformationFunctions.createFlipTransform()
if (flipX && flipY) {
    transform.scale(-1.0, -1.0);
    transform.translate(-imageWidth, -imageHeight);
} else if (flipX) {
    transform.scale(-1.0, 1.0);
    transform.translate(-imageWidth, 0.0);
} else if (flipY) {
    transform.scale(1.0, -1.0);
    transform.translate(0.0, -imageHeight);
}
```

**Per-detector flip**: Different cameras may have different flip states. The flip is read from `resources_LOCI.yml` per detector (see [HARDWARE_ABSTRACTION.md](HARDWARE_ABSTRACTION.md#per-detector-optical-flip)).

### Step 3: Affine Transform (alignment calibration)

The affine transform maps macro pixel coordinates to stage micrometers. It is computed either during the Microscope Alignment workflow (by collecting 3+ corresponding points in both coordinate spaces) or is auto-registered at import time by a BoundingBox acquisition (see "Auto-Registered Transforms" below).

```
| a  b  tx |     | macro_x |     | stage_x |
| c  d  ty |  *  | macro_y |  =  | stage_y |
| 0  0  1  |     |    1    |     |    1    |
```

The transform encodes scale, rotation, and translation. It is stored persistently as JSON by `AffineTransformManager`.

## Two Tiers of Transform Storage

`AffineTransformManager` exposes two independent persistence paths:

| Tier | File | Key | Created by | Consumed by |
|------|------|-----|------------|-------------|
| **Named presets** | `microscope_configurations/saved_transforms.json` | Preset name (scope-wide) | Manual alignment workflow when the user saves a reusable preset | `loadSavedTransformFromPreferences` — applied globally |
| **Per-slide alignments** | `{project}/alignmentFiles/{imageName}_alignment.json` | Image file name | Manual alignment workflow (per slide) **and** BoundingBox auto-registration | `StageControlPanel.initializeCentroidButton` via `AffineTransformManager.loadSlideAlignment(project, imageName)` |

Named presets survive across projects and sessions. Per-slide alignments live inside a specific project and are keyed by the stitched image's on-disk file name, which is the same string `QPProjectFunctions.getActualImageFileName(imageData)` returns — this is how the Live Viewer's Go-to-centroid button decides whether an alignment exists for the currently open image.

## Auto-Registered Transforms (BoundingBox acquisitions)

Every BoundingBox acquisition registers its own per-slide alignment automatically at stitch-import time, so Live Viewer Move-to-centroid and click-to-center work on the resulting image with zero manual alignment steps. The trick: BoundingBox already knows every input the transform needs.

**Inputs:**

| Input | Source |
|-------|--------|
| Stage bounds `(x1, y1, x2, y2)` in µm | User-entered in the BoundingBox dialog, carried through `StitchingMetadata.stageBoundsX1Um/...` |
| Stitched image pixel dimensions `(widthPx, heightPx)` | Read from the stitched file's `ImageServer` after import |
| Orientation | Guaranteed canonical — the stitcher already honours `StageImageTransform.stitcherFlipFlags()` when writing output |

**Math** (in `AffineTransformManager.buildTransformFromStageBounds`):

```java
scaleX = (x2 - x1) / widthPx;
scaleY = (y2 - y1) / heightPx;
transform = new AffineTransform();
transform.translate(x1, y1);
transform.scale(scaleX, scaleY);
```

The result is a pixel → stage transform with positive scale components and a translation equal to the top-left stage corner.

**Hook points** — `StitchingHelper.autoRegisterBoundsTransformIfAvailable` is called from three import sites so every flow is covered:

1. `StitchingHelper.importMergedImageOnly` — merged multichannel output (IF / BF+IF)
2. `StitchingHelper.importPerChannelFallback` — merge-failure fallback (each per-channel file gets its own alignment)
3. `TileProcessingUtilities.stitchImagesAndUpdateProject` single-file branch — non-channel region stitching (single-angle, PPM, etc.)

The helper is a no-op unless `StitchingMetadata.hasStageBounds()` is true, so annotation-based acquisitions are unaffected and continue to inherit alignment from the parent macro image.

**Annotation acquisitions remain parent-inherited:** they do not register a standalone alignment because they already work through the parent's transform plus the `xOffset`/`yOffset` metadata fields (see `StageControlPanel.handleGoToCentroid` sub-image branch, which derives sign from the parent alignment's scale signs).

## Flip vs Inversion

These are different concepts that must not be confused:

```mermaid
graph TB
    subgraph "Optical Flip (per-detector)"
        F["Light path mirrors the image<br/>relative to the physical slide"]
        F1["Corrected by creating a<br/>flipped duplicate in QuPath"]
        F2["Stored in resources_LOCI.yml<br/>as flip_x / flip_y per detector"]
    end

    subgraph "Stage Inversion (per-microscope)"
        I["Positive stage command moves<br/>opposite to visual convention"]
        I1["Corrected in tiling traversal<br/>order and transform sign"]
        I2["Stored in QuPath preferences<br/>as stageInvertedX / stageInvertedY"]
    end
```

| Property | Flip | Inversion |
|----------|------|-----------|
| What it is | Optical mirror in light path | Stage axis direction convention |
| What it affects | Image orientation in QuPath | Tile traversal order, transform sign |
| Where configured | `resources_LOCI.yml` per detector | QuPath preferences per microscope |
| Applied when | Creating flipped image duplicates | Computing tile grid positions |

## SIFT Alignment with Per-Detector Flip

When refining stage position via SIFT feature matching, the WSI region must be oriented to match the microscope's live view. If the WSI was flipped (for display) but the detector is also flipped (optical path), the two flips cancel out:

```
sift_flip_x = macro_flip_x XOR detector_flip_x
sift_flip_y = macro_flip_y XOR detector_flip_y
```

```mermaid
graph TB
    subgraph "Example: Both flipped"
        M1["Macro flip_x = true<br/>(WSI is displayed flipped)"]
        D1["Detector flip_x = true<br/>(camera image is flipped)"]
        R1["SIFT flip_x = true XOR true = false<br/>(no SIFT flip needed!)"]
    end

    subgraph "Example: Only macro flipped"
        M2["Macro flip_x = true"]
        D2["Detector flip_x = false"]
        R2["SIFT flip_x = true XOR false = true<br/>(SIFT must flip WSI region)"]
    end
```

## Z-Focus Prediction (Tilt Correction)

For large acquisitions, the sample may be tilted relative to the focal plane. The `ZFocusPredictionModel` builds a tilt model from autofocus results and predicts the Z position for each tile:

```mermaid
graph LR
    AF["Autofocus results<br/>(Z position per tile)"]
    -->|"linear regression"| ZM["ZFocusPredictionModel<br/>(plane fit: Z = aX + bY + c)"]
    -->|"predict Z for tile XY"| HZ["--hint-z parameter<br/>(sent to Python server)"]
```

The `--hint-z` flag tells the server to start its autofocus search near the predicted Z, reducing search time.

## Key Files

| File | Purpose |
|------|---------|
| `utilities/TransformationFunctions.java` | Complete transform chain (pixel <-> stage) |
| `utilities/AffineTransformManager.java` | Persistent transform storage (JSON) |
| `utilities/AffineTransform3D.java` | 3D transform with Z scale/offset |
| `utilities/ImageFlipHelper.java` | Creates flipped duplicate images |
| `utilities/TilingUtilities.java` | Grid computation with axis inversion |
| `utilities/ZFocusPredictionModel.java` | Tilt correction model |
| `controller/MicroscopeAlignmentWorkflow.java` | Calibrates the affine transform (manual 3-point workflow) |
| `controller/workflow/SingleTileRefinement.java` | SIFT-based position refinement |
| `controller/workflow/StitchingHelper.java` | `autoRegisterBoundsTransformIfAvailable` — BoundingBox auto-registration |
| `model/StitchingMetadata.java` | Carries optional stage bounds through the stitch path |
| `controller/BoundedAcquisitionWorkflow.java` | Passes `(x1, y1, x2, y2)` into the bounds-aware `performRegionStitching` overload |
| `preferences/QPPreferenceDialog.java` | Stage inversion flags |
| `utilities/MicroscopeConfigManager.java` | Per-detector flip lookup |
