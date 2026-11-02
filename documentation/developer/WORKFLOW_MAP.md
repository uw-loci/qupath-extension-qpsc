---
title: QPSC Workflow Map
purpose: Machine-readable single source of truth for workflow dispatch, data surfaces, and cross-workflow dependencies. Optimized for LLM agents, not human reading.
maintenance: Update alongside any code change that affects a watched_file or renames a watched_symbol. Verified by tools/check_workflow_map.py at pre-push time (Phase 5 of tools/pre-push-checks.sh). Missing symbols BLOCK; watched-file drift WARNS with the offending commits.
last_synced_commit: 122f31fe
watched_files:
  - src/main/java/qupath/ext/qpsc/SetupScope.java
  - src/main/java/qupath/ext/qpsc/ModalityUis.java
  - src/main/java/qupath/ext/qpsc/controller/QPScopeController.java
  - src/main/java/qupath/ext/qpsc/controller/ExistingImageWorkflowV2.java
  - src/main/java/qupath/ext/qpsc/controller/BoundedAcquisitionWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/MicroscopeAlignmentWorkflow.java
  - src/main/java/qupath/ext/qpsc/service/microscope/MicroscopeController.java
  - src/main/java/qupath/ext/qpsc/controller/MultiSlideExistingImageWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/ForwardPropagationWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/BackgroundCollectionWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/WhiteBalanceWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/WBComparisonWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/StitchingRecoveryWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/MakePortableWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/AutofocusBenchmarkWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/AutofocusEditorWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/ProbeStageAfWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/NoiseCharacterizationWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/TestAutofocusWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/RapidScanWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/ExportSpatialSceneWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/MicroscopeSwitcher.java
  - src/main/java/qupath/ext/qpsc/controller/DemoMicroscope.java
  - src/main/java/qupath/ext/qpsc/controller/workflow/AcquisitionManager.java
  - src/main/java/qupath/ext/qpsc/controller/workflow/AlignmentHelper.java
  - src/main/java/qupath/ext/qpsc/controller/workflow/AnnotationHelper.java
  - src/main/java/qupath/ext/qpsc/controller/workflow/ExistingAlignmentPath.java
  - src/main/java/qupath/ext/qpsc/controller/workflow/ManualAlignmentPath.java
  - src/main/java/qupath/ext/qpsc/controller/workflow/ProjectHelper.java
  - src/main/java/qupath/ext/qpsc/controller/workflow/SingleTileRefinement.java
  - src/main/java/qupath/ext/qpsc/controller/workflow/StitchingHelper.java
  - src/main/java/qupath/ext/qpsc/controller/workflow/TileCleanupHelper.java
  - src/main/java/qupath/ext/qpsc/controller/workflow/TileHelper.java
  - src/main/java/qupath/ext/qpsc/controller/workflow/WorkflowRegistry.java
  - src/main/java/qupath/ext/qpsc/modality/ModalityRegistry.java
  - src/main/java/qupath/ext/qpsc/modality/ModalityHandler.java
  - src/main/java/qupath/ext/qpsc/modality/ModalityUi.java
  - src/main/java/qupath/ext/qpsc/modality/ppm/PPMModalityHandler.java
  - src/main/java/qupath/ext/qpsc/modality/ppm/ui/PPMModalityUi.java
  - src/main/java/qupath/ext/qpsc/modality/lcpolscope/LCPolScopeModalityHandler.java
  - src/main/java/qupath/ext/qpsc/modality/lcpolscope/ui/LCPolScopeModalityUi.java
  - src/main/java/qupath/ext/qpsc/modality/widefield/ui/WidefieldModalityUi.java
  - src/main/java/qupath/ext/qpsc/modality/ui/BrightfieldModalityUi.java
  - src/main/java/qupath/ext/qpsc/modality/lcpolscope/workflow/LCCalibrationWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/FocusApproachValidationWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/RegisterObjectiveWorkflow.java
  - src/main/java/qupath/ext/qpsc/modality/ppm/workflow/BirefringenceOptimizationWorkflow.java
  - src/main/java/qupath/ext/qpsc/modality/ppm/workflow/PPMSensitivityTestWorkflow.java
  - src/main/java/qupath/ext/qpsc/modality/ppm/workflow/PolarizerCalibrationWorkflow.java
  - src/main/java/qupath/ext/qpsc/modality/ppm/workflow/SunburstCalibrationWorkflow.java
  - src/main/java/qupath/ext/qpsc/utilities/ImageMetadataManager.java
  - src/main/java/qupath/ext/qpsc/utilities/AffineTransformManager.java
  - src/main/java/qupath/ext/qpsc/config/MicroscopeConfigManager.java
  - src/main/java/qupath/ext/qpsc/geometry/ImageFlipHelper.java
  - src/main/java/qupath/ext/qpsc/geometry/FlipResolver.java
  - src/main/java/qupath/ext/qpsc/geometry/StageImageTransform.java
  - src/main/java/qupath/ext/qpsc/project/QPProjectFunctions.java
  - src/main/java/qupath/ext/qpsc/project/ProjectEntryReservation.java
  - src/main/java/qupath/ext/qpsc/stitching/StitchOutputPlanner.java
  - src/main/java/qupath/ext/qpsc/acquisition/StagePositionManager.java
  - src/main/java/qupath/ext/qpsc/geometry/CrossScopeTransformBuilder.java
  - src/main/java/qupath/ext/qpsc/geometry/TransformationFunctions.java
  - src/main/java/qupath/ext/qpsc/preferences/PersistentPreferences.java
  - src/main/java/qupath/ext/qpsc/preferences/QPPreferenceDialog.java
  - src/main/java/qupath/ext/qpsc/state/ModalityState.java
  - src/main/java/qupath/ext/qpsc/state/ObjectiveState.java
  - src/main/java/qupath/ext/qpsc/service/microscope/MicroscopeSocketClient.java
  - src/main/java/qupath/ext/qpsc/ui/acquisition/AcquisitionWizardDialog.java
  - src/main/java/qupath/ext/qpsc/ui/acquisition/AcquisitionDialogBuilder.java
  - src/main/java/qupath/ext/qpsc/ui/acquisition/ExistingImageAcquisitionController.java
  - src/main/java/qupath/ext/qpsc/ui/acquisition/UnifiedAcquisitionController.java
  - src/main/java/qupath/ext/qpsc/ui/acquisition/SinglePointAcquisitionController.java
  - src/main/java/qupath/ext/qpsc/ui/acquisition/StitchingQueueWindow.java
  - src/main/java/qupath/ext/qpsc/ui/background/BackgroundProfilesDialog.java
  - src/main/java/qupath/ext/qpsc/ui/liveviewer/LiveViewerWindow.java
  - src/main/java/qupath/ext/qpsc/ui/stagemap/StageMapWindow.java
watched_symbols:
  # Image metadata constants (DS9..DS18)
  - ImageMetadataManager.IMAGE_COLLECTION
  - ImageMetadataManager.XY_OFFSET_X
  - ImageMetadataManager.XY_OFFSET_Y
  - ImageMetadataManager.Z_OFFSET
  - ImageMetadataManager.SAMPLE_NAME
  - ImageMetadataManager.ORIGINAL_IMAGE_ID
  - ImageMetadataManager.MODALITY
  - ImageMetadataManager.OBJECTIVE
  - ImageMetadataManager.ANGLE
  - ImageMetadataManager.ANNOTATION_NAME
  - ImageMetadataManager.IMAGE_INDEX
  - ImageMetadataManager.BASE_IMAGE
  - ImageMetadataManager.DETECTOR_ID
  - ImageMetadataManager.SOURCE_MICROSCOPE
  - ImageMetadataManager.ACQUIRED_ON_MICROSCOPE
  - ImageMetadataManager.FOV_X_UM
  - ImageMetadataManager.FOV_Y_UM
  - ImageMetadataManager.SOURCE_ROI_X_PX
  - ImageMetadataManager.SOURCE_ROI_Y_PX
  - ImageMetadataManager.SOURCE_ROI_W_PX
  - ImageMetadataManager.SOURCE_ROI_H_PX
  - ImageMetadataManager.SOURCE_ROI_FLIP_X
  - ImageMetadataManager.SOURCE_ROI_FLIP_Y
  - ImageMetadataManager.STAGE_BOUNDS_X1_UM
  - ImageMetadataManager.STAGE_BOUNDS_Y1_UM
  - ImageMetadataManager.STAGE_BOUNDS_X2_UM
  - ImageMetadataManager.STAGE_BOUNDS_Y2_UM
  - ImageMetadataManager.STITCHER_FLIP_X
  - ImageMetadataManager.STITCHER_FLIP_Y
  - ImageMetadataManager.PPM_CALIBRATION
  - ImageMetadataManager.SLIDE_POSITION
  - ImageMetadataManager.SLIDE_CARRIER
  - ImageMetadataManager.MS_RUN_ID
  - ImageMetadataManager.WHITE_BALANCE
  - ImageMetadataManager.BACKGROUND_PROFILE
  - ImageMetadataManager.FLATFIELD_PROFILE
  - ImageMetadataManager.CAMERA_VIEW
  - ImageMetadataManager.LP_BAKED_PARITY_X
  - ImageMetadataManager.LP_BAKED_PARITY_Y
  - ImageMetadataManager.LP_ROTATION_DEG
  - ImageMetadataManager.getSiblingsByBaseImage
  - ImageMetadataManager.bakedParity
  - ImageMetadataManager.getLightPath
  - ImageMetadataManager.getRotationDeg
  - ImageMetadataManager.isCameraView
  - ImageMetadataManager.getBaseImage
  - ImageMetadataManager.getOriginalImageId
  - ImageMetadataManager.getAcquiredOnMicroscope
  - ImageMetadataManager.setAcquiredOnMicroscope
  # Alignment surfaces
  - AffineTransformManager.PIXEL_FRAME_MACRO
  - AffineTransformManager.PIXEL_FRAME_SUB
  - AffineTransformManager.saveSlideAlignment
  - AffineTransformManager.loadSlideAlignment
  - AffineTransformManager.loadSlideAlignmentWithFrame
  - AffineTransformManager.loadSlideAlignmentWithFrameForScope
  - AffineTransformManager.loadDerivedAlignment
  - AffineTransformManager.loadDerivedAlignmentWithFrame
  - AffineTransformManager.getDerivedAlignmentMicroscope
  - AffineTransformManager.loadAllSlideAlignmentsFromDirectory
  - AffineTransformManager.savePreset
  - AffineTransformManager.getBestPresetForPair
  - AffineTransformManager.getTransformsForMicroscope
  - AffineTransformManager.SlideAlignmentResult
  - AffineTransformManager.SlideAlignmentRecord
  - AffineTransformManager.TransformPreset
  # Workflow entry points / dispatch
  - QPScopeController.startWorkflow
  - ExistingImageWorkflowV2.start
  - ExistingImageWorkflowV2.startAsync
  - ExistingImageWorkflowV2.startSetupAsync
  - ExistingImageWorkflowV2.startAcquireAsync
  - ExistingImageWorkflowV2.SetupResult
  - ExistingImageWorkflowV2.routeSubWorkflow
  - ExistingImageWorkflowV2.checkAndHandleOrphanedFlippedSibling
  - ExistingImageWorkflowV2.processSubAcquisitionPath
  - ExistingImageWorkflowV2.processSlideSpecificAlignment
  - ExistingImageWorkflowV2.processExistingAlignmentPath
  - ExistingImageWorkflowV2.processManualAlignmentPath
  - ExistingImageWorkflowV2.handleRefinement
  - ExistingImageWorkflowV2.performAcquisition
  - ExistingImageWorkflowV2.cleanup
  - BoundedAcquisitionWorkflow.run
  - MicroscopeAlignmentWorkflow.run
  - MultiSlideExistingImageWorkflow.start
  - ForwardPropagationWorkflow.run
  - ForwardPropagationWorkflow.runBack
  - AlignmentHelper.checkForSlideAlignment
  - AlignmentHelper.resolveMacroLookupKey
  - StitchingHelper.autoRegisterBoundsTransformIfAvailable
  - StitchingHelper.performAnnotationStitching
  - StitchingHelper.convertSingleZarrToTiff
  - ImageFlipHelper.validateAndFlipIfNeeded
  - ImageFlipHelper.mirrorAnnotationsToSibling
  - ImageMetadataManager.isCameraView
  - FlipResolver.resolveFlipX
  - FlipResolver.resolveFlipY
  - FlipResolver.seedFlipForNewAlignment
  - CrossScopeTransformBuilder.compose
  - StageImageTransform.current
  - StageImageTransform.stitcherFlipFlags
  - WorkflowRegistry.tryActivate
  - WorkflowRegistry.isActive
  - WorkflowRegistry.release
  - AcquisitionManager.stopActiveAcquisition
  - ExistingImageAcquisitionController.isDialogOpen
  - ProjectEntryReservation.STATUS_KEY
  - ProjectEntryReservation.fill
  - StitchingQueueWindow.enqueue
  - StitchingQueueWindow.open
  # Controller / singletons
  - MicroscopeController.getInstance
  - MicroscopeController.setCurrentTransform
  - MicroscopeController.getCurrentTransform
  - MicroscopeController.setAcquisitionActive
  - MicroscopeController.isConnected
  - MicroscopeController.connect
  - MicroscopeController.disconnect
  - MicroscopeConfigManager.getInstance
  - MicroscopeConfigManager.getMicroscopeName
  - MicroscopeConfigManager.getPixelSize
  - MicroscopeConfigManager.getModalityFOV
  - MicroscopeConfigManager.getStageZTravelUm
  - ModalityRegistry.getHandler
  - ModalityRegistry.registerHandler
  - ModalityRegistry.registerUi
  - ModalityRegistry.getUi
  - ModalityUis.registerAll
  - ModalityState.getInstance
  - ObjectiveState.getInstance
  - StagePositionManager.getInstance
  # Modality UI halves (menu contributions, bounding-box panels, Live Viewer controls)
  - ModalityUi.getMenuContributions
  - ModalityUi.createBoundingBoxUI
  - ModalityUi.buildLiveCameraControls
  - ModalityUi.confirmBeforeAcquiring
  - ModalityUi.confirmBatch
  - LCPolScopeModalityUi.getMenuContributions
  # PPM workflow entries
  - PPMModalityUi.getMenuContributions
  - PolarizerCalibrationWorkflow.run
  - BirefringenceOptimizationWorkflow.run
  - SunburstCalibrationWorkflow.run
  - PPMSensitivityTestWorkflow.run
acknowledged_stale:
  - "334f4fda/0f5a7980: W1 gained startAsync() (completion future for batch orchestration); MultiSlide Run button now awaits it. Map's dispatch/reads/writes unchanged."
  - "85b5f498: MultiSlide panel gained 'Run All Remaining' -- sequential auto-run over W1.startAsync() across slots (opens each entry, awaits completion, advances). Per-slide dispatch/reads/writes unchanged; still one W1 invocation per slot."
  - "ae3d7c2b: W1 gained startSetupAsync() and startAcquireAsync() for two-pass batch mode; MultiSlide panel dispatches 'Set Up All Remaining' over startSetupAsync() (no acquisition), then 'Acquire All Set-Up' over startAcquireAsync() (unattended replay). Per-slide alignment persists to disk between passes; reads/writes largely unchanged, just split across two invocations per slot."
  - "a9e7c256: rotated holder slots now assign a single composed (rotated N)(flipped XY) entry (QPProjectFunctions.createRotatedFlippedDuplicate, chosen by MultiSlideAssignmentDialog.resolveAssignedEntry via the source/active preset flip) instead of a bare (rotated N) intermediate + later flip. Fixes the ACQUIRE_ONLY defect where alignmentChoice==null made validateAndFlipIfNeeded no-op, leaving acquisition on the annotation-free, wrong-frame intermediate. No dispatch/reads/writes change; the acquire-path annotation guards are now belt-and-suspenders. See COORDINATE_TRANSFORMS.md section 3."
  - "79012d3e: cosmetic only -- Stage Map flip checkbox relabeled 'Camera View' (checked) / 'Stage View' (unchecked), bound to selected state. No behavior/dispatch change."
  - "da84add5: MultiSlideExistingImageWorkflow.openEntry now quiet-saves the current image before gui.openImageEntry, preventing QuPath's modal Save-changes dialog from stalling the unattended two-pass acquire between slides. No dispatch/reads/writes change."
  - "772bc5e6: multi-slide diagonal transform corrected -- R270 anchor is kept for POSITION (finds where the full-res image center lands, ~1.3mm), then a pure diagonal is built on that center for ORIENTATION. Fixes the out-of-bounds validation crash from the prior R270-stripped build while keeping QuPath-X -> stage-X. ExistingAlignmentPath only."
  - "9e0d234f: multi-slide tile->stage made DIAGONAL (pure scale anchored at slot center) -- dropped the spurious R270 in ExistingAlignmentPath (ef9926e5) and the coupled name-based FOV-swap in TilingUtilities. The (rotated N)(flipped XY) entry is camera-oriented (camera-X = QuPath-X = stage-X), so tiles are now landscape (match the camera) instead of portrait. Measured root cause: annotation 11581_35306 QuPath-X -> stage-Y at 0.2505 um/px. Requires a FRESH multi-slide align to regenerate the diagonal JSON; pending on-scope verify. No dispatch/reads/writes change."
  - "80328889: new MULTI_TILE refinement (RefinementChoice in both RefinementSelectionController and ExistingImageAcquisitionController). handleRefinement's MULTI_TILE case shares performSingleTileRefinement's tile-creation + save path; createRefinementTilesAndRun dispatches to MultiTileRefinement (2+ tiles -> TransformationFunctions.computeStageSpaceSimilarity -> rotation+scale correction composed onto the alignment) vs SingleTileRefinement (translation only). Same reads/writes as single-tile (per-slide JSON via saveRefinedAlignment). Fixes slide-play-in-slot rotation that single-tile cannot correct."
  - "Phase D: the first alignment landmark of a multi-slide slide now searches for tissue before focusing. AffineTransformationController.resolveTissueSearch builds a stage-space direction hint (toward the tile-grid center) and passes it to SlotJumpAutofocus.runAfterSlotMove(TissueSearchHint), which runs FINDTISS on the AF daemon thread before the STRMAFZ scan -- so W1's dispatch is unchanged and nothing new runs on the FX thread. New socket verb FINDTISS (MicroscopeSocketClient.findTissue) and server-side handler + pure pattern module (microscope_command_server 66c4ecc). First landmark ONLY, and automatic batches ONLY: measured landing error is 613 um at landmark 1 and 26 um at landmark 2, so the error is a constant per-slide offset. Degrades to the previous behavior (focus from the predicted position) on an older server. No reads/writes change. UNVERIFIED ON HARDWARE."
  - "ff94104e: every tile pick in the W1 setup pass is now made by ReferenceTileSelector.autoPickIfArmed -- the first landmark (AffineTransformationController.resolveReferenceTile), SingleTileRefinement.resolveRefinementTile, and each MultiTileRefinement point. This is what removes the batch's hard stop: UIFunctions.promptTileSelectionDialogAsync creates Confirm disabled and Timeline-gates it on a viewer selection, so no countdown could drive it. MultiTileRefinement also self-drives addButton/solveButton via repeated AutoAdvanceController.attach, re-armed only when the panel is idle. New AutoAdvanceController.requestOperatorAttention (override + ntfy) fires on a low-confidence SIFT, on running out of auto-picked tiles, and from a 20-minute setup-slot watchdog in driveSequential; MultiSlidePreflight lists annotation-free slots before an automatic Set Up All. Reads project entry hierarchies (ProjectImageEntry.readHierarchy) for the pre-flight; no new dispatch or writes, and MANUAL remains a no-op."
  - "f13d43df: multi-slide setup-pass automation (Phases A+B). New MultiSlideAcquisitionMode preference + AutoAdvanceController, armed by the W1b batch driver and attached at four W1 setup dialogs (annotation class selection, consolidated acquisition dialog, UIFunctions.stageAlignmentConfirmAsync, RefinementSelectionController). It only fires each dialog's existing primary button on a timer -- no new dispatch, reads, or writes, and MANUAL (the default) is a no-op. Countdown from acquisition > multislide > auto_advance_seconds."
---

# QPSC Workflow Map

<!-- LLM-FACING: read Section 0 first if this is your first time. -->

## 0. How to navigate this document

This is a structured single source of truth for QPSC workflow dispatch, data
surfaces, and cross-workflow dependencies. It is optimized for LLM agents.

1. **Find what you are touching first.** Scan Section 1 (the lookup index) for
   the file, symbol, or concept you are about to change. It will point you to
   workflow IDs (`W*`) and data surface IDs (`DS*`).
2. **Read the YAML, not the prose.** Each workflow and each data surface has a
   YAML block that lists `reads`, `writes`, `invariants`, and
   `common_failures` by ID. Prose is only present when YAML cannot capture a
   nuance. Cross-reference IDs are stable.
3. **Section 4 is the master dependency matrix.** Use it to answer "what else
   touches this data surface" before you change a producer.
4. **Section 5 is the invariant list.** Skim it before any non-trivial change
   in this codebase. Most listed items are scar tissue from real outages.
5. **When changing a watched file or watched symbol:** update the relevant
   workflow/data surface YAML in the same commit, and bump
   `last_synced_commit` in the frontmatter. The frontmatter is parsed by the
   pre-push hook (`tools/check_workflow_map.py`, Phase 5 of
   `tools/pre-push-checks.sh`) to catch drift. Missing symbols block
   the push; watched-file drift warns with the offending commit SHAs.
6. **If you cannot fit a change cleanly into the existing structure,** add
   the surface or workflow with the next free ID and update Section 1 and
   Section 4. Do not retag existing IDs (they leak into git history and
   external references).

---

## 1. Quick-lookup index -- "I'm working on..."

