---
title: QPSC Workflow Map
purpose: Machine-readable single source of truth for workflow dispatch, data surfaces, and cross-workflow dependencies. Optimized for LLM agents, not human reading.
maintenance: Update alongside any code change that affects a watched_file or renames a watched_symbol. Verified by tools/check_workflow_map.py at pre-push time (planned).
last_synced_commit: 0a5fd6522f4bfce906a8ce5f628796247b6f85a2
watched_files:
  - src/main/java/qupath/ext/qpsc/SetupScope.java
  - src/main/java/qupath/ext/qpsc/controller/QPScopeController.java
  - src/main/java/qupath/ext/qpsc/controller/ExistingImageWorkflowV2.java
  - src/main/java/qupath/ext/qpsc/controller/BoundedAcquisitionWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/MicroscopeAlignmentWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/MicroscopeController.java
  - src/main/java/qupath/ext/qpsc/controller/MultiSlideExistingImageWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/ForwardPropagationWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/BackgroundCollectionWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/WhiteBalanceWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/WBComparisonWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/StitchingRecoveryWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/MicroManagerStitchWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/MakePortableWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/AutofocusBenchmarkWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/AutofocusEditorWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/ProbeStageAfWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/NoiseCharacterizationWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/StackTimeLapseWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/TestAutofocusWorkflow.java
  - src/main/java/qupath/ext/qpsc/controller/RapidScanWorkflow.java
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
  - src/main/java/qupath/ext/qpsc/modality/ModalityRegistry.java
  - src/main/java/qupath/ext/qpsc/modality/ModalityHandler.java
  - src/main/java/qupath/ext/qpsc/modality/ppm/PPMModalityHandler.java
  - src/main/java/qupath/ext/qpsc/modality/ppm/workflow/BirefringenceOptimizationWorkflow.java
  - src/main/java/qupath/ext/qpsc/modality/ppm/workflow/PPMSensitivityTestWorkflow.java
  - src/main/java/qupath/ext/qpsc/modality/ppm/workflow/PolarizerCalibrationWorkflow.java
  - src/main/java/qupath/ext/qpsc/modality/ppm/workflow/SunburstCalibrationWorkflow.java
  - src/main/java/qupath/ext/qpsc/utilities/ImageMetadataManager.java
  - src/main/java/qupath/ext/qpsc/utilities/AffineTransformManager.java
  - src/main/java/qupath/ext/qpsc/utilities/MicroscopeConfigManager.java
  - src/main/java/qupath/ext/qpsc/utilities/ImageFlipHelper.java
  - src/main/java/qupath/ext/qpsc/utilities/FlipResolver.java
  - src/main/java/qupath/ext/qpsc/utilities/StageImageTransform.java
  - src/main/java/qupath/ext/qpsc/utilities/QPProjectFunctions.java
  - src/main/java/qupath/ext/qpsc/utilities/StagePositionManager.java
  - src/main/java/qupath/ext/qpsc/utilities/FlippedDuplicateMigrator.java
  - src/main/java/qupath/ext/qpsc/utilities/CrossScopeTransformBuilder.java
  - src/main/java/qupath/ext/qpsc/utilities/TransformationFunctions.java
  - src/main/java/qupath/ext/qpsc/preferences/PersistentPreferences.java
  - src/main/java/qupath/ext/qpsc/preferences/QPPreferenceDialog.java
  - src/main/java/qupath/ext/qpsc/state/ModalityState.java
  - src/main/java/qupath/ext/qpsc/state/ObjectiveState.java
  - src/main/java/qupath/ext/qpsc/service/microscope/MicroscopeSocketClient.java
  - src/main/java/qupath/ext/qpsc/ui/AcquisitionWizardDialog.java
  - src/main/java/qupath/ext/qpsc/ui/liveviewer/LiveViewerWindow.java
  - src/main/java/qupath/ext/qpsc/ui/stagemap/StageMapWindow.java
watched_symbols:
  # Image metadata constants (DS9..DS18)
  - ImageMetadataManager.IMAGE_COLLECTION
  - ImageMetadataManager.XY_OFFSET_X
  - ImageMetadataManager.XY_OFFSET_Y
  - ImageMetadataManager.Z_OFFSET
  - ImageMetadataManager.FLIP_X
  - ImageMetadataManager.FLIP_Y
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
  - ImageMetadataManager.isFlippedX
  - ImageMetadataManager.isFlippedY
  - ImageMetadataManager.getSiblingsByBaseImage
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
  - StitchingHelper.queueBackgroundZarrToTiffConversion
  - ImageFlipHelper.validateAndFlipIfNeeded
  - ImageFlipHelper.mirrorAnnotationsToSibling
  - ImageFlipHelper.isFlippedSiblingName
  - FlipResolver.resolveFlipX
  - FlipResolver.resolveFlipY
  - FlipResolver.seedFlipForNewAlignment
  - CrossScopeTransformBuilder.compose
  - FlippedDuplicateMigrator.migrate
  - StageImageTransform.current
  - StageImageTransform.stitcherFlipFlags
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
  - ModalityRegistry.getHandler
  - ModalityRegistry.registerHandler
  - ModalityState.getInstance
  - ObjectiveState.getInstance
  - StagePositionManager.getInstance
  # PPM workflow entries
  - PPMModalityHandler.getMenuContributions
  - PolarizerCalibrationWorkflow.run
  - BirefringenceOptimizationWorkflow.run
  - SunburstCalibrationWorkflow.run
  - PPMSensitivityTestWorkflow.run
acknowledged_stale: []
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
   pre-push hook (planned) to catch drift.
6. **If you cannot fit a change cleanly into the existing structure,** add
   the surface or workflow with the next free ID and update Section 1 and
   Section 4. Do not retag existing IDs (they leak into git history and
   external references).

---

## 1. Quick-lookup index -- "I'm working on..."

