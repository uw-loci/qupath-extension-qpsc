# Orientation stack -- one source of truth for alignment & directional settings

This is the canonical developer reference for **how a slide on the stage becomes the pixels QPSC
displays and stitches**, and for **where every orientation / directional setting lives per
microscope**. When a report says "the arrows move the wrong way", "the Stage Map is 180 off", or
"SIFT moved to the mirror", start here.

Companion material:
- **Code aggregator:** [`LightPathModel`](../../src/main/java/qupath/ext/qpsc/utilities/LightPathModel.java)
  -- reads every factor from its authoritative source and prints one `describe()` dump. Logged at
  startup as `Orientation stack at startup: ...` (see `SetupScope`).
- **Composite transform:** [`StageImageTransform`](../../src/main/java/qupath/ext/qpsc/utilities/StageImageTransform.java).
- **Macro flip resolution:** [`FlipResolver`](../../src/main/java/qupath/ext/qpsc/utilities/FlipResolver.java).
- **Narrative + visual model:** [`COORDINATE_TRANSFORMS.md`](COORDINATE_TRANSFORMS.md) and the
  published Stage Map orientation artifact.

> **Rule for this whole area: never guess a sign.** The frame composition below is rigorous. The
> per-factor *directions* are physical facts of a specific rig -- they come from measurement or from
> the operator's eye, never from reasoning. Reasoning about signs has produced repeated regressions.

## The stack (light path, slide to pixels)

Four factors stack along the light path, plus the macro-image flip that orients the overlay:

| # | Factor | What it is | Authoritative source | Status |
|---|--------|-----------|----------------------|--------|
| 1a | **Scope type** | Inverted scope (objective below, coverslip faces down) vs upright (objective above, coverslip faces up). Mirrors the *naked-eye* view of the slide on the stage relative to the camera view; leaves the eyepiece/camera view of a given feature unchanged (coverslip always faces the objective). | **Not encoded** (see below) | **GAP** |
| 1b | **Slide insertion** | A slide can be slotted into the holder **two ways** -- an in-plane 180-degree rotation (frosted label at one end or the other). This rotates both the bench view and the camera view of the whole slide by 180. | **Not encoded** (per-slide, physical) | **GAP** |
| 2 | **Optical flip** | Objective + tube-lens parity. | Per-detector YAML `id_detector.flip_x/flip_y` -> `MicroscopeConfigManager.getDetectorFlipX/Y(detectorId)` | false on both rigs |
| 3 | **Camera orientation** | Camera mounting rotation on the port. | `CameraOrientation` pref -> `QPPreferenceDialog.getCameraOrientationProperty()` | pref |
| 4 | **Stage polarity** | Physical stage direction for a `+X/+Y` command. **Net of MicroManager's own axis inversion** (e.g. OWS3 inverts X in MM) plus wiring -- detected from behaviour, so never add a QPSC flip to compensate for the MM invert. | `StagePolarity` pref -> `QPPreferenceDialog.getStagePolarityProperty()` | pref |
| + | **Macro image flip** | Parity between a scanner's macro image and the target microscope. Per `(source-scanner, target)` pair, applied to overlay pixels only -- **not** a camera-vs-stage flip. | Active `TransformPreset.flipMacroX/Y` -> `FlipResolver` | per-preset |

### How they compose (what is trustworthy)

- **Composite** `StageImageTransform.stitcherFlipFlags()` = factor 4 XOR factor 3. Drives
  acquisition, stitching, arrows, click-to-center. **Verified correct on both rigs.**
- **Camera-only** `StageImageTransform.cameraFlipFlags()` = factor 3. Drives the Stage Map's
  Camera View.
- **The per-slide alignment transform** (fit to real image-to-stage correspondences) captures the
  *combined* effect of factors 1-4. It is the empirically correct relationship -- which is why
  Select-tile lands and SIFT matches even though the decomposition is incomplete.

Because **factors 1 and 2 are effectively unencoded** (detector flip false, no slide-placement
field), any surface that reads the *decomposition alone* -- the Stage Map -- can be wrong while the
composite/transform are right. That is the root of the Stage-Map and SIFT-auto-move bugs.

### Known values

| Rig | Slide (physical) | Optical | Camera | Polarity | Composite | Camera-only |
|-----|------------------|---------|--------|----------|-----------|-------------|
| **OWS3** | inverted / face-down | (false,false) | NORMAL | INVERT_XY | **(true,true)** | (false,false) |
| **PPM**  | upright / face-up    | (false,false) | NORMAL | NORMAL    | **(false,false)** | (false,false) |

## The Stage View orientation control (factor 1, made explicit)

Factor 1 is unencoded in the *hardware* stack, so the Stage Map cannot derive the difference between
"how the camera sees it" and "how the slide sits on the stage" from polarity/camera/detector alone.
Instead it is described **per microscope** in the YAML `light_path` block and reduced to a bench flip
deterministically:

```yaml
light_path:
  scope_type: inverted        # factor 1a: upright | inverted
  slide_insertion: A          # factor 1b default: A | B (in-plane 180)
  inverted_flip_axis: vertical # how the slide is turned over: vertical | horizontal
```

- **Camera View** = camera-only flip (matches the Live Viewer / acquisition image).
- **Stage View** = Camera View XOR `LightPathModel.benchFlipFlags(scope, insertion, axis)` -- the
  pure-geometry reduction `scopeFace o insertion` (unit-tested in `LightPathModelBenchFlipTest`).
- Defaults (`upright + A`) reduce to identity, so Stage View == Camera View -- the historical, inert
  behaviour. No regression until the rig is described.

The Stage Map's three drop-downs (scope / slide / turn-over axis) read and write this block via
`ConfigYamlEditor.setTopLevelChildScalar`, so the map and the setup wizard share one source of truth.
`LightPathModel.benchFlipFlags` is that shared derivation; the light-path simulator uses the same
`scopeFace o insertion` composition.

## Per-microscope source of truth + setup (status)

The goal is **one per-microscope source of truth** for these settings, reviewed/confirmed during
initial setup. Where each factor lives today:

- Factor 1 (1a scope type, 1b slide insertion): **per-microscope YAML `light_path` block** (done).
- Factor 2 (optical flip): **per-detector YAML** `id_detector.flip_x/flip_y`.
- Factors 3 and 4 (camera, polarity): **install-wide prefs**, auto-detected.

Decisions locked (2026-08-07):

1. **DONE** -- `light_path` YAML block (`scope_type`, `slide_insertion`, `inverted_flip_axis`), read
   by `LightPathModel` and written by `ConfigYamlEditor.setTopLevelChildScalar`.
2. **DONE** -- the Stage Map Stage View uses explicit physical drop-downs (scope / slide / turn-over)
   backed by that block, replacing the interim by-eye `stageViewOrientation` pref (now removed).
3. **PENDING (Phase 3)** -- a **fully editable setup-wizard page** with a live slide + FOV preview
   (mirroring the light-path simulator) that writes all orientation factors to the YAML. This is the
   remaining piece; it needs JavaFX runtime verification and will build on `LightPathModel`.

`LightPathModel` and this document remain the single source of truth in code.
