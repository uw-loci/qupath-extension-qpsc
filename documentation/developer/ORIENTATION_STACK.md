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
| 1a | **Scope type** | Upright (objective above, face up) vs inverted (objective below, face down). Mirrors the *naked-eye* view of the slide on the stage; leaves the camera view of a given feature unchanged (coverslip always faces the objective). | YAML `light_path.scope_type` -> `LightPathModel.scopeType()` | encoded |
| 1b | **Slide insertion** | Which side the label is on -- the two ways a slide drops into the holder (an in-plane 180). With scope type, spans all four axis-aligned orientations; there is **no separate turn-over axis** (turning a slide over its long vs short axis == an in-plane 180 == insertion). | YAML `light_path.slide_insertion` -> `LightPathModel.slideInsertion()` | encoded |
| 2 | **Optical flip** | Objective + tube-lens parity -- the camera-vs-stage flip. **This is why a scope needs a distinct Camera View at all** (e.g. PPM objective inversion = 180/XY). Display only. | YAML `light_path.optical_flip` -> `LightPathModel.opticalFlip()` (the per-detector `flip_x/flip_y` stays for macro resolution) | encoded |
| 3 | **Camera orientation** | Camera mounting rotation on the port. | `CameraOrientation` pref -> `QPPreferenceDialog.getCameraOrientationProperty()` | pref (NORMAL both rigs) |
| 4 | **Stage polarity** | Physical stage direction for a `+X/+Y` command. **Net of MicroManager's own axis inversion** (e.g. OWS3 inverts X in MM) plus wiring -- detected from behaviour, so never add a QPSC flip to compensate for the MM invert. Settled: unchanged across all working versions. | `StagePolarity` pref -> `QPPreferenceDialog.getStagePolarityProperty()` | pref |
| + | **Macro image flip** | Parity between a scanner's macro image and the target microscope. Per `(source-scanner, target)` pair, applied to overlay pixels only -- **not** a camera-vs-stage flip. | Active `TransformPreset.flipMacroX/Y` -> `FlipResolver` | per-preset |

### How they compose (what is trustworthy)

- **Composite** `StageImageTransform.stitcherFlipFlags()` = factor 4 XOR factor 3. Drives
  acquisition, stitching, arrows, click-to-center, SIFT match. **Verified correct on both rigs** --
  and the per-slide alignment transform (fit to real image-to-stage correspondences) captures the
  *combined* effect of every factor, so acquisition/navigation/stitching are correct regardless of
  the display factors below. This is why the Stage-Map/SIFT display bugs never corrupted data.
- **Stage Map Camera View** = `cameraFlipFlags()` (factor 3) XOR `opticalFlipFlags` (factor 2) --
  so the map matches the Live Viewer. **Display only.**
- **Stage Map Stage View** = `slideRotationFlipFlags(insertion)` (factor 1b **only**) -- the slide-180
  rotation, and *nothing else*. It does **not** apply the scope-face flip (factor 1a). On an inverted
  scope the physical inversion is already carried by the stage polarity (factor 4) and the per-slide
  alignment transform, so the Stage Map's macro overlay is already drawn in that inverted baseline
  (OWS3 2026-08-09: the macro overlay's own `sX=sY=+1` because the insert inversion `dirX=dirY=-1` and
  the transform sign `m00=m11<0` cancel). Re-applying `benchFlipFlags`'s scope-face mirror on top
  double-counted it and mirrored an already-correct macro. `slideRotationFlipFlags(insertion) ==
  benchFlipFlags(upright, insertion)`, so this is a no-op on upright scopes (PPM) and only removes the
  spurious mirror on inverted ones. `benchFlipFlags` remains the full physical reduction for the setup
  wizard preview, which shows the whole bench (scope + insertion); the Stage Map does not.

### Known values

