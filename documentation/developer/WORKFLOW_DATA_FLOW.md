# Workflow Data Flow

Map of how acquisition workflows in this extension move information between
the user, the QuPath GUI, the YAML config, MicroManager (live), the per-slide
alignment JSON, and on-disk artifacts (tile configurations, calibration
profiles, background folders). The motivating incident: an Existing-Image
acquisition where the wizard auto-detected the 10x objective from MM, planned
tiles for the 10x FoV, the user later changed MM to 20x, the wizard updated
its dropdown but the on-disk tile configuration was *not* regenerated, and
the resulting mosaic had ~50% empty space between every tile (10x stride at
20x camera FoV). The pixel-size mismatch check existed but had a 25% threshold
and only fired with a "Continue anyway?" confirm, so the warning was reachable
but easy to ignore. See `claude-reports/2026-05-10_objective-pixel-size-validation.md`
for the bug write-up and the fix.

This document is the canonical reference for **what each workflow reads,
compares, writes, and gates**. Read it before changing any acquisition
workflow; update it when the data flow changes.

## Sources of truth

| Concept | Source of truth | Notes |
|---|---|---|
| Active objective for a workflow | The dialog the user just dismissed (`SampleSetupController`, `WhiteBalanceDialog`, `BackgroundCollectionController`, the Acquisition Wizard, the Rapid Scan dialog, etc.) | The dialog binds to QuPath preferences; auto-detection from MM seeds the dropdown but the user's final choice is authoritative |
| Pixel size for an objective | `hardware.objectives[<id>].pixel_size_xy_um[<detector>]` in microscope YAML | Looked up via `MicroscopeConfigManager.getHardwarePixelSize(objective, detector)` |
| Camera FoV | `MicroscopeConfigManager.getModalityFOV(modality, objective, detector)` (= detector dims * pixel size) | Or live: `MicroscopeController.getCameraFOV()` -- only safe when MM has the same objective active |
| MM live pixel size | `MicroscopeSocketClient.getMicroscopePixelSize()` | Reflects whatever objective MM thinks is active; **may diverge from the wizard objective** |
| Optical flip (per scope pair) | `TransformPreset.flipMacroX/Y` (saved alignment preset) and per-slide alignment JSON | NOT per-entry metadata. See `claude-reports/design/2026-05-07_step-b-flipped-duplicate-restoration.md` |
| Stage polarity | Stage-polarity preference (auto-detected once per scope) | Independent of optical flip |
| Source scanner of an entry | `source_microscope` per-entry metadata | Set at import time |
| Alignment-JSON pixel frame | `pixelFrame` field in each alignment JSON: `"macro"` or `"sub"` | Macro = transform scale equals macro image pixel size (canonical workflow input). Sub = transform scale equals the sub-image's own pixel calibration (Live Viewer Go-To-Centroid input). Workflows operating on macro-frame annotations refuse `"sub"` at load. Legacy JSONs without the field default to `"macro"`. |
| Parent macro of a sub-image entry | `base_image` per-entry metadata (set at import) | Workflow paths resolve this via `AlignmentHelper.resolveMacroLookupKey` before any alignment lookup, so a sub-image entry never causes its own alignment JSON to be picked up. |

The wizard's objective dropdown is **always** the source of truth for the
workflow's geometry. MM's reported pixel size is only used to detect
disagreement with that choice.

## The validation gates

Two gates run side by side before any tile-writing or calibration-writing
workflow step. Both abort the workflow on failure; neither offers a "continue
anyway" path.

### 1. Camera ROI (`QPScopeChecks.validateCameraRoi`)

Catches a cropped camera ROI silently persisting in MicroManager. Historically
the residue of a streaming-AF call that didn't restore on exit; as of
microscope_command_server `7f40a47` (2026-05-11) the streaming-AF code path
unconditionally clears the ROI to full sensor on every exit, so this gate is
now belt-and-suspenders for non-streaming-AF ROI manipulation (operator-set MM
Property Browser, future code paths, regressions). Behavior:

1. If MM is unreachable or no detector dims are configured, the call is a
   no-op (returns `true`).
2. Queries the live frame via `MicroscopeSocketClient.getFrame()` and reads
   `width` / `height` from the returned `FrameData`.