| I'm changing... | Read these workflows | Read these data surfaces |
|---|---|---|
| Alignment save/load (saveSlideAlignment, TransformPreset, checkForSlideAlignment) | W1, W1c, W1d, W3, W14 | DS4, DS5, DS6, DS7, DS17 |
| Flipped sibling / `(Camera View)` companion entries / mirror annotations | W1, W1b, W1d, W3 | DS9 (light-path snapshot: CAMERA_VIEW, LP_BAKED_PARITY_X/Y), DS5, DS17 |
| Image metadata fields on ProjectImageEntry | All acquisition workflows (W1, W2, W3, W4, W11..W13) | DS8..DS18 |
| Source vs acquired microscope (cross-scope gating) | W1, W1a, W1c | DS9 (SOURCE_MICROSCOPE, ACQUIRED_ON_MICROSCOPE), DS5, DS6 |
| Autofocus parameters / streaming AF | W5, W6, W8, W20, W34 | DS3, DS19 (modality config), DS20 (parfocality YAML) |
| White balance modes / per-channel exposures | W9, W10 | DS21 (WB calibration YAML), DS22, DS24 |
| Background correction / flat-field | W4, W39, W40 | DS23 (background TIFFs + profiles), DS24 (activeBackgroundProfile, flatfieldMethod), DS3 |
| Stage polarity / camera orientation | All workflows touching stage motion | DS3 (`stage.polarity`), DS25 (cameraOrientation), DS26 (StageImageTransform) |
| Stitching pipeline (tiles -> OME-TIFF/ZARR) | W1, W2, W14, W15, W16, W37 | DS28..DS31, DS48 (reserved entries) |
| Two runs overlapping (stitching outlives acquisition) / Stop acquisition | W1, W37, W38 | DS17, DS49 (WorkflowRegistry slot) |
| Cross-scope acquisition (Ocus40 macro -> PPM tiles) | W1, W1c, W3 | DS5, DS6, DS9, DS39 (CrossScopeTransformBuilder) |
| Sub-image / forward+back propagation | W1, W1a, W12, W13 | DS5, DS6, DS9 (SOURCE_ROI_*), DS40 |
| Per-acquisition tile generation (TilingUtilities) | W1, W2, W3, W10, W11 | DS28 (tile config files), DS33 (TilingRequest) |
| Modality dispatch / handler registration / modality UI (panels, menu items, Live Viewer controls) | All workflows | DS41 (ModalityRegistry handlers + ModalityUi halves), DS42 (ModalityState) |
| Acquisition dialogs (Bounded / Existing Image shared code, Z range limit) | W1, W2, W32 | DS3 (`stage.limits.z_um`), DS24, DS25 |
| Live Viewer / Stage Map UI gates | W19, W21 | DS43 (live viewer state), DS44 (stage map state), DS17 |
| Z-stack or time-lapse single-point | W32 (W7 removed) | DS3, DS19 |
| Socket protocol / new command paths | W1, W2, W19, W21, W6, W8 | DS45 (MicroscopeSocketClient), DS17 |
| Preferences (QPPreferenceDialog, PersistentPreferences) | All workflows | DS24, DS25 |
| Project structure (sample dir, tile dir, alignmentFiles/) | W1, W2, W3, W11, W14, W15 | DS27 (project dir), DS32 (sample dir) |
| Multi-slide carrier assignments | W1b | DS9 (SLIDE_*), DS44 (StageMapWindow) |
| Setup wizard / first-time config | W22 | DS3, DS19 (config YAML) |
| Make-portable conversion (ZARR -> OME-TIFF) | W15 | DS28, DS32 |
| Stitching recovery from tile folder | W16 | DS28, DS27 |
| PPM rotation / calibration | W23, W24, W25, W26 | DS3, DS21, DS47 |
| LC-PolScope states / LC calibration | W36 | DS19, DS41 (name `lcpolscope`, type `polarized`) |
| Approach-from-safe-Z autofocus / focus validation | W34, W5, W6, W1b | DS3 (safe Z), DS19, DS24 (stored verdict) |
| Adding an objective to the config | W35 | DS3, DS19 |
| Switching microscope config at runtime / demo microscope | W43, W44 | DS3, DS25 (microscopeConfigFile, server host) |
| Light-path orientation (scope type, optical flip) | W45 | DS3 (`light_path` block) |
| Project-wide spatial export (OME-NGFF scene) | W42 | DS5, DS6, DS9 |

---

## 2. Workflows

Workflow IDs are stable. Sub-workflows nested under a parent use the parent's
ID plus a lowercase letter (e.g. `W1a`). Helper classes that are routed to
(not directly entered from the menu) are tagged `category: helper` and use
shared IDs the helpers actually use.

### W1: ExistingImageWorkflowV2

```yaml
id: W1
class: qupath.ext.qpsc.controller.ExistingImageWorkflowV2
file: src/main/java/qupath/ext/qpsc/controller/ExistingImageWorkflowV2.java
entry: start() | startAsync() -> CompletableFuture<WorkflowState> (full run; non-null = acquired, null = short-circuit/cancel/handled-error; never exceptional -- for batch orchestrators) | startSetupAsync() -> CompletableFuture<SetupResult> (setup-only: align + refine + tissue without acquisition, persists per-slide alignment JSON) | startAcquireAsync(SetupResult) -> AcquireHandle (acquire-only: replays captured config unattended against persisted alignment JSON; the handle also carries the stitching-settled future for the pipelined batch)
category: acquisition
ui_entries:
  - "Menu: Extensions > QP Scope > Acquisition controls > Acquire from Existing Image (SetupScope.java:440, dispatch 'existingImage'; disabled with no open image)"
  - "Wizard: Acquisition Wizard 'Existing Image Acquisition' button (AcquisitionWizardDialog.onExistingImageAcquisition)"
  - "Per-slot driver: MultiSlideExistingImageWorkflow shepherds one invocation per slide-carrier slot"
dialogs:
  - "AnnotationAcquisitionDialog (class selection, only when the image has annotations) then ExistingImageAcquisitionController.showDialog -- the consolidated dialog; shares its fields with Bounded Acquisition's UnifiedAcquisitionController through ui/acquisition/AcquisitionDialogBuilder."
  - "ExistingImageAcquisitionController opens once: a second start while it is up raises it (isDialogOpen / bringOpenDialogToFront) BEFORE the WorkflowRegistry slot is claimed. Start is disabled if the open project or image changes while the dialog is up."
  - "Z range maximum in the dialog is the stage's Z travel, MicroscopeConfigManager.getStageZTravelUm() = |stage.limits.z_um.high - low| (DS3)."
  - "Modality panel inside the dialog comes from ModalityRegistry.getUi(handler).createBoundingBoxUI(modality) (DS41)."
dispatches_to:
  - "W1a processSubAcquisitionPath WHEN isSubAcquisition() (xy_offset present AND base_image distinct from own name)"
  - "W1c processSlideSpecificAlignment WHEN per-slide alignment JSON exists AND refinement != FULL_MANUAL"
  - "W1d processExistingAlignmentPath WHEN alignmentChoice.useExistingAlignment() AND no slide JSON"
  - "W1e processManualAlignmentPath ELSE"
reads:
  - DS3 (microscope YAML via MicroscopeConfigManager)
  - DS5 (per-slide alignment JSON, macro frame -- via AlignmentHelper.checkForSlideAlignment)
  - DS9 (open entry: SOURCE_MICROSCOPE, ACQUIRED_ON_MICROSCOPE, BASE_IMAGE, ORIGINAL_IMAGE_ID, MODALITY, OBJECTIVE, DETECTOR_ID, XY_OFFSET_X/Y, light-path snapshot via bakedParity / isCameraView, STAGE_BOUNDS_*)
  - DS17 (MicroscopeController state: isConnected, currentTransform, acquisitionActive)
  - DS24 (PersistentPreferences: getLastModality, class list, metadata propagation prefix, activeBackgroundProfile, flatfieldMethod)
  - DS25 (QPPreferenceDialog: projectsFolder, tileOverlapPercent, stitching format)
  - DS3 (stage.polarity via StagePolarity.current(); stage.limits.z_um for the Z range)
  - DS27 (project image list, sibling resolution)
  - DS41 (ModalityRegistry -> ModalityHandler for the chosen modality; ModalityRegistry.getUi -> ModalityUi for its dialog panel and confirmBeforeAcquiring)
  - DS42 (ModalityState)
  - DS43 (live viewer streaming state -- only to suspend during acquisition)
  - DS49 (WorkflowRegistry active slot)
writes:
  - DS5 (per-slide alignment JSON refresh, only on refinement accepted -- via saveRefinedAlignment, 7-arg saveSlideAlignment)
  - DS6 (auto-registered sub-frame alignment JSON -- via StitchingHelper.autoRegisterBoundsTransformIfAvailable)
  - DS17 (MicroscopeController.currentTransform -- set after validateAndFlipIfNeeded, cleared in cleanup() only while this run holds the DS49 slot)
  - DS28 (TileConfiguration.txt and _QP.txt under <project>/<sample>/<acquisitionFolder>/<region>/)
  - DS30 (stitched OME-TIFF or ZARR plus pyramid)
  - DS48 (reserved project entries, qpsc_stitch_status=pending, added before each stitch job and filled in place when it lands; OME-TIFF only)
  - DS9 (new sub-image entry metadata at stitch import or reservation: base_image, original_image_id, modality, objective, detector_id, xy_offset, source_microscope, acquired_on_microscope, fov_x_um, fov_y_um, source_roi_*, stage_bounds_*, white_balance, background_profile, flatfield_profile via StitchingHelper / StitchInfoSupport)
  - DS19 (calls only -- e.g. acquisition command file)
  - DS27 (project sync; new ProjectImageEntry per acquired sub-image; imports never open an image, save the viewer's image data or re-set the project)
  - DS49 (tryActivate after prerequisites; release in cleanup)
steps:
  - { num: 1,  name: validatePrerequisites,                  file_line: "1428-1466", reads: ["getImageData", "MicroscopeController.isConnected", "DS17.acquisitionActive"], writes: [], gate: "no image OR not connected -> error dialog -> abort; an acquisition already holds the stage -> refuse and offer Stop acquisition (W38)" }
  - { num: 1.0, name: WorkflowRegistry.tryActivate,          file_line: "385-394",   reads: ["DS49"], writes: ["DS49 (this run takes the active slot; a run that is only stitching yields it)"], gate: "refused while an acquisition is active" }
  - { num: 2,  name: checkAndHandleSourceMismatch,           file_line: "1188-1333", reads: ["DS9.SOURCE_MICROSCOPE", "DS9.ACQUIRED_ON_MICROSCOPE", "MicroscopeConfigManager.getMicroscopeName"], writes: ["DS9.SOURCE_MICROSCOPE (only if user fixes)"], gate: "see DS39; auto-resolves when acquired_on equals active; dialog when mismatch and no acquired_on" }
  - { num: 3,  name: checkAndHandleOrphanedFlippedSibling,   file_line: "1335-1426", reads: ["DS27.imageList", "DS9.BASE_IMAGE", "DS5"], writes: [], gate: "orphan dialog: re-link or restart" }
  - { num: 4,  name: AnnotationAcquisitionDialog + ExistingImageAcquisitionController.showDialog, file_line: "465-525 (execute)", reads: ["DS24", "DS25", "DS3"], writes: ["DS24 (selected classes, last modality)"], gate: "user cancel -> CancellationException; ACQUIRE_ONLY replays the captured config with no dialogs" }
  - { num: 5,  name: ensureAnnotationsExist / selectClassesAfterDetection, file_line: "1013-1172", reads: ["hierarchy"], writes: ["may run tissue detection"], gate: "no valid annotations -> retry/cancel" }
  - { num: 6,  name: initializeFromConfig (build WorkflowState), file_line: "1499-1590", reads: ["DS3", "DS9"], writes: [], gate: "null config -> CancellationException -> handleError" }
  - { num: 7,  name: checkExistingSlideAlignment,            file_line: "1604-1715", reads: ["DS5 (Layer 1) via AlignmentHelper"], writes: [], gate: "if pixelFrame != 'macro' -> Layer 2 gate -> hard-cancel" }
  - { num: 8,  name: confirmCrossScopeAlignment + tryComposeCrossScopeAlignment, file_line: "1717-1895", reads: ["DS5 across all scopes (loadAllSlideAlignmentsFromDirectory)", "DS3 across both scopes"], writes: [], gate: "cross-scope dialog: select source scope, modal" }
  - { num: 9,  name: routeSubWorkflow,                       file_line: "1896-1968", reads: ["DS9 (XY_OFFSET, BASE_IMAGE), state.useExistingSlideAlignment, state.alignmentChoice"], writes: [], gate: "selects W1a/W1c/W1d/W1e" }
  - { num: 10, name: reReadAnnotationsAfterRouting + pinSource, file_line: "834-916", reads: ["hierarchy on possibly-switched entry"], writes: ["state.annotations (refreshed)", "state.sourceEntry (pinned; the flipped sibling when routing switched to it)"], gate: "after routing may switch open entry" }
  - { num: 10a, name: assertSourceImageOpen,                 file_line: "918-953",   reads: ["state.sourceEntry", "viewer's open entry"], writes: [], gate: "re-opens the pinned entry before refinement and acquisition if the viewer moved" }
  - { num: 11, name: handleRefinement (orchestrates SingleTileRefinement / MultiTileRefinement), file_line: "2639-2688", reads: ["DS17.currentTransform", "DS3"], writes: ["DS28 (refinement tile config)", "DS17 (refined transform on accept)", "DS5 (saveRefinedAlignment ONLY on accept)"], gate: "validateMMAgainstSelection (pixel size / camera ROI 5%) -> hard-cancel on mismatch; RefinementOutcomePolicy: only SKIPPED proceeds unrefined, the dialog's X cancels" }
  - { num: 12, name: performAcquisition,                     file_line: "3098-3130", reads: ["state.tilesByAnnotation", "DS17.currentTransform"], writes: ["DS17 (acquisitionActive=true)", "DS45 (socket: ACQUIRE)"], gate: "validateMMAgainstSelection final backstop; CancelGate consulted before each region, before awaitServerIdle and before startAcquisition" }
  - { num: 13, name: waitForCompletion / showSuccessNotification, file_line: "3131-3252", reads: ["AcquisitionMonitorService events"], writes: [], gate: "showSuccessNotification short-circuits on null state (Phase 3 H5)" }
  - { num: 14, name: stitch + autoRegisterBoundsTransformIfAvailable, file_line: "via AcquisitionManager.launchStitching -> StitchingHelper", reads: ["DS28"], writes: ["DS48 (reserve, then fill)", "DS30", "DS6 (sub-frame JSON)", "DS9 (full metadata stamp on new entries)"], gate: "job enqueued in StitchingQueueWindow (W37); stitching errors -> StitchingRecoveryWorkflow path" }
  - { num: 15, name: cleanup + cleanupTilesAfterStitching,   file_line: "3154-3202", reads: ["DS49"], writes: ["DS17.currentTransform = null (H6) and acquisitionActive=false (Phase 11), ONLY while this run holds the DS49 slot", "DS49 (release)", "DS28 (tile handling per preference, success path)"], gate: "force-delete tile dir even on error (Phase 11)" }
key_invariants:
  - "ImageFlipHelper.validateAndFlipIfNeeded MUST run BEFORE MicroscopeController.setCurrentTransform (M11 deferred install)"
  - "cleanup() clears MicroscopeController.currentTransform (H6 fix) only while this run holds the WorkflowRegistry slot (DS49). Run N's stitching outlives its acquisition and run N+1 may already have set its own transform; run N's cleanup nulled run N+1's transform on 2026-10-27 before this guard."
  - "Stitch imports never touch the viewer: no openImageEntry, no saveImageData of the viewer's image, no setProject (2026-10-27 incident: a stitch landing under the next slide's refinement dialog). Added entries are collected through StitchingHelper.ImportContext and reported in the completion toast and the stitching queue."
  - "saveSlideAlignment writers MUST read flipMacroX/Y from the open entry (ImageMetadataManager.bakedParity, the light-path snapshot's baked parity), NOT from preset (2026-05-18 stage-mirror bug). All 4 save sites converged on AlignmentHelper.resolveMacroLookupKey + entry-flip-read pattern (Phase 4 M1)."
  - "A second start while ExistingImageAcquisitionController is open raises that dialog and returns null; it never claims the WorkflowRegistry slot from the run that owns the dialog."
  - "routeSubWorkflow MUST check isSubAcquisition() BEFORE useExistingSlideAlignment branch (2026-05-13 sub-image routing fix)."
  - "Sub-image acquisitions MUST hard-cancel when acquired_on_microscope != active scope (Phase 2 H1/H4)."
  - "Cross-scope alignment requires SOURCE_MICROSCOPE != active microscope name; compose uses source scope's stored offset, not active scope's."
  - "saveRefinedAlignment short-circuits when isSubAcquisition() (Phase 1 H2/H3)."
  - "validateMMAgainstSelection fires at 3 points: wizard launch (AcquisitionWizardDialog.confirmCalibrationStatus), handleRefinement, performAcquisition."
  - "Layer 2 pixel_frame gate in AlignmentHelper.checkForSlideAlignment refuses any non-'macro' frame at load."
common_failures:
  - "Off-by-125x transform when ManualAlignmentPath reads scanner macro pixel size instead of slide calibration -- guarded by routing through processSlideSpecificAlignment when JSON exists."
  - "Cross-scope sub-image stage drift when acquired_on_microscope != active scope -- hard cancel in processSubAcquisitionPath."
  - "Stitching cleanup race producing 'Failed to read tile region from _temp_*' -- fixed by tiles-to-pyramid 0d7da0a + qpsc 732fbd4 (moveDirectoryWithRetryAndCopyFallback)."
  - "Wizard objective mismatch with MicroManager -- 5% pixel size threshold, hard-cancel via QPScopeChecks."
  - "Orphaned flipped sibling entry without parent macro -- dialog forces restart with valid base."
related_design_reports:
  - claude-reports/archive/2026-05/2026-05-30_stitching-recovery-dialog-fix.md
  - claude-reports/design/2026-05-13_subimage-acquisition-routing-fix.md
  - claude-reports/design/2026-05-07_step-b-flipped-duplicate-restoration.md
  - claude-reports/archive/2026-05/2026-05-19_flipped-sibling-annotation-mirror-and-bake-removal.md
  - claude-reports/archive/2026-05/2026-05-19_stitch-cleanup-stage-cache-dialog-storage.md
```

### W1a: processSubAcquisitionPath (sub-route of W1)

```yaml
id: W1a
class: qupath.ext.qpsc.controller.ExistingImageWorkflowV2
file: src/main/java/qupath/ext/qpsc/controller/ExistingImageWorkflowV2.java
entry: processSubAcquisitionPath (line 2203)
category: acquisition_subroute
parent: W1
reads:
  - DS9 (XY_OFFSET_X/Y, BASE_IMAGE, MODALITY, OBJECTIVE, DETECTOR_ID, ACQUIRED_ON_MICROSCOPE)
  - DS3 (FOV from config; pixel calibration)
  - DS6 (derived alignment JSON for the parent stitch; via AffineTransformManager.getDerivedAlignmentMicroscope for the legacy fallback when ACQUIRED_ON_MICROSCOPE is absent)
writes:
  - DS28 (tile config in sub-image pixel coords)
key_invariants:
  - "DOES NOT consume the parent macro alignment JSON. Doing so would apply a macro-pixel transform to sub-image (camera-pixel) coords and shrink stage moves by camera_px/macro_px (MH_Colon incident class)."
  - "DOES NOT call ImageFlipHelper.validateAndFlipIfNeeded. Sub-images have no flipped sibling; the helper short-circuits anyway."
  - "Cross-scope hard-cancel: if acquired_on_microscope is set AND differs from active microscope, abort with showSubImageCrossScopeMismatchDialog."
  - "Legacy fallback: when acquired_on_microscope is missing, use AffineTransformManager.getDerivedAlignmentMicroscope (parses filename of derived JSON)."
```

### W1b: MultiSlideExistingImageWorkflow

```yaml
id: W1b
class: qupath.ext.qpsc.controller.MultiSlideExistingImageWorkflow
file: src/main/java/qupath/ext/qpsc/controller/MultiSlideExistingImageWorkflow.java
entry: start() / startAsync()
category: acquisition_shepherd
status: experimental
ui_entries:
  - "Menu: Extensions > QP Scope > Multi-Slide Acquisition... (top level, directly under Acquisition Wizard; SetupScope.java:464, dispatch 'multiSlideExistingImage', which also auto-opens the Live Viewer and Stage Map; shown when the config defines a multi-slide holder; no preference toggle; disabled with no open project)"
dispatches_to:
  - "W1 (per-slot ExistingImageWorkflowV2.startAsync / startSetupAsync / startAcquireAsync)"
  - "ModalityRegistry.getUi(modality).confirmBatch(slides) once per modality before a batch (LC-PolScope: the per-slide calibration reminder)"
reads:
  - DS44 (StageInsertRegistry: multi-slot SLIDE_HOLDER carriers)
  - DS27 (macro entries: those without base_image set)
  - DS9 (SLIDE_POSITION pre-fill)
writes:
  - DS9 (per-assigned-entry: SLIDE_POSITION, SLIDE_CARRIER, MS_RUN_ID -- via ImageMetadataManager.setSlideAssignment)
  - DS27 (project sync after assignment)
key_invariants:
  - "Each per-slot W1 invocation runs the full validation chain independently."
  - "Menu VISIBILITY is driven by SetupScope.configHasMultiSlideHolder() -- the config declaring a multi-slot SLIDE_HOLDER. There is no preference toggle (an earlier revision of this map claimed one; there never was)."
  - "Setup-pass automation mode auto-advances the dialogs and auto-picks alignment tiles (ReferenceTileSelector / MultiTileRefinement / SiftCapturePane). Every stop is ANNOUNCED: a batch that cannot proceed says why rather than stalling silently."
  - "Reference tiles are chosen by TISSUE DENSITY first, not texture, and every measured point searches for tissue -- not only the first."
  - "The tissue search is best-effort: a failed search must not halt the batch, and an operator cancel during it is its own outcome, distinct from a failure."
  - "Unattended batches must never wait on advisory dialogs."
  - "Abort All must stop a running autofocus, not just the batch loop -- otherwise the stage keeps being driven after the abort."
  - "Slot-jump autofocus seeds from the slide's saved focus Z and hands the stage back afterwards; both runAfterSlotMove entry points lock on the pending-search holder."
  - "A rotated slot assigns ONE composed (rotated N)(Camera View) entry (createRotatedFlippedDuplicate), never a bare (rotated N) intermediate on a flip-needing scope. Orientation is metadata-driven: MultiSlideAssignmentDialog stamps the light-path snapshot (camera_view flag + baked parity + lp_rotation_deg) on the companion at creation, and code reads rotation from getRotationDeg / classifies via isCameraView -- the '(rotated N) (Camera View)' name is user-reference only. isCameraView makes validateAndFlipIfNeeded no-op, so both passes open the correct working entry. Base annotations are brought onto the empty companion (AnnotationHelper.bringSourceAnnotationsOntoOpenEntry, rotate+flip) before the acquisition dialogs. Do not reintroduce the intermediate or parse rotation from the name."
```

