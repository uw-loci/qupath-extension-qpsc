# Documentation Image Registry

This file tracks the relationship between documentation screenshots and the Java UI classes that produce them. When a tracked Java file is modified, the corresponding screenshot may need to be re-captured.

**Maintenance:** Update this registry when adding new screenshots or when UI classes are renamed/moved.

**Annotation callouts:** Screenshots with numbered brackets/circles (baked on by `tools/annotate_screenshots.py`) have their intended targets documented in [`ANNOTATION_REFERENCE.md`](ANNOTATION_REFERENCE.md) -- read it before re-capturing an annotated dialog.

## Image-to-Source Mapping

| Screenshot | Java Source File(s) | Last Verified | Status |
|------------|-------------------|---------------|--------|
| `Docs_AcquisitionWizard.png` | `ui/AcquisitionWizardDialog.java` | 2026-05-27 | OK |
| `Docs_AcquisitionWorkflowProgress.png` | `ui/DualProgressDialog.java` | 2026-05-27 | OK |
| `Docs_AnnotationAcquisition.png` | `ui/AnnotationAcquisitionDialog.java` | 2026-07-23 | OK -- re-mapped from the broad ExistingImageAcquisitionController to the actual class selection dialog; the multi-tile refinement radio lives in the consolidated dialog, not here, so this no longer false-flags |
| `Docs_AutofocusConfigurationEditor.png` | `controller/AutofocusEditorWorkflow.java` | 2026-05-27 | OK |
| `Docs_AutofocusParameterBenchmark.png` | `ui/AutofocusBenchmarkDialog.java` | -- | CHECK |
| `Docs_BackgroundCollection.png` | `ui/BackgroundCollectionController.java` | 2026-05-27 | OK |
| `Docs_BoundedAcquisition.png` | `ui/UnifiedAcquisitionController.java` | 2026-05-27 | OK |
| `Docs_BoundedAcquisition_TileGrid.png` | Lab-meeting slide deck (workflow figure; not a single UI class) | 2026-06-01 | OK |
| `Docs_CameraControl.png` | `ui/CameraControlController.java` | 2026-05-27 | OK |
| `Docs_CommunicationSettings.png` | `ui/ServerConnectionController.java` | 2026-05-27 | OK |
| `Docs_CommunicationSettings_Alerts.png` | `ui/ServerConnectionController.java` | 2026-05-27 | OK |
| `Docs_CrossInstrumentWorkflow.png` | Lab-meeting slide deck (workflow figure; not a single UI class) | 2026-06-01 | OK |
| `Docs_ExistingImage_ConsolidatedDialog.png` | `ui/ExistingImageAcquisitionController.java` | 2026-05-27 | OK |
| `Docs_ExistingImage_FluorescenceProject.png` | Lab-meeting slide deck (QuPath project view; not a single UI class) | 2026-06-01 | OK |
| `Docs_LiveViewer.png` | `ui/liveviewer/LiveViewerWindow.java` | 2026-05-27 | OK |
| `Docs_LiveViewer_Navigate.png` | `ui/liveviewer/StageControlPanel.java` | -- | CHECK -- renamed from `Docs_LiveViewer_Position.png` (tab renamed Position -> Navigate 2026-07-22); needs re-capture |
| `Docs_LiveViewer_SavedPoints.png` | `ui/liveviewer/StageControlPanel.java` | 2026-07-22 | OK -- drift check false-flagged it on 2026-07-22 (shared source with Navigate); no visible change, no re-capture needed |
| `Docs_LiveViewer_Camera.png` | `ui/liveviewer/LiveViewerWindow.java` (camera tab) | -- | CHECK -- previously untracked |
| `Docs_MicroscopeAlignment_SelectSourceMicroscope.png` | `ui/MicroscopeSelectionDialog.java` | -- | CHECK |
| `Docs_MicroscopeAlignment_RefineAlignment.png` | `controller/workflow/SingleTileRefinement.java` (3-point / single-tile refinement dialog) | -- | CHECK -- SIFT refinement dialogs changed substantially; verify or re-capture |
| `Docs_MultiTileRefinement.png` | `controller/workflow/MultiTileRefinement.java` (numbered multi-tile SIFT panel) | -- | PLACEHOLDER -- new dialog, needs first real capture on Windows |
| `Docs_SiftCapturePane.png` | `controller/workflow/SiftCapturePane.java` / `ui/SiftAutoAlignHelper.java` | -- | PLACEHOLDER -- Auto-Align (SIFT) capture pane, needs first real capture on Windows |
| `Docs_StageMap.png` | `ui/stagemap/StageMapWindow.java`, `ui/stagemap/StageMapCanvas.java` | -- | CHECK -- new "Calibrate..." button since 2026-05-27 |
| `Docs_DishCoverslipCalibration.png` | Diagram / `ui/stagemap/StageMapWindow.java` insert calibration | -- | CHECK -- previously untracked |
| `Docs_PropagationManager.png` | `controller/ForwardPropagationWorkflow.java` | -- | CHECK -- previously untracked |
| `Docs_NoiseCharacterization.png` | `ui/NoiseCharacterizationDialog.java` | -- | CHECK -- previously untracked |
| `Docs_ZStackTimelapse.png` | `controller/StackTimeLapseWorkflow.java` | -- | CHECK -- previously untracked |
| `Docs_SetupWizard_Welcome.png` | `ui/setupwizard/SetupWizardDialog.java` | -- | CHECK -- previously untracked |
| `Docs_WBComparisonTest.png` | `ui/WBComparisonDialog.java` | -- | CHECK -- previously untracked |
| `Docs_SystemArchitecture.png` | Lab-meeting slide deck (architecture diagram; not a UI class) | 2026-06-01 | OK |
| `Docs_WorkflowOverview_Desktop.png` | Lab-meeting slide deck (full-desktop composite; multiple UI classes) | 2026-06-01 | OK |
| `Docs_WhiteBalanceCalibration.png` | `ui/WhiteBalanceDialog.java` | 2026-05-27 | OK |
| `Docs_mainmenu.png` | `SetupScope.java` | -- | CHECK -- menu changes since 2026-05-27 |
| `Docs_ppmmenu.png` | `SetupScope.java` (PPM modality submenu) | -- | CHECK |
| `Docs_MultiSlide_Assignment.png` | `ui/MultiSlideAssignmentDialog.java` | -- | PLACEHOLDER -- needs first real capture on Windows |
| `Docs_MultiSlide_BatchPanel.png` | `controller/MultiSlideExistingImageWorkflow.java` | -- | PLACEHOLDER -- needs first real capture on Windows |