| Rig | Scope | Insertion | Optical flip | Camera | Polarity |
|-----|-------|-----------|--------------|--------|----------|
| **PPM**  | upright | **B** | **180 / XY** (objective inversion) | NORMAL | NORMAL |
| **OWS3** | (TBD by matching) | (TBD) | net camera view **reversed in X** (camera+optics combined; set optical to the observed net -- don't split it) | NORMAL | INVERT_XY |

PPM was pinned by matching the simulator. OWS3's optical value is the *net* the operator observes
(camera view reversed in X); the camera-vs-optical split is unknown and does not need splitting.

## The Stage Map orientation controls (factors 1 and 2, made explicit)

The Stage Map is the surface that reads the decomposition, so factors 1 and 2 are described **per
microscope** in the YAML `light_path` block:

```yaml
light_path:
  scope_type: upright      # factor 1a: upright | inverted (face up | face down)
  slide_insertion: B       # factor 1b: A | B (which side the label is on; in-plane 180)
  optical_flip: xy         # factor 2:  none | x | y | xy (objective + tube-lens parity)
```

- **Camera View** = `cameraFlipFlags` (factor 3) XOR `opticalFlipFlags` (factor 2) -- matches the
  Live Viewer. The optical flip is why Camera View differs from Stage View at all.
- **Stage View** = `slideRotationFlipFlags(insertion)` (factor 1b **only**) -- the slide-180 rotation.
  It does **not** apply the scope-face flip (see "How they compose": the scope inversion is already in
  the polarity + alignment-transform baseline, so re-applying it double-counts). `A` = identity, `B` =
  180. Unit-tested in `LightPathModelBenchFlipTest`.
- Defaults (`upright + A + optical none`) reduce both to identity, so Camera View == Stage View --
  the historical, inert behaviour. No regression until the rig is described.

**Where each control lives (2026-08-09).** The Stage Map exposes **only** the slide-180 rotation
(`slide_insertion`) -- a "Slide: Label left / Label right (180)" combo -- because that is the only thing
that changes run to run. The set-once properties **scope type** and **optical flip** are edited in
**Utilities -> Microscope Configuration -> Light Path Orientation** (`LightPathSetupDialog`), which
writes the same YAML block and calls `MicroscopeConfigManager.reload()` so the change takes effect
(reopen the Stage Map / Live Viewer to see it). All three still route through
`ConfigYamlEditor.setTopLevelChildScalar` and are read back through `LightPathModel`, the single source
of truth, so the map, the Utilities dialog, and the setup wizard never diverge. Everything here is
**display only** -- it never touches acquisition, which the empirical alignment transform already gets
right.

## Per-microscope source of truth + setup (status)

The goal is **one per-microscope source of truth** for these settings, reviewed/confirmed during
initial setup. Where each factor lives today:

- Factors 1 and 2 (scope type, slide insertion, optical flip): **per-microscope YAML `light_path`
  block** (done).
- Factors 3 and 4 (camera, polarity): **install-wide prefs**, auto-detected; polarity is settled.

Decisions locked (2026-08-07):

1. **DONE** -- `light_path` YAML block (`scope_type`, `slide_insertion`, `optical_flip`), read by
   `LightPathModel` and written by `ConfigYamlEditor.setTopLevelChildScalar`. The turn-over axis was
   removed as redundant with insertion.
2. **DONE (revised 2026-08-09)** -- controls split by how often they change. The **Stage Map** exposes
   only the **slide-180 rotation** (`slide_insertion`); **scope type** and **optical flip** moved to
   **Utilities -> Microscope Configuration -> Light Path Orientation** (`LightPathSetupDialog`). Stage
   View is `slideRotationFlipFlags(insertion)` (scope-face flip dropped -- it double-counted the
   polarity/transform baseline on inverted scopes; OWS3 2026-08-09). Camera View still adds the optical
   flip. Superseded the earlier three-dropdown Stage Map design (and the interim by-eye
   `stageViewOrientation` pref, removed).
3. **PENDING (Phase 3)** -- a **fully editable setup-wizard page** with a live slide + FOV preview
   (mirroring the light-path simulator) that writes all orientation factors to the YAML. This is the
   remaining piece; it needs JavaFX runtime verification and will build on `LightPathModel`.

Known values (by matching, not derivation): **PPM** = upright, Slide B, Optics 180 (XY). **OWS3** =
inverted scope, Optics `none` (Camera View correct at zero flip; verified 2026-08-09), slide rotation
per placement. On OWS3 the Stage View is correct with the scope-face flip **dropped** -- the inversion
is already in the polarity/transform baseline.

`LightPathModel` and this document remain the single source of truth in code.