### W1c: processSlideSpecificAlignment (sub-route of W1)

```yaml
id: W1c
class: qupath.ext.qpsc.controller.ExistingImageWorkflowV2
file: src/main/java/qupath/ext/qpsc/controller/ExistingImageWorkflowV2.java
entry: processSlideSpecificAlignment (line 1970)
category: acquisition_subroute
parent: W1
reads:
  - DS5 (loaded slide alignment; transform + flipMacroX/Y + objective + detector)
  - DS9 (entry metadata, esp. flip flags for save-site)
  - DS3
writes:
  - DS5 (re-save with current entry flips and active scope name; uses AlignmentHelper.resolveMacroLookupKey)
  - DS17 (setCurrentTransform after validateAndFlipIfNeeded)
key_invariants:
  - "Save uses the OPEN ENTRY's flip flags, not the preset/saved alignment's. (2026-05-18 stage-mirror)"
  - "Save uses AlignmentHelper.resolveMacroLookupKey to walk base_image to the macro-owning entry."
  - "H8 advisory (objective mismatch) fires only when wizard mag is HIGHER than saved mag (2026-05-19 directional)."
```

### W1d: processExistingAlignmentPath (delegates to ExistingAlignmentPath helper)

```yaml
id: W1d
class: qupath.ext.qpsc.controller.ExistingImageWorkflowV2
file: src/main/java/qupath/ext/qpsc/controller/ExistingImageWorkflowV2.java
entry: processExistingAlignmentPath (line 2113) -> ExistingAlignmentPath.execute()
category: acquisition_subroute
parent: W1
helper_class: qupath.ext.qpsc.controller.workflow.ExistingAlignmentPath
reads:
  - DS4 (TransformPreset for the source-scanner / active-scope pair)
  - DS46 (data bounds classifier .json from QPPreferenceDialog.getDataBoundsClassifierProperty -- optional)
  - DS9 (macro pixel size, source_microscope)
writes:
  - DS5 (saveSlideAlignment with entry flip flags)
  - DS17
```

### W1e: processManualAlignmentPath (delegates to ManualAlignmentPath helper)

```yaml
id: W1e
class: qupath.ext.qpsc.controller.ExistingImageWorkflowV2
file: src/main/java/qupath/ext/qpsc/controller/ExistingImageWorkflowV2.java
entry: processManualAlignmentPath (line 2139) -> ManualAlignmentPath.execute()
category: acquisition_subroute
parent: W1
helper_class: qupath.ext.qpsc.controller.workflow.ManualAlignmentPath
reads:
  - DS9 (slide calibration, NOT scanner macro pixel size)
  - DS17 (stage queries via MicroscopeController)
writes:
  - DS5 (createManualAlignment writes via saveSlideAlignment)
  - DS17
```

### W2: BoundedAcquisitionWorkflow

```yaml
id: W2
class: qupath.ext.qpsc.controller.BoundedAcquisitionWorkflow
file: src/main/java/qupath/ext/qpsc/controller/BoundedAcquisitionWorkflow.java
entry: run()
category: acquisition
ui_entries:
  - "Menu: Extensions > QP Scope > Acquisition controls > Bounded Acquisition (SetupScope.java:425, dispatch 'boundedAcquisition')"
  - "Wizard: Acquisition Wizard 'Bounded Acquisition' button"
reads:
  - DS3 (modality FOV, pixel size, detector dimensions; stage.limits.z_um for the dialog's Z range; stage.polarity)
  - DS17 (isConnected; auto-connect if not)
  - DS25 (projectsFolder, tileOverlapPercent, tileHandling, stitching format)
  - DS24 (dialog seeds)
  - DS45 (validateCameraRoi: getFrame())
  - DS41 (ModalityRegistry handler for the modality; ModalityRegistry.getUi -> createBoundingBoxUI panel and confirmBeforeAcquiring)
writes:
  - DS27 (project: createAndOpenQuPathProject OR use existing)
  - DS28 (TileConfiguration.txt in stage-micron coords; modality folder includes objective suffix)
  - DS48 (reserved entries for each stitch job, OME-TIFF only)
  - DS30 (stitched output -- post-acquisition)
  - DS19 (acquisition command file via AcquisitionCommandBuilder)
  - DS9 (new entry metadata after stitching)
steps:
  - { num: 1, name: ensure connection, file_line: "86-99", reads: ["DS17"], writes: [], gate: "no connect -> error -> abort" }
  - { num: 2, name: UnifiedAcquisitionController.showDialog (extends AcquisitionDialogBuilder), file_line: "101", reads: ["DS25", "DS24", "DS3"], writes: [], gate: "null result -> abort" }
  - { num: 3, name: derive modalityFolder (ObjectiveUtils.createEnhancedFolderName), file_line: "135-137", reads: [], writes: [], gate: "" }
  - { num: 4, name: project setup (createAndOpen OR reuse), file_line: "~140-210", reads: ["DS27"], writes: ["DS27"], gate: "" }
  - { num: 5, name: copyConfigsToProject (provenance), file_line: "212", reads: ["DS3"], writes: ["DS27/microscope_configs/"], gate: "best-effort" }
  - { num: 6, name: getModalityFOV + getPixelSize, file_line: "220-240", reads: ["DS3"], writes: [], gate: "null FOV -> error -> abort" }
  - { num: 7, name: QPScopeChecks.validateObjectivePixelSize + validateCameraRoi, file_line: "242-248", reads: ["DS3", "DS45"], writes: [], gate: "5% threshold -> hard-cancel on mismatch (single OK button)" }
  - { num: 8, name: StitchingConfiguration.validateWithRetry, file_line: "251", reads: ["DS25"], writes: [], gate: "user cancel -> abort" }
  - { num: 9, name: TilingUtilities.createTiles (bounding-box request), file_line: "~255-270", reads: ["DS33"], writes: ["DS28"], gate: "" }
  - { num: 10, name: ChannelResolutionService + AngleResolutionService, file_line: "280-310", reads: ["DS3"], writes: [], gate: "empty selection on channel-based modality -> abort" }
  - { num: 10a, name: ModalityUi.confirmBeforeAcquiring + BackgroundIlluminationCheck, file_line: "354-360", reads: ["DS41", "DS23"], writes: [], gate: "modality confirmation refused -> abort (LC-PolScope per-slide calibration reminder)" }
  - { num: 11, name: launch acquisition via socket, file_line: "484", reads: [], writes: ["DS17", "DS19", "DS45"], gate: "" }
  - { num: 12, name: stitch (StitchingHelper.performRegionStitching) + autoRegisterBoundsTransformIfAvailable, file_line: "596-610, via STITCH_EXECUTOR", reads: ["DS28"], writes: ["DS48", "DS30", "DS6", "DS9 (full metadata stamp)"], gate: "" }
  - { num: 13, name: cleanup tiles per preference, file_line: "670, via TileCleanupHelper", reads: ["DS25.tileHandling"], writes: ["DS28"], gate: "" }
key_invariants:
  - "Bounded acquisition is stage-frame native -- NO macro flip, NO alignment JSON read. Stitcher flips are stage-polarity-driven only."
  - "Does not claim the WorkflowRegistry slot (DS49); only W1 uses it."
  - "When project already open, projectsFolder and sampleName MUST be derived from the existing project path, not preference. Otherwise tiles land in wrong directory."
  - "validateObjectivePixelSize fires after dialog returns, before TilingUtilities.createTiles -- earliest gate in this codebase."
common_failures:
  - "Tile stride/FoV mismatch from wizard-vs-MM objective divergence -- covered by gate at step 7."
related_design_reports:
  - documentation/developer/WORKFLOW_DATA_FLOW.md
```

### W3: MicroscopeAlignmentWorkflow

```yaml
id: W3
class: qupath.ext.qpsc.controller.MicroscopeAlignmentWorkflow
file: src/main/java/qupath/ext/qpsc/controller/MicroscopeAlignmentWorkflow.java
entry: run()
category: alignment
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Microscope Alignment (SetupScope.java:487, dispatch 'microscopeAlignment')"
  - "Wizard: AcquisitionWizardDialog alignment step (startWorkflow('microscopeAlignment'))"
  - "Disabled unless MacroImageUtility.isMacroImageAvailable(qupath) returns true"
reads:
  - DS3 (incl. stage.polarity)
  - DS4 (existing TransformPresets for current (source, target) pair)
  - DS9 (open entry: SOURCE_MICROSCOPE, macro pixel size, bakedParity, isCameraView)
  - DS25 (cameraOrientation)
  - DS17
  - DS46 (data bounds classifier .json -- checked up front in run(), before the first dialog; required)
writes:
  - DS4 (savePreset on success -- new TransformPreset stored in alignment YAML)
  - DS5 (saveGeneralTransform writes per-slide alignment JSON; saveSlideAlignment call at line ~1624, commit 5bf0f7bf -- reads open-entry flips for save)
  - DS17 (currentTransform during refinement)
  - DS28 (alignment-tile config under temp dir)
key_invariants:
  - "savePreset stores flipMacroX/Y, sourceScanner, greenBoxParams, optional 3D Z scale/offset."
  - "saveGeneralTransform reads ImageMetadataManager.bakedParity(openEntry) for flipMacroX/Y on the per-slide JSON (matches load-side logic, 2026-05-19 fix; isFlippedX/Y and the flip_x/flip_y keys were replaced by the light-path snapshot in v0.9.0)."
  - "Refinement-tile cleanup happens explicitly on accept and reject (Phase 11)."
  - "run() refuses to start when DS46 is unset or names a missing file (ImageProcessing.dataBoundsClassifierProblem); the save-time check in saveGeneralTransform still guards the same case."
common_failures:
  - "Pre-fix per-slide JSONs (no flipFrameVerified field) -> Continue/Cancel advisory."
related_design_reports:
  - documentation/developer/COORDINATE_TRANSFORMS.md
  - claude-reports/archive/2026-05/2026-05-26_microscope-alignment-flip-short-circuit.md
```

### W4: BackgroundCollectionWorkflow

```yaml
id: W4
class: qupath.ext.qpsc.controller.BackgroundCollectionWorkflow
file: src/main/java/qupath/ext/qpsc/controller/BackgroundCollectionWorkflow.java
entry: run() / executeBackgroundAcquisitionDirect()
category: calibration
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Image Quality > Collect Background Images (SetupScope.java:558, dispatch 'backgroundCollection')"
  - "Wizard: AcquisitionWizardDialog background step"
  - "Offered after calibrations that invalidate a background set (BackgroundCollectionWorkflow.offerCollection / run(Preselection))"
reads:
  - DS3 (pixel size, modality FOV)
  - DS22 (WB mode validity via BackgroundValidityChecker)
  - DS24 (activeBackgroundProfile -- refuses without one)
  - DS45 (validateCameraRoi)
writes:
  - DS23 (background TIFFs at <base>/profiles/<backgroundProfile>/<detector>/<modality>/<mag>/<wbMode>/; refuses without a background profile)
key_invariants:
  - "Folder structure encodes magnification. Misfile silently corrupts subsequent --bg-folder lookups -- 5% gate prevents this."
  - "validateObjectivePixelSize at top of executeBackgroundAcquisition, before any socket call."
```

### W5: AutofocusBenchmarkWorkflow

```yaml
id: W5
class: qupath.ext.qpsc.controller.AutofocusBenchmarkWorkflow
file: src/main/java/qupath/ext/qpsc/controller/AutofocusBenchmarkWorkflow.java
entry: run()
category: utility
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Image Quality > Autofocus Parameter Benchmark... (SetupScope.java:665, dispatch 'autofocusBenchmark')"
reads:
  - DS3 (autofocus YAML section)
  - DS17
writes:
  - per-run CSV / report files under user-chosen output
key_invariants:
  - "Single-tile workflow -- no tile grid, no per-objective calibration -- NO pixel-size gate."
```

### W6: AutofocusEditorWorkflow

```yaml
id: W6
class: qupath.ext.qpsc.controller.AutofocusEditorWorkflow
file: src/main/java/qupath/ext/qpsc/controller/AutofocusEditorWorkflow.java
entry: run() / showValidationResultStatic()
category: configuration
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Image Quality > Autofocus Configuration Editor... (SetupScope.java:597, dispatch 'autofocusEditor')"
reads:
  - DS3 (autofocus YAML)
writes:
  - DS3 (autofocus YAML edits; per-objective AF params)
key_invariants:
  - "Config-only -- no microscope motion."
```

### W7: StackTimeLapseWorkflow (REMOVED)

```yaml
id: W7
status: removed
removed_in: "dadece3e (2026-06-25): StackTimeLapseWorkflow.java deleted together with the singlePointDialogEnabled preference that chose between it and W32"
replaced_by: W32 SinglePointAcquisitionController
note: "ID kept so older references resolve. The Z-Stack / Time-Lapse menu item goes to W32 unconditionally."
```

### W8: TestAutofocusWorkflow

```yaml
id: W8
class: qupath.ext.qpsc.controller.TestAutofocusWorkflow
file: src/main/java/qupath/ext/qpsc/controller/TestAutofocusWorkflow.java
entry: runStandard() / runSweep()
category: test
ui_entries:
  - "Autofocus Configuration Editor (W6) test buttons: runStandard / runSweep (AutofocusEditorWorkflow.java ~1778, ~1828)"
  - "Helpers getCurrentObjective / getDefaultOutputPath are also used by the Live Viewer autofocus, SlotJumpAutofocus, SiftCapturePane, AlignmentLivePrep and W34"
reads:
  - DS3
writes:
  - per-run logs
```

### W9: WhiteBalanceWorkflow

```yaml
id: W9
class: qupath.ext.qpsc.controller.WhiteBalanceWorkflow
file: src/main/java/qupath/ext/qpsc/controller/WhiteBalanceWorkflow.java
entry: run()
category: calibration
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Image Quality > JAI Camera > JAI White Balance Calibration... (when hasJAICamera; SetupScope.java:864, dispatch 'whiteBalance')"
  - "Wizard: AcquisitionWizardDialog white-balance step"
reads:
  - DS3 (pixel size)
  - DS24 (last objective / detector)
writes:
  - DS21 (imaging_profiles.<modality>.<objective>.<detector>.exposures_ms.* in white_balance_calibration YAML)
key_invariants:
  - "Single-tile per-objective calibration. validateObjectiveBeforeCalibration MUST fire before runSimpleCalibration/runPPMCalibration -- a wrong objective silently overwrites the wrong YAML key."
```

### W10: WBComparisonWorkflow

```yaml
id: W10
class: qupath.ext.qpsc.controller.WBComparisonWorkflow
file: src/main/java/qupath/ext/qpsc/controller/WBComparisonWorkflow.java
entry: run()
category: comparison
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Image Quality > WB Comparison Test... (only when hasJAICamera; SetupScope.java:625, dispatch 'wbComparison')"
reads:
  - DS3
  - DS21
writes:
  - DS27 (new project)
  - DS28, DS30 (per-mode WB tile sets and stitched outputs)
key_invariants:
  - "Side-by-side camera_awb / simple / per_angle comparison. validateObjectivePixelSize before project creation."
```

### W11: RapidScanWorkflow

```yaml
id: W11
class: qupath.ext.qpsc.controller.RapidScanWorkflow
file: src/main/java/qupath/ext/qpsc/controller/RapidScanWorkflow.java
entry: show(qupath)
category: acquisition
ui_entries: []  # No caller in src/main at 05ee3284: no menu item, no dispatch case, no other class calls show()
reads:
  - DS3
writes:
  - DS28 (stage-micron coords, in output directory)
  - DS30 (optional stitched output)
key_invariants:
  - "Modality is fixed to 'brightfield'. validateObjectivePixelSize in start-button handler before socketClient.startRapidScan."
```

### W12: ForwardPropagationWorkflow (offline)

```yaml
id: W12
class: qupath.ext.qpsc.controller.ForwardPropagationWorkflow
file: src/main/java/qupath/ext/qpsc/controller/ForwardPropagationWorkflow.java
entry: run(qupath)
category: propagation
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Project Tools > Propagation Manager... (SetupScope.java:751, direct call, not via QPScopeController)"
reads:
  - DS5 (tier 1 lookup: macro-frame JSON via AlignmentHelper)
  - DS6 (tier 2 lookup: derived/ sub-frame JSON)
  - DS9 (tier 3 lookup: entry-level STAGE_BOUNDS_*)
  - DS9 (per-entry pixel size, FOV_X_UM/FOV_Y_UM, MODALITY, OBJECTIVE, DETECTOR_ID)
  - DS17 (live FoV ONLY as source-4 fallback, +-10% gate)
writes:
  - DS27 (annotations on parent or sub-acquisitions; no tile dir writes)
key_invariants:
  - "MUST work offline -- never depend on live microscope. Source-4 (live FoV) rejected if pixel size disagrees with entry (+-10%)."
  - "Three-tier alignment lookup: macro JSON -> derived JSON -> entry STAGE_BOUNDS. AlignmentHelper is macro-only (Layer 2 gate); the tier 2/3 fallbacks are handled INSIDE the propagation builder, not by adding tier 2 to AlignmentHelper."
  - "FOV depends on OBJECTIVE AT ACQUISITION TIME (entry metadata), not current objective."
  - "Half-FOV offset is required -- missing it shifts annotations by half a tile."
```

### W13: ForwardPropagationWorkflow.runBack (back-prop)

```yaml
id: W13
class: qupath.ext.qpsc.controller.ForwardPropagationWorkflow
file: src/main/java/qupath/ext/qpsc/controller/ForwardPropagationWorkflow.java
entry: runBack(qupath) / showManager(qupath, Direction)
category: propagation
parent: W12
reads:
  - DS5, DS6, DS9 (same three-tier lookup)
  - DS9 (SOURCE_ROI_X/Y/W/H_PX -- ground-truth path)
writes:
  - DS27 (annotations on parent macro)
key_invariants:
  - "When tile detections from TilingUtilities are present on the parent, back-prop BYPASSES the alignment / flip / half-FOV chain entirely in favor of pure linear interpolation. PRESERVE THAT PATH."
  - "Cross-scope back-prop MUST use the SOURCE scope's alignment, not the active scope's, because the stored offset lives in the source scope's stage frame."
  - "SOURCE_ROI_FLIP_X/Y handles tile-detection-derived rectangles from flipped-sibling entries."
```

### W14: StitchingHelper (helper -- referenced by W1, W2, W3, W10, W11)

```yaml
id: W14
class: qupath.ext.qpsc.controller.workflow.StitchingHelper
file: src/main/java/qupath/ext/qpsc/controller/workflow/StitchingHelper.java
entry: performAnnotationStitching / performRegionStitching / autoRegisterBoundsTransformIfAvailable / convertSingleZarrToTiff
category: helper
called_from: "AcquisitionManager.launchStitching (W1; also enqueues the job in StitchingQueueWindow, W37), BoundedAcquisitionWorkflow (W2). W16 does not call it; it reports through StitchingQueueWindow.show directly."
reads:
  - DS28 (tile files + TileConfiguration.txt)
  - DS25 (stitching format/compression)
  - DS3 (microscope name for ACQUIRED_ON_MICROSCOPE stamping)
  - DS26 (StageImageTransform.stitcherFlipFlags)
writes:
  - DS48 (reserveOutputs: StitchOutputPlanner predicts each output's key and name; ProjectEntryReservation.reserve adds the entries on the FX thread before the job is queued; fill / fail / dropUnfilled when it ends)
  - DS30 (OME-TIFF/ZARR + pyramid)
  - DS6 (sub-frame alignment JSON via autoRegisterBoundsTransformIfAvailable)
  - DS9 (full metadata stamp on each newly-imported sub-image entry: BASE_IMAGE, ORIGINAL_IMAGE_ID, MODALITY, OBJECTIVE, DETECTOR_ID, XY_OFFSET, SOURCE_MICROSCOPE, ACQUIRED_ON_MICROSCOPE, FOV_X_UM, FOV_Y_UM, SOURCE_ROI_*, STAGE_BOUNDS_*, STITCHER_FLIP_X/Y)
key_invariants:
  - "autoRegisterBoundsTransformIfAvailable stamps STAGE_BOUNDS_* on the entry alongside the derived/ JSON so Move-to-Centroid works even if JSON lookup fails."
  - "STITCHER_FLIP_X/Y are recorded so Move-to-Centroid can rebuild pixel->stage from scratch."
  - "Imports only add the entry, stamp metadata and refreshProject(); they never open an entry in the viewer, save the viewer's image data or call setProject (4c841bf5). Added entries are collected through StitchingHelper.ImportContext (by operation id) for the completion toast and StitchingQueueWindow.recordOutput."
  - "Reservation is OME-TIFF only: a ZARR job gets an empty plan and imports the ordinary way. A wrong prediction costs only a placeholder entry, dropped when the job finishes."
  - "Background ZARR->TIFF escalation was removed in the 2026-06-01 stitching refactor (OmePyramidErrorMonitor / StitchRetryExecutor / OME_TIFF_VIA_ZARR deleted); the direct OME-TIFF writer writes the final path so no escalation is needed. convertSingleZarrToTiff is kept only for W15 MakePortableWorkflow."
```

### W15: MakePortableWorkflow

