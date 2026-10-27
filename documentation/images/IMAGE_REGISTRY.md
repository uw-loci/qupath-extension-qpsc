# Documentation Image Registry

This file tracks the relationship between documentation screenshots and the Java UI classes that produce them. When a tracked Java file is modified, the corresponding screenshot may need to be re-captured.

**Maintenance:** Update this registry when adding new screenshots or when UI classes are renamed/moved.

**Annotation callouts:** Screenshots with numbered brackets/circles (baked on by `tools/annotate_screenshots.py`) have their intended targets documented in [`ANNOTATION_REFERENCE.md`](ANNOTATION_REFERENCE.md) -- read it before re-capturing an annotated dialog.

## Image-to-Source Mapping

| Screenshot | Java Source File(s) | Last Verified | Status |
|------------|-------------------|---------------|--------|
| `Docs_AcquisitionWizard.png` | `ui/AcquisitionWizardDialog.java` | 2026-10-27 | OK -- re-captured 2026-10-27 on the Windows QuPath (11, 150% display, downscaled to 100%) against the demo microscope, Modena Light, main `b4c82163`, profile "Demo quad carrier" active: the Background profile row (combo + Manage...) under Detector, the checklist states the demo gives (connected; no WB for a non-JAI camera; no backgrounds; no alignment), the Validate AF row, the icon acquire buttons. The PPM multi-dot WB states are described in the text, not shown |
| `Docs_AcquisitionWorkflowProgress.png` | `ui/DualProgressDialog.java` | 2026-05-27 | OK |
| `Docs_AnnotationAcquisition.png` | `ui/AnnotationAcquisitionDialog.java` | 2026-08-22 | OK -- re-captured (unannotated). Mapped to the actual class selection dialog rather than the broad ExistingImageAcquisitionController; the multi-tile refinement radio lives in the consolidated dialog, not here, so this no longer false-flags |
| `Docs_AutofocusConfigurationEditor.png` | `controller/AutofocusEditorWorkflow.java` | 2026-05-27 | OK |
| `Docs_AutofocusParameterBenchmark.png` | `ui/AutofocusBenchmarkDialog.java` | -- | CHECK |
| `Docs_BackgroundCollection.png` | `ui/BackgroundCollectionController.java` | 2026-10-27 | OK -- re-captured 2026-10-27 on the Windows QuPath (11, 150% display, downscaled to 100%) against the demo microscope, Modena Light, main `b4c82163`, profile "Demo quad carrier" active: Background folder (renamed from Output folder), Background profile row with Manage..., Acquisition Profile, lamp, exposure modes; the whole form, rendered from the scene graph with the native title bar so nothing scrolls |
| `Docs_BackgroundProfiles.png` | `ui/BackgroundProfilesDialog.java` | 2026-10-27 | OK -- re-captured 2026-10-27 on the Windows QuPath (11, 150% display, downscaled to 100%) against the demo microscope, Modena Light, main `b4c82163`, profile "Demo quad carrier" active: the profile list with one active profile selected and its record (id, description, expects, created, sets, folder), New/Edit/Set active/Open folder/Delete |
| `Docs_BoundedAcquisition.png` | `ui/UnifiedAcquisitionController.java` | 2026-10-27 | OK -- re-captured 2026-10-27 on the Windows QuPath (11, 150% display, downscaled to 100%) against the demo microscope, Modena Light, main `b4c82163`, profile "Demo quad carrier" active: the Background profile row (combo, Manage..., "No backgrounds" status) in HARDWARE CONFIGURATION; AUTOFOCUS expanded, MODALITY-SPECIFIC / Z-STACK / TIME-LAPSE collapsed; brightfield, so no PPM angle content. Rendered from the scene graph at 830 px wide (the row truncates narrower) |
| `Docs_BoundedAcquisition_TileGrid.png` | Lab-meeting slide deck (workflow figure; not a single UI class) | 2026-06-01 | OK |
| `Docs_CameraControl.png` | `ui/CameraControlController.java` | 2026-05-27 | OK |
| `Docs_CommunicationSettings.png` | `ui/ServerConnectionController.java` | 2026-05-27 | OK |
| `Docs_CommunicationSettings_Alerts.png` | `ui/ServerConnectionController.java` | 2026-05-27 | OK |
| `Docs_CrossInstrumentWorkflow.png` | Lab-meeting slide deck (workflow figure; not a single UI class) | 2026-06-01 | OK |
| `Docs_ExistingImage_ConsolidatedDialog.png` | `ui/ExistingImageAcquisitionController.java` | 2026-10-27 | OK -- re-captured 2026-10-27 on the Windows QuPath (11, 150% display, downscaled to 100%) against the demo microscope, Modena Light, main `b4c82163`, profile "Demo quad carrier" active: the Background profile row, the demo image's auto-registered alignment, collapsed AUTOFOCUS / MODALITY-SPECIFIC / Z-STACK headers, one Tissue annotation (36 tiles). Rendered from the scene graph at 820 px wide so nothing scrolls; see `Docs_ExistingImage_Modality_Zstack.png` for the panes expanded |
| `Docs_ExistingImage_FluorescenceProject.png` | Lab-meeting slide deck (QuPath project view; not a single UI class) | 2026-06-01 | OK |
| `Docs_ExistingImage_Modality_Zstack.png` | `ui/ExistingImageAcquisitionController.java` | 2026-10-27 | OK -- re-captured 2026-10-27 on the Windows QuPath (11, 150% display, downscaled to 100%) against the demo microscope, Modena Light, main `b4c82163`, profile "Demo quad carrier" active: AUTOFOCUS, MODALITY-SPECIFIC (brightfield exposure) and Z-STACK panes expanded, Z-stack enabled, cropped from a JavaFX render of the dialog content. A PPM version would show the angle checkboxes instead |
| `Docs_LiveViewer.png` | `ui/liveviewer/LiveViewerWindow.java` | 2026-10-27 | OK -- re-captured 2026-10-27 on the Windows QuPath (11, 150% display, downscaled to 100%) against the demo microscope, Modena Light, main `b4c82163`, profile "Demo quad carrier" active, live on the demo H&E slide at 10x: Hist: Bottom, so the Histogram & Contrast panel sits under the image and the Navigate tab runs down to the status bar; Go to Z and Move Z under the Z step. Unannotated |
| `Docs_LiveViewer_Navigate.png` | `ui/liveviewer/StageControlPanel.java` (Navigate tab only) | 2026-10-27 | OK -- cropped from the same 2026-10-27 Live Viewer capture: step, joystick, Coarse/Fine Z bars, Mark Z, Go to Z + Move Z under the step size, Move to Position (X/Y only: the demo has no rotation stage), Calibrate Directions. The class also builds the Camera tab, so camera-only edits flag this figure spuriously |
| `Docs_LiveViewer_SavedPoints.png` | `ui/liveviewer/StageControlPanel.java` (Saved Points tab only) | 2026-07-22 | CHECK -- shares a source class with the Navigate and Camera tabs, so it flags on camera edits that cannot affect it; verify before re-capturing |
| `Docs_LiveViewer_Camera.png` | `ui/liveviewer/LiveViewerWindow.java` (camera tab) | -- | CHECK -- previously untracked |
| `Docs_MicroscopeAlignment_SelectSourceMicroscope.png` | `ui/MicroscopeSelectionDialog.java` | -- | CHECK |
| `Docs_MicroscopeAlignment_RefineAlignment.png` | `controller/workflow/SingleTileRefinement.java` (3-point / single-tile refinement dialog) | -- | CHECK -- SIFT refinement dialogs changed substantially; verify or re-capture |
| `Docs_MultiTileRefinement.png` | `controller/workflow/MultiTileRefinement.java` (numbered multi-tile SIFT panel, initial state) | 2026-07-23 | OK |
| `Docs_MultiTileRefinement2.png` | `controller/workflow/MultiTileRefinement.java` (per-point capture state) + SIFT Matching Settings dialog (`ui/SiftAutoAlignHelper.java`) | 2026-07-23 | OK |
| `Docs_StageMap.png` | `ui/stagemap/StageMapWindow.java`, `ui/stagemap/StageMapCanvas.java` | 2026-08-22 | OK -- re-captured; single horizontal slide with macro overlay, now showing the Slide-placement dropdown and "Calibrate..." |
| `Docs_DishCoverslipCalibration.png` | Diagram / `ui/stagemap/StageMapWindow.java` insert calibration | -- | CHECK -- previously untracked |
| `Docs_PropagationManager.png` | `controller/ForwardPropagationWorkflow.java` | -- | CHECK -- previously untracked |
| `Docs_NoiseCharacterization.png` | `ui/NoiseCharacterizationDialog.java` | -- | CHECK -- previously untracked |
| `Docs_ZStackTimelapse.png` | `controller/StackTimeLapseWorkflow.java` | -- | CHECK -- previously untracked |
| `Docs_SetupWizard_Welcome.png` | `ui/setupwizard/SetupWizardDialog.java` | -- | CHECK -- previously untracked |
| `Docs_WBComparisonTest.png` | `ui/WBComparisonDialog.java` | -- | CHECK -- previously untracked |
| `Docs_SystemArchitecture.png` | Lab-meeting slide deck (architecture diagram; not a UI class) | 2026-06-01 | OK |
| `Docs_WorkflowOverview_Desktop.png` | Lab-meeting slide deck (full-desktop composite; multiple UI classes) | 2026-06-01 | OK |
| `Docs_WhiteBalanceCalibration.png` | `ui/WhiteBalanceDialog.java` | 2026-05-27 | OK |
| `Docs_ppmmenu.png` | `SetupScope.java` (PPM modality submenu) | -- | CHECK |
| `Docs_menu_acquisition_controls.png` | GENERATED from `images/menus/Docs_menu_acquisition_controls.menu` by `tools/render_menu_mock.py` | 2026-09-19 | OK -- mock-up, not a screenshot. Do not re-capture; edit the `.menu` spec and re-run |
| `Docs_menu_image_quality.png` | GENERATED from `images/menus/Docs_menu_image_quality.menu` by `tools/render_menu_mock.py` | 2026-09-19 | OK -- mock-up, not a screenshot. Do not re-capture; edit the `.menu` spec and re-run |
| `Docs_menu_microscope_configuration.png` | GENERATED from `images/menus/Docs_menu_microscope_configuration.menu` by `tools/render_menu_mock.py` | 2026-09-19 | OK -- mock-up, not a screenshot. Do not re-capture; edit the `.menu` spec and re-run |
| `Docs_MultiSlide_Assignment.png` | `ui/MultiSlideAssignmentDialog.java` | 2026-09-19 | OK -- re-captured; shows the Modality / Objective / Detector block and the per-mode calibration status lines incl. a FAILED focus-approach characterisation |
| `Docs_MultiSlide_BatchPanel.png` | `controller/MultiSlideExistingImageWorkflow.java` | 2026-09-19 | OK -- re-captured at the END of a run (all four slots Done, Finish enabled, measured run estimate shown). Supersedes the 2026-07-23 figure, whose title bar still read "MS Workflow Progress (experimental)" before the rename to "Multi-Slide Acquisition Progress". The Advanced / SIFT settings pane is collapsed here; it was expanded in the old figure |
| `Docs_SaturationSummary.png` | `ui/SaturationSummaryDialog.java` | 2026-09-19 | OK -- multi-slide run (7 acquisitions); 3 flagged samples expanded worst-first, clean ones collapsed, and the collapsed "Expected bright tiles" row that separates modality-normal brightness from concerning saturation |
| `Docs_MultiSlide_StageMap.png` | `ui/stagemap/StageMapWindow.java`, `ui/stagemap/StageMapCanvas.java` (four-slide vertical carrier) | 2026-09-19 | OK -- re-captured; zoomed to show per-slide tissue bounding boxes, the camera-FOV crosshair, and a 180-degree-rotated slide label |