| I'm changing... | Read these workflows | Read these data surfaces |
|---|---|---|
| Alignment save/load (saveSlideAlignment, TransformPreset, checkForSlideAlignment) | W1, W1c, W1d, W3, W14, W18 | DS4, DS5, DS6, DS7, DS17 |
| Flipped sibling entries / `(flipped X|Y|XY)` naming / mirror annotations | W1, W1d, W17 (FlippedDuplicateMigrator), W3 | DS9 (FLIP_X/Y), DS5, DS17 |
| Image metadata fields on ProjectImageEntry | All acquisition workflows (W1, W2, W3, W4, W11..W13) | DS8..DS18 |
| Source vs acquired microscope (cross-scope gating) | W1, W1a, W1c | DS9 (SOURCE_MICROSCOPE, ACQUIRED_ON_MICROSCOPE), DS5, DS6 |
| Autofocus parameters / streaming AF | W5, W6, W7, W20 | DS3, DS19 (modality config), DS20 (parfocality YAML) |
| White balance modes / per-channel exposures | W9, W10 | DS21 (WB calibration YAML), DS22, DS24 |
| Background correction / flat-field | W4 | DS23 (background TIFFs), DS3 |
| Stage polarity / camera orientation | All workflows touching stage motion | DS25 (preferences), DS26 (StageImageTransform) |
| Stitching pipeline (tiles -> OME-TIFF/ZARR) | W1, W2, W14, W15, W16 | DS28..DS31 |
| Cross-scope acquisition (Ocus40 macro -> PPM tiles) | W1, W1c, W3 | DS5, DS6, DS9, DS39 (CrossScopeTransformBuilder) |
| Sub-image / forward+back propagation | W1, W1a, W12, W13 | DS5, DS6, DS9 (SOURCE_ROI_*), DS40 |
| Per-acquisition tile generation (TilingUtilities) | W1, W2, W3, W10, W11 | DS28 (tile config files), DS33 (TilingRequest) |
| Modality dispatch / handler registration | All workflows | DS41 (ModalityRegistry), DS42 (ModalityState) |
| Live Viewer / Stage Map UI gates | W19, W21 | DS43 (live viewer state), DS44 (stage map state), DS17 |
| Z-stack or time-lapse single-point | W7 | DS3, DS19 |
| Socket protocol / new command paths | W1, W2, W19, W21, W6, W8 | DS45 (MicroscopeSocketClient), DS17 |
| Preferences (QPPreferenceDialog, PersistentPreferences) | All workflows | DS24, DS25 |
| Project structure (sample dir, tile dir, alignmentFiles/) | W1, W2, W3, W11, W14, W15 | DS27 (project dir), DS32 (sample dir) |
| Multi-slide carrier assignments | W1b | DS9 (SLIDE_*), DS44 (StageMapWindow) |
| Setup wizard / first-time config | W22 | DS3, DS19 (config YAML) |
| Make-portable conversion (ZARR -> OME-TIFF) | W15 | DS28, DS32 |
| Stitching recovery from tile folder | W16 | DS28, DS27 |
| PPM rotation / calibration | W23, W24, W25, W26 | DS3, DS21, DS47 |

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
entry: start()
category: acquisition
ui_entries:
  - "Menu: Extensions > QP Scope > Acquire from Existing Image (SetupScope.java:200, dispatch 'existingImage')"
  - "Wizard: Acquisition Wizard Start Existing-Image Acquisition (AcquisitionWizardDialog)"
  - "Per-slot driver: MultiSlideExistingImageWorkflow shepherds one invocation per slide-carrier slot"
dispatches_to:
  - "W1a processSubAcquisitionPath WHEN isSubAcquisition() (xy_offset present AND base_image distinct from own name)"
  - "W1c processSlideSpecificAlignment WHEN per-slide alignment JSON exists AND refinement != FULL_MANUAL"
  - "W1d processExistingAlignmentPath WHEN alignmentChoice.useExistingAlignment() AND no slide JSON"
  - "W1e processManualAlignmentPath ELSE"
reads:
  - DS3 (microscope YAML via MicroscopeConfigManager)
  - DS5 (per-slide alignment JSON, macro frame -- via AlignmentHelper.checkForSlideAlignment)
  - DS9 (open entry: SOURCE_MICROSCOPE, ACQUIRED_ON_MICROSCOPE, BASE_IMAGE, ORIGINAL_IMAGE_ID, MODALITY, OBJECTIVE, DETECTOR_ID, XY_OFFSET_X/Y, FLIP_X/Y, STAGE_BOUNDS_*)
  - DS17 (MicroscopeController state: isConnected, currentTransform)
  - DS24 (PersistentPreferences: getLastModality, class list, metadata propagation prefix)
  - DS25 (QPPreferenceDialog: projectsFolder, tileOverlapPercent, stagePolarity, stitching format)
  - DS27 (project image list, sibling resolution)
  - DS41 (ModalityRegistry -> ModalityHandler for the chosen modality)
  - DS42 (ModalityState)
  - DS43 (live viewer streaming state -- only to suspend during acquisition)
writes:
  - DS5 (per-slide alignment JSON refresh, only on refinement accepted -- via saveRefinedAlignment, 7-arg saveSlideAlignment)
  - DS6 (auto-registered sub-frame alignment JSON -- via StitchingHelper.autoRegisterBoundsTransformIfAvailable)
  - DS17 (MicroscopeController.currentTransform -- set after validateAndFlipIfNeeded, cleared in cleanup())
  - DS28 (TileConfiguration.txt and _QP.txt under <project>/<sample>/<modality>/<region>/)
  - DS30 (stitched OME-TIFF or ZARR plus pyramid)
  - DS9 (new sub-image entry metadata at stitch import: base_image, original_image_id, modality, objective, detector_id, xy_offset, source_microscope, acquired_on_microscope, fov_x_um, fov_y_um, source_roi_*, stage_bounds_* via StitchingHelper)
  - DS19 (calls only -- e.g. acquisition command file)
  - DS27 (project sync; new ProjectImageEntry per acquired sub-image)