```yaml
id: W15
class: qupath.ext.qpsc.controller.MakePortableWorkflow
file: src/main/java/qupath/ext/qpsc/controller/MakePortableWorkflow.java
entry: run(qupath)
category: utility
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Project Tools > Make Project Portable... (SetupScope.java:782, direct call)"
reads:
  - DS27 (project image list; entries with .zarr backing)
  - DS28 (raw tile folders, when ZARR is absent)
writes:
  - DS30 (OME-TIFF replacements; preserves annotations and metadata)
  - DS27 (project sync after image swap)
key_invariants:
  - "Surfaces raw tile folders even when no ZARR remains (commit 7dc4a775)."
  - "Leaves everything in a _protected folder (TileProcessingUtilities.PROTECTED_FOLDER) alone, as tile cleanup does (ac9a1397)."
  - "Spotless line-wrap + UTILITIES note for tiles-only flow (commit 8d324838 area)."
```

### W16: StitchingRecoveryWorkflow

```yaml
id: W16
class: qupath.ext.qpsc.controller.StitchingRecoveryWorkflow
file: src/main/java/qupath/ext/qpsc/controller/StitchingRecoveryWorkflow.java
entry: run()
category: utility
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Project Tools > Re-stitch Tiles... (SetupScope.java:759, dispatch 'stitchingRecovery')"
reads:
  - DS28 (user-selected tile folder; TileConfiguration.txt)
  - DS25 (compression / output format)
writes:
  - DS30 (re-stitched image added to current project)
  - DS27 (project sync)
  - W37 queue rows (progress shown through StitchingQueueWindow.show, not a modal dialog)
key_invariants:
  - "Project-anchored SlideImages folder; rename-after-stitch naming; typed compression/format (OME_TIFF_VIA_ZARR dropped in the 2026-06-01 refactor; persisted values fall back to OME_TIFF)."
```

### W17: FlippedDuplicateMigrator (REMOVED)

```yaml
id: W17
status: removed
removed_in: "1355d237 (2026-08-11, v0.9.0 orientation refactor): FlippedDuplicateMigrator.java and its menu item deleted together with the flip_x/flip_y metadata keys"
note: "ID kept so older references resolve. Companion entries (now named '(Camera View)', built by QPProjectFunctions.createFlippedDuplicate / createRotatedFlippedDuplicate) are still created on demand for alignment UX and classified by ImageMetadataManager.isCameraView -- DO NOT strip them in the workflow path."
```

### W18: MicroManagerStitchWorkflow (REMOVED)

```yaml
id: W18
status: removed
removed_in: "2026-11-02: MicroManagerStitchWorkflow.java, its 'Stitch MicroManager Folder...' menu item, the 'stitchMicroManagerFolder' dispatch, the mmStitch* preferences and StitchingRegistration.applyTo deleted together"
replaced_by: "Tiles to Pyramid's own dialog (Extensions > Tiles to Pyramid > Tiles-to-pyramid, Stitching Method 'MicroManager metadata (MMStack or TIFF series)')"
note: "ID kept so older references resolve. The <stem>.mm-metadata.json sidecar it wrote is no longer produced by QPSC."
```

### W19: LiveViewerWindow (UI singleton)

```yaml
id: W19
class: qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow
file: src/main/java/qupath/ext/qpsc/ui/liveviewer/LiveViewerWindow.java
entry: show() / hide() / lockControls(reason) / unlockControls() / beginExternalAutofocus(cancel) / endExternalAutofocus()
category: ui_singleton
ui_entries:
  - "Menu: Extensions > QP Scope > Acquisition controls > Live Viewer (SetupScope.java:520, dispatch 'liveViewer')"
  - "Auto-opened by Multi-Slide Acquisition (dispatch 'multiSlideExistingImage') and the Acquisition Wizard; reopened by Switch Microscope (W43) if it was open"
  - "Implicit: any workflow with primary-socket activity locks the live viewer (W1 acquisition)"
reads:
  - DS17 (currentTransform for Go-To-Centroid path; acquisitionActive for the Stop acquisition button)
  - DS45 (auxiliary socket frames; stage polling)
  - DS9 (STAGE_BOUNDS for Go-To-Centroid fallback)
  - DS6 (per-slide sub-frame JSON for click-to-stage)
  - DS41 (ModalityRegistry.getUi(modality).buildLiveCameraControls -- modality camera panel, e.g. LCLiveCameraControls; built in StageControlPanel)
writes:
  - DS43 (live viewer streaming state, contrast, histogram)
  - DS17 (joystick / WASD stage motion via MicroscopeController.moveStage*; StageControlPanel set/getCurrentTransform for slide-transform restore and acquired-image alignment)
  - "W38 (red 'Stop acquisition' button while an acquisition holds the stage -> AcquisitionManager.stopActiveAcquisition)"
key_invariants:
  - "Registers its streaming controls with MicroscopeController at startup (f17bd1cd), so MicroscopeController imports nothing from the UI."
  - "Z bar widget + movement gate (commit 8d324838) -- suppresses Z trace during motion."
  - "Lock/unlock controls via static helpers; isLocked() reports global state."
  - "beginExternalAutofocus/endExternalAutofocus: partial lock for scans started OUTSIDE this window (multi-slide slot-jump AF via SlotJumpAutofocus, W-multislide). Disables stage-movement controls but leaves the Autofocus button live as a Cancel toggle (ABORTAF). Distinct from lockControls, which disables the Autofocus button too."
  - "All control updates MUST go through Platform.runLater (UI conventions)."
  - "isShowTilesEnabled + showAcquiredTile path renders live tiles during acquisition."
```

### W20: ProbeStageAfWorkflow

```yaml
id: W20
class: qupath.ext.qpsc.controller.ProbeStageAfWorkflow
file: src/main/java/qupath/ext/qpsc/controller/ProbeStageAfWorkflow.java
entry: run()
category: utility
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Image Quality > Re-probe Stage AF... (SetupScope.java:682, dispatch 'probeStageAf')"
reads:
  - DS3 (stage YAML)
  - DS17 (live stage probe)
writes:
  - DS3 (stage.streaming_af.* in config_<scope>.yml)
key_invariants:
  - "Streaming AF is INCOMPATIBLE with OWS3 (Prior ZDrive ignores Speed on non-blocking moves). Keep stage.streaming_af.enabled=false on OWS3."
```

### W21: StageMapWindow (UI singleton)

```yaml
id: W21
class: qupath.ext.qpsc.ui.stagemap.StageMapWindow
file: src/main/java/qupath/ext/qpsc/ui/stagemap/StageMapWindow.java
entry: show() / hide() / setBoundingBoxPreview() / clearBoundingBoxPreview()
category: ui_singleton
ui_entries:
  - "Menu: Extensions > QP Scope > Acquisition controls > Stage Map (SetupScope.java:511, direct StageMapWindow.show())"
  - "Auto-opened by Multi-Slide Acquisition; reopened by Switch Microscope (W43) if it was open"
reads:
  - DS44 (StageInsertRegistry: carrier definitions)
  - DS9 (entry metadata: BASE_IMAGE chain ancestry walk to macro-owning ancestor; SOURCE_MICROSCOPE; light-path snapshot)
  - DS3 (stage.polarity, light_path) + DS25 (cameraOrientation), composed through StageImageTransform (DS26)
  - DS4 (active source preset for source dropdown)
  - DS5 (per-slide alignment JSON; the macro is drawn where this slide's own alignment puts it, dea33682)
  - DS17 (current stage position)
writes:
  - mostly display-only; MAY trigger stage motion via MicroscopeController.moveStageXY
  - "DS9 slide insertion (PlacementChangeCoordinator: setSlideInsertion on the base, removes the stale (Camera View) companion, clears DS17.currentTransform)"
  - "DS3 insert reference points (Calibrate... -> stage insert calibration)"
key_invariants:
  - "Apply Flips source is hardware-only (stage+camera composite XOR active source preset's macro flip). Never read entry.source_microscope to derive display behavior (commit 365045d2)."
  - "Source dropdown is display-only -- walks base_image/original_image_id ancestor chain. NEVER overwrite an existing entry.source_microscope from the dropdown (commit d531d696)."
  - "Bounding-box preview debounced and cleared on close (commit d553535)."
  - "Resets warning flag on close."
```

### W22: SetupWizardDialog

```yaml
id: W22
class: qupath.ext.qpsc.ui.setupwizard.SetupWizardDialog
file: src/main/java/qupath/ext/qpsc/ui/setupwizard/SetupWizardDialog.java
entry: show()
category: configuration
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Microscope Configuration > Setup Wizard... (SetupScope.java:821, dispatch 'setupWizard')"
  - "Top-level when !configValid: 'Setup Wizard (Start Here)...' inserted before main wizard (SetupScope.java:945)"
reads:
  - DS3 (loading existing config if any)
  - DS17 (server connection probe)
writes:
  - DS3 (writes config_<scope>.yml + resource files via ConfigFileWriter)
```

### W23: PolarizerCalibrationWorkflow (PPM)

```yaml
id: W23
class: qupath.ext.qpsc.modality.ppm.workflow.PolarizerCalibrationWorkflow
file: src/main/java/qupath/ext/qpsc/modality/ppm/workflow/PolarizerCalibrationWorkflow.java
entry: run()
category: ppm_calibration
ui_entries:
  - "Dynamic: Extensions > QP Scope > <PPM submenu> > Polarizer Calibration (PPM)... -- PPMModalityUi.getMenuContributions() -> 'polarizerCalibration' (when PPM modality present; read via ModalityRegistry.getUi(handler))"
reads:
  - DS3
  - DS47 (PPM calibration directory: PPMPreferences.activeCalibrationPath)
writes:
  - DS47 (per-angle calibration files)
key_invariants:
  - "PPM workflows are not in QPScopeController switch -- routed dynamically via modality menu contributions."
```

### W24: BirefringenceOptimizationWorkflow (PPM)

```yaml
id: W24
class: qupath.ext.qpsc.modality.ppm.workflow.BirefringenceOptimizationWorkflow
file: src/main/java/qupath/ext/qpsc/modality/ppm/workflow/BirefringenceOptimizationWorkflow.java
entry: run()
category: ppm_optimization
ui_entries:
  - "Dynamic: <PPM submenu> > PPM Birefringence Optimization... -- PPMModalityUi.getMenuContributions() -> 'birefringenceOptimization'"
reads:
  - DS3
  - DS47
writes:
  - DS47 (results: optimal angles, metrics, visualization)
```

### W25: SunburstCalibrationWorkflow (PPM reference slide)

```yaml
id: W25
class: qupath.ext.qpsc.modality.ppm.workflow.SunburstCalibrationWorkflow
file: src/main/java/qupath/ext/qpsc/modality/ppm/workflow/SunburstCalibrationWorkflow.java
entry: run()
category: ppm_calibration
ui_entries:
  - "Dynamic: <PPM submenu> > PPM Reference Slide... -- PPMModalityUi.getMenuContributions() -> 'sunburstCalibration'"
reads:
  - DS3
  - DS25 (sunburst preferences from QPPreferenceDialog: spokes, saturation, value, inner/outer radius)
writes:
  - DS47 (hue-to-angle linear regression)
```

### W26: PPMSensitivityTestWorkflow

```yaml
id: W26
class: qupath.ext.qpsc.modality.ppm.workflow.PPMSensitivityTestWorkflow
file: src/main/java/qupath/ext/qpsc/modality/ppm/workflow/PPMSensitivityTestWorkflow.java
entry: run()
category: ppm_test
ui_entries:
  - "Dynamic: <PPM submenu> > PPM Rotation Sensitivity Test... -- PPMModalityUi.getMenuContributions() -> 'ppmSensitivityTest'"
reads:
  - DS3
writes:
  - per-test analysis report
```

### W27: NoiseCharacterizationWorkflow (JAI)

```yaml
id: W27
class: qupath.ext.qpsc.controller.NoiseCharacterizationWorkflow
file: src/main/java/qupath/ext/qpsc/controller/NoiseCharacterizationWorkflow.java
entry: run()
category: utility
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Image Quality > JAI Camera > JAI Noise Characterization... (when hasJAICamera; SetupScope.java:881, dispatch 'noiseCharacterization')"
reads:
  - DS3
writes:
  - per-run noise characterization output
```

### W28: AcquisitionWizardDialog (top-of-menu router)

```yaml
id: W28
class: qupath.ext.qpsc.ui.acquisition.AcquisitionWizardDialog
file: src/main/java/qupath/ext/qpsc/ui/acquisition/AcquisitionWizardDialog.java
entry: show()
category: ui_router
ui_entries:
  - "Menu: Extensions > QP Scope > Acquisition Wizard... (top of menu, SetupScope.java:398, dispatch 'acquisitionWizard')"
dispatches_to:
  - "W1 'Existing Image Acquisition' button (onExistingImageAcquisition)"
  - "W2 'Bounded Acquisition' button (onBoundedAcquisition)"
  - "W19 Live Viewer (startWorkflow('liveViewer')), W3 / W4 / W9 calibration steps (startWorkflow('microscopeAlignment' / 'backgroundCollection' / 'whiteBalance'))"
  - "Connection checklist"
reads:
  - DS3
  - DS17
  - DS25
writes:
  - DS24 (last modality / objective remembered)
key_invariants:
  - "confirmCalibrationStatus is the wizard-time call to validateObjectivePixelSize / validateCameraRoi. Earliest of three gate points for W1."
```

### W29: ServerConnectionController (dialog)

```yaml
id: W29
class: qupath.ext.qpsc.ui.connection.ServerConnectionController
file: src/main/java/qupath/ext/qpsc/ui/connection/ServerConnectionController.java
entry: showDialog()
category: configuration
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Microscope Configuration > Communication Settings... (SetupScope.java:641, dispatch 'serverConnection')"
reads:
  - DS25 (server host/port preferences)
  - DS17 (connection probe)
writes:
  - DS25 (server host/port, notification settings)
```

### W30: CameraControlController

```yaml
id: W30
class: qupath.ext.qpsc.ui.connection.CameraControlController
file: src/main/java/qupath/ext/qpsc/ui/connection/CameraControlController.java
entry: showCameraControlDialog()
category: utility
ui_entries:
  - "Menu: Extensions > QP Scope > Acquisition controls > Camera Control... (SetupScope.java:718, dispatch 'cameraControl')"
reads:
  - DS3 (imaging profiles)
  - DS17 (live exposures/gains)
writes:
  - DS17 (apply camera settings + rotation stage angle) -- non-persistent test settings
```

### W31: ParfocalityCalibrationController

```yaml
id: W31
class: qupath.ext.qpsc.ui.calibration.ParfocalityCalibrationController
file: src/main/java/qupath/ext/qpsc/ui/calibration/ParfocalityCalibrationController.java
entry: show()
category: calibration
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Microscope Configuration > Calibrate Parfocality... (SetupScope.java:700, dispatch 'parfocalityCalibration')"
reads:
  - DS3
  - DS17 (current Z under each profile)
writes:
  - DS20 (parfocality_<scope>.yml sidecar next to microscope config)
```

### W32: SinglePointAcquisitionController (Z-stack / time-lapse)

```yaml
id: W32
class: qupath.ext.qpsc.ui.acquisition.SinglePointAcquisitionController
file: src/main/java/qupath/ext/qpsc/ui/acquisition/SinglePointAcquisitionController.java
entry: show()
category: acquisition
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Z-Stack / Time-Lapse... (SetupScope.java:741, stackTimeLapseOption -> SinglePointAcquisitionController.show(), unconditional since W7 was removed)"
reads:
  - DS3 (incl. stage.limits.z_um: the Z range maximum is getStageZTravelUm, 3074f504)
  - DS17
  - DS41 (current modality handler)
  - DS42, DS43
writes:
  - DS30 (multi-dim OME-TIFF)
key_invariants:
  - "Honors the selected modality instead of hard-coding brightfield (legacy W7 difference)."
  - "Syncs Live button with server streaming state."
```

### W33: NotificationService

```yaml
id: W33
class: qupath.ext.qpsc.service.notification.NotificationService
file: src/main/java/qupath/ext/qpsc/service/notification/NotificationService.java
entry: notify*
category: service
reads:
  - DS25 (notification host/topic/enable flags)
writes:
  - external (ntfy.sh push)
key_invariants:
  - "Best-effort; failures are not workflow-fatal."
```

### W34: FocusApproachValidationWorkflow

```yaml
id: W34
class: qupath.ext.qpsc.controller.FocusApproachValidationWorkflow
file: src/main/java/qupath/ext/qpsc/controller/FocusApproachValidationWorkflow.java
entry: run()
category: calibration
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Image Quality > Focus Approach Validation... (SetupScope.java:614, direct call; disabled when the config is invalid or the scope is offline)"
reads:
  - DS3 (stage YAML: safe Z per insert)
  - DS19 (modality config: objective, focus channel)
  - DS17 (live stage + camera)
writes:
  - DS24 (persisted validation verdict, keyed by scope / modality / objective)
key_invariants:
  - "Scans TWICE -- once over tissue, once over bare slide -- so a peak that is coverslip rather than sample is identified by measurement, not by threshold guesswork."
  - "The verdict is what licenses approach-from-safe-Z autofocus (W5/W6 and the multi-slide slot jump in W1b). Without a stored validation those paths warn and fall back."
  - "A peak far from the sample FAILS validation; it does not silently move the tissue gate."
  - "The operator's tissue-gate choice is sticky across runs."
```

### W35: RegisterObjectiveWorkflow

```yaml
id: W35
class: qupath.ext.qpsc.controller.RegisterObjectiveWorkflow
file: src/main/java/qupath/ext/qpsc/controller/RegisterObjectiveWorkflow.java
entry: run(QuPathGUI)
category: configuration
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Microscope Configuration > Register Current Objective... (SetupScope.java:799, direct call; needs a writable config; disabled offline)"
reads:
  - DS17 (MicroManager: pixel size of the objective currently in the light path)
  - DS3 / DS19 (existing config files)
writes:
  - DS3 / DS19 (skeleton objective entries via ObjectiveConfigWriter)
key_invariants:
  - "MicroManager exposes ONLY pixel size (plus binning and a raw turret label). Magnification, NA, id and display name cannot be read and are prompted -- never inferred."
  - "Calibration-heavy fields are written as `calibrated: false` placeholders. This is the registration half of a two-step flow; the autofocus / white-balance / background utilities finish it."
```

### W36: LCCalibrationWorkflow (LC-PolScope)

```yaml
id: W36
class: qupath.ext.qpsc.modality.lcpolscope.workflow.LCCalibrationWorkflow
file: src/main/java/qupath/ext/qpsc/modality/lcpolscope/workflow/LCCalibrationWorkflow.java
entry: run()
category: calibration
ui_entries:
  - "Menu contribution: LCPolScopeModalityUi.getMenuContributions() -> 'Calibrate Liquid Crystals...' (id lcCalibration; appears only on a scope whose config declares the lcpolscope modality; read via ModalityRegistry.getUi(handler)). The 'LC-PolScope Setup...' item (id lcSetup, LCPolScopeSetupWindow.show()) sits beside it and calls this workflow's run() / applySaved() for step 2, startBackgroundCollection() for step 3, and LCVoltageSweepWorkflow.run() ('Measure LC voltage curves...', diagnostic, changes nothing)."
  - "Per-slide reminder before acquiring: LCPolScopeModalityUi.confirmBeforeAcquiring / confirmBatch -> LCPolScopeSlideReminder (called from AcquisitionManager, W2 and W1b through ModalityRegistry.getUi)."
reads:
  - DS17 (live camera / LC device)
  - DS19 (modality config)
writes:
  - DS19 (LC palette: extinction point + swing states)
key_invariants:
  - "Run on a clear, specimen-free field: the calibration measures how dark extinction can be made, so anything birefringent in the field degrades the result."
  - "Reports an extinction ratio as its quality figure; 100+ is good."
  - "The server's `assessment` of `unreliable` overrides the ratio band: the headline, the dialog's Apply confirmation and the log warning follow it, whatever the ratio. The client does not re-derive reliability; it reads `reliability_issues` from the server result."
  - "Modality NAME vs TYPE: dispatch keys off the NAME (`lcpolscope` / `lcps` prefixes in DS41); the YAML `type:` is `polarized`, deliberately shared with PPM so LC-PolScope inherits the right sample-physics defaults. Writing `type: lcpolscope` in a YAML yields a modality that resolves to nothing."
```

### W37: StitchingQueueWindow (UI singleton)

```yaml
id: W37
class: qupath.ext.qpsc.ui.acquisition.StitchingQueueWindow
file: src/main/java/qupath/ext/qpsc/ui/acquisition/StitchingQueueWindow.java
model: qupath.ext.qpsc.stitching.StitchingQueueModel (pure, unit-tested)
entry: open() / enqueue(...) / show(operationId, label) / recordOutput(operationId, entry)
category: ui_singleton
ui_entries:
  - "Menu: Extensions > QP Scope > Acquisition controls > Stitching queue (SetupScope.java:547, direct StitchingQueueWindow.open())"
  - "Implicit: AcquisitionManager.launchStitching enqueues each job (W1); StitchingHelper and StitchingRecoveryWorkflow (W16) report progress through show()"
reads:
  - StitchingQueueModel (jobs of this QuPath session: QUEUED / RUNNING / DONE / FAILED, status line, elapsed, produced entries)
writes:
  - none persistent; 'Open' on a finished row opens that entry -- the only place a stitched image is opened, and only on the operator's click
key_invariants:
  - "Non-modal, owned by the QuPath main window, never forces itself to the front. Replaced the window-modal always-on-top StitchingBlockingDialog (a9357ec4)."
  - "Hides itself about 20 s after the queue drains unless a job failed; reopened by the menu item or the next enqueue."
  - "DualProgressDialog closes when the acquisition ends, not when stitching ends."
```

### W38: Stop acquisition

```yaml
id: W38
class: qupath.ext.qpsc.controller.workflow.AcquisitionManager
file: src/main/java/qupath/ext/qpsc/controller/workflow/AcquisitionManager.java
entry: stopActiveAcquisition() (static)
category: control
ui_entries:
  - "Menu: Extensions > QP Scope > Acquisition controls > Stop acquisition... (SetupScope.java:538 -> SetupScope.confirmAndStopAcquisition)"
  - "Live Viewer red 'Stop acquisition' button while the stage is held (W19)"
  - "W1 validatePrerequisites offers it when a new run is refused because an acquisition is running"
reads:
  - "AcquisitionManager.ACTIVE (the manager whose acquisition holds the stage)"
  - DS17 (acquisitionActive, for the no-registered-run fallback)
writes:
  - "state.cancelRequested on the active run (read by CancelGate before each stage-driving step)"
  - DS45 (CANCEL, via the progress dialog's requestCancel or directly on a worker thread)
key_invariants:
  - "The current tile finishes, then the microscope stops; already-acquired data is kept."
  - "Works when the progress dialog has already closed itself; the progress dialog no longer closes on Cancel until the run acknowledges it (60 s fallback, CancelAckState)."
```

