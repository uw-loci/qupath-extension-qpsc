package qupath.ext.qpsc.service.microscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FxThreadSocketUseTest {

    @BeforeEach
    void reset() {
        FxThreadSocketUse.resetForTests();
    }

    @Test
    void offTheFxThreadNothingIsLogged() {
        assertFalse(FxThreadSocketUse.check("primary", () -> false));
    }

    @Test
    void eachCallSiteIsReportedOnce() {
        boolean first = false;
        boolean second = false;
        for (int i = 0; i < 2; i++) {
            boolean logged = FxThreadSocketUse.check("primary", () -> true);
            if (i == 0) {
                first = logged;
            } else {
                second = logged;
            }
        }
        assertTrue(first, "the first command from a site on the FX thread is reported");
        assertFalse(second, "the same site is not reported again");
        assertTrue(FxThreadSocketUse.check("auxiliary", () -> true), "the other socket counts as another site");
    }

    @Test
    void theCallSiteIsTheFirstFrameOutsideTheSocketClasses() {
        String site = FxThreadSocketUse.callSite().orElseThrow();
        assertEquals(
                FxThreadSocketUseTest.class.getName() + ".theCallSiteIsTheFirstFrameOutsideTheSocketClasses",
                site.substring(0, site.lastIndexOf(':')));
    }
}
