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

The wizard's objective dropdown is **always** the source of truth for the
workflow's geometry. MM's reported pixel size is only used to detect
disagreement with that choice.

## The validation gate

`QPScopeChecks.validateObjectivePixelSize(objective, detector, modality, configPx)`
is the single gate. Behavior:

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
| Read | Wizard objective+detector+modality (`AcquisitionWizardDialog`); macro pixel size from project entry; per-slide alignment JSON (`AffineTransformManager.loadSlideAlignmentWithFrame`); MM live pixel size for validation |
| Compare | YAML pixel size for chosen objective vs MM live (5% threshold) |
| Write | Tile config (`<sample>/<modality>/<region>/TileConfiguration.txt`, `_QP.txt`) in macro-pixel coords; per-slide alignment JSON refresh; acquisition command file |
| Gate | `validateObjectivePixelSize` in `performAcquisition` (line 1071) -- after wizard, after alignment refinement, before `AcquisitionManager.execute()` |

Notes: the alignment refinement step (`SingleTileRefinement`) plans alignment
tiles with the wizard's objective. If the wizard objective is changed after
this step but before the acquisition phase, the tile config from refinement
is stale. The current gate fires before acquisition only -- if you want to
re-run alignment with a new objective, restart the workflow.

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
| Read | Per-entry pixel size, alignment JSON's `flipMacroX/Y`, FoV (config -> entry metadata -> live detector dims -> raw live FoV) |
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