### W39: BackgroundProfilesDialog

```yaml
id: W39
class: qupath.ext.qpsc.ui.background.BackgroundProfilesDialog
file: src/main/java/qupath/ext/qpsc/ui/background/BackgroundProfilesDialog.java
entry: show(owner, baseFolder, onClosed)
category: configuration
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Image Quality > Background Profiles... (SetupScope.java:582, dispatch 'backgroundProfiles'; needs a valid config, works offline)"
reads:
  - DS23 (<base>/profiles/<id>/profile.yml via BackgroundProfiles.list / find)
  - DS44 (current Stage Map insert, for the profile's expected setup)
writes:
  - DS23 (create / edit / delete profiles; Adopt moves pre-profile background folders into a profile)
  - DS24 (activeBackgroundProfile via BackgroundProfiles.setActiveId)
key_invariants:
  - "Modeless and always on top, one window at a time."
  - "No active profile -> no backgrounds are applied and W4 refuses to collect."
```

### W40: FlatfieldFitDialog (BaSiC fit from tiles)

```yaml
id: W40
class: qupath.ext.qpsc.ui.background.FlatfieldFitDialog
file: src/main/java/qupath/ext/qpsc/ui/background/FlatfieldFitDialog.java
entry: show(ownerStage)
category: calibration
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Image Quality > Estimate Flat-Field from Tiles (BaSiC)... (SetupScope.java:573, direct call; needs a valid config)"
reads:
  - user-selected tile folder (must be visible from the server machine)
writes:
  - DS45 (FLATFIT, then FLATPOLL until the fit finishes -- MicroscopeSocketClient.fitFlatfield)
  - "server-written BaSiC profile folder; selected for acquisitions by DS24 flatfieldMethod=BASIC + flatfieldProfileFolder (sent as --flatfield-folder) and stamped on entries as DS9 FLATFIELD_PROFILE"
key_invariants:
  - "For detectors with no background image (PMT, laser scanner). The acquisition-side BaSiC choice is not offered for BF+IF, and --flatfield-folder is never sent together with --bg-correction."
```

### W41: OrientationColorViewWindow (LC-PolScope display)

```yaml
id: W41
class: qupath.ext.qpsc.display.orientation.OrientationColorViewWindow
file: src/main/java/qupath/ext/qpsc/display/orientation/OrientationColorViewWindow.java
entry: show(qupath)
category: ui_singleton
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Project Tools > Orientation Color View... (SetupScope.java:773, direct call; no microscope)"
reads:
  - "stitched LC-PolScope orientation + retardance pyramids of the open image (OrientationPairing)"
writes:
  - "viewer pixel-layer overlay only (replaces a pixel classifier preview in that slot)"
  - "DS30-like: 'Export color image...' writes an RGB OME-TIFF and adds it to the project (OrientationColorExport.write / addToProject)"
key_invariants:
  - "Display only unless Export is pressed: nothing is re-stitched; tiles are recolored for what is on screen."
```

### W42: ExportSpatialSceneWorkflow

```yaml
id: W42
class: qupath.ext.qpsc.controller.ExportSpatialSceneWorkflow
file: src/main/java/qupath/ext/qpsc/controller/ExportSpatialSceneWorkflow.java
entry: run(qupath)
category: utility
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Project Tools > Export Spatial Relationships (OME-NGFF 0.6)... (SetupScope.java:790, direct call; offline)"
reads:
  - DS5, DS6 (saved alignments), DS9 (XY offsets, stage bounds, light-path snapshot, isCameraView), DS27
writes:
  - "<project>/ngff/*.json (OME-NGFF 0.6 scene metadata, one file per connected group)"
key_invariants:
  - "No new transform math: every transform comes from the function the rest of QPSC uses for that relationship (ForwardPropagationWorkflow.buildSubToStageTransform, ImageMetadataManager.buildBoundingBoxPixelToStageTransform, ForwardPropagationWorkflow.createFlip, the saved JSON as-is)."
  - "Nothing in acquisition or propagation reads these files; changes nothing in the project."
```

### W43: MicroscopeSwitcher (switch config without restart)

```yaml
id: W43
class: qupath.ext.qpsc.controller.MicroscopeSwitcher
file: src/main/java/qupath/ext/qpsc/controller/MicroscopeSwitcher.java
entry: switchTo(configPath, confirm)
category: configuration
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Microscope Configuration > Switch Microscope... (SetupScope.java:655; applies the Microscope Config File preference)"
  - "Called by W44 DemoMicroscope"
reads:
  - DS25 (microscopeConfigFile)
  - DS17 (acquisitionActive; the server's own account)
writes:
  - DS3 (reload; rolls the config manager and preference back on an invalid file)
  - DS44 (drops StageInsertRegistry and holder-tilt registries derived from the config)
  - DS45 (disconnect, reconnect, CONFIG re-sent on both sockets)
  - "the QP Scope menu (SetupScope rebuild, which clears and re-adds every item, modality submenus included); reopens W19 / W21 if they were open"
key_invariants:
  - "Refused while an acquisition is running. Closes every QP Scope window (QpscWindows) first."
```

### W44: DemoMicroscope

```yaml
id: W44
class: qupath.ext.qpsc.controller.DemoMicroscope
file: src/main/java/qupath/ext/qpsc/controller/DemoMicroscope.java
entry: connect()
category: configuration
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Microscope Configuration > Connect to a Demo Microscope... (SetupScope.java:414; always available)"
reads:
  - "bundled demo config (BundledScopeInstaller.Bundle.DEMO)"
  - DS25 (server host / port, server launcher)
writes:
  - "extracts the demo config to disk if missing"
  - DS25 (server host set to this computer)
  - "starts microscope-command-server --demo if nothing listens on the port"
  - "W43 switch to the demo config when it is not already active; opens W19"
```

### W45: LightPathSetupDialog

```yaml
id: W45
class: qupath.ext.qpsc.ui.alignment.LightPathSetupDialog
file: src/main/java/qupath/ext/qpsc/ui/alignment/LightPathSetupDialog.java
entry: show()
category: configuration
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Microscope Configuration > Light Path Orientation... (SetupScope.java:810; needs a writable config)"
reads:
  - DS3 (light_path block via LightPathModel)
writes:
  - DS3 (light_path: scope type, optical flip) and reloads the config
key_invariants:
  - "Set once per rig. Optical flip drives the Stage Map Camera View; scope type is recorded but not re-applied by Stage View (stage polarity + alignment already carry it). The run-to-run slide-180 rotation stays on the Stage Map. See ORIENTATION_STACK.md."
```

### W46: BugReportDialog

```yaml
id: W46
class: qupath.ext.qpsc.ui.connection.BugReportDialog
file: src/main/java/qupath/ext/qpsc/ui/connection/BugReportDialog.java
entry: show()
category: service
ui_entries:
  - "Menu: Extensions > QP Scope > Report a Bug... (SetupScope.java:1042; always available)"
writes:
  - "external: BugReportService -> Cloudflare Worker -> public GitHub issue (the Worker also pushes new issues to ntfy when its NTFY_TOPIC secret is set)"
key_invariants:
  - "One of three copies (QPSC, QP-CAT, DL classifier) with no shared code. Read claude-reports/design/2026-08-19_bug-reporter-architecture.md (INV-1..INV-9) before changing the scrubber, payload or artifact set."
```

---

## 3. Data surfaces

Data surface IDs are stable. Grouped by category. When a surface is written
by multiple producers, list them all in `written_by` for traceability.

### Configuration & YAML

#### DS3: Microscope YAML config

```yaml
id: DS3
category: configuration
storage_kind: file
location: "configured via QPPreferenceDialog.getMicroscopeConfigFileProperty(); typically config_<scope>.yml + shared resource YAMLs"
fields:
  microscope.name: "string -- canonical microscope name"
  microscope.type: "string -- LOCI scope type"
  hardware.objectives[<id>].pixel_size_xy_um[<detector>]: "double"
  hardware.detectors[<id>].width_px / height_px: "int"
  imaging_profiles[<modality>.<objective>.<detector>]: "modality-specific profile (exposures, gains, AF, etc.)"
  stage.streaming_af.*: "stage probe results -- rewritten by W20"
  stage.polarity: "stage wiring polarity, read through StagePolarity.current() (was two preferences until f326a092, 2026-10-27)"
  stage.limits.z_um.low / high: "Z travel; MicroscopeConfigManager.getStageZTravelUm() = |high - low| is the Z range maximum in W1, W2 and W32 dialogs (3074f504)"
  light_path: "scope type, slide insertion, optical flip (LightPathModel) -- written by W45"
  rotation.angles[<modality>]: "rotation stage presets"
written_by:
  - "W22 ConfigFileWriter"
  - "W20 ProbeStageAfWorkflow (stage.streaming_af.*)"
  - "W6 AutofocusEditorWorkflow"
  - "W35 RegisterObjectiveWorkflow (objective stubs)"
  - "W45 LightPathSetupDialog (light_path)"
  - "W21 Stage Map insert calibration (insert reference points)"
read_by:
  - "ALL workflows via MicroscopeConfigManager.getInstance / MicroscopeConfigManager.current()"
invariants:
  - "Loaded once per process; reload() must be explicitly called after edits. W43 MicroscopeSwitcher reloads and rebuilds everything derived from it at runtime."
  - "Workflows disable themselves on bad config (configValid check at SetupScope startup)."
gates_at_load:
  - "validateRequiredKeys at startup"
related_surfaces:
  - DS19 (acquisition command file derives modality/objective from DS3)
  - DS20 (parfocality sidecar)
  - DS21 (WB calibration YAML)
  - DS47 (PPM calibration files)
```

#### DS19: Acquisition command file (per-run)

```yaml
id: DS19
category: configuration
storage_kind: file
location: "<tempTileDir>/.. transient command file written by AcquisitionCommandBuilder"
fields:
  modality, objective, detector, exposures, angles, focusChannelId, wbMode, channelExposures, tile config path
written_by:
  - "W1, W2, W10, W11 (via AcquisitionCommandBuilder)"
read_by:
  - "microscope command server (out of process)"
invariants:
  - "Transient -- consumed once per acquisition. Not persisted with project."
```

#### DS20: Parfocality YAML sidecar

```yaml
id: DS20
category: configuration
storage_kind: file
location: "parfocality_<scope>.yml next to microscope config"
fields:
  per-profile relative Z offsets, reference profile
written_by:
  - "W31 ParfocalityCalibrationController"
read_by:
  - "AcquisitionManager / MicroscopeController during modality switch"
```

#### DS21: White-balance calibration YAML

```yaml
id: DS21
category: configuration
storage_kind: file
location: "white_balance_calibration/ directory (path from microscope YAML)"
fields:
  imaging_profiles.<modality>.<objective>.<detector>.exposures_ms.*
  black_level entries
  gain entries
written_by:
  - "W9 WhiteBalanceWorkflow (runSimpleCalibration / runPPMCalibration)"
read_by:
  - "W1, W2, W10 (consumed by ChannelResolutionService for exposures)"
  - "W30 (Camera Control dialog displays calibrated values)"
invariants:
  - "validateObjectiveBeforeCalibration MUST fire before write to prevent overwriting wrong YAML key."
related_surfaces:
  - DS22 (WB mode validity check)
```

#### DS22: BackgroundSettings / WB mode validity

```yaml
id: DS22
category: configuration
storage_kind: derived
fields:
  ChannelBackground records; WbModeValidity records
written_by:
  - "BackgroundSettingsReader (loads from disk)"
read_by:
  - "W4 BackgroundValidityChecker"
  - "Per-workflow background validity gate"
```

#### DS23: Background-correction TIFF files

```yaml
id: DS23
category: configuration
storage_kind: file
location: "<base>/profiles/<backgroundProfile>/<detector>/<modalityFamily>/<magnification>/<wbMode>/ (angle modes)
           OR <base>/profiles/<backgroundProfile>/<detector>/<modalityFamily>/<magnification>/<profileKey>/ (channel modes).
           <base> = imageprocessing_<scope>.yml -> background_correction.base_folder (scope-wide).
           <backgroundProfile> = DS24 pref activeBackgroundProfile, resolved by BackgroundProfiles.activeFolder;
           none active -> no backgrounds. Folders directly under <base> (pre-profile layout) are not read.
           wbMode is 'off' -> collapses (no wbMode subdir) | 'simple' | 'per_angle' | 'camera_awb'."
fields:
  per_angle_tif: "<folder>/<angleName>.tif per rotation angle (PPM-style)"
  per_channel_tif: "<folder>/<channelId>.tif per channel (fluorescence-style)"
  manifest: "<folder>/background_settings.yml -- BackgroundSettings: modality, objective, detector, magnification, angle_exposures, wb_mode, profile_key, profile_illumination_intensity, lamp_available, applied_lamp_intensity, lamp_device_label, channel_backgrounds[], context{profile, insert, slide_insertion, stage_xy_um, stage_z_um, collected_at}"
  profile_record: "<base>/profiles/<id>/profile.yml -- BackgroundProfiles.BackgroundProfile: id, name, description, created, expects{insert, slide_insertion}, attributes{}"
written_by:
  - "W4 BackgroundCollectionWorkflow (server-driven BGACQUIRE)"
  - "W39 BackgroundProfilesDialog (profile.yml create / edit / delete / adopt)"
read_by:
  - "Microscope server during ACQUIRE -- AcquisitionCommandBuilder passes --bg-folder and --bg-profile when bgMethod enabled"
  - "BackgroundSettingsReader.findBackgroundSettings / BackgroundValidityChecker (manifest only); BrightfieldModalityHandler.resolveExposureMs (the exposure)"
  - "BackgroundProfiles (profile.yml), BackgroundProfileSelector / BackgroundProfilesDialog (UI), BackgroundProfileHint (Stage Map insert change)"
  - "BackgroundIlluminationCheck (profile expects vs Stage Map insert / entry slide placement, warn only)"
  - "DS22 (loaded form)"
lifecycle:
  created_when: "W4 completes successfully"
  destroyed_when: "manually deleted; not auto-cleaned"
  overwritten_when: "re-collecting BG for the same (backgroundProfile, detector, modality, magnification, wbMode/profileKey) tuple"
invariants:
  - "Folder structure encodes magnification: a pixel-size mismatch between wizard objective and the magnification subfolder silently files BG for the wrong objective. W4 pre-validates via QPScopeChecks.validateObjectivePixelSize."
  - "Per-modality monochrome choice (DS24 pref flatfieldMethod: NONE / BACKGROUND / BASIC, read via PersistentPreferences.getFlatfieldMethod) controls whether mono detector channels apply BG, or a BaSiC profile (pref flatfieldProfileFolder, sent as --flatfield-folder) instead. AcquisitionConfigurationBuilder.effectiveFlatfieldMethod resolves the choice per run."
  - "Per-modality-family adaptive mode (DS24 pref qpscBgExposureMode.<family>) chooses PROFILE / TARGET / OVERRIDE exposure strategy."
related_surfaces:
  - DS22 (loaded form of the manifest)
  - DS3 (imageprocessing_<scope>.yml -> background_correction.* config root)
  - DS19 (autofocus config -- separate, but BG path resolution lives next to it in imageprocessing_<scope>.yml)
```

#### DS47: PPM calibration files

```yaml
id: DS47
category: configuration
storage_kind: file
location: "PPMPreferences.activeCalibrationPath -- directory selected via QPPreferenceDialog"
fields:
  per-angle calibration data; hue-to-angle regression; birefringence optima
written_by:
  - "W23 PolarizerCalibrationWorkflow"
  - "W24 BirefringenceOptimizationWorkflow"
  - "W25 SunburstCalibrationWorkflow"
read_by:
  - "Downstream PPM analysis (qupath-extension-ppm; out of repo)"
  - "W26 PPMSensitivityTestWorkflow"
invariants:
  - "Five 'PPM Polarity:' MeasurementList keys are a stable downstream contract (commit 35df8fb). Don't rename without coordinating with qupath-extension-ppm."
related_surfaces:
  - DS9 (PPM_CALIBRATION per-entry metadata pointer)
```

### Alignment surfaces

#### DS4: TransformPreset (per scope pair)

```yaml
id: DS4
category: alignment
storage_kind: file
location: "alignment YAML at AffineTransformManager(configDirectory) -- typically next to microscope config"
fields:
  name: "string -- typically '<source>_to_<target>' or similar"
  microscope: "string -- target scope name"
  sourceScanner: "string"
  mountingMethod: "string"
  transform: "AffineTransform serialized via custom JsonAdapter"
  flipMacroX, flipMacroY: "Boolean -- optical flip for THIS (source, target) pair"
  greenBoxParams: "GreenBoxDetector.DetectionParams"
  zScale, zOffset: "double -- optional 3D transform"
  notes, createdDate
  greenBoxDisplayCenterX/Y, stageAnchorX/Y: "double -- overlay anchor"
  macroDisplayWidth/Height, macroPixelSizeUm: "int / double"
written_by:
  - "W3 MicroscopeAlignmentWorkflow.savePreset (on alignment success)"
read_by:
  - "W1d ExistingAlignmentPath (getBestPresetForPair for source-scanner / active-scope pair)"
  - "W21 StageMapWindow (active source preset for Apply Flips composite)"
  - "AffineTransformManager.getTransformsForMicroscope (presets list dialog)"
invariants:
  - "Per (source-scanner, target-microscope) pair. Multiple presets allowed; getBestPresetForPair picks current."
  - "flipMacroX/Y is the optical flip on THE PRESET. Per-slide JSON stores its own snapshot of flip-at-alignment-time (see DS5)."
gates_at_load: []
related_surfaces:
  - DS5 (per-slide JSON -- derived at alignment time from the preset)
```

#### DS5: Per-slide alignment JSON (macro frame)

```yaml
id: DS5
category: alignment
storage_kind: file
location: "<projectDir>/alignmentFiles/<lookupKey>_<scope>_alignment.json"
location_legacy: "<projectDir>/alignmentFiles/<lookupKey>_alignment.json (unscoped fallback)"
pixel_frame: PIXEL_FRAME_MACRO
fields:
  transform: "6-double affine matrix [m00, m10, m01, m11, m02, m12]"
  flipMacroX: "bool -- alignment-time macro flip; reads open-entry flip at save time"
  flipMacroY: "bool"
  pixel_frame: "string -- 'macro' here"
  objective: "string -- objective ID the alignment was built against (H8 advisory)"
  detector: "string -- detector ID at alignment time"
  created: "ISO timestamp"
  flipFrameVerified: "bool -- true post-2026-05-19 fix"
written_by:
  - "W3 MicroscopeAlignmentWorkflow.saveGeneralTransform (method at line 1300, saveSlideAlignment call ~1624, commit 5bf0f7bf)"
  - "W1e ManualAlignmentPath.createManualAlignment"
  - "W1d ExistingAlignmentPath.saveSlideAlignment"
  - "W1 ExistingImageWorkflowV2.saveRefinedAlignment (line 2943-3097, ONLY on refinement accepted; short-circuits on sub-acquisition)"
read_by:
  - "W1 via AlignmentHelper.checkForSlideAlignment (tier 1)"
  - "W21 StageMapWindow (source dropdown / Apply Flips)"
  - "W19 LiveViewerWindow Go-To-Centroid"
  - "W12 ForwardPropagationWorkflow.buildGroups (tier 1 of three-tier lookup)"
  - "W1 cross-scope path: AffineTransformManager.loadAllSlideAlignmentsFromDirectory enumerates all scopes"
  - "W42 ExportSpatialSceneWorkflow"
  - "All loaders share one scoped-then-unscoped file search, and the transform-only loaders read through the frame-returning loader (d87df372)"
lifecycle:
  created_when: "any of the 4 save-site workflows succeeds"
  destroyed_when: "manually deleted; not auto-cleaned"
  overwritten_when: "re-alignment writes to same lookup key + scope"
invariants:
  - "ALWAYS save with PIXEL_FRAME_MACRO (default)."
  - "flipMacroX/Y MUST come from the open entry's metadata, not preset (stage-mirror bug, 2026-05-18)."
  - "lookupKey MUST be resolved via AlignmentHelper.resolveMacroLookupKey (walks base_image chain)."
  - "All 4 save sites converged on the entry-flip-read + resolveMacroLookupKey pattern (Phase 4 M1)."
gates_at_load:
  - "Layer 2 gate at AlignmentHelper.checkForSlideAlignment refuses pixel_frame != 'macro'"
  - "Objective mismatch advisory (DIRECTIONAL: only fires when wizard mag > saved mag, 2026-05-19)"
  - "Pre-fix JSON (flipFrameVerified absent) -> Continue/Cancel advisory"
related_surfaces:
  - DS6 (sub-frame variant)
  - DS17 (currentTransform -- same transform, ephemeral)
  - DS4 (TransformPreset -- different scale, related concept)
```

#### DS6: Per-slide alignment JSON (sub frame, auto-registered)

```yaml
id: DS6
category: alignment
storage_kind: file
location: "<projectDir>/alignmentFiles/derived/<subImageName>_<scope>_alignment.json"
pixel_frame: PIXEL_FRAME_SUB
fields: "same shape as DS5 but pixel_frame='sub'; transform scale equals the sub-image's own pixel calibration"
written_by:
  - "StitchingHelper.autoRegisterBoundsTransformIfAvailable (W14) -- one per stitched sub-image"
read_by:
  - "W19 LiveViewerWindow Go-To-Centroid"
  - "W12 ForwardPropagationWorkflow (tier 2)"
  - "W1a processSubAcquisitionPath legacy fallback via AffineTransformManager.getDerivedAlignmentMicroscope"
invariants:
  - "ONLY StitchingHelper auto-register writes pixel_frame='sub'. Workflows operating on macro-frame annotations REFUSE 'sub' at load."
  - "Filename encodes the acquiring scope; getDerivedAlignmentMicroscope parses this for legacy cross-scope fallback."
related_surfaces:
  - DS9 (STAGE_BOUNDS_* and STITCHER_FLIP_* on the entry are the self-contained backup record)
```