steps:
  - { num: 1,  name: validatePrerequisites,                  file_line: "575-589",   reads: ["getImageData", "MicroscopeController.isConnected"], writes: [], gate: "no image OR not connected -> error dialog -> abort" }
  - { num: 2,  name: checkAndHandleSourceMismatch,           file_line: "378-491",   reads: ["DS9.SOURCE_MICROSCOPE", "DS9.ACQUIRED_ON_MICROSCOPE", "MicroscopeConfigManager.getMicroscopeName"], writes: ["DS9.SOURCE_MICROSCOPE (only if user fixes)"], gate: "see DS39; auto-resolves when acquired_on equals active; dialog when mismatch and no acquired_on" }
  - { num: 3,  name: checkAndHandleOrphanedFlippedSibling,   file_line: "494-570",   reads: ["DS27.imageList", "DS9.BASE_IMAGE", "DS5"], writes: [], gate: "orphan dialog: re-link or restart" }
  - { num: 4,  name: showAcquisitionWizard,                  file_line: "~111-200",  reads: ["DS24", "DS25"], writes: ["DS24 (last modality)"], gate: "user cancel -> abort" }
  - { num: 5,  name: ensureAnnotationsExist / chooseClasses, file_line: "234-376",   reads: ["hierarchy"], writes: ["may run tissue detection"], gate: "no valid annotations -> retry/cancel" }
  - { num: 6,  name: initializeFromConfig (build WorkflowState), file_line: "606-687", reads: ["DS3", "DS9"], writes: [], gate: "null config -> CancellationException -> handleError" }
  - { num: 7,  name: checkExistingSlideAlignment,            file_line: "699-753",   reads: ["DS5 (Layer 1) via AlignmentHelper"], writes: [], gate: "if pixelFrame != 'macro' -> Layer 2 gate -> hard-cancel" }
  - { num: 8,  name: confirmCrossScopeAlignment + tryComposeCrossScopeAlignment, file_line: "754-933", reads: ["DS5 across all scopes (loadAllSlideAlignmentsFromDirectory)", "DS3 across both scopes"], writes: [], gate: "cross-scope dialog: select source scope, modal" }
  - { num: 9,  name: routeSubWorkflow,                       file_line: "934-976",   reads: ["DS9 (XY_OFFSET, BASE_IMAGE), state.useExistingSlideAlignment, state.alignmentChoice"], writes: [], gate: "selects W1a/W1c/W1d/W1e" }
  - { num: 10, name: reReadAnnotationsAfterRouting,          file_line: "288",       reads: ["hierarchy on possibly-switched entry"], writes: ["state.annotations (refreshed)"], gate: "after routing may switch open entry" }
  - { num: 11, name: handleRefinement (orchestrates SingleTileRefinement), file_line: "1476-1556", reads: ["DS17.currentTransform", "DS3"], writes: ["DS28 (refinement tile config)", "DS17 (refined transform on accept)", "DS5 (saveRefinedAlignment ONLY on accept)"], gate: "validateMMAgainstSelection (pixel size / camera ROI 5%) -> hard-cancel on mismatch" }
  - { num: 12, name: performAcquisition,                     file_line: "1772-1798", reads: ["state.tilesByAnnotation", "DS17.currentTransform"], writes: ["DS17 (acquisitionActive=true)", "DS45 (socket: startAcquisition)"], gate: "validateMMAgainstSelection final backstop" }
  - { num: 13, name: waitForCompletion / showSuccessNotification, file_line: "1800-1926", reads: ["AcquisitionMonitorService events"], writes: [], gate: "showSuccessNotification short-circuits on null state (Phase 3 H5)" }
  - { num: 14, name: stitch + autoRegisterBoundsTransformIfAvailable, file_line: "via StitchingHelper", reads: ["DS28"], writes: ["DS30", "DS6 (sub-frame JSON)", "DS9 (full metadata stamp on new entries)"], gate: "stitching errors -> StitchingRecoveryWorkflow path" }
  - { num: 15, name: cleanup + cleanupTilesAfterStitching,   file_line: "1824-1854", reads: [], writes: ["DS17.currentTransform = null (H6)", "DS17.acquisitionActive=false (Phase 11)", "DS28 (delete tile files)"], gate: "force-delete tile dir even on error (Phase 11)" }
key_invariants:
  - "ImageFlipHelper.validateAndFlipIfNeeded MUST run BEFORE MicroscopeController.setCurrentTransform (M11 deferred install)"
  - "cleanup() ALWAYS clears MicroscopeController.currentTransform (H6 fix). Stale transform leaks to Live Viewer and next workflow."
  - "saveSlideAlignment writers MUST read flipMacroX/Y from the open entry, NOT from preset (2026-05-18 stage-mirror bug). All 4 save sites converged on AlignmentHelper.resolveMacroLookupKey + entry-flip-read pattern (Phase 4 M1)."
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
  - claude-reports/2026-05-31_stitching-recovery-dialog-fix.md
  - claude-reports/design/2026-05-13_subimage-acquisition-routing-fix.md
  - claude-reports/design/2026-05-07_step-b-flipped-duplicate-restoration.md
  - claude-reports/design/2026-05-19_flip-frame-save-site-fix.md
  - claude-reports/design/2026-05-19_stitching-cleanup-race.md
```

### W1a: processSubAcquisitionPath (sub-route of W1)

```yaml
id: W1a
class: qupath.ext.qpsc.controller.ExistingImageWorkflowV2
file: src/main/java/qupath/ext/qpsc/controller/ExistingImageWorkflowV2.java
entry: processSubAcquisitionPath (line 1213)
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
  - "Menu: Extensions > QP Scope > Multi-Slide Existing Image (when QPPreferenceDialog.getEnableMultiSlideWorkflow())"
dispatches_to:
  - "W1 (per-slot ExistingImageWorkflowV2.start)"
reads:
  - DS44 (StageInsertRegistry: multi-slot SLIDE_HOLDER carriers)
  - DS27 (macro entries: those without base_image set)
  - DS9 (SLIDE_POSITION pre-fill)
writes:
  - DS9 (per-assigned-entry: SLIDE_POSITION, SLIDE_CARRIER, MS_RUN_ID -- via ImageMetadataManager.setSlideAssignment)
  - DS27 (project sync after assignment)
key_invariants:
  - "Adds no new validation gates; each per-slot W1 invocation runs the full validation chain independently."
  - "Menu hidden by default; requires preference flag."
```

### W1c: processSlideSpecificAlignment (sub-route of W1)

```yaml
id: W1c
class: qupath.ext.qpsc.controller.ExistingImageWorkflowV2
file: src/main/java/qupath/ext/qpsc/controller/ExistingImageWorkflowV2.java
entry: processSlideSpecificAlignment (line 977)
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
entry: processExistingAlignmentPath (line 1123) -> ExistingAlignmentPath.execute()
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
entry: processManualAlignmentPath (line 1149) -> ManualAlignmentPath.execute()
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
  - "Menu: Extensions > QP Scope > Bounded Acquisition (SetupScope.java:185, dispatch 'boundedAcquisition')"
reads:
  - DS3 (modality FOV, pixel size, detector dimensions)
  - DS17 (isConnected; auto-connect if not)
  - DS24 (projectsFolder pref, tileOverlapPercent, stagePolarity)
  - DS25
  - DS45 (validateCameraRoi: getFrame())
  - DS41 (ModalityRegistry for enhanced modality)
writes:
  - DS27 (project: createAndOpenQuPathProject OR use existing)
  - DS28 (TileConfiguration.txt in stage-micron coords; modality folder includes objective suffix)
  - DS30 (stitched output -- post-acquisition)
  - DS19 (acquisition command file via AcquisitionCommandBuilder)
  - DS9 (new entry metadata after stitching)