## Missing Screenshots (UI exists but no image)

| UI Component | Java Source | Suggested Filename |
|-------------|------------|-------------------|
| Hardware Error Recovery Dialog | `controller/workflow/AcquisitionManager.java` | `Docs_HardwareErrorDialog.png` |
| Alignment Quality Summary | `controller/MicroscopeAlignmentWorkflow.java` | `Docs_AlignmentQualitySummary.png` |
| Autofocus Validation Result | `controller/AutofocusEditorWorkflow.java` | `Docs_AutofocusValidation.png` |
| Setup Wizard | `ui/setupwizard/SetupWizardDialog.java` | `Docs_SetupWizard.png` |
| Alignment Refinement with SIFT | `controller/workflow/SingleTileRefinement.java` | `Docs_AlignmentRefinementSIFT.png` |
| Propagation Manager | `controller/ForwardPropagationWorkflow.java` | `Docs_PropagationManager.png` |
| Z-Stack / Time-Lapse Dialog | `controller/StackTimeLapseWorkflow.java` | `Docs_ZStackTimeLapse.png` |
| PPM Polarizer Calibration dialog | qupath-extension-ppm | `Docs_PPM_PolarizerCalibration.png` |
| PPM Birefringence Optimization dialog | qupath-extension-ppm | `Docs_PPM_BirefringenceOptimization.png` |
| PPM Reference Slide / Sunburst calibration | qupath-extension-ppm | `Docs_PPM_ReferenceSlide.png` |
| PPM Polarity Plot | qupath-extension-ppm | `Docs_PPM_PolarityPlot.png` |

