package qupath.ext.qpsc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.QPScopeChecks.PixelSizeAgreement;

/**
 * Three bands for config vs MicroManager pixel size: under 0.5% agrees, 0.5-5% has drifted
 * (warn and acquire), over 5% is the wrong objective (cancel). The middle band is new: a
 * 0.1-1% calibration drift used to pass in silence and be absorbed by tile registration.
 */
class PixelSizeAgreementTest {

    private static final double PPM_20X = 0.1729; // um/px, the shape of a real config value

    @Test
    void identicalAndTinyDifferencesAgree() {
        assertEquals(PixelSizeAgreement.AGREES, PixelSizeAgreement.of(PPM_20X, PPM_20X));
        assertEquals(PixelSizeAgreement.AGREES, PixelSizeAgreement.of(PPM_20X, PPM_20X * 1.004));
        assertEquals(PixelSizeAgreement.AGREES, PixelSizeAgreement.of(PPM_20X, PPM_20X * 0.996));
    }

    @Test
    void halfAPercentToFivePercentIsDrift() {
        assertEquals(PixelSizeAgreement.DRIFTED, PixelSizeAgreement.of(PPM_20X, PPM_20X * 1.006));
        assertEquals(PixelSizeAgreement.DRIFTED, PixelSizeAgreement.of(PPM_20X, PPM_20X * 1.01));
        assertEquals(PixelSizeAgreement.DRIFTED, PixelSizeAgreement.of(PPM_20X, PPM_20X * 0.96));
        assertEquals(PixelSizeAgreement.DRIFTED, PixelSizeAgreement.of(PPM_20X, PPM_20X * 1.049));
    }

    @Test
    void beyondFivePercentIsTheWrongObjective() {
        assertEquals(PixelSizeAgreement.MISMATCHED, PixelSizeAgreement.of(PPM_20X, PPM_20X * 1.06));
        assertEquals(PixelSizeAgreement.MISMATCHED, PixelSizeAgreement.of(PPM_20X, PPM_20X * 2.0)); // 10x in path
        assertEquals(PixelSizeAgreement.MISMATCHED, PixelSizeAgreement.of(PPM_20X, PPM_20X * 0.5)); // 40x in path
    }
}