steps:
  - { num: 1, name: ensure connection, file_line: "76-87", reads: ["DS17"], writes: [], gate: "no connect -> error -> abort" }
  - { num: 2, name: UnifiedAcquisitionController.showDialog, file_line: "90-103", reads: ["DS25", "DS3"], writes: [], gate: "null result -> abort" }
  - { num: 3, name: derive enhancedModality (ObjectiveUtils.createEnhancedFolderName), file_line: "120-122", reads: [], writes: [], gate: "" }
  - { num: 4, name: project setup (createAndOpen OR reuse), file_line: "131-172", reads: ["DS27"], writes: ["DS27"], gate: "" }
  - { num: 5, name: copyConfigsToProject (provenance), file_line: "195-201", reads: ["DS3"], writes: ["DS27/microscope_configs/"], gate: "best-effort" }
  - { num: 6, name: getModalityFOV + getPixelSize, file_line: "204-224", reads: ["DS3"], writes: [], gate: "null FOV -> error -> abort" }
  - { num: 7, name: QPScopeChecks.validateObjectivePixelSize + validateCameraRoi, file_line: "227-233", reads: ["DS3", "DS45"], writes: [], gate: "5% threshold -> hard-cancel on mismatch (single OK button)" }
  - { num: 8, name: StitchingConfiguration.validateWithRetry, file_line: "236-238", reads: ["DS25"], writes: [], gate: "user cancel -> abort" }
  - { num: 9, name: TilingUtilities.createTiles (bounding-box request), file_line: "241-256", reads: ["DS33"], writes: ["DS28"], gate: "" }
  - { num: 10, name: ChannelResolutionService + AngleResolutionService, file_line: "265-296", reads: ["DS3"], writes: [], gate: "empty selection on channel-based modality -> abort" }
  - { num: 11, name: launch acquisition via socket, file_line: "297+", reads: [], writes: ["DS17", "DS19", "DS45"], gate: "" }
  - { num: 12, name: stitch + autoRegisterBoundsTransformIfAvailable, file_line: "via STITCH_EXECUTOR", reads: ["DS28"], writes: ["DS30", "DS6", "DS9 (full metadata stamp)"], gate: "" }
  - { num: 13, name: cleanup tiles per preference, file_line: "via TileCleanupHelper", reads: ["DS25.tileHandlingMethod"], writes: ["DS28"], gate: "" }
key_invariants:
  - "Bounded acquisition is stage-frame native -- NO macro flip, NO alignment JSON read. Stitcher flips are stage-polarity-driven only."
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
  - "Menu: Extensions > QP Scope > Utilities > Microscope Alignment (SetupScope.java:246, dispatch 'microscopeAlignment')"
  - "Disabled unless MacroImageUtility.isMacroImageAvailable(qupath) returns true"
reads:
  - DS3
  - DS4 (existing TransformPresets for current (source, target) pair)
  - DS9 (open entry: SOURCE_MICROSCOPE, macro pixel size)
  - DS25 (stagePolarity, cameraOrientation)
  - DS17
writes:
  - DS4 (savePreset on success -- new TransformPreset stored in alignment YAML)
  - DS5 (saveGeneralTransform writes per-slide alignment JSON; line ~1503, commit 5bf0f7bf -- reads open-entry flips for save)
  - DS17 (currentTransform during refinement)
  - DS28 (alignment-tile config under temp dir)
key_invariants:
  - "savePreset stores flipMacroX/Y, sourceScanner, greenBoxParams, optional 3D Z scale/offset."
  - "saveGeneralTransform reads ImageMetadataManager.isFlippedX/Y(openEntry) for flipMacroX/Y on the per-slide JSON (matches load-side bake-delta logic, 2026-05-19 fix)."
  - "Refinement-tile cleanup happens explicitly on accept and reject (Phase 11)."
common_failures:
  - "Pre-fix per-slide JSONs (no flipFrameVerified field) -> Continue/Cancel advisory."
related_design_reports:
  - documentation/developer/COORDINATE_TRANSFORMS.md
  - claude-reports/design/2026-05-26_microscope-alignment-flip-short-circuit.md
```

### W4: BackgroundCollectionWorkflow

```yaml
id: W4
class: qupath.ext.qpsc.controller.BackgroundCollectionWorkflow
file: src/main/java/qupath/ext/qpsc/controller/BackgroundCollectionWorkflow.java
entry: run() / executeBackgroundAcquisitionDirect()
category: calibration
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Background Collection (dispatch 'backgroundCollection')"
reads:
  - DS3 (pixel size, modality FOV)
  - DS22 (WB mode validity via BackgroundValidityChecker)
  - DS45 (validateCameraRoi)
writes:
  - DS23 (background TIFFs at <base>/<detector>/<modality>/<mag>/<wbMode>/)
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
  - "Menu: Extensions > QP Scope > Utilities > Autofocus Benchmark (dispatch 'autofocusBenchmark')"
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
  - "Menu: Extensions > QP Scope > Utilities > Autofocus Editor (dispatch 'autofocusEditor')"
reads:
  - DS3 (autofocus YAML)
writes:
  - DS3 (autofocus YAML edits; per-objective AF params)
key_invariants:
  - "Config-only -- no microscope motion."
```

### W7: StackTimeLapseWorkflow

```yaml
id: W7
class: qupath.ext.qpsc.controller.StackTimeLapseWorkflow
file: src/main/java/qupath/ext/qpsc/controller/StackTimeLapseWorkflow.java
entry: show(qupath)
category: acquisition
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Z-Stack / Time-Lapse (SetupScope.java:453)"
  - "When QPPreferenceDialog.isSinglePointDialogEnabled(): SinglePointAcquisitionController.show() instead (default)"
reads:
  - DS3
  - DS17 (current stage Z)
writes:
  - DS30 (multi-plane OME-TIFF at current stage position)
key_invariants:
  - "Single-tile at current position -- NO objective parameter, NO tile grid, NO pixel-size gate."
```

### W8: TestAutofocusWorkflow

```yaml
id: W8
class: qupath.ext.qpsc.controller.TestAutofocusWorkflow
file: src/main/java/qupath/ext/qpsc/controller/TestAutofocusWorkflow.java
entry: runStandard() / runSweep()
category: test
ui_entries: []  # invoked programmatically (probe + script usage)
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
  - "Menu: Extensions > QP Scope > Utilities > JAI Camera > JAI White Balance (when hasJAICamera; dispatch 'whiteBalance')"
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
  - "Menu: Extensions > QP Scope > Utilities > WB Comparison (dispatch 'wbComparison')"
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
ui_entries: []  # Programmatically reached -- not currently in main menu wiring above
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
  - "Menu: Extensions > QP Scope > Utilities > Propagation Manager (SetupScope.java:469)"
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
entry: performAnnotationStitching / performRegionStitching / autoRegisterBoundsTransformIfAvailable / queueBackgroundZarrToTiffConversion
category: helper
reads:
  - DS28 (tile files + TileConfiguration.txt)
  - DS25 (stitching format/compression)
  - DS3 (microscope name for ACQUIRED_ON_MICROSCOPE stamping)
  - DS26 (StageImageTransform.stitcherFlipFlags)
