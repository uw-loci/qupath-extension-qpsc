# Documentation Image Registry

This file tracks the relationship between documentation screenshots and the Java UI classes that produce them. When a tracked Java file is modified, the corresponding screenshot may need to be re-captured.

**Maintenance:** Update this registry when adding new screenshots or when UI classes are renamed/moved.

## Image-to-Source Mapping

| Screenshot | Java Source File(s) | Last Verified | Status |
|------------|-------------------|---------------|--------|
| `Docs_AcquisitionWizard.png` | `ui/AcquisitionWizardDialog.java` | 2026-03-25 | NEEDS UPDATE (AF validate button, disable AF checkbox added) |
| `Docs_AcquisitionWorkflowProgress.png` | `ui/DualProgressDialog.java` | 2026-03-25 | NEEDS UPDATE (separate acq/stitch timers) |
| `Docs_AnnotationAcquisition.png` | `ui/ExistingImageAcquisitionController.java` | -- | NEEDS UPDATE (WB mode moved to hardware section, Quick Start link) |
| `Docs_AutofocusConfigurationEditor.png` | `controller/AutofocusEditorWorkflow.java` | -- | NEEDS UPDATE (sweep drift check section, validate button) |
| `Docs_AutofocusParameterBenchmark.png` | `ui/AutofocusBenchmarkDialog.java` | -- | CHECK |
| `Docs_BackgroundCollection.png` | `ui/BackgroundCollectionController.java` | -- | NEEDS UPDATE (Advice button, stays open during acq) |
| `Docs_BoundedAcquisition.png` | `ui/UnifiedAcquisitionController.java` | -- | NEEDS UPDATE (WB mode in hardware section, Quick Start link, pixel size warning) |
| `Docs_CameraControl.png` | `ui/CameraControlController.java` | -- | CHECK |
| `Docs_CommunicationSettings.png` | `ui/ServerConnectionController.java` | -- | CHECK |
| `Docs_CommunicationSettings_Alerts.png` | `ui/ServerConnectionController.java` | -- | CHECK |
| `Docs_ExistingImage_ConsolidatedDialog.png` | `ui/ExistingImageAcquisitionController.java` | -- | NEEDS UPDATE (WB mode in hardware section, Quick Start link) |
| `Docs_LiveViewer.png` | `ui/liveviewer/LiveViewerWindow.java` | -- | NEEDS UPDATE (Camera Settings button, Show Tiles checkbox, tabs merged) |
| `Docs_LiveViewer_Position.png` | `ui/liveviewer/StageControlPanel.java` | -- | NEEDS UPDATE (Position tab removed, merged into Navigate) |
| `Docs_LiveViewer_SavedPoints.png` | `ui/liveviewer/StageControlPanel.java` | -- | CHECK |
| `Docs_MicroscopeAlignment_SelectSourceMicroscope.png` | `ui/MicroscopeSelectionDialog.java` | -- | CHECK |
| `Docs_StageMap.png` | `ui/stagemap/StageMapWindow.java`, `ui/stagemap/StageMapCanvas.java` | -- | NEEDS UPDATE (Apply Flips checkbox, auto-overlay) |
| `Docs_WhiteBalanceCalibration.png` | `ui/WhiteBalanceDialog.java` | -- | NEEDS UPDATE (color-coded mode labels, defocus tip, doc links) |
| `Docs_mainmenu.png` | `SetupScope.java` | -- | NEEDS UPDATE (menu color dot) |
| `Docs_ppmmenu.png` | `SetupScope.java` (PPM modality submenu) | -- | CHECK |

## Missing Screenshots (UI exists but no image)

| UI Component | Java Source | Suggested Filename |
|-------------|------------|-------------------|
| Hardware Error Recovery Dialog | `controller/workflow/AcquisitionManager.java` | `Docs_HardwareErrorDialog.png` |
| Alignment Quality Summary | `controller/MicroscopeAlignmentWorkflow.java` | `Docs_AlignmentQualitySummary.png` |
| Saturation Summary Dialog | `ui/SaturationSummaryDialog.java` | `Docs_SaturationSummary.png` |
| Autofocus Validation Result | `controller/AutofocusEditorWorkflow.java` | `Docs_AutofocusValidation.png` |
| Setup Wizard | `ui/setupwizard/SetupWizardDialog.java` | `Docs_SetupWizard.png` |

## How to Use This Registry

1. When you modify a Java UI file listed above, check if the corresponding screenshot needs updating
2. Re-capture the screenshot on the Windows workstation with representative data
3. Update the "Last Verified" date and set Status to "OK"
4. Commit the updated image alongside the code change

## CI Hook (planned)

A pre-commit or CI check can compare the modification dates of tracked Java files against this registry to flag stale screenshots. See the `check-doc-images` script (TODO).