## How to Use This Registry

1. When you modify a Java UI file listed above, check if the corresponding screenshot needs updating
2. Re-capture the screenshot on the Windows workstation with representative data
3. Update the "Last Verified" date and set Status to "OK"
4. Commit the updated image alongside the code change

## CI Hook -- `tools/check-doc-images.sh`

`tools/check-doc-images.sh` parses this registry and, for each screenshot, diffs
its source class from the screenshot's commit to `HEAD`. It only flags a
screenshot when the added/removed lines touch **UI-construction code** (new
controls, layout additions, visible text -- `new Button`, `ButtonType`,
`getChildren().add`, `setText`, `setTitle`, ...). Internal wiring, logging,
refactors, and comment edits do not trigger it, so a single sweep commit across
many UI files no longer produces a wall of false positives.

```bash
tools/check-doc-images.sh            # human-readable report
tools/check-doc-images.sh --ci       # exit 1 if any real UI change (CI gate)
tools/check-doc-images.sh --verbose  # also list suppressed / unchanged images
```

### Dismissing a change without re-capturing

When a source has a real UI change you do **not** want to re-capture (e.g. a new
footer button that won't fit the existing figure), acknowledge it instead:

```bash
tools/check-doc-images.sh --ack Docs_ExistingImage_ConsolidatedDialog.png \
    --note "Save MDA... footer button; figure already full"
```

This pins the current commit in `IMAGE_ACKS.tsv` (git-tracked, commit it with
your change). The screenshot stops flagging until a **new** UI change lands on
top of the acked commit -- so you stay protected against future drift while
silencing the one change you accepted. Re-capturing the image (a newer image
commit) supersedes the ack automatically.