writes:
  - DS30 (OME-TIFF/ZARR + pyramid)
  - DS6 (sub-frame alignment JSON via autoRegisterBoundsTransformIfAvailable)
  - DS9 (full metadata stamp on each newly-imported sub-image entry: BASE_IMAGE, ORIGINAL_IMAGE_ID, MODALITY, OBJECTIVE, DETECTOR_ID, XY_OFFSET, SOURCE_MICROSCOPE, ACQUIRED_ON_MICROSCOPE, FOV_X_UM, FOV_Y_UM, SOURCE_ROI_*, STAGE_BOUNDS_*, STITCHER_FLIP_X/Y)
key_invariants:
  - "autoRegisterBoundsTransformIfAvailable stamps STAGE_BOUNDS_* on the entry alongside the derived/ JSON so Move-to-Centroid works even if JSON lookup fails."
  - "STITCHER_FLIP_X/Y are recorded so Move-to-Centroid can rebuild pixel->stage from scratch."
  - "Background ZARR->TIFF conversion is queued (queueBackgroundZarrToTiffConversion), runs after stitching to free Live Viewer."
```

### W15: MakePortableWorkflow

```yaml
id: W15
class: qupath.ext.qpsc.controller.MakePortableWorkflow
file: src/main/java/qupath/ext/qpsc/controller/MakePortableWorkflow.java
entry: run(qupath)
category: utility
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Make Project Portable (SetupScope.java:536)"
reads:
  - DS27 (project image list; entries with .zarr backing)
  - DS28 (raw tile folders, when ZARR is absent)
writes:
  - DS30 (OME-TIFF replacements; preserves annotations and metadata)
  - DS27 (project sync after image swap)
key_invariants:
  - "Surfaces raw tile folders even when no ZARR remains (commit 7dc4a775)."
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
  - "Menu: Extensions > QP Scope > Utilities > Stitching Recovery (dispatch 'stitchingRecovery')"
reads:
  - DS28 (user-selected tile folder; TileConfiguration.txt)
  - DS25 (compression / output format)
writes:
  - DS30 (re-stitched image added to current project)
  - DS27 (project sync)
key_invariants:
  - "Project-anchored SlideImages folder; rename-after-stitch naming; typed compression/format with OME_TIFF_VIA_ZARR (2026-05-19 polish)."
```

### W17: FlippedDuplicateMigrator (one-shot utility)

```yaml
id: W17
class: qupath.ext.qpsc.utilities.FlippedDuplicateMigrator
file: src/main/java/qupath/ext/qpsc/utilities/FlippedDuplicateMigrator.java
entry: migrate(typedProject)
category: utility
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Migrate Flipped Duplicates (SetupScope.java:479)"
reads:
  - DS27 (project list)
  - DS9 (BASE_IMAGE / sibling chains; isFlippedX/Y)
writes:
  - DS27 (removes legacy '(flipped X|Y|XY)' entries; transfers annotations to unflipped base)
  - DS9 (entry metadata only via removal)
key_invariants:
  - "Safe to run repeatedly -- no-op when no duplicates remain. Idempotent."
  - "Companion '(flipped X|Y|XY)' entries are still legitimately created on demand for visual-UX during alignment -- DO NOT strip them in the workflow path. Only legacy duplicates targeted."
```

### W18: MicroManagerStitchWorkflow (no project, no microscope)

```yaml
id: W18
class: qupath.ext.qpsc.controller.MicroManagerStitchWorkflow
file: src/main/java/qupath/ext/qpsc/controller/MicroManagerStitchWorkflow.java
entry: run()
category: utility
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Stitch MicroManager Folder (dispatch 'stitchMicroManagerFolder')"
reads:
  - user-selected MicroManager tile folder (OME-TIFF with MMStack sidecars)
writes:
  - DS30-like (stitched output to user-chosen folder; .mm-metadata.json sidecar)
key_invariants:
  - "No project required, no microscope required."
```

### W19: LiveViewerWindow (UI singleton)

```yaml
id: W19
class: qupath.ext.qpsc.ui.liveviewer.LiveViewerWindow
file: src/main/java/qupath/ext/qpsc/ui/liveviewer/LiveViewerWindow.java
entry: show() / hide() / lockControls(reason) / unlockControls()
category: ui_singleton
ui_entries:
  - "Menu: Extensions > QP Scope > Live Viewer (dispatch 'liveViewer')"
  - "Implicit: any workflow with primary-socket activity locks the live viewer (W1 acquisition)"
reads:
  - DS17 (currentTransform for Go-To-Centroid path)
  - DS45 (auxiliary socket frames; stage polling)
  - DS9 (STAGE_BOUNDS for Go-To-Centroid fallback)
  - DS6 (per-slide sub-frame JSON for click-to-stage)
writes:
  - DS43 (live viewer streaming state, contrast, histogram)
  - DS17 (joystick / WASD stage motion via MicroscopeController.moveStage*)
key_invariants:
  - "Z bar widget + movement gate (commit 8d324838) -- suppresses Z trace during motion."
  - "Lock/unlock controls via static helpers; isLocked() reports global state."
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
  - "Menu: Extensions > QP Scope > Utilities > Re-probe Stage AF (dispatch 'probeStageAf')"
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
  - "Menu: Extensions > QP Scope > Stage Map (SetupScope.java:270)"
reads:
  - DS44 (StageInsertRegistry: carrier definitions)
  - DS9 (entry metadata: BASE_IMAGE chain ancestry walk to macro-owning ancestor; SOURCE_MICROSCOPE)
  - DS25 (stagePolarity + cameraOrientation composite)
  - DS4 (active source preset for source dropdown)
  - DS5 (per-slide alignment JSON)
  - DS17 (current stage position)
writes:
  - none (display-only); MAY trigger stage motion via MicroscopeController.moveStageXY
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
  - "Menu: Extensions > QP Scope > Utilities > Setup Wizard (dispatch 'setupWizard')"
  - "Top-level when !configValid: 'Setup Wizard (Start Here)' inserted before main wizard (SetupScope.java:624)"
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
  - "Dynamic: PPMModalityHandler.getMenuContributions() -> 'polarizerCalibration' (when PPM modality present)"
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
  - "Dynamic: PPMModalityHandler menu -> 'birefringenceOptimization'"
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
  - "Dynamic: PPMModalityHandler menu -> 'sunburstCalibration'"
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
  - "Dynamic: PPMModalityHandler menu -> 'ppmSensitivityTest'"
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
  - "Menu: Extensions > QP Scope > Utilities > JAI Camera > JAI Noise Characterization (when hasJAICamera; dispatch 'noiseCharacterization')"
reads:
  - DS3
writes:
  - per-run noise characterization output
