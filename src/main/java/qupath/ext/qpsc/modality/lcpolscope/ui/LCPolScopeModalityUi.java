package qupath.ext.qpsc.modality.lcpolscope.ui;

import java.util.List;
import java.util.Optional;
import qupath.ext.qpsc.modality.LiveCameraHost;
import qupath.ext.qpsc.modality.ModalityHandler;
import qupath.ext.qpsc.modality.ModalityMenuItem;
import qupath.ext.qpsc.modality.ModalityUi;
import qupath.ext.qpsc.modality.lcpolscope.workflow.LCCalibrationWorkflow;
import qupath.ext.qpsc.modality.widefield.ui.WidefieldChannelBoundingBoxUI;

/**
 * The LC-PolScope's panels, Live Viewer controls, per-slide reminder and menu items; the
 * acquisition logic is {@code LCPolScopeModalityHandler}.
 */
public class LCPolScopeModalityUi implements ModalityUi {

    @Override
    public Optional<ModalityHandler.BoundingBoxUI> createBoundingBoxUI(String modalityName) {
        // Reuse the generic channel picker rather than forking a near-identical dialog --
        // the LC states are channels as far as the UI is concerned.
        WidefieldChannelBoundingBoxUI ui = new WidefieldChannelBoundingBoxUI(modalityName);
        if (!ui.hasChannels()) {
            return Optional.empty();
        }
        // All five states, one exposure: the picker offers no per-state exposures, include boxes
        // or split, so no notice asking for equal exposures is needed.
        ui.useEqualExposureStates("LC-PolScope states");
        // Calibration, background, camera and adapter status before the stage moves; each used
        // to appear only as a log line at the first tile.
        // Each panel goes on top, so the readiness box, added last, is read first.
        ui.addModalityPanel(new LCColormapPane());
        ui.addModalityPanel(new LCPolScopeReadinessPane());
        ui.addModalityNotice(
                "Orientation is axial: stitching and downsampling need care",
                "This run also produces a slow-axis orientation map, where 0 and 180 degrees mean the "
                        + "same thing. Averaging such values is invalid -- the mean of 179 and 1 is 90 "
                        + "degrees, perpendicular to the truth and entirely plausible-looking. Seam "
                        + "blending and pyramid downsampling must therefore go through sin(2t)/cos(2t), "
                        + "and mirroring the image negates the angle. The written tiles declare this in "
                        + "their OME metadata; any external tool you use on them must honor it.");
        return Optional.of(ui);
    }

    /**
     * LC-PolScope calibration, surfaced only when the microscope configuration
     * declares this modality.
     *
     * <p>Calibration drives hardware and needs a live connection, so it lives here rather
     * than in an analysis extension -- the same division that puts PPM's polarizer and
     * reference-slide calibrations in this repository while its hue-range and polarity-plot
     * tools live in qupath-extension-ppm.
     */
    @Override
    public List<ModalityMenuItem> getMenuContributions() {
        return List.of(
                new ModalityMenuItem(
                        "lcSetup",
                        "LC-PolScope Setup...",
                        "Get the LC-PolScope ready in order: camera settings, calibration (which measures the "
                                + "black level and sets the exposure), then the background. Shows what is still "
                                + "missing and rechecks as you go.",
                        LCPolScopeSetupWindow::show),
                new ModalityMenuItem(
                        "lcCalibration",
                        "Calibrate Liquid Crystals...",
                        "Find the extinction point and the swing states, and write the palette the "
                                + "acquisition will use. Run on a clear, specimen-free field: the calibration "
                                + "measures how dark extinction can be made, so anything birefringent in the "
                                + "field makes the result worse. Reports an extinction ratio as its quality "
                                + "figure -- 100 or above is good; this instrument has reached 267.",
                        () -> LCCalibrationWorkflow.run()));
    }

    @Override
    public boolean buildLiveCameraControls(String modality, LiveCameraHost host) {
        new LCLiveCameraControls(host).build(modality);
        return true;
    }

    @Override
    public boolean confirmBeforeAcquiring(String modality) {
        return LCPolScopeSlideReminder.confirmBeforeAcquiring(modality);
    }

    @Override
    public boolean confirmBatch(int slides) {
        return LCPolScopeSlideReminder.confirmBatch(slides);
    }
}