## Missing Screenshots (UI exists but no image)

| UI Component | Java Source | Suggested Filename |
|-------------|------------|-------------------|
| Hardware Error Recovery Dialog | `controller/workflow/AcquisitionManager.java` | `Docs_HardwareErrorDialog.png` |
| Alignment Quality Summary | `controller/MicroscopeAlignmentWorkflow.java` | `Docs_AlignmentQualitySummary.png` |
| Saturation Summary Dialog | `ui/SaturationSummaryDialog.java` | `Docs_SaturationSummary.png` |
| Autofocus Validation Result | `controller/AutofocusEditorWorkflow.java` | `Docs_AutofocusValidation.png` |
| Setup Wizard | `ui/setupwizard/SetupWizardDialog.java` | `Docs_SetupWizard.png` |
| Alignment Refinement with SIFT | `controller/workflow/SingleTileRefinement.java` | `Docs_AlignmentRefinementSIFT.png` |
| Propagation Manager | `controller/ForwardPropagationWorkflow.java` | `Docs_PropagationManager.png` |
| Z-Stack / Time-Lapse Dialog | `controller/StackTimeLapseWorkflow.java` | `Docs_ZStackTimeLapse.png` |
| PPM modality options (angles) in acquisition dialog | `modality/ppm/*` UI section | `Docs_PPM_ModalityOptions.png` |
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