#### DS7: Saved macro raw image

```yaml
id: DS7
category: alignment
storage_kind: file
location: "<projectDir>/alignmentFiles/<lookupKey>_macro.raw or similar (see AffineTransformManager.isSavedMacroRawFormat / loadSavedMacroImage)"
fields: "raw pixel dump for the macro image used at alignment time"
written_by:
  - "W3 MicroscopeAlignmentWorkflow (when saving macro snapshot)"
read_by:
  - "W12, W13 (propagation may load macro for visual context)"
  - "W21 StageMapWindow"
```

#### DS17: MicroscopeController.currentTransform (in-memory)

```yaml
id: DS17
category: alignment
storage_kind: in_memory
location: "MicroscopeController singleton (qupath.ext.qpsc.service.microscope; process-scoped; imports nothing from the UI since f17bd1cd)"
fields:
  currentTransform: AffineTransform
  acquisitionActive: boolean
  isConnected (composite of primary + aux): boolean
  rotation stage state
  live exposures / gains
written_by:
  - "W1 setCurrentTransform after validateAndFlipIfNeeded (MUST be in this order)"
  - "W1 cleanup() sets currentTransform = null (H6)"
  - "W2 implicitly (stage-frame native; no transform set)"
  - "W3 during refinement and at end of MicroscopeAlignmentWorkflow.saveGeneralTransform"
  - "W19 LiveViewer stage motion (moveStageXY/Z/R)"
  - "W19 StageControlPanel direct setCurrentTransform calls -- slide-transform restore + acquired-image alignment (StageControlPanel.java:~4654, ~4991)"
  - "W1d ExistingAlignmentPath (ExistingAlignmentPath.java:~329) and W1e ManualAlignmentPath after user accepts manual alignment (ManualAlignmentPath.java:~393)"
  - "W21 PlacementChangeCoordinator clears it (null) when the slide placement changes"
  - "W1 createRefinementTilesAndRunOnSource on refinement-accepted (only), for SingleTileRefinement / MultiTileRefinement"
read_by:
  - "Every workflow that issues stage commands -- onMoveButtonClicked converts via currentTransform"
  - "W19 LiveViewerWindow Go-To-Centroid (StageControlPanel:~4642, ~5001)"
  - "ExistingImageAcquisitionController (dialog preview of the current transform)"
  - "W14 StitchingHelper metadata calc at launchStitching (reads MicroscopeController.getCurrentTransform)"
invariants:
  - "currentTransform MUST be cleared in cleanup() (H6 fix) -- by the run that holds the DS49 WorkflowRegistry slot only. A stale transform leaks to Live Viewer and the next workflow; a cleanup by a run that no longer holds the slot nulls the next run's transform."
  - "validateAndFlipIfNeeded MUST run BEFORE setCurrentTransform (M11 deferred install)."
  - "currentTransformProperty (JavaFX property) emits change events; subscribers update UI."
  - "Both primary and aux socket may be connected independently; isConnected returns true if either is."
related_surfaces:
  - DS5 (persisted form of the same transform)
  - DS45 (socket clients -- where transform applies to stage motion commands)
```

### Image metadata (DS8..DS18 -- one surface per metadata group on ProjectImageEntry)

#### DS8: Image collection grouping

```yaml
id: DS8
category: metadata
storage_kind: project_entry_metadata
fields:
  IMAGE_COLLECTION = "image_collection": int -- 1..N, next number per project
written_by:
  - "ImageMetadataManager.getNextImageCollectionNumber (called at image-add sites in W1, W2, W14)"
read_by:
  - "ImageMetadataManager.isInCollection (filters)"
  - "Project image-list views"
invariants:
  - "Numbers are assigned sequentially -- highest existing + 1."
```

#### DS9: Per-entry metadata bundle (consolidated -- the big one)

```yaml
id: DS9
category: metadata
storage_kind: project_entry_metadata
location: "ProjectImageEntry.getMetadataMap() (QuPath built-in storage; serialized with project)"
fields_xy:
  XY_OFFSET_X = "xy_offset_x_microns": double
  XY_OFFSET_Y = "xy_offset_y_microns": double
  Z_OFFSET = "z_offset_microns": double
fields_light_path:
  # flip_x / flip_y were REMOVED in v0.9.0 (1355d237); orientation is the light-path snapshot below.
  LP_SCOPE_TYPE, LP_SLIDE_INSERTION, LP_OPTICAL_FLIP, LP_CAMERA_ORIENTATION, LP_STAGE_POLARITY = "lp_*": string (snapshot at entry creation; setLightPath / getLightPath)
  LP_MACRO_FLIP_X, LP_MACRO_FLIP_Y = "lp_macro_flip_x/y": bool
  LP_BAKED_PARITY_X, LP_BAKED_PARITY_Y = "lp_baked_parity_x/y": bool (net mirror baked into this entry's pixels; read via bakedParity(entry))
  LP_ROTATION_DEG = "lp_rotation_deg": int (getRotationDeg / setRotationDeg)
  CAMERA_VIEW = "camera_view": bool (the '(Camera View)' companion; isCameraView / setCameraView)
  SLIDE_CENTER_STAGE_X_UM, SLIDE_CENTER_STAGE_Y_UM: double
fields_identity:
  SAMPLE_NAME = "sample_name": string
  ORIGINAL_IMAGE_ID = "original_image_id": string (QuPath entry ID of the original import)
  BASE_IMAGE = "base_image": string (name of macro/parent for sub-images)
  IMAGE_INDEX = "image_index": int
fields_acquisition_params:
  MODALITY = "modality": string
  OBJECTIVE = "objective": string
  ANGLE = "angle": string (PPM rotation angle name)
  ANNOTATION_NAME = "annotation_name": string
  DETECTOR_ID = "detector_id": string
  WHITE_BALANCE = "white_balance": string (WB mode of the acquisition, 14b8946e)
  BACKGROUND_PROFILE = "background_profile": string (active background profile id at acquisition)
  FLATFIELD_PROFILE = "flatfield_profile": string (BaSiC profile folder when one was used)
fields_stitch_reservation:
  "qpsc_stitch_status" (ProjectEntryReservation.STATUS_KEY): "pending" while a reserved entry waits for its stitch; see DS48
fields_source:
  SOURCE_MICROSCOPE = "source_microscope": string (the SCANNER that produced the original WSI; inherited from parent macro)
  ACQUIRED_ON_MICROSCOPE = "acquired_on_microscope": string (the MICROSCOPE that physically captured this image; absent on macro entries; added 2026-05-14 for cross-scope gating)
fields_fov:
  FOV_X_UM = "fov_x_um": double
  FOV_Y_UM = "fov_y_um": double
fields_source_roi:
  SOURCE_ROI_X_PX, SOURCE_ROI_Y_PX, SOURCE_ROI_W_PX, SOURCE_ROI_H_PX: double (ground-truth source rectangle on unflipped base, base-pixel coords)
  SOURCE_ROI_FLIP_X, SOURCE_ROI_FLIP_Y: bool (true when bbox derived from tile detections on a flipped sibling)
fields_stage_bounds:
  STAGE_BOUNDS_X1_UM, Y1_UM, X2_UM, Y2_UM: double (bounding-box image stage extent including half-FOV overshoot)
  STITCHER_FLIP_X, STITCHER_FLIP_Y: bool (stitcher flips applied at pyramid build time)
fields_ppm:
  PPM_CALIBRATION = "ppm_calibration": string (path or marker)
fields_multi_slide:
  SLIDE_POSITION = "slide_position": int
  SLIDE_CARRIER = "slide_carrier": string (carrier id like "quad_v")
  MS_RUN_ID = "ms_run_id": string (UUID)
  MS_ASSIGNED_AT = "ms_assigned_at": long
written_by:
  - "W1, W2 stitch-import / reservation sites via StitchingHelper -> full bundle stamp on each new sub-image entry; WHITE_BALANCE / BACKGROUND_PROFILE / FLATFIELD_PROFILE via StitchInfoSupport from the stitch record"
  - "ModalityHandler.stampImageMetadata (PPM calibration stamp)"
  - "W1b setSlideAssignment (SLIDE_POSITION, SLIDE_CARRIER, MS_RUN_ID, MS_ASSIGNED_AT); MultiSlideAssignmentDialog stamps the light-path snapshot on rotated companions"
  - "W21 PlacementChangeCoordinator (slide insertion)"
  - "Project import flow (manual scanner image add): SOURCE_MICROSCOPE via MicroscopeSelectionDialog"
  - "User edits (rare): manual metadata fields"
read_by:
  - "EVERY workflow -- gating, lookup, propagation, display"
invariants:
  - "The baked parity (LP_BAKED_PARITY_X/Y) is the OPTICAL mirror in the entry's pixels. Independent of stage polarity (DS3 stage.polarity)."
  - "SOURCE_MICROSCOPE is per-import; never overwrite from StageMapWindow dropdown (commit d531d696)."
  - "ACQUIRED_ON_MICROSCOPE is per-import-at-acquisition-time. Macro entries do NOT carry this field."
  - "BASE_IMAGE chain MUST be walked via AlignmentHelper.resolveMacroLookupKey to find macro-owning ancestor."
  - "SOURCE_ROI_* is base-pixel coords on the UNFLIPPED base entry; SOURCE_ROI_FLIP_* records mirror flag relative to the rectangle when derived from a flipped sibling."
  - "STAGE_BOUNDS_* + STITCHER_FLIP_* allow Move-to-Centroid to reconstruct pixel->stage without DS6 (cross-scope guard, restructured directory, legacy file)."
  - "bakedParity / isCameraView / getRotationDeg are the canonical readers (not direct metadata lookup, and never the entry NAME: '(rotated N) (Camera View)' is for the user only). FlipResolver composes them with the preset for the current flip."
  - "getSiblingsByBaseImage enumerates entries sharing the same BASE_IMAGE -- used for flipped sibling resolution."
related_surfaces:
  - DS5, DS6 (alignment JSONs cross-reference via lookupKey from getSampleName / BASE_IMAGE)
  - DS10 (former flip flags; replaced by the light-path snapshot above)
  - DS48 (reserved entries carry this bundle before their image exists)
```

#### DS10: Image flip flags (REMOVED; see DS9 light-path snapshot)

```yaml
id: DS10
category: metadata
status: removed
note: "flip_x / flip_y and ImageMetadataManager.isFlippedX/Y were removed in v0.9.0 (1355d237). Read ImageMetadataManager.bakedParity(entry) and isCameraView(entry) instead. Per CLAUDE.md: optical flip is independent of stage wiring polarity. Do NOT XOR with stage polarity."
related_surfaces:
  - DS9 (fields_light_path)
  - DS3 (stage.polarity -- distinct concept)
```

### Preferences

#### DS24: PersistentPreferences (per-user, lightweight)

```yaml
id: DS24
category: preferences
storage_kind: qupath_preferences
location: "QuPath PathPrefs (Java Preferences API, per-user)"
fields_examples:
  slideLabel, selectedScanner, boundingBoxString, boundingBoxWidth/Height/CenterX/Y
  greenThreshold, greenSaturationMin, greenBrightnessMin/Max, greenHueMin/Max, greenEdgeThickness, greenMinBoxWidth/Height
  tissueDetectionMethod, tissueMinRegionSize, tissuePercentile, tissueFixedThreshold, tissueEosinThreshold, tissueHematoxylinThreshold
  tissueSaturationThreshold, tissueBrightnessMin/Max, tissueArtifactFilterEnabled, tissueTwoPassRefine
  tissueMedianKernel, tissueMorphCloseKernel/Iter
  macroImagePixelSizeInMicrons, restitchPixelSize, restitchParallelAngles
  metadataPropagationPrefix, classList, modalityForAutomation
  activeBackgroundProfile (BackgroundProfiles.activeId), flatfieldMethod (NONE / BACKGROUND / BASIC), flatfieldProfileFolder
  (about 300 public static accessors -- see PersistentPreferences.java; Java names use unit suffixes such as getMacroImagePixelSizeUm, the stored keys did not change)
written_by:
  - "Various UI dialogs (SampleSetupController, AcquisitionWizardDialog, BackgroundCollectionController, etc.)"
read_by:
  - "Every workflow on dialog open"
invariants:
  - "Lightweight per-user prefs only. Dialog positions used to live here (8 KB cap caused issues) -- now in DS25a (JSON file). See 2026-05-19 dialog-position-storage refactor."
```

#### DS25: QPPreferenceDialog (per-user, structured)

```yaml
id: DS25
category: preferences
storage_kind: qupath_preferences
fields_examples:
  # Stage polarity is NOT here: stage.polarity in the microscope config (DS3) since f326a092.
  cameraOrientation
  microscopeServerHost, microscopeServerPort, autoConnectToServer
  microscopeConfigFile (path to YAML; applied at runtime by W43 Switch Microscope)
  projectsFolder
  tissueDetectionScript, dataBoundsClassifier
  saveRawTiles, tileHandling (getTileHandlingMethodProperty), tileOverlapPercent
  stitchingConcurrency, tileRegistrationEnabled
  multiSlideAcquisitionMode, multiSlideReuseAlignment, focusSurfaceMode, alignmentCheckMode, focusSurveyPoints, inheritHolderTilt
  compressionType (OMEPyramidWriter.CompressionType)
  stitching output format (StitchingConfig.OutputFormat)
  includeObjective/Modality/Annotation/Angle InFilename
  metadataPropagationPrefix
  skipManualAutofocus, disableAllAutofocus
  warnOnLowDiskSpace
  suppressExposureWarning, streamingMaxExposureMs
  streamingAfMigrationAcknowledged, autofocusYamlMigrationAcknowledged, autofocusEditorAdvancedMode
  notificationsEnabled, notificationTopic, notificationServer
  notifyOnAcquisition, notifyOnStitching, notifyOnErrors
  completionBeepEnabled
  lastCalibrationFolder
  sunburstLastModality, sunburstExpectedSpokes, sunburstSaturationThreshold, sunburstValueThreshold
  sunburstRadiusInner, sunburstRadiusOuter
  (singlePointDialogEnabled was removed with W7 in dadece3e)
written_by:
  - "QuPath Preferences dialog (shown via installPreferences())"
  - "Various code paths via static setters"
read_by:
  - "Every workflow"
invariants:
  - "Stage polarity is read via StagePolarity.current() (DS3 stage.polarity) -- do NOT read polarity directly in new code for sign conventions (use StageImageTransform.current() -- see DS26)."
  - "cameraOrientation composes with stage polarity through StageImageTransform; never read polarity booleans directly for live-view sign conventions."
related_surfaces:
  - DS26 (StageImageTransform composes DS3 stage.polarity + DS25 cameraOrientation)
```

#### DS25a: Dialog position storage (JSON file)

```yaml
id: DS25a
category: preferences
storage_kind: file
location: "DialogPositionManager 0.4.0 -- JSON file (NOT PathPrefs); shared across machines optionally"
fields:
  per-dialog x, y, w, h
written_by:
  - "DialogPositionManager (all dialogs save position on close)"
read_by:
  - "All dialog show() paths"
invariants:
  - "Migrated out of PathPrefs (8 KB cap) at 0.4.0 (commit e7a726b). One-time migration alert."
related_design_reports:
  - claude-reports/archive/2026-05/2026-05-19_stitch-cleanup-stage-cache-dialog-storage.md
```

### In-memory / Singleton state

#### DS41: ModalityRegistry (in-memory)

```yaml
id: DS41
category: in_memory
storage_kind: singleton_registry
location: "ModalityRegistry (static)"
fields:
  prefix -> ModalityHandler map (PPM, BF, BfIf, LSM, Fluorescence, Widefield, etc.)
default_registrations:
  - "ppm -> PPMModalityHandler"
  - "bf / brightfield -> BrightfieldModalityHandler"
  - "lsm / shg / 2p / confocal -> LaserScanningModalityHandler"
  - "fl / fluorescence / widefield / epi -> WidefieldFluorescenceModalityHandler"
  - "lcpolscope / lcps -> LCPolScopeModalityHandler"
  - "bf_if -> BfIfModalityHandler"
  handler class -> ModalityUi map (the UI half; ModalityUi.NONE when a handler has none)
ui_registrations:
  - "PPMModalityHandler -> modality/ppm/ui/PPMModalityUi (menu items, PPMBoundingBoxUI)"
  - "BrightfieldModalityHandler -> modality/ui/BrightfieldModalityUi (BrightfieldExposureBoundingBoxUI)"
  - "WidefieldFluorescenceModalityHandler (and its subclass BfIfModalityHandler) -> modality/widefield/ui/WidefieldModalityUi (WidefieldChannelBoundingBoxUI)"
  - "LCPolScopeModalityHandler -> modality/lcpolscope/ui/LCPolScopeModalityUi (menu items, bounding-box panel, LCLiveCameraControls, per-slide reminder)"
written_by:
  - "Static initializer block in ModalityRegistry (handlers)"
  - "registerHandler(prefix, handler) -- runtime extension"
  - "qupath.ext.qpsc.ModalityUis.registerAll() -> ModalityRegistry.registerUi(handlerClass, ui); called first in SetupScope.installExtension, and by harnesses that build QPSC dialogs without the extension"
read_by:
  - "getHandler(modality) -- longest registered prefix the name starts with; returns NoOpModalityHandler if unmatched"
  - "getUi(modalityName) / getUi(handler) -- the UI registered for the handler's class or its nearest superclass, else ModalityUi.NONE"
  - "Every acquisition workflow"
  - "SetupScope menu build (getMenuContributions), AcquisitionDialogBuilder (createBoundingBoxUI), StageControlPanel (buildLiveCameraControls), AcquisitionManager and W2 (confirmBeforeAcquiring), W1b (confirmBatch)"
invariants:
  - "Prefix matching is startsWith. Longer prefixes should register before shorter ones to avoid unintended matches."
  - "Returns NoOpModalityHandler for unknown -- never null. getUi never returns null either (ModalityUi.NONE)."
  - "Handlers hold no UI (ba56f743): the modality package may not import dialogs or workflows. Anything that shows a panel, a menu item, a Live Viewer control or a confirmation goes on the modality's ModalityUi, registered from the top-level ModalityUis. Shared code asks the handler hooks (reconstructsFromBackground, usesRotationStage, stampImageMetadata, retardanceCeilingNm, ...) instead of testing the modality name (7c145c1d)."
  - "PPMModalityUi.getMenuContributions() provides dynamic menu items (polarizerCalibration, ppmSensitivityTest, birefringenceOptimization, sunburstCalibration) -- not in QPScopeController.startWorkflow switch."
  - "LCPolScopeModalityUi.getMenuContributions() likewise provides 'LC-PolScope Setup...' (a window, LCPolScopeSetupWindow) and 'Calibrate Liquid Crystals...' (W36) -- also outside the startWorkflow switch, so it appears only on a scope whose config declares the modality."
  - "SetupScope builds one submenu per handler (identity set), so a handler registered under several prefixes (lcpolscope + lcps) appears once; the submenu is named by handler.getDisplayName()."
  - "A handler's registered PREFIX is the modality NAME, not its YAML `type:`. LC-PolScope registers `lcpolscope`/`lcps` but its type is `polarized` (shared with PPM). Do not conflate them."
related_surfaces:
  - DS42 (ModalityState active modality)
```

#### DS42: ModalityState (singleton)

```yaml
id: DS42
category: in_memory
storage_kind: singleton
location: "ModalityState.getInstance()"
fields:
  modality: string (current)
  validModalities: Set<String>
written_by:
  - "User actions (modality selection in wizard/dialog)"
read_by:
  - "Workflows that need active modality for handler dispatch"
related_surfaces:
  - DS41
```

#### DS42a: ObjectiveState (singleton)

```yaml
id: DS42a
category: in_memory
storage_kind: singleton
location: "ObjectiveState.getInstance()"
fields:
  objective: string (current)
  validObjectives: Set<String>
written_by:
  - "User actions (objective combo box)"
read_by:
  - "Workflows that need active objective for pixel size / FOV lookups"
```

#### DS26: StageImageTransform (composite, derived from DS25)

```yaml
id: DS26
category: in_memory
storage_kind: derived
location: "StageImageTransform.current() -- composes StagePolarity.current() (DS3 stage.polarity) + cameraOrientation (DS25)"
fields:
  stagePolarity, cameraOrientation
  screenPanDeltaToMmDelta: function
  clickOffsetToMmTarget: function
  stitcherFlipFlags: bool[]
  cameraFlipFlags: bool[] (Stage Map Apply Flips; NOT stitcherFlipFlags)
written_by:
  - "Implicit via DS3 stage.polarity and the DS25 cameraOrientation setter"
read_by:
  - "Live Viewer joystick / WASD / click-to-center"
  - "Stitcher flip flag derivation"
  - "Stage Map Apply Flips composite"
invariants:
  - "ALL live-view sign conventions (arrows, joystick, click-to-center, stitcher flip flags) MUST go through this composite. Don't read polarity booleans directly in new code."
  - "Polarity and camera orientation are INDEPENDENT -- six pre-2026 call sites read StagePolarity directly without composing with CameraOrientation; treated as a bug class."
related_design_reports:
  - claude-reports/design/2026-04-09_stage-image-transform-refactor.md
```

#### DS43: LiveViewer state

```yaml
id: DS43
category: in_memory
storage_kind: singleton
location: "LiveViewerWindow (static)"
fields:
  visible, streaming active, contrast, histogram bins
  show tiles enabled
  locked (with reason)
  last shown tile path
written_by:
  - "show / hide / lockControls / unlockControls / stopStreaming / restartStreaming"
  - "showAcquiredTile / scanAndShowLatestTile"
read_by:
  - "Any workflow that suspends the live viewer during primary-socket work"
invariants:
  - "lockControls(reason) and unlockControls() are static -- global state."
```

#### DS44: StageMap state / StageInsertRegistry