```

### W28: AcquisitionWizardDialog (top-of-menu router)

```yaml
id: W28
class: qupath.ext.qpsc.ui.AcquisitionWizardDialog
file: src/main/java/qupath/ext/qpsc/ui/AcquisitionWizardDialog.java
entry: show()
category: ui_router
ui_entries:
  - "Menu: Extensions > QP Scope > Acquisition Wizard (SetupScope.java:168, dispatch 'acquisitionWizard')"
dispatches_to:
  - "W1 'Start Existing-Image Acquisition' button"
  - "W2 bounded acquisition entry"
  - "Connection checklist, calibration steps"
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
class: qupath.ext.qpsc.ui.ServerConnectionController
file: src/main/java/qupath/ext/qpsc/ui/ServerConnectionController.java
entry: showDialog()
category: configuration
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Server Connection (dispatch 'serverConnection')"
reads:
  - DS25 (server host/port preferences)
  - DS17 (connection probe)
writes:
  - DS25 (server host/port, notification settings)
```

### W30: CameraControlController

```yaml
id: W30
class: qupath.ext.qpsc.ui.CameraControlController
file: src/main/java/qupath/ext/qpsc/ui/CameraControlController.java
entry: showCameraControlDialog()
category: utility
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Camera Control (dispatch 'cameraControl')"
reads:
  - DS3 (imaging profiles)
  - DS17 (live exposures/gains)
writes:
  - DS17 (apply camera settings + rotation stage angle) -- non-persistent test settings
```

### W31: ParfocalityCalibrationController

```yaml
id: W31
class: qupath.ext.qpsc.ui.ParfocalityCalibrationController
file: src/main/java/qupath/ext/qpsc/ui/ParfocalityCalibrationController.java
entry: show()
category: calibration
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Parfocality Calibration (dispatch 'parfocalityCalibration')"
reads:
  - DS3
  - DS17 (current Z under each profile)
writes:
  - DS20 (parfocality_<scope>.yml sidecar next to microscope config)
```

### W32: SinglePointAcquisitionController (Z-stack / time-lapse default)

```yaml
id: W32
class: qupath.ext.qpsc.ui.SinglePointAcquisitionController
file: src/main/java/qupath/ext/qpsc/ui/SinglePointAcquisitionController.java
entry: show()
category: acquisition
ui_entries:
  - "Menu: Extensions > QP Scope > Utilities > Z-Stack / Time-Lapse -- default path (SetupScope.java:460-465 selects this when isSinglePointDialogEnabled())"
reads:
  - DS3
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
  rotation.angles[<modality>]: "rotation stage presets"
written_by:
  - "W22 ConfigFileWriter"
  - "W20 ProbeStageAfWorkflow (stage.streaming_af.*)"
  - "W6 AutofocusEditorWorkflow"
read_by:
  - "ALL workflows via MicroscopeConfigManager.getInstance"
invariants:
  - "Loaded once per process; reload() must be explicitly called after edits."
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
  - "W22 (Camera Control dialog displays calibrated values)"
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
  - "W3 MicroscopeAlignmentWorkflow.saveGeneralTransform (line ~1503, commit 5bf0f7bf)"
  - "W1e ManualAlignmentPath.createManualAlignment"
  - "W1d ExistingAlignmentPath.saveSlideAlignment"
  - "W1 ExistingImageWorkflowV2.saveRefinedAlignment (line 1699-1771, ONLY on refinement accepted; short-circuits on sub-acquisition)"
read_by:
  - "W1 via AlignmentHelper.checkForSlideAlignment (tier 1)"
  - "W21 StageMapWindow (source dropdown / Apply Flips)"
  - "W19 LiveViewerWindow Go-To-Centroid"
  - "W12 ForwardPropagationWorkflow.buildGroups (tier 1 of three-tier lookup)"
  - "W1 cross-scope path: AffineTransformManager.loadAllSlideAlignmentsFromDirectory enumerates all scopes"
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
location: "MicroscopeController singleton (process-scoped)"
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
  - "W3 during refinement"
  - "W19 LiveViewer stage motion (moveStageXY/Z/R)"
read_by:
  - "Every workflow that issues stage commands -- onMoveButtonClicked converts via currentTransform"
  - "W19 LiveViewerWindow Go-To-Centroid"
invariants:
  - "currentTransform MUST be cleared in cleanup() (H6 fix). A stale transform leaks to Live Viewer and the next workflow."
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
fields_flip:
  FLIP_X = "flip_x": bool
  FLIP_Y = "flip_y": bool
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
written_by:
  - "W1, W2 stitch-import sites via StitchingHelper -> full bundle stamp on each new sub-image entry"
  - "W1b setSlideAssignment (SLIDE_POSITION, SLIDE_CARRIER, MS_RUN_ID)"
  - "Project import flow (manual scanner image add): SOURCE_MICROSCOPE via MicroscopeSelectionDialog"
  - "User edits (rare): manual metadata fields"
read_by:
  - "EVERY workflow -- gating, lookup, propagation, display"
invariants:
  - "FLIP_X/Y are the OPTICAL flip of the image. Independent of stage polarity (DS25)."
  - "SOURCE_MICROSCOPE is per-import; never overwrite from StageMapWindow dropdown (commit d531d696)."
  - "ACQUIRED_ON_MICROSCOPE is per-import-at-acquisition-time. Macro entries do NOT carry this field."
  - "BASE_IMAGE chain MUST be walked via AlignmentHelper.resolveMacroLookupKey to find macro-owning ancestor."
  - "SOURCE_ROI_* is base-pixel coords on the UNFLIPPED base entry; SOURCE_ROI_FLIP_* records mirror flag relative to the rectangle when derived from a flipped sibling."
  - "STAGE_BOUNDS_* + STITCHER_FLIP_* allow Move-to-Centroid to reconstruct pixel->stage without DS6 (cross-scope guard, restructured directory, legacy file)."
  - "isFlippedX/Y/isFlipped read FLIP_X/Y; these helpers are the canonical readers (not direct metadata lookup)."
  - "getSiblingsByBaseImage enumerates entries sharing the same BASE_IMAGE -- used for flipped sibling resolution."
related_surfaces:
  - DS5, DS6 (alignment JSONs cross-reference via lookupKey from getSampleName / BASE_IMAGE)
  - DS10 (legacy single-image metadata; superseded by this bundle)
```

#### DS10: Image flip flags (legacy alias for DS9 FLIP_X/Y)

```yaml
id: DS10
category: metadata
storage_kind: project_entry_metadata
fields:
  FLIP_X, FLIP_Y (see DS9)
note: "Maintained as a separate ID for cross-reference. All accesses MUST go through ImageMetadataManager.isFlippedX/Y. Per CLAUDE.md: optical flip is independent of stage wiring polarity. Do NOT XOR with stage polarity."
related_surfaces:
  - DS25 (StagePolarity preference -- distinct concept)
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
  mmStitchOutputDir, mmStitchInputDir
  metadataPropagationPrefix, classList, modalityForAutomation
  (235 accessors total -- see PersistentPreferences.java)
