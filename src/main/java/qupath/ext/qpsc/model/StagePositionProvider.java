package qupath.ext.qpsc.model;

import java.io.IOException;

/**
 * Interface for querying stage position from the microscope hardware.
 * Decouples position polling from the controller layer.
 */
public interface StagePositionProvider {

    /** Returns true if the provider is connected and can return positions. */
    boolean isConnected();

    /** Returns current XY stage position as [x, y] in microns. */
    double[] getStagePositionXY() throws IOException;

    /** Returns current Z stage position in microns. */
    double getStagePositionZ() throws IOException;

    /** Returns current R (rotation) stage position in degrees. */
    double getStagePositionR() throws IOException;
}