```yaml
id: DS44
category: in_memory
storage_kind: singleton + registry
location: "StageMapWindow (static), StageInsertRegistry (static)"
fields:
  visible, bounding-box preview (debounced)
  StageInsertRegistry.getAvailableInserts() -- carrier definitions
written_by:
  - "show / hide / setBoundingBoxPreview / clearBoundingBoxPreview"
  - "resetPollingErrors / resetWarningFlag"
read_by:
  - "W1b MultiSlideExistingImageWorkflow (carrier dropdown)"
  - "W21 itself"
  - "W39 / BackgroundProfiles.currentInsert (profile setup check)"
invariants:
  - "Bounding-box preview cleared on close (commit d553535)."
  - "W43 MicroscopeSwitcher drops StageInsertRegistry (and holder tilts) so they rebuild from the new config."
```

#### DS49: WorkflowRegistry active slot (singleton)

```yaml
id: DS49
category: in_memory
storage_kind: singleton
location: "qupath.ext.qpsc.controller.workflow.WorkflowRegistry.get()"
fields:
  activeId, activeKind, activeSource: "the run that owns MicroscopeController's currentTransform and acquisitionActive flag"
written_by:
  - "W1 execute(): tryActivate('existing-image', entryName) after validatePrerequisites"
  - "W1 cleanup(): release(registryId)"
read_by:
  - "W1 cleanup(): isActive(registryId) decides whether to clear DS17"
invariants:
  - "tryActivate is refused while an acquisition holds the stage (MicroscopeController.isAcquisitionActive); the refusal names Stop acquisition (W38)."
  - "A new run takes over the slot from a run that is only stitching; that older run's cleanup then leaves DS17 alone."
  - "Only W1 uses it at 05ee3284; W2 does not claim the slot."
```

#### DS45: MicroscopeSocketClient (singleton, two sockets)

```yaml
id: DS45
category: service
storage_kind: singleton
location: "qupath.ext.qpsc.service.microscope.MicroscopeSocketClient; MicroscopeController (same package) owns it; two TCP sockets to Python command server"
fields:
  primary socket: acquisition / CONFIG / long-running ops
  aux socket: Live Viewer frames, stage polling
  Command enum (8-byte verbs: CONFIG, GETXY, GETZ, MOVE, MOVEZ, ACQUIRE, BGACQUIRE, CANCEL, STATUS, PROGRESS, FLATFIT, FLATPOLL, LCCALIB, LCSWEEP, STRMAFZ, FINDTISS, ABORTAF, ...)
  AcquisitionState, AcquisitionProgress
written_by:
  - "MicroscopeController commands"
read_by:
  - "Every workflow with hardware interaction"
invariants:
  - "One exchange path (12e0f614): commands go through exchange / withReadTimeout / send / readReply / readStartedThenResult, which hold the socket lock, set the read timeout, send and read. A new command uses these, not its own lock-send-read code. A FAILED reply is an answer (ServerAnswerException), not a connection error, so it does not count toward 'Microscope Connection Unstable'."
  - "The client announces a loaded config through addConfiguredListener; it does not call up into controllers or dialogs (1ac7e68a)."
  - "Either-connected counts as connected for UI purposes."
  - "Route long-running ops to primary; anything that must not block Live Viewer to aux."
  - "Read timeouts and same-IP takeover guard prevent reconnect storms during long captures."
  - "Wire-level protocol documented in documentation/developer/SOCKET_PROTOCOL.md."
related_design_reports:
  - claude-reports/design/2026-04-22_dual-socket-connection-state-fix.md
  - claude-reports/design/2026-04-26_connection-stability-and-pre-acq-af-edge-retry.md
related_surfaces:
  - DS17 (controller state)
```

#### DS46: Data bounds classifier (.json)

```yaml
id: DS46
category: configuration
storage_kind: file
location: "QPPreferenceDialog.getDataBoundsClassifierProperty() -- user-chosen path"
fields: "QuPath pixel classifier JSON"
read_by:
  - "W1d ExistingAlignmentPath (used to detect signal-bearing region inside the macro)"
  - "W3 MicroscopeAlignmentWorkflow (required; gated up front in run())"
invariants:
  - "Different sample classes need different classifiers. Update the preference whenever you swap sample classes."
```

### Files & directories

#### DS27: QuPath project structure

```yaml
id: DS27
category: file
storage_kind: directory
location: "<projectsFolder>/<sampleName>/project.qpproj + sibling folders"
structure:
  project.qpproj: "QuPath project file"
  data/: "QuPath entry data"
  thumbnails/: "QuPath thumbnails"
  scripts/: "QuPath scripts"
  classifiers/: "QuPath classifiers"
  alignmentFiles/: "DS5, DS6, DS7"
  microscope_configs/: "DS3 copies (provenance)"
  SlideImages/: "DS30 stitched outputs"
  <acquisitionFolder>/: "DS28 per-acquisition tile dirs (modality folder + run index, e.g. ppm_20x_1; ProjectInfo.getAcquisitionFolder)"
  qpsc/stitching-pending.tif: "DS48 placeholder image shared by reserved entries"
  ngff/: "W42 OME-NGFF scene JSON"
written_by:
  - "W1, W2, W10 (createAndOpenQuPathProject)"
  - "QPProjectFunctions.addImageToProjectWithMetadata"
read_by:
  - "Every project-aware workflow"
invariants:
  - "When a project is already open, projectsFolder MUST be derived from the existing project's path (not from preference)."
```

#### DS28: Tile configuration files (per acquisition)

```yaml
id: DS28
category: file
storage_kind: directory
location: "<projectDir>/<sampleName>/<acquisitionFolder>/<annotation>/"
files:
  TileConfiguration.txt: "ImageJ-style tile positions (microns)"
  TileConfiguration_QP.txt: "QuPath-pixel-coord positions"
  tile images (TIFF or sub-folders per angle)
written_by:
  - "TilingUtilities.createTiles (W1, W2)"
  - "TilingUtilities.createTilesForAnnotations (W3 alignment, W1)"
  - "RapidScan output path (W11)"
read_by:
  - "Microscope command server (consumes positions)"
  - "Stitcher (qupath-extension-tiles-to-pyramid)"
  - "W16 StitchingRecoveryWorkflow"
lifecycle:
  created_when: "before acquisition launches"
  destroyed_when: "TileCleanupHelper.performCleanup per QPPreferenceDialog.getTileHandlingMethodProperty (Delete / Zip / Keep; _protected folders are kept); force-delete on workflow error (Phase 11)"
invariants:
  - "The modality folder includes the objective suffix (e.g. 'Fluorescence_10x') via ObjectiveUtils.createEnhancedFolderName; the acquisition folder adds the run index (GLOSSARY.md)."
  - "TileConfiguration.txt is in MICRONS (stage); _QP.txt is in QuPath full-res PIXELS."
related_surfaces:
  - DS33 (TilingRequest input)
```

#### DS30: Stitched output images

```yaml
id: DS30
category: file
storage_kind: file
location: "<projectDir>/<sampleName>/SlideImages/<annotation>_<modality>_<angle>.ome.tif (or .zarr)"
formats:
  - OME-TIFF (default)
  - ZARR direct (convert to OME-TIFF on demand via W15 MakePortableWorkflow / StitchingHelper.convertSingleZarrToTiff)
written_by:
  - "Stitcher (tiles-to-pyramid) called from W14 StitchingHelper"
  - "W16 StitchingRecoveryWorkflow"
  - "W15 MakePortableWorkflow (ZARR -> OME-TIFF)"
read_by:
  - "QuPath project (added as new ProjectImageEntry)"
invariants:
  - "Naming: rename-after-stitch (commit area of 2026-05-19 polish)."
  - "Compression / format set via QPPreferenceDialog.compressionTypeProperty / stitchingOutputFormat."
  - "moveDirectoryWithRetryAndCopyFallback (qpsc 732fbd4) prevents stranded _temp_<angle>_<hash> folders."
related_design_reports:
  - claude-reports/archive/2026-05/2026-05-19_stitch-cleanup-stage-cache-dialog-storage.md
related_surfaces:
  - DS28 (input)
  - DS9 (entry metadata stamped post-stitch)
  - DS6 (auto-registered sub-frame alignment)
```

#### DS32: Sample directory

```yaml
id: DS32
category: file
storage_kind: directory
location: "<projectsFolder>/<sampleName>/"
note: "Parent of DS27. Owned by project setup; tile dirs and SlideImages live within."
```

#### DS48: Reserved stitch entries (ProjectEntryReservation)

```yaml
id: DS48
category: file
storage_kind: project_entry + file
location: "real ProjectImageEntry objects in the project; image = <project>/qpsc/stitching-pending.tif (StitchPlaceholderImage, written once per project)"
fields:
  qpsc_stitch_status: "pending while waiting (ProjectEntryReservation.STATUS_KEY); a failed job keeps its entries with the reason"
  metadata: "the full DS9 bundle the ordinary import would stamp (parent, offsets, sample, modality, objective, angle, annotation, index, acquiring microscope)"
written_by:
  - "StitchingHelper.reserveOutputs (W14) for W1 and W2: StitchOutputPlanner predicts one entry per output (angles plus biref/sum, LC-PolScope merge plus retardance / orientation / orientation_color, split IF channels plus the merge, the single brightfield file); ProjectEntryReservation.reserve runs synchronously on the FX thread before the job is queued; the reservation rides in stitchParams"
  - "ProjectEntryReservation.fill when the OME-TIFF lands (updateURIs -- no new entry, no reordering; sets image type, channel names, display ranges, saves image data and thumbnail; closes the Bio-Formats reader, 532e509d)"
  - "ProjectEntryReservation.fail / dropUnfilled when the job ends"
read_by:
  - "TileProcessingUtilities, StitchingHelper.importMergedImageOnly / importPerChannelFallback (fill when they hold a reservation for their key)"
  - "QuPath project browser (puzzle thumbnail from the moment the region is handed to the stitching queue)"
lifecycle:
  created_when: "a stitch job is queued (OME-TIFF output only; a ZARR job reserves nothing)"
  destroyed_when: "dropUnfilled for outputs that never arrived (the real file, if any, is imported the ordinary way)"
invariants:
  - "Never opened in the viewer, filled or not."
  - "A wrong name prediction costs one placeholder entry; never rename a filled entry to match a prediction."
```

### Derived / Service

#### DS33: TilingRequest (per-workflow build)

```yaml
id: DS33
category: derived
storage_kind: in_memory
fields:
  outputFolder, modalityName, frameSize, overlapPercent, boundingBox OR annotations
  stageInvertedX, stageInvertedY (from StagePolarity.current(), DS3 stage.polarity)
  createDetections flag
written_by:
  - "W1, W2, W3 (Builder pattern)"
read_by:
  - "TilingUtilities.createTiles / createTilesForAnnotations"
```

#### DS39: CrossScopeTransformBuilder output

```yaml
id: DS39
category: derived
storage_kind: in_memory
fields:
  composed transform from (source-scope per-slide JSON) + (active-scope alignment compose-through)
written_by:
  - "CrossScopeTransformBuilder.compose (called from W1 tryComposeCrossScopeAlignment)"
read_by:
  - "W1 cross-scope confirmation dialog and subsequent acquisition"
invariants:
  - "Compose uses SOURCE SCOPE's stored offset, not active scope's, because the stored offset lives in the source scope's stage frame."
```

#### DS40: Propagation builder output

```yaml
id: DS40
category: derived
storage_kind: in_memory
fields:
  per-target transform built from three-tier lookup (DS5 -> DS6 -> DS9.STAGE_BOUNDS) + half-FOV
written_by:
  - "ForwardPropagationWorkflow.buildGroups (W12, W13)"
read_by:
  - "W12, W13 propagation execute path"
invariants:
  - "Tile-detection ground-truth path (SOURCE_ROI_*) bypasses this chain entirely."
```

### Cross-cutting / less-commonly-touched

#### DS18: Source-mismatch resolution state

```yaml
id: DS18
category: in_memory
storage_kind: ephemeral
fields:
  source-mismatch dialog choice (fix / keep / cancel)
written_by:
  - "W1 checkAndHandleSourceMismatch (line 378-491)"
read_by:
  - "W1 itself only"
invariants:
  - "Auto-resolves silently when acquired_on_microscope equals active scope (commit cd7897b6)."
  - "Cross-scope 'keep source' is correct for genuine Ocus40-scan -> active-scope acquisition."
```

#### DS31: Recovery tile-folder selection

```yaml
id: DS31
category: ephemeral
storage_kind: in_memory
fields:
  user-selected tile folder for W16
written_by:
  - "W16 dialog"
read_by:
  - "W16 itself"
```

---

## 4. Cross-workflow dependency matrix

This is the master "what affects what" lookup. Each row reads "Producer X
writes DS Y consumed by Consumer Z."