written_by:
  - "Various UI dialogs (SampleSetupController, AcquisitionWizardDialog, BackgroundCollectionController, etc.)"
read_by:
  - "Every workflow on dialog open"
invariants:
  - "Lightweight per-user prefs only. Dialog positions used to live here (8 KB cap caused issues) -- now in DS24a (JSON file). See 2026-05-19 dialog-position-storage refactor."
```

#### DS25: QPPreferenceDialog (per-user, structured)

```yaml
id: DS25
category: preferences
storage_kind: qupath_preferences
fields_examples:
  stageInvertedX, stageInvertedY (StagePolarity composite)
  cameraOrientation
  microscopeServerHost, microscopeServerPort, autoConnectToServer
  microscopeConfigFile (path to YAML)
  projectsFolder
  tissueDetectionScript, dataBoundsClassifier
  saveRawTiles, tileHandlingMethod, tileOverlapPercent
  compressionType (OMEPyramidWriter.CompressionType)
  stitchingOutputFormat (StitchingConfig.OutputFormat)
  includeObjective/Modality/Annotation/Angle InFilename
  metadataPropagationPrefix
  skipManualAutofocus, disableAllAutofocus
  warnOnLowDiskSpace, enableMultiSlideWorkflow
  suppressExposureWarning, streamingMaxExposureMs
  streamingAfMigrationAcknowledged, autofocusYamlMigrationAcknowledged, autofocusEditorAdvancedMode
  notificationsEnabled, notificationTopic, notificationServer
  notifyOnAcquisition, notifyOnStitching, notifyOnErrors
  completionBeepEnabled
  lastCalibrationFolder
  sunburstLastModality, sunburstExpectedSpokes, sunburstSaturationThreshold, sunburstValueThreshold
  sunburstRadiusInner, sunburstRadiusOuter
  singlePointDialogEnabled (W7 vs W32 routing)
written_by:
  - "QuPath Preferences dialog (shown via installPreferences())"
  - "Various code paths via static setters"
read_by:
  - "Every workflow"
invariants:
  - "stageInvertedX/Y read via StagePolarity composite enum -- do NOT read individual booleans directly in new code (use StageImageTransform.current() -- see DS26)."
  - "cameraOrientation composes with stageInvertedX/Y through StageImageTransform; never read polarity booleans directly for live-view sign conventions."
related_surfaces:
  - DS26 (StageImageTransform composes DS25 fields)
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
  - claude-reports/design/2026-05-19_dialog-position-storage-refactor.md
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
  - "bf_if -> BfIfModalityHandler"
written_by:
  - "Static initializer block in ModalityRegistry"
  - "registerHandler(prefix, handler) -- runtime extension"
read_by:
  - "getHandler(modality) -- startsWith prefix match; returns NoOpModalityHandler if unmatched"
  - "Every acquisition workflow"
invariants:
  - "Prefix matching is startsWith. Longer prefixes should register before shorter ones to avoid unintended matches."
  - "Returns NoOpModalityHandler for unknown -- never null."
  - "PPMModalityHandler.getMenuContributions() provides dynamic menu items (polarizerCalibration, ppmSensitivityTest, birefringenceOptimization, sunburstCalibration) -- not in QPScopeController.startWorkflow switch."
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
location: "StageImageTransform.current() -- composes stagePolarity + cameraOrientation"
fields:
  stagePolarity, cameraOrientation
  screenPanDeltaToMmDelta: function
  clickOffsetToMmTarget: function
  stitcherFlipFlags: bool[]
written_by:
  - "Implicit via DS25 setters"
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
invariants:
  - "Bounding-box preview cleared on close (commit d553535)."
```

#### DS45: MicroscopeSocketClient (singleton, two sockets)

```yaml
id: DS45
category: service
storage_kind: singleton
location: "MicroscopeController owns; two TCP sockets to Python command server"
fields:
  primary socket: acquisition / CONFIG / long-running ops
  aux socket: Live Viewer frames, stage polling
  Command enum (GET_XY, GET_Z, MOVE, START_ACQ, etc.)
  AcquisitionState, AcquisitionProgress
written_by:
  - "MicroscopeController commands"
read_by:
  - "Every workflow with hardware interaction"
invariants:
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
  <modalityWithIndex>/: "DS28 per-acquisition tile dirs"
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
location: "<projectDir>/<sampleName>/<modalityWithIndex>/<annotation>/"
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
  destroyed_when: "TileCleanupHelper.performCleanup per QPPreferenceDialog.tileHandlingMethod; force-delete on workflow error (Phase 11)"
invariants:
  - "Modality folder includes objective suffix (e.g. 'Fluorescence_10x') via ObjectiveUtils.createEnhancedFolderName."
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
  - OME-TIFF via ZARR (background conversion; see W14 queueBackgroundZarrToTiffConversion)
  - ZARR direct
written_by:
  - "Stitcher (tiles-to-pyramid) called from W14 StitchingHelper"
  - "W16 StitchingRecoveryWorkflow"
  - "W18 MicroManagerStitchWorkflow"
  - "W15 MakePortableWorkflow (ZARR -> OME-TIFF)"
read_by:
  - "QuPath project (added as new ProjectImageEntry)"
invariants:
  - "Naming: rename-after-stitch (commit area of 2026-05-19 polish)."
  - "Compression / format set via QPPreferenceDialog.compressionTypeProperty / stitchingOutputFormat."
  - "moveDirectoryWithRetryAndCopyFallback (qpsc 732fbd4) prevents stranded _temp_<angle>_<hash> folders."
related_design_reports:
  - claude-reports/design/2026-05-19_stitching-cleanup-race.md
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

### Derived / Service

#### DS33: TilingRequest (per-workflow build)