3. Compares against `width_px` / `height_px` for the detector in the merged
   resources YAML (`getDetectorDimensions`). If both axes match within 5%,
   passes.
4. On mismatch: shows a dialog with config dims, live dims, diff percent,
   and step-by-step instructions to reset the ROI in MicroManager. Returns
   `false`.

The 5% threshold is in `QPScopeChecks.CAMERA_ROI_MISMATCH_THRESHOLD`. Same
dialog plumbing as the pixel-size gate; FX-thread-safe.

### 1b. Alignment pixel-frame (`AlignmentHelper.checkForSlideAlignment`)

Layer 2 of the 2026-05-11 alignment-lookup restructure. Catches the case where
the lookup somehow returns a sub-frame transform when the workflow needs a
macro-frame one (e.g. a sub-image JSON ending up at the macro filename via
external editing, or a future regression in Layer 1's `base_image` resolution).

1. After the lookup-key resolution and the JSON read, inspect
   `SlideAlignmentResult.getPixelFrame()`.
2. If anything other than `"macro"`, log an error and show a hard-cancel
   dialog explaining that the loaded transform is in the wrong frame, what
   the lookup key was, and how to fix it (open the macro entry, restart).
3. Return `null` from the workflow's alignment check so the caller aborts.

Legacy JSONs that don't carry the field load as `"macro"` by default. The
gate cannot fire for them, which is correct -- they predate sub-image
auto-registration. Only `StitchingHelper`'s auto-register writes `"sub"`.

### 2. Objective pixel size (`QPScopeChecks.validateObjectivePixelSize`)

1. If MM is unreachable or returns 0.0, the call is a no-op (returns `true`).
2. Compares `configPx` (from YAML for the wizard's chosen objective+detector)
   to MM's live pixel size. If `|config - live| / config <= 0.05` (5%), passes.
3. On mismatch: looks up the **closest configured objective** for MM's pixel
   size and shows a warning dialog with all four facts (wizard objective +
   expected pixel size, MM-reported pixel size, inferred MM objective, diff
   percent), explanation of consequences, and a single OK button. Returns
   `false`. The caller must abort the workflow.

The 5% threshold is set in `QPScopeChecks.PIXEL_SIZE_MISMATCH_THRESHOLD`.
Adjacent magnifications on a typical microscope differ by 2x, so 5% is wide
enough to absorb cal-pixel-size drift but narrow enough to catch any
mismatch a user could plausibly cause by changing one but not the other.

The dialog is **not** a "Continue anyway?" confirm. Mismatch always cancels.
The user fixes MM (turret) or the wizard dropdown and restarts.

## Per-workflow flow

Each workflow column lists, in order:

- **Read** — what the workflow reads from each source
- **Compare** — what it compares (and at what threshold)
- **Write** — what it persists, and what coordinate frame
- **Gate** — when `validateObjectivePixelSize` (or other gates) fires

### Existing Image (`ExistingImageWorkflowV2`)

| Step | What |
|---|---|
| Read | Wizard objective+detector+modality (`AcquisitionWizardDialog`); macro pixel size from project entry; per-slide alignment JSON (`AffineTransformManager.loadSlideAlignmentWithFrame`) -- lookup key is resolved through the open entry's `base_image` metadata via `AlignmentHelper.resolveMacroLookupKey`, so a sub-image entry resolves to the parent macro's key, never to its own auto-registered sub-frame JSON; MM live pixel size for validation |
| Compare | YAML pixel size for chosen objective vs MM live (5% threshold); loaded alignment's `pixelFrame == "macro"` |
| Write | Tile config (`<sample>/<modality>/<region>/TileConfiguration.txt`, `_QP.txt`) in macro-pixel coords; per-slide alignment JSON refresh (written under the macro lookup key, with `pixelFrame="macro"`); acquisition command file |
| Gate | `validateObjectivePixelSize` and `validateCameraRoi` fire at **three** points: (a) `AcquisitionWizardDialog.confirmCalibrationStatus` -- before the workflow even launches, so a wizard-vs-MM objective mismatch surfaces before any project / flipped-duplicate / alignment-JSON writes; (b) `ExistingImageWorkflowV2.handleRefinement` -- before any tile creation or stage motion (defense-in-depth if the wizard gate was unable to run, e.g. MM not connected at wizard time); (c) `ExistingImageWorkflowV2.performAcquisition` -- final backstop, catches MM state that changed during refinement. The pixel-frame gate runs earlier inside `AlignmentHelper.checkForSlideAlignment`. |

Notes: the wizard-time gate is the user-facing "you picked the wrong
objective" warning. The two workflow-time gates are safety nets: they
cover (a) the case where MM was offline when the wizard opened and a
delayed connection allowed the workflow to start, and (b) the case where
the user changed objective in MicroManager mid-workflow. All three call
`validateMMAgainstSelection`, which is a thin wrapper around the same
two `QPScopeChecks` methods, so the threshold (5% pixel size, 5% sensor
ROI) and the diagnostic dialog content are identical at every layer.

**Saved-alignment objective-mismatch advisory (`AlignmentHelper.checkForSlideAlignment`,
directional as of 2026-05-19).** When the per-slide alignment JSON
records the objective it was built against and that name differs from
`sample.objective()`, the gate compares pixel sizes via
`MicroscopeConfigManager.getPixelSize(objective, detector)`. The advisory
only surfaces when the wizard's pixel size is meaningfully *smaller*
than the saved one (within the same 5% tolerance the live gate uses) --
i.e. the wizard is at a higher magnification than the saved alignment.
Going the other direction (40x saved -> 10x wizard) leaves the linear
transform valid and any refinement translation shrinks into a sub-tile
fraction of the new tile, so the dialog is suppressed. On pixel-size
lookup failure (config gap, missing objective entry) the gate falls back
to the pre-2026-05-19 behaviour and surfaces the advisory regardless of
direction. Pre-Phase-3 JSONs without the `objective` field load with
`null` and stay silent.

**Sub-image-as-source path (`processSubAcquisitionPath`).** When the open
entry is a previously-acquired sub-image (non-zero `xy_offset`, `base_image`
distinct from its own name), `routeSubWorkflow` dispatches to the
offset-based path **before** the slide-specific-alignment branch. The
sub-image branch builds its pixel -> stage transform directly from the
entry's `xy_offset`, the image's pixel calibration, and a half-FOV
correction derived from the entry's `modality / objective / detector`
metadata. It does **not** consume the parent macro's alignment JSON; doing
so would apply a macro-pixel transform to sub-image (camera-pixel)
annotation coords and shrink every stage move by `camera_px / macro_px`
(see the 2026-05-10 MH_Colon incident class, and the 2026-05-13
sub-image-acquisition-routing fix for the routing regression that this
ordering closes). The sub-image branch also does not call
`ImageFlipHelper.validateAndFlipIfNeeded`, because sub-images have no
flipped sibling -- the helper itself short-circuits for sub-acquisition
entries.

**Sub-image cross-scope gate (`processSubAcquisitionPath`, added 2026-05-14).**
Before any stage motion, the sub-image branch checks the entry's
`acquired_on_microscope` metadata against the active microscope name. The
field is stamped at stitch-import time (see `StitchingHelper` and
`TileProcessingUtilities`); legacy sub-images without the field fall back
to `AffineTransformManager.getDerivedAlignmentMicroscope`, which parses
the filename of any derived alignment JSON. On mismatch the workflow
hard-cancels with a clear dialog -- the entry's `xy_offset` is in the
acquiring scope's stage frame and is meaningless on any other scope. When
neither source surfaces an acquiring-scope name, the gate logs at WARN
and proceeds (preserving the pre-2026-05-14 behavior for legacy projects
that pre-date both data sources). Same-scope sub-image acquisition is
unchanged. The compose-through-parent alternative was considered and
rejected: too much new code, adds another silent-fallback class. Users
who want to acquire from a sub-image on a different scope should open
the parent macro entry and let the cross-scope alignment path compose a
fresh active-scope transform there.

### Multi-Slide Existing Image (`MultiSlideExistingImageWorkflow`, experimental)

| Step | What |
|---|---|
| Read | Carrier registry (`StageInsertRegistry.getAvailableInserts()` filtered to multi-slot `SLIDE_HOLDER` carriers); project macro entries (those without `base_image` set); per-entry `slide_position` metadata to pre-fill the assignment dialog |
| Compare | None of its own. Each per-slot single-slide invocation runs the full `ExistingImageWorkflowV2` validation chain (objective pixel size, camera ROI, alignment pixel frame) independently. |
| Write | Per-assigned-entry metadata: `slide_position` (1..N), `slide_carrier` (carrier id like `quad_v`), `ms_run_id` (UUID). Stored via `ImageMetadataManager.setSlideAssignment`. Project synced once after assignment. |
| Gate | Same gates as the single-slide workflow, one set per invoked slot. The MS controller adds no new gates. |

Notes: this is an experimental shepherding layer over `ExistingImageWorkflowV2`. The MS panel does not modify the underlying acquisition or alignment logic; it tracks which carrier slot each project entry maps to and walks the user through running the single-slide workflow on each one. The menu entry only appears when `Enable Multi-Slide Workflow (experimental)` is on in QuPath preferences.

### Bounded Acquisition (`BoundedAcquisitionWorkflow`)

| Step | What |
|---|---|
| Read | `SampleSetupResult` (objective+detector+modality+sample name); FoV from config |
| Compare | YAML pixel size for chosen objective vs MM live (5% threshold) |
| Write | Tile config in stage-micron coords (single bounding box); acquisition command |
| Gate | `validateObjectivePixelSize` (line 227) -- after sample-setup dialog, after FoV lookup, **before** `TilingUtilities.createTiles` |

This is the cleanest gate point of the bunch -- the check runs before any
disk I/O.

### Forward / Back Propagation (`ForwardPropagationWorkflow`)

Operates **offline**. Does not read MM live state and does not gate on MM.
The pixel-size validation in this file is unrelated -- it sanity-checks that
the live FoV (when used as a source-4 fallback) matches the entry's recorded
pixel size, with a fixed +-10% ratio threshold. This is intentional: forward
prop must work when no microscope is connected, so an MM mismatch cannot be
the gate.

| Step | What |
|---|---|
| Read | Per-entry pixel size, alignment JSON's `flipMacroX/Y` (via three-tier lookup: macro-frame JSON, derived/ sub-frame JSON, entry-level `STAGE_BOUNDS`), FoV (config -> entry metadata -> live detector dims -> raw live FoV). The three-tier fallback enables propagation on no-macro bases where the base is itself an auto-registered stitch living in `alignmentFiles/derived/`. |
| Compare | Live FoV pixel size vs entry pixel size if source-4 is reached (90-110% acceptable) |
| Write | Annotations on parent or sub-acquisitions; nothing under `<sample>/<modality>/...` |
| Gate | None against MM. Source-4 fallback rejected if pixel size disagrees |

### Microscope Alignment (`MicroscopeAlignmentWorkflow`)

| Step | What |
|---|---|
| Read | `SampleSetupResult` (objective+detector+modality); annotations of valid classes |
| Compare | YAML pixel size for chosen objective vs MM live (5% threshold) |
| Write | Tile config for alignment tiles (`<temp>/TileConfiguration.txt`); transform preset on success |
| Gate | `validateObjectivePixelSize` at top of `createTilesForAnnotations` -- covers all three caller paths (collection-classes, Tissue fallback, valid-classes fallback) |

### Rapid Scan (`RapidScanWorkflow`)

| Step | What |
|---|---|
| Read | Objective+detector from in-dialog combo boxes; pixel size and FoV from `MicroscopeConfigManager` |
| Compare | YAML pixel size for chosen objective vs MM live (5% threshold) |
| Write | Tile images + tile config (in `output` directory, stage-micron coords); optional stitched output |
| Gate | `validateObjectivePixelSize` in start-button handler -- after objective + FoV resolved, before `socketClient.startRapidScan` |

Modality is not user-selected (always brightfield); the validation passes
the literal string `"brightfield"` for display.

### WB Comparison (`WBComparisonWorkflow`)

| Step | What |
|---|---|
| Read | `WBComparisonDialog` params -> `resolveModality/Objective/Detector(configManager, ...)`; FoV from config; angle/exposure pairs |
| Compare | YAML pixel size for chosen objective vs MM live (5% threshold) |
| Write | Per-mode WB tile sets and stitched outputs under the project; tile config in stage-micron coords |
| Gate | `validateObjectivePixelSize` after objective+detector resolved, before project creation |

### White Balance (`WhiteBalanceWorkflow`)

Single-tile per-objective calibration. The mismatch concern is **not** tile
geometry -- it is that the calibration is written into
`imaging_profiles.<modality>.<objective>.<detector>` in the configured
`white_balance_calibration` YAML, so a wrong objective silently overwrites
the wrong key.

| Step | What |
|---|---|
| Read | `WhiteBalanceDialog.SimpleWBParams` / `PPMWBParams` (objective, detector, exposures); modality resolved from config |
| Compare | YAML pixel size for chosen objective vs MM live (5% threshold) |
| Write | `imaging_profiles.<modality>.<objective>.<detector>.exposures_ms.*` in `white_balance_calibration/` YAML; black-level entries; gain entries |
| Gate | `validateObjectiveBeforeCalibration` (calls `validateObjectivePixelSize`) inside `confirm.showAndWait().ifPresent(...)`, before `runSimpleCalibration` / `runPPMCalibration` |

### Background Collection (`BackgroundCollectionWorkflow`)

Output folder structure encodes the magnification:
`<base>/<detector>/<modality>/<magnification>/<wbMode>/`. A mismatch silently
files the background under the wrong magnification -- subsequent acquisitions
that reference this folder via `--bg-folder` will then load the wrong
correction TIFFs.

| Step | What |
|---|---|
| Read | `BackgroundCollectionController` result (modality, objective, detector, angle/exposure pairs, output path, wbMode); pixel size from config |
| Compare | YAML pixel size for chosen objective vs MM live (5% threshold) |
| Write | Background TIFFs at `<base>/<detector>/<modality>/<mag>/<wbMode>/` |
| Gate | `validateObjectivePixelSize` at top of `executeBackgroundAcquisition`, before any socket call |

### Stack / Time-Lapse (`StackTimeLapseWorkflow`)

Single-tile at the current stage position. **No objective parameter, no tile
grid, no stitching.** Whatever MM has active is the pixel size of the saved
images. Mismatch with any wizard cannot occur -- there is no wizard objective.
**No gate.**

### Autofocus / probe / noise workflows

`AutofocusBenchmarkWorkflow`, `AutofocusEditorWorkflow`, `TestAutofocusWorkflow`,
`NoiseCharacterizationWorkflow`, `ProbeStageAfWorkflow`. All single-tile,
all at current position, none plan tile grids or write per-objective
calibration files keyed by magnification. **No gate.** If we add a workflow
in this category that does write per-objective state, add the gate to it.

## Failure modes that prompted this work

1. **Stride / FoV mismatch** (Existing Image, Bounded, Rapid Scan, WB
   Comparison, Microscope Alignment): tile config planned for objective A,
   acquisition runs at objective B. Result is a regular grid with empty
   gaps between each tile (B's FoV is smaller than A's stride) or massive
   overlap and waste (B's FoV is larger than A's stride). The 2026-05-10
   PPM/Existing-Image gap incident is the canonical example.

2. **Misfiled per-objective calibration** (White Balance, Background
   Collection): the dialog wrote calibration values under the objective the
   user *intended*, but MM was on a different objective. The next workflow
   that reads this calibration loads values that were collected under
   different optics, silently corrupting exposures, white balance, or
   background correction.

Both classes are caught by the 5% gate. Both used to be reachable; now both
hard-cancel.

## Maintenance contract

When you add or modify an acquisition workflow:

- If the workflow writes a tile grid, calibration file, or anything keyed by
  objective magnification, add a `validateObjectivePixelSize` gate **after
  the dialog returns** and **before** any disk write.
- If the workflow operates offline (forward propagation, project import) it
  must not depend on MM state and the gate does not apply.
- Update this document with a per-workflow row before merging. A code change
  that contradicts the table here makes both the doc and the next person's
  mental model wrong.
- The gate threshold lives in one place (`QPScopeChecks.PIXEL_SIZE_MISMATCH_THRESHOLD`).
  If you change it, update the "5%" references in this document in the same
  commit.