| Producer | Data Surface | Consumer | Notes |
|---|---|---|---|
| W3 | DS4 (TransformPreset) | W1d, W21, W1 cross-scope path | TransformPreset persists alignment for a scope pair |
| W1, W1c, W1d, W1e, W3 | DS5 (per-slide JSON macro) | W1, W12, W13, W19, W21 | Tier 1 of three-tier alignment lookup |
| W14 (StitchingHelper.autoRegisterBoundsTransformIfAvailable) | DS6 (per-slide JSON sub) | W12, W13, W19, W1a (legacy fallback) | Tier 2; only StitchingHelper writes 'sub' |
| W3 | DS7 (saved macro raw) | W12, W13, W21 | Optional macro snapshot |
| W1, W2, W3, W19, W21 (PlacementChangeCoordinator clears) | DS17 (currentTransform) | W19 Live Viewer, next workflow on cleanup | MUST be cleared in cleanup() (H6), only by the run holding DS49 |
| W1, W2, W14 (StitchingHelper stamp) | DS9 (metadata bundle) | EVERY workflow | The single biggest cross-cutting surface |
| W1b | DS9 (SLIDE_POSITION/CARRIER/MS_RUN_ID) | W21 StageMapWindow, W1 per-slot | Multi-slide carrier tracking |
| (User dialog) | DS9 (SOURCE_MICROSCOPE) | W1 source-mismatch gate, W21 source dropdown | Set at scanner import; NEVER overwrite from StageMapWindow (commit d531d696) |
| W1, W2, W14 (StitchingHelper) | DS9 (ACQUIRED_ON_MICROSCOPE) | W1a cross-scope gate, W12/W13 back-prop | Macro entries do NOT carry this field |
| W14 (StitchingHelper) | DS9 (SOURCE_ROI_*) | W13 back-prop ground-truth path | Bypasses alignment chain when present |
| W14 (StitchingHelper) | DS9 (STAGE_BOUNDS_*, STITCHER_FLIP_*) | W19 Go-To-Centroid (tier 3 fallback) | Self-contained Move-to-Centroid record |
| W22 (SetupWizard) | DS3 (microscope YAML) | EVERY workflow via MicroscopeConfigManager | Loaded once per process |
| W20 ProbeStageAfWorkflow | DS3 (stage.streaming_af.*) | All AF-using workflows | OWS3 INCOMPATIBLE; keep disabled |
| W6 AutofocusEditorWorkflow | DS3 (autofocus YAML section) | W1, W2, W11 (AF during acquisition) | Per-objective AF params |
| W9 WhiteBalanceWorkflow | DS21 (WB calibration YAML) | W1, W2, W10 (via ChannelResolutionService) | validateObjectiveBeforeCalibration MUST gate |
| W4 BackgroundCollectionWorkflow | DS23 (background TIFFs) | W1, W2, W10 via --bg-folder | Wrong magnification silently corrupts |
| W1, W2 (per acquisition) | DS19 (acquisition command file) | microscope command server (out of process) | Transient |
| W31 ParfocalityCalibrationController | DS20 (parfocality YAML) | AcquisitionManager during modality switch | Per-profile relative Z offsets |
| W23, W24, W25 | DS47 (PPM calibration) | qupath-extension-ppm (out of repo), W26 | Five 'PPM Polarity:' MeasurementList keys are stable contract |
| W1, W2, W3, W10, W11 | DS28 (tile config files) | microscope command server, stitcher, W16 recovery | TileConfiguration.txt is microns; _QP.txt is QuPath pixels |
| W1, W2, W14, W16 | DS30 (stitched OME-TIFF / ZARR) | QuPath project (new ProjectImageEntry) | Compression/format from DS25 |
| W15 MakePortableWorkflow | DS30 (OME-TIFF replacements) | QuPath project | Surfaces tile folders when no ZARR remains |
| Any dialog | DS24 (PersistentPreferences) | Workflow on next dialog open | Lightweight per-user prefs |
| Any dialog | DS25 (QPPreferenceDialog) | EVERY workflow | Camera orientation, server settings, stitching format (stage polarity is DS3 stage.polarity) |
| DS3 (stage.polarity) + DS25 (cameraOrientation) | DS26 (StageImageTransform) | W19 LiveViewer (joystick/WASD/click), stitcher flip flags, W21 Apply Flips (cameraFlipFlags) | Composes the two -- don't read polarity booleans directly |
| Setup / startup (ModalityRegistry static block; ModalityUis.registerAll for the UI halves) | DS41 (ModalityRegistry) | EVERY acquisition workflow via getHandler; menu, dialogs, Live Viewer via getUi | Prefix match; NoOpModalityHandler / ModalityUi.NONE if unmatched |
| User selection | DS42 (ModalityState) / DS42a (ObjectiveState) | EVERY workflow needing active modality / objective | Singleton |
| W1c cross-scope | DS39 (CrossScopeTransformBuilder output) | W1 acquisition path | Uses source scope's stored offset |
| W12, W13 | DS40 (propagation builder output) | W12, W13 execute | Tile-detection path bypasses |
| W19 LiveViewerWindow | DS43 (live viewer state), DS17 (stage motion) | Any workflow that takes/releases lock | Locked during primary-socket work |
| W21 StageMapWindow | DS44 (stage map state) | -- (display-only) | Reads DS9 ancestry; never overwrites SOURCE_MICROSCOPE |
| W14 (StitchingHelper) | DS6 (sub-frame JSON) | W19 Go-To-Centroid, W12/W13 tier 2 | Filename encodes scope (parsed by getDerivedAlignmentMicroscope) |
| AcquisitionWizardDialog (W28) | DS24 (last modality/objective) | W1, W2 on next launch | Re-seeds dialogs |
| W1 | DS28 (tile cleanup) | DS27 (less data on disk) | TileCleanupHelper.performCleanup; force-delete on error (Phase 11) |
| W1 setCurrentTransform | DS17 | All subsequent stage commands during this workflow | MUST be AFTER validateAndFlipIfNeeded |
| W1 saveRefinedAlignment | DS5 (overwrites on accept) | W1 next launch, W12/W13 | Short-circuits for sub-acquisitions (H2/H3) |
| W3 saveGeneralTransform | DS5 (entry-flip-read for save) | W1 alignment lookup | flipFrameVerified=true post-2026-05-19 |
| W15 (StitchingHelper.convertSingleZarrToTiff) | DS30 (ZARR -> OME-TIFF on demand) | W15 MakePortableWorkflow | On-demand conversion only; background escalation removed in 2026-06-01 refactor |
| W1d ExistingAlignmentPath | DS46 (data bounds classifier .json) | W1d only | Detects signal-bearing region inside macro |
| W1 routeSubWorkflow | (no DS write) | W1a, W1c, W1d, W1e | isSubAcquisition() MUST be checked before useExistingSlideAlignment |
| W1 checkAndHandleSourceMismatch | DS9 (SOURCE_MICROSCOPE fix) | W1 subsequent steps | Auto-resolves via acquired_on |
| W1 checkAndHandleOrphanedFlippedSibling | (no DS write; just guards) | W1 abort or continue | Dialog forces restart with valid base |
| W1c (slide-specific alignment save) | DS5 (entry-flip-read) | W1 next launch | Uses AlignmentHelper.resolveMacroLookupKey |
| W1e ManualAlignmentPath | DS5 (createManualAlignment writes) | W1, W21 | Reads slide calibration, NOT scanner macro pixel size |
| ImageFlipHelper.validateAndFlipIfNeeded | DS9 (light-path snapshot / Camera View companion), DS17 | W1 acquisition | MUST run before setCurrentTransform; no-op on an isCameraView entry |
| ImageFlipHelper.mirrorAnnotationsToSibling | DS27 (sibling entry annotations) | W1, W3 | Single annotation mirror (replace, not append; commit 4515a2f) |
| FlipResolver.resolveFlipX/Y | (composes DS9 + DS5 + DS4) | W1, W3, W12, W13 | Canonical reader for current flip semantics |
| StageImageTransform.stitcherFlipFlags | (composes DS25) | W14 stitcher | Drives stitcher flip flags |
| W19 lockControls | DS43 | All workflows reading isLocked | Static; global state |
| W1 wizard-gate validateMMAgainstSelection | (no DS write) | W1 abort | First of three gate points |
| W1 refinement-gate validateMMAgainstSelection | (no DS write) | W1 abort | Second of three gate points |
| W1 acquisition-gate validateMMAgainstSelection | (no DS write) | W1 abort | Third / final backstop |
| W29 ServerConnectionController | DS25 (server host/port, notif) | W29 itself, NotificationService | Configuration write-back |
| W30 CameraControlController | DS17 (live exposures/gains) | Test only -- not persisted | Apply buttons set camera + rotation stage angle |
| W33 NotificationService | external (ntfy.sh) | -- | Best-effort; failures not workflow-fatal |
| Setup time | DS3 (microscope.name) | DS9 (SOURCE_MICROSCOPE / ACQUIRED_ON_MICROSCOPE stamping) | Source-of-truth for both fields at stamping time |
| W28 AcquisitionWizardDialog | (calls W1) | W1 | The entry point for guided existing-image acquisition |
| W14 StitchingHelper.reserveOutputs (W1, W2) | DS48 (reserved entries) | Project browser; filled by TileProcessingUtilities / importMergedImageOnly / importPerChannelFallback | OME-TIFF only; never opened in the viewer |
| W1 execute / cleanup | DS49 (WorkflowRegistry slot) | W1 cleanup of the older run | Guards DS17 against an overlapping run's cleanup |
| AcquisitionManager.launchStitching | W37 StitchingQueueWindow rows | Operator (Open on finished rows) | Non-modal; the only place a stitched image is opened |
| W38 stopActiveAcquisition | DS45 (CANCEL), active run's cancelRequested | W1 / W2 CancelGate | Reachable from the menu, the Live Viewer and W1's refusal |
| W39 BackgroundProfilesDialog | DS23 (profile.yml), DS24 (activeBackgroundProfile) | W4, every acquisition (--bg-folder resolution) | No active profile -> no backgrounds |
| W40 FlatfieldFitDialog | server-side BaSiC profile folder (via FLATFIT / FLATPOLL) | Acquisitions with flatfieldMethod=BASIC (--flatfield-folder), Tiles to Pyramid | Never sent with --bg-correction |
| W43 MicroscopeSwitcher | DS3 (reload), DS44 (registries dropped), DS45 (reconnect + CONFIG), QP Scope menu | EVERY workflow | Refused while acquiring |
| W45 LightPathSetupDialog | DS3 (light_path) | W21 Stage Map Camera View, LightPathModel readers | Set once per rig |
| W42 ExportSpatialSceneWorkflow | <project>/ngff/*.json | External tools only | Reuses existing transform functions; writes nothing else |

---

## 5. Common pitfalls and invariants

Numbered list of "don't do X" / "must do Y" -- the ones the team has paid for in
real outages. Each item names the surface(s) and workflow(s) it applies to.

1. **`saveSlideAlignment` writers MUST read `flipMacroX/Y` from the open entry,
   never from the preset.** Reading from the preset breaks for sub-images and
   was the source of the 2026-05-18 stage-mirror bug. Affects: DS5, DS6.
   Watched: W1c, W1d, W1e, W3, W1.saveRefinedAlignment.

2. **`MicroscopeController.currentTransform` MUST be cleared in `cleanup()`.**
   (H6 fix.) A stale transform leaks to the Live Viewer and the next workflow.
   Affects: DS17. Watched: every workflow with cleanup().

3. **`ImageFlipHelper.validateAndFlipIfNeeded` MUST run before
   `setCurrentTransform`.** (M11 deferred install.) Otherwise the transform is
   applied to a possibly-unflipped image and motion lands wrong. Affects: DS17,
   DS9. Watched: W1.

4. **`routeSubWorkflow` MUST check `isSubAcquisition()` BEFORE the
   `useExistingSlideAlignment` branch.** (2026-05-13 sub-image routing fix.)
   Reversing the order applies a macro-pixel transform to sub-image coords and
   shrinks every stage move by `camera_px / macro_px`. Affects: W1.
   Watched: W1.routeSubWorkflow.

5. **Sub-image acquisitions MUST hard-cancel when `acquired_on_microscope` !=
   active scope.** (Phase 2 H1/H4.) The entry's `xy_offset` is in the
   acquiring scope's stage frame and is meaningless on any other scope.
   Affects: DS9. Watched: W1a.

6. **Cross-scope back-prop MUST use the SOURCE scope's alignment, not the
   active scope's.** The stored offset lives in the source scope's stage frame.
   Affects: DS5, DS9 (ACQUIRED_ON_MICROSCOPE). Watched: W13.

7. **`saveRefinedAlignment` MUST short-circuit when `isSubAcquisition()`.**
   (Phase 1 H2/H3.) Sub-images do not own a macro-frame alignment to refresh.
   Affects: DS5. Watched: W1.saveRefinedAlignment.

8. **`validateMMAgainstSelection` fires at three points:** wizard launch
   (`AcquisitionWizardDialog.confirmCalibrationStatus`), `handleRefinement`,
   `performAcquisition`. Do not collapse to fewer points without analysis -- each
   covers a different failure window (MM-offline-at-wizard, mid-workflow MM
   change, last-second MM divergence). Affects: every acquisition workflow.

9. **Layer 2 `pixel_frame` gate refuses non-'macro' transforms at load.**
   Workflows operating on macro-frame annotations cannot consume a sub-frame
   transform. Affects: DS5, DS6. Watched: `AlignmentHelper.checkForSlideAlignment`.

10. **PPM workflows are NOT in `QPScopeController.startWorkflow` switch.** They
    are registered dynamically via `PPMModalityUi.getMenuContributions()`
    (the handler's UI half, read through `ModalityRegistry.getUi`). Adding a
    new PPM workflow requires extending the menu contributions list, not the
    switch. Same for LC-PolScope (`LCPolScopeModalityUi`). Affects: W23-W26,
    W36. Watched: PPMModalityUi, LCPolScopeModalityUi.

11. **Stage polarity and camera orientation are INDEPENDENT.** Six pre-2026
    call sites read `StagePolarity` directly without composing with
    `CameraOrientation`; treat as a bug class. All new code MUST go through
    `StageImageTransform.current()`. Affects: DS3, DS25, DS26. Watched: every live-view
    sign-convention call site.

12. **Bounded acquisition is stage-frame native -- NO macro flip, NO alignment
    JSON read.** Stitcher flips are stage-polarity-driven only. Affects: W2.

13. **When project already open, `projectsFolder` and `sampleName` MUST be
    derived from the existing project path,** not from preference. Otherwise
    tiles land in the wrong directory. Affects: DS27, DS32. Watched: W2.

14. **Stage Map Apply Flips source is hardware-only** (stage+camera composite
    XOR active source preset's macro flip). Never read `entry.source_microscope`
    to derive display behavior. (Commit 365045d2.) Affects: W21, DS9.

15. **Stage Map source dropdown is display-only.** Walks
    `base_image`/`original_image_id` ancestor chain. NEVER overwrite an
    existing `entry.source_microscope` from the dropdown. (Commit d531d696.)
    Affects: W21, DS9.

16. **`(Camera View)` companion entries (formerly `(flipped X|Y|XY)`) are
    still legitimately created on demand for visual-UX during alignment.** Do
    NOT strip them in the workflow path. The one-shot `FlippedDuplicateMigrator`
    (W17) was removed in v0.9.0; nothing deletes companions except a slide
    placement change (W21 PlacementChangeCoordinator, stale companion only).
    Affects: DS27, DS9.

17. **Streaming AF is INCOMPATIBLE with OWS3.** Prior ZDrive ignores Speed on
    non-blocking moves; rapid jumps wedge CONFIG. Keep
    `stage.streaming_af.enabled=false` on OWS3. Affects: DS3, W20.

18. **Three-tier alignment lookup pattern:** macro JSON (DS5) -> derived JSON
    (DS6) -> entry STAGE_BOUNDS (DS9). `AlignmentHelper` is intentionally
    macro-only (Layer 2 gate). DO NOT add tier 2 to macro-frame workflows.
    Affects: W1, W12, W13, W19.

19. **PPM Polarity Plot measurement keys are a stable downstream contract.**
    Five `PPM Polarity:` MeasurementList keys are consumed by downstream
    segmentation (qupath-extension-ppm). Don't rename without coordinating.
    (Commit 35df8fb.) Affects: DS47.

20. **Source-mismatch dialog auto-resolves via `acquired_on`.** Only prompts
    when `acquired_on_microscope` is missing. The cross-scope "keep source"
    option is correct for genuine Ocus40-scan -> active-scope acquisitions.
    (Commit cd7897b6.) Affects: W1, DS9, DS18.

21. **Microscope-alignment same-scope flip short-circuit is skipped when caller
    supplies explicit flip flags** (e.g. StageMapWindow auto-stamp). Prevents
    silent nullification of orientation-dialog / preset-driven flips. (Commit
    1dd83ced, 2026-05-26.) Affects: ImageFlipHelper, W3, W21.

22. **Tile-detection ground-truth path in back-prop BYPASSES alignment / flip /
    half-FOV chain.** When tile detections from `TilingUtilities` are present
    on the parent, back-prop uses pure linear interpolation. PRESERVE this
    path. Affects: W13, DS9 (SOURCE_ROI_*).

23. **Stitching cleanup race produces 'Failed to read tile region from _temp_*'
    storms.** Fixed by tiles-to-pyramid `0d7da0a` (compositor closed flag) +
    qpsc `732fbd4` (`moveDirectoryWithRetryAndCopyFallback`). Do not roll back.
    Affects: W14, DS30. Watched: StitchingHelper move-and-cleanup paths.

24. **Dialog Position Manager 0.4.0 moved positions out of PathPrefs into a JSON
    file** (8 KB cap caused issues). One-time migration alert. Don't re-add
    dialog positions to `PersistentPreferences`. Affects: DS25a.
    Watched: every dialog show() path.

25. **Flipped-sibling entry annotation mirror is REPLACE, not append**
    (commit 4515a2f). Bake-delta was removed from the flipped-sibling workflow
    path entirely (commit 08036ea). Don't reintroduce. Affects: ImageFlipHelper,
    W1, W3.

26. **Wizard + pre-refinement pixel-size gate** fires at wizard launch AND
    before refinement, not just before acquisition. Don't remove the wizard
    gate. Affects: W1, W28.

27. **`AcquisitionWizardDialog.confirmCalibrationStatus` is the earliest
    failure point for objective-mismatch-with-MM**. Surfaces before any
    project / flipped-duplicate / alignment-JSON writes. Affects: W1, W28.

28. **Cross-scope sub-image acquisition is currently DISALLOWED.** Users must
    open the parent macro entry and let the cross-scope alignment path compose
    a fresh active-scope transform. Compose-through-parent was considered and
    rejected as adding another silent-fallback class. Affects: W1a.

29. **`flipFrameVerified` field on per-slide JSON marks post-2026-05-19 saves.**
    Pre-fix JSONs (field absent) trigger a Continue/Cancel advisory at load.
    Don't silently fix-up old JSONs. Affects: DS5, DS6.

30. **`MultiSlideExistingImageWorkflow` (W1b) is gated by holder availability**
    (shown only when the config defines a multi-slide holder; no preference).
    It adds no new validation gates; each per-slot W1 invocation
    runs the full validation chain independently. Don't bypass per-slot gates
    for "performance". Affects: W1b.

31. **`ModalityRegistry.getHandler` returns `NoOpModalityHandler` for unknown
    modalities, never null.** Prefix matching is `startsWith`, and the longest
    matching prefix wins (`bf_if_20x` resolves to `bf_if`, not `bf`), in any
    registration order. Affects: DS41.

32. **Either-socket-connected counts as "connected" for UI purposes.** Primary
    and aux sockets are independent. Route long-running ops to primary; anything
    that must not block Live Viewer to aux. Affects: DS17, DS45.

33. **`AlignmentHelper.resolveMacroLookupKey` walks the `base_image` chain to
    the macro-owning ancestor.** This is the canonical lookup-key resolver for
    every per-slide alignment read/write site. Sub-image entries resolve to the
    parent macro key, never their own. Affects: DS5, DS6, W1, W1c, W1d, W1e,
    W3, W12, W13.

34. **Bounded acquisition workflow auto-connects if not connected.** Other
    workflows assume connection. If you change connection-state composition,
    update both the connection probe and the auto-connect path. Affects: W2,
    DS17.

35. **`STAGE_BOUNDS_*` + `STITCHER_FLIP_*` on the entry are the tier-3 fallback
    for Move-to-Centroid.** Move-to-Centroid MUST work even if DS6 lookup
    fails (cross-scope guard, restructured directory, legacy file). Don't
    remove the entry-level stamp. Affects: DS9, W19, W14.

36. **`AcquisitionWizardDialog` records `last modality` / `last objective` in
    `PersistentPreferences`.** Other dialogs re-seed from these. Don't change
    naming without coordinated dialog updates. Affects: DS24, multiple dialogs.

37. **`SOURCE_ROI_FLIP_X/Y` records mirror flag relative to the rectangle when
    the bbox was derived from tile detections on a flipped sibling.** The
    rectangle is stored in unflipped-base coords (so it lines up with the
    entry being written), but the sub-image's pixel layout is mirrored.
    Affects: DS9, W13.

38. **`ChannelResolutionService.isEmptySelectionForChannelBasedModality` fires
    early** to refuse channel-based modalities with no channels selected.
    Don't move into async chain. Affects: W2 (line ~280).

39. **Only the run that holds the `WorkflowRegistry` slot may clear
    `MicroscopeController`'s transform and acquisition flag.** Run N's
    stitching outlives its acquisition and run N+1 starts meanwhile; on
    2026-10-27 run N's cleanup nulled run N+1's transform. Affects: DS17,
    DS49, W1.

40. **Stitch imports never touch the viewer.** No `openImageEntry`, no
    `saveImageData` of the viewer's image, no `setProject` from a stitch
    callback; reserved entries (DS48) are filled in place. A stitch landing
    under the next slide's refinement dialog re-set the project under it on
    2026-10-27. Affects: W14, DS48, DS27.

41. **Modality handlers hold no UI.** Panels, menu items, Live Viewer controls
    and pre-acquisition confirmations live on the modality's `ModalityUi`
    (registered by `ModalityUis.registerAll()`); the `modality` package may not
    import dialogs or workflows. Shared code asks a handler hook rather than
    testing the modality name. Affects: DS41, every modality.

42. **Socket commands share one exchange path** in `MicroscopeSocketClient`
    (`exchange`, `withReadTimeout`, `send`, `readReply`,
    `readStartedThenResult`). Do not add a command with its own
    lock-send-read code. A FAILED reply is a `ServerAnswerException`, not a
    connection failure. Affects: DS45.

---

## 6. Glossary

Terms an LLM needs to interpret the rest of this document. Each entry one or
two lines.

- **pixel_frame** -- A persistence-format string on the per-slide alignment JSON;
  one of `"macro"` (DS5) or `"sub"` (DS6). Indicates whether the affine's
  scale is in macro-image pixels or in the acquired sub-image's own camera
  pixels. Layer 2 gate refuses non-`macro` at load for macro-frame workflows.

- **lookupKey** -- The string used to find the right per-slide alignment JSON.
  Resolved via `AlignmentHelper.resolveMacroLookupKey` which walks the
  `base_image` chain to the macro-owning ancestor. NOT the entry's own name
  for sub-images.

- **sibling** -- Two entries with the same `BASE_IMAGE`. Enumerated via
  `ImageMetadataManager.getSiblingsByBaseImage`. A flipped sibling is a
  `(Camera View)` companion: `isCameraView` true, its mirror in
  `bakedParity` (the `flip_x`/`flip_y` keys were removed in v0.9.0).

- **sub-acquisition** -- An acquisition whose target is a previously-acquired
  sub-image (has `xy_offset` and `base_image` distinct from its own name).
  `W1.isSubAcquisition()` returns true; routed to `W1a`.

- **cross-scope** -- An acquisition where the current microscope differs from
  the scanner / scope that produced the macro image (`SOURCE_MICROSCOPE` !=
  active scope, OR `ACQUIRED_ON_MICROSCOPE` != active for sub-images).
  Triggers compose-through-source-scope alignment path in W1.

- **three-tier lookup** -- The propagation alignment lookup order: macro JSON
  (DS5) -> derived/ sub-frame JSON (DS6) -> entry-level STAGE_BOUNDS (DS9).
  Implemented inside W12/W13 propagation builder. AlignmentHelper itself is
  tier-1 only (intentional macro-only Layer 2 gate).

- **base_image ancestry** -- The chain `entry -> entry.BASE_IMAGE -> ...` that
  leads from a sub-image up to the macro-owning entry. Used for alignment
  lookup, source attribution, propagation, and Stage Map display.

- **BoundedAcquisition (W2)** -- Stage-frame-native acquisition; bounding-box
  region in stage microns. No alignment JSON read. Stitcher flips driven only
  by stage polarity.

- **ExistingImage (W1)** -- Annotation-driven acquisition from an existing
  macro / sub-image. Consumes per-slide alignment JSON. Most complex
  workflow.

- **macro vs full-res** -- The macro image is the low-resolution overview from
  the scanner (used to draw annotations); full-res is the acquired sub-image
  pixel calibration. Per-slide JSON in macro frame applies to annotations
  living on the macro entry.

- **scope vs scanner** -- Scope = the QPSC microscope that ACQUIRES (e.g. PPM,
  CAMM). Scanner = the device that produced the original WSI / macro
  (e.g. Ocus40). Stored as `SOURCE_MICROSCOPE` (scanner) and
  `ACQUIRED_ON_MICROSCOPE` (scope) per entry.

- **PIXEL_FRAME_MACRO** / **PIXEL_FRAME_SUB** -- String constants on
  `AffineTransformManager`. Always written explicitly to per-slide JSONs.
  Legacy JSONs without the field default to `"macro"`.

- **in-memory transform** -- `MicroscopeController.currentTransform` (DS17).
  Ephemeral; set during a workflow, cleared at cleanup.

- **persisted alignment** -- The per-slide JSON on disk (DS5/DS6) or the
  TransformPreset (DS4). Survives across sessions.

- **dispatcher (QPScopeController)** -- The central switch that menu items
  call via `startWorkflow(mode)`. PPM and LC-PolScope workflows bypass this
  and are registered via `ModalityUi.getMenuContributions()`; several
  utilities (Stage Map, Propagation Manager, Make Portable, Export Spatial
  Relationships, Focus Approach Validation, Register Objective, Light Path,
  Switch Microscope, demo, flat-field fit, Orientation Color View, Stitching
  queue, Stop acquisition, Report a Bug) are wired directly in `SetupScope`.

- **preset vs per-slide JSON** -- TransformPreset (DS4) is the per-scope-pair
  template stored in alignment YAML. Per-slide JSON (DS5/DS6) is the snapshot
  applied to a specific entry in a project. Both store flipMacroX/Y but the
  per-slide JSON's flip MUST come from the open entry at save time, not from
  the preset.

- **sub-frame JSON (derived/)** -- DS6. Auto-registered by
  `StitchingHelper.autoRegisterBoundsTransformIfAvailable` after stitching;
  stored under `alignmentFiles/derived/`. Filename encodes the acquiring scope.
  Pixel frame is `"sub"`.

- **flipped sibling** -- A `(Camera View)` companion entry (formerly named
  `(flipped X|Y|XY)`) created on demand for visual-UX during alignment;
  `ImageMetadataManager.isCameraView` is true on it. Classify by metadata,
  never by name.

- **half-FOV correction** -- The offset that accounts for tile centers vs
  tile corners. Missing it shifts propagated annotations by half a field of
  view. FOV is read from `OBJECTIVE AT ACQUISITION TIME` (DS9.OBJECTIVE), not
  the current objective.

- **modality handler** -- A class implementing `ModalityHandler` registered
  with a string prefix in `ModalityRegistry`. Provides modality-specific
  acquisition parameters, validation and hooks. Lookup is `startsWith` on the
  modality name (longest prefix wins).

- **modality UI (ModalityUi)** -- The UI half of a handler: bounding-box panel
  (`createBoundingBoxUI`), Live Viewer camera controls
  (`buildLiveCameraControls`), `confirmBeforeAcquiring`, `confirmBatch`,
  `getMenuContributions`. Lives in each modality's `ui` package, registered by
  handler class in `ModalityUis.registerAll()`, read with
  `ModalityRegistry.getUi(...)`; `ModalityUi.NONE` when a handler has none.

- **WorkflowRegistry** -- DS49. Owner of the active-workflow slot that decides
  which run may clear `MicroscopeController`'s transform and acquisition flag.

- **reserved entry** -- DS48. A project entry added before its stitch runs,
  pointing at a placeholder image with `qpsc_stitch_status=pending`, filled
  in place when the OME-TIFF lands.

- **StageImageTransform** -- Composite of stage polarity and camera
  orientation (DS3 `stage.polarity` + DS25 cameraOrientation -> DS26). Single source of truth for all
  live-view sign conventions. Do not read polarity booleans directly.

- **validateMMAgainstSelection** -- Wrapper around `QPScopeChecks.
  validateObjectivePixelSize` + `validateCameraRoi`. 5% threshold. Hard-cancel
  on mismatch. Fires at three points in W1 (wizard, refinement, acquisition).

- **AlignmentHelper.resolveMacroLookupKey** -- The canonical lookup-key
  resolver. Walks `base_image` chain. Every per-slide alignment read/write
  site MUST go through this.

- **Layer 2 gate** -- The `pixel_frame != 'macro'` rejection in
  `AlignmentHelper.checkForSlideAlignment`. Layer 1 is the lookup-key
  resolution; Layer 2 is frame validation. Together they form the macro-frame
  contract.

- **acquisitionActive** -- Boolean on `MicroscopeController`. Set true at
  acquisition start; reset on cleanup (Phase 11). Used to gate concurrent
  operations.

- **lockControls(reason) / unlockControls()** -- Static API on
  `LiveViewerWindow`. Global UI lock with reason string. Used during
  primary-socket-blocking operations.

- **beginExternalAutofocus(cancel) / endExternalAutofocus()** -- Static API on
  `LiveViewerWindow`. Partial lock for an autofocus scan started OUTSIDE the
  Live Viewer (the multi-slide slot-jump AF in `SlotJumpAutofocus`). Disables
  the stage-movement controls (StageControlPanel, live/stage toggles, focus
  range) to prevent stage bumps mid-scan, but leaves the Autofocus button
  ENABLED and relabeled "Cancel Autofocus" wired to `cancel` (ABORTAF). Unlike
  `lockControls`, which also disables the Autofocus button.

- **stitcher flip flags** -- Bool[] returned by `StageImageTransform.
  stitcherFlipFlags()`. Drives X/Y flip during stitching pyramid build.
  Persisted on entry as `STITCHER_FLIP_X/Y` for Move-to-Centroid replay.

- **acquired_on_microscope** -- DS9 field. The scope that PHYSICALLY captured
  this image. Distinct from `source_microscope` (the scanner of the
  original WSI). Macro entries do not carry this field. Cross-scope
  hard-cancel uses it.

- **NoOpModalityHandler** -- The default handler returned by
  `ModalityRegistry.getHandler` for unknown modalities. Provides sensible
  defaults; never null.

- **WorkflowState** -- The internal mutable bundle passed through W1's
  CompletableFuture chain. Holds annotations, alignment, transform,
  refinement choices, etc. Defined inside `ExistingImageWorkflowV2`.

- **dispatcher entry mode** -- The string passed to
  `QPScopeController.startWorkflow(mode)`. Examples: `"existingImage"`,
  `"boundedAcquisition"`, `"microscopeAlignment"`. See Section 2 each
  workflow's `ui_entries`.

---

<!-- End of WORKFLOW_MAP.md. Update last_synced_commit in frontmatter when changing this file. -->