```yaml
id: DS33
category: derived
storage_kind: in_memory
fields:
  outputFolder, modalityName, frameSize, overlapPercent, boundingBox OR annotations
  stageInvertedAxes (read from DS25)
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
| W1, W2, W3, W19 | DS17 (currentTransform) | W19 Live Viewer, next workflow on cleanup | MUST be cleared in cleanup() (H6) |
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
| W1, W2, W14, W16, W18 | DS30 (stitched OME-TIFF / ZARR) | QuPath project (new ProjectImageEntry) | Compression/format from DS25 |
| W15 MakePortableWorkflow | DS30 (OME-TIFF replacements) | QuPath project | Surfaces tile folders when no ZARR remains |
| Any dialog | DS24 (PersistentPreferences) | Workflow on next dialog open | Lightweight per-user prefs |
| Any dialog | DS25 (QPPreferenceDialog) | EVERY workflow | Stage polarity, server settings, stitching format |
| DS25 (stagePolarity + cameraOrientation) | DS26 (StageImageTransform) | W19 LiveViewer (joystick/WASD/click), stitcher flip flags, W21 Apply Flips | Composes the two -- don't read polarity booleans directly |
| Setup / startup | DS41 (ModalityRegistry) | EVERY acquisition workflow via getHandler | Prefix match; NoOpModalityHandler if unmatched |
| User selection | DS42 (ModalityState) / DS42a (ObjectiveState) | EVERY workflow needing active modality / objective | Singleton |
| W1c cross-scope | DS39 (CrossScopeTransformBuilder output) | W1 acquisition path | Uses source scope's stored offset |
| W12, W13 | DS40 (propagation builder output) | W12, W13 execute | Tile-detection path bypasses |
| W17 FlippedDuplicateMigrator | DS27 (project), DS9 (entries removed) | All workflows that enumerate project entries | Idempotent one-shot |
| W19 LiveViewerWindow | DS43 (live viewer state), DS17 (stage motion) | Any workflow that takes/releases lock | Locked during primary-socket work |
| W21 StageMapWindow | DS44 (stage map state) | -- (display-only) | Reads DS9 ancestry; never overwrites SOURCE_MICROSCOPE |
| W14 (StitchingHelper) | DS6 (sub-frame JSON) | W19 Go-To-Centroid, W12/W13 tier 2 | Filename encodes scope (parsed by getDerivedAlignmentMicroscope) |
| AcquisitionWizardDialog (W28) | DS24 (last modality/objective) | W1, W2 on next launch | Re-seeds dialogs |
| W1 | DS28 (tile cleanup) | DS27 (less data on disk) | TileCleanupHelper.performCleanup; force-delete on error (Phase 11) |
| W1 setCurrentTransform | DS17 | All subsequent stage commands during this workflow | MUST be AFTER validateAndFlipIfNeeded |
| W1 saveRefinedAlignment | DS5 (overwrites on accept) | W1 next launch, W12/W13 | Short-circuits for sub-acquisitions (H2/H3) |
| W3 saveGeneralTransform | DS5 (entry-flip-read for save) | W1 alignment lookup | flipFrameVerified=true post-2026-05-19 |
| W14 (StitchingHelper.queueBackgroundZarrToTiffConversion) | DS30 (delayed ZARR -> TIFF) | W15 MakePortableWorkflow (after conversion) | Runs in background to free Live Viewer |
| W1d ExistingAlignmentPath | DS46 (data bounds classifier .json) | W1d only | Detects signal-bearing region inside macro |
| W1 routeSubWorkflow | (no DS write) | W1a, W1c, W1d, W1e | isSubAcquisition() MUST be checked before useExistingSlideAlignment |
| W1 checkAndHandleSourceMismatch | DS9 (SOURCE_MICROSCOPE fix) | W1 subsequent steps | Auto-resolves via acquired_on |
| W1 checkAndHandleOrphanedFlippedSibling | (no DS write; just guards) | W1 abort or continue | Dialog forces restart with valid base |
| W1c (slide-specific alignment save) | DS5 (entry-flip-read) | W1 next launch | Uses AlignmentHelper.resolveMacroLookupKey |
| W1e ManualAlignmentPath | DS5 (createManualAlignment writes) | W1, W21 | Reads slide calibration, NOT scanner macro pixel size |
| ImageFlipHelper.validateAndFlipIfNeeded | DS9 (FLIP_X/Y consistency), DS17 | W1 acquisition | MUST run before setCurrentTransform |
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
    are registered dynamically via `PPMModalityHandler.getMenuContributions()`.
    Adding a new PPM workflow requires extending the menu contributions list,
    not the switch. Affects: W23-W26. Watched: PPMModalityHandler.

11. **Stage polarity and camera orientation are INDEPENDENT.** Six pre-2026
    call sites read `StagePolarity` directly without composing with
    `CameraOrientation`; treat as a bug class. All new code MUST go through
    `StageImageTransform.current()`. Affects: DS25, DS26. Watched: every live-view
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

16. **`(flipped X|Y|XY)` companion entries are still legitimately created on
    demand for visual-UX during alignment.** Do NOT strip them in the workflow
    path. Only `FlippedDuplicateMigrator` (W17) targets legacy duplicates.
    Affects: W17, DS27.

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

30. **`MultiSlideExistingImageWorkflow` (W1b) is experimental and gated by
    preference.** It adds no new validation gates; each per-slot W1 invocation
    runs the full validation chain independently. Don't bypass per-slot gates
    for "performance". Affects: W1b.

31. **`ModalityRegistry.getHandler` returns `NoOpModalityHandler` for unknown
    modalities, never null.** Prefix matching is `startsWith`. Longer prefixes
    must register before shorter ones to avoid unintended matches. Affects: DS41.

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
    Don't move into async chain. Affects: W2 (line 265-273).

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
  `ImageMetadataManager.getSiblingsByBaseImage`. Flipped siblings have
  `FLIP_X/Y` true relative to the base.

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
  call via `startWorkflow(mode)`. PPM workflows bypass this and are
  registered via `ModalityHandler.getMenuContributions()`.

- **preset vs per-slide JSON** -- TransformPreset (DS4) is the per-scope-pair
  template stored in alignment YAML. Per-slide JSON (DS5/DS6) is the snapshot
  applied to a specific entry in a project. Both store flipMacroX/Y but the
  per-slide JSON's flip MUST come from the open entry at save time, not from
  the preset.

- **sub-frame JSON (derived/)** -- DS6. Auto-registered by
  `StitchingHelper.autoRegisterBoundsTransformIfAvailable` after stitching;
  stored under `alignmentFiles/derived/`. Filename encodes the acquiring scope.
  Pixel frame is `"sub"`.

- **flipped sibling** -- A `(flipped X|Y|XY)` companion entry created on
  demand for visual-UX during alignment. NOT the same as a legacy duplicate
  (which `W17 FlippedDuplicateMigrator` removes).

- **half-FOV correction** -- The offset that accounts for tile centers vs
  tile corners. Missing it shifts propagated annotations by half a field of
  view. FOV is read from `OBJECTIVE AT ACQUISITION TIME` (DS9.OBJECTIVE), not
  the current objective.

- **modality handler** -- A class implementing `ModalityHandler` registered
  with a string prefix in `ModalityRegistry`. Provides modality-specific
  acquisition parameters, validation, and menu contributions. Lookup is
  `startsWith` on the modality name.

- **StageImageTransform** -- Composite of stage polarity and camera
  orientation (DS25 + DS25 -> DS26). Single source of truth for all
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
