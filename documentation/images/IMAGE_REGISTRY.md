# Documentation Image Registry

This file tracks the relationship between documentation screenshots and the Java UI classes that produce them. When a tracked Java file is modified, the corresponding screenshot may need to be re-captured.

**Maintenance:** Update this registry when adding new screenshots or when UI classes are renamed/moved.

## Image-to-Source Mapping

| Screenshot | Java Source File(s) | Last Verified | Status |
|------------|-------------------|---------------|--------|
| `Docs_AcquisitionWizard.png` | `ui/AcquisitionWizardDialog.java` | 2026-05-27 | OK |
| `Docs_AcquisitionWorkflowProgress.png` | `ui/DualProgressDialog.java` | 2026-05-27 | OK |
| `Docs_AnnotationAcquisition.png` | `ui/ExistingImageAcquisitionController.java` | 2026-05-27 | OK |
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
| `Docs_LiveViewer_Position.png` | `ui/liveviewer/StageControlPanel.java` | 2026-05-27 | OK |
| `Docs_LiveViewer_SavedPoints.png` | `ui/liveviewer/StageControlPanel.java` | 2026-05-27 | OK |
| `Docs_MicroscopeAlignment_SelectSourceMicroscope.png` | `ui/MicroscopeSelectionDialog.java` | -- | CHECK |
| `Docs_StageMap.png` | `ui/stagemap/StageMapWindow.java`, `ui/stagemap/StageMapCanvas.java` | 2026-05-27 | OK |
| `Docs_SystemArchitecture.png` | Lab-meeting slide deck (architecture diagram; not a UI class) | 2026-06-01 | OK |
| `Docs_WorkflowOverview_Desktop.png` | Lab-meeting slide deck (full-desktop composite; multiple UI classes) | 2026-06-01 | OK |
| `Docs_WhiteBalanceCalibration.png` | `ui/WhiteBalanceDialog.java` | 2026-05-27 | OK |
| `Docs_mainmenu.png` | `SetupScope.java` | 2026-05-27 | OK |
| `Docs_ppmmenu.png` | `SetupScope.java` (PPM modality submenu) | -- | CHECK |

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

## How to Use This Registry

1. When you modify a Java UI file listed above, check if the corresponding screenshot needs updating
2. Re-capture the screenshot on the Windows workstation with representative data
3. Update the "Last Verified" date and set Status to "OK"
4. Commit the updated image alongside the code change

## CI Hook (planned)

A pre-commit or CI check can compare the modification dates of tracked Java files against this registry to flag stale screenshots. See the `check-doc-images` script (TODO).
