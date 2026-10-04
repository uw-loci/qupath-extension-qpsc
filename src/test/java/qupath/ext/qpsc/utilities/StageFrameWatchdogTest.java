package qupath.ext.qpsc.utilities;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.model.StagePositionProvider;

/**
 * Behaviour of the stage frame watchdog over a sequence of position readings.
 *
 * <p>Driven through the real {@link StagePositionManager} with a scripted position provider,
 * because the thing being tested is what the watchdog makes of a STREAM of samples: a single
 * sample outside a region is a point on the way somewhere, and only a run of them means the
 * stage is parked where the coordinates say it is not.
 *
 * <p>Note that the interesting case is a stage holding STILL at the wrong place. The position
 * manager fires change events, so a parked stage produces exactly one -- which is why the
 * watchdog samples the cached position on its own clock, and why these tests sample explicitly
 * rather than relying on events.
 */
class StageFrameWatchdogTest {

    /** A stage whose reported position the test sets directly. */
    private static final class ScriptedStage implements StagePositionProvider {
        double x = 0;
        double y = 0;

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public double[] getStagePositionXY() {
            return new double[] {x, y};
        }

        @Override
        public double getStagePositionZ() {
            return 0;
        }

        @Override
        public boolean hasRotationStage() {
            return false;
        }

        @Override
        public double getStagePositionR() {
            return 0;
        }
    }

    // Slot 4 of the PPM quad holder, region 33775_25384 from the 2026-10-02 run.
    private static final double MIN_X = -51994;
    private static final double MAX_X = -39751;
    private static final double MIN_Y = -26533;
    private static final double MAX_Y = -15468;

    private ScriptedStage stage;
    private StageFrameWatchdog watchdog;

    @BeforeEach
    void setUp() {
        stage = new ScriptedStage();
        StagePositionManager.getInstance().setPositionProvider(stage);
        watchdog = StageFrameWatchdog.getInstance();
        watchdog.resetForTesting();
        // The shipped grace period is 30 s, to cover the traverse into a region.
        watchdog.setGraceMillisForTesting(0);
    }

    @AfterEach
    void tearDown() {
        watchdog.resetForTesting();
    }

    /**
     * Puts the stage somewhere and lets the watchdog take one sample of it.
     *
     * <p>Two steps on purpose, mirroring production: the manager refreshes its cached position
     * from the hardware, and the watchdog samples that cache on its own clock. It does NOT react
     * to position events, because the manager fires those only when a position CHANGES -- a
     * re-zeroed stage parked at 0,0 emits one event and then nothing.
     */
    private void report(double x, double y) {
        stage.x = x;
        stage.y = y;
        StagePositionManager.getInstance().forceRefresh();
        watchdog.sample();
    }

    @Test
    void positionsInsideTheRegionAreNotSuspicious() {
        watchdog.arm(MIN_X, MIN_Y, MAX_X, MAX_Y, "33775_25384", null);
        // A raster across the region, the way an acquisition walks it.
        for (double x = MIN_X; x <= MAX_X; x += 2000) {
            report(x, MIN_Y + 500);
        }
        assertThat(watchdog.suspectReason()).isNull();
    }

    @Test
    void theAutofocusTissueSearchJustOutsideTheRegionIsTolerated() {
        watchdog.arm(MIN_X, MIN_Y, MAX_X, MAX_Y, "33775_25384", null);
        // Up to three one-FOV-diagonal steps from a corner, which is inside the margin.
        report(MAX_X + 450, MIN_Y - 450);
        report(MAX_X + 900, MIN_Y - 900);
        report(MAX_X + 1340, MIN_Y - 1340);
        assertThat(watchdog.suspectReason()).isNull();
    }

    @Test
    void oneReadingOutsideIsNotEnough() {
        watchdog.arm(MIN_X, MIN_Y, MAX_X, MAX_Y, "33775_25384", null);
        // Mid-traverse: the reading is a point on a path, not a place.
        report(0, 0);
        assertThat(watchdog.suspectReason()).isNull();
        report(-45000, -20000);
        assertThat(watchdog.suspectReason()).isNull();
    }

    @Test
    void aStageParkedOutsideTheRegionIsReported() {
        AtomicInteger callbacks = new AtomicInteger();
        watchdog.arm(MIN_X, MIN_Y, MAX_X, MAX_Y, "33775_25384", callbacks::incrementAndGet);
        // What a re-zeroed controller reports while slot 4 is being acquired.
        report(0, 0);
        report(0, 0);
        report(0, 0);

        String reason = watchdog.suspectReason();
        assertThat(reason).isNotNull();
        assertThat(reason).contains("33775_25384");
        assertThat(reason).contains("0,0 moved");
        // The operator has to be told what to do about it, not just what happened.
        assertThat(reason).contains("fiducial");
        assertThat(callbacks.get()).isEqualTo(1);
    }

    @Test
    void theCallbackFiresOnceNoMatterHowManyMoreReadingsArrive() {
        AtomicInteger callbacks = new AtomicInteger();
        watchdog.arm(MIN_X, MIN_Y, MAX_X, MAX_Y, "33775_25384", callbacks::incrementAndGet);
        for (int i = 0; i < 10; i++) {
            report(0, 0);
        }
        assertThat(callbacks.get()).isEqualTo(1);
    }

    @Test
    void returningInsideTheRegionResetsTheRun() {
        watchdog.arm(MIN_X, MIN_Y, MAX_X, MAX_Y, "33775_25384", null);
        // Two outside, then back in: a traverse, not a parked stage.
        report(0, 0);
        report(0, 0);
        report(MIN_X + 100, MIN_Y + 100);
        report(0, 0);
        report(0, 0);
        assertThat(watchdog.suspectReason()).isNull();
    }

    @Test
    void theSuspicionOutlivesDisarmingAndTheRegionThatFoundIt() {
        watchdog.arm(MIN_X, MIN_Y, MAX_X, MAX_Y, "33775_25384", null);
        report(0, 0);
        report(0, 0);
        report(0, 0);
        assertThat(watchdog.isSuspect()).isTrue();

        // Sticky across the end of the region: this is what lets the multi-slide driver
        // refuse to start the next slide, which is the whole point of catching it.
        watchdog.disarm();
        assertThat(watchdog.isSuspect()).isTrue();

        watchdog.arm(MIN_X, MIN_Y, MAX_X, MAX_Y, "30636_102874", null);
        assertThat(watchdog.isSuspect()).isTrue();
    }

    @Test
    void onlyAnExplicitClearForgetsIt() {
        watchdog.arm(MIN_X, MIN_Y, MAX_X, MAX_Y, "33775_25384", null);
        report(0, 0);
        report(0, 0);
        report(0, 0);
        assertThat(watchdog.isSuspect()).isTrue();

        watchdog.clearSuspect();
        assertThat(watchdog.isSuspect()).isFalse();
    }

    @Test
    void nothingIsWatchedWhileDisarmed() {
        report(0, 0);
        report(0, 0);
        report(0, 0);
        assertThat(watchdog.suspectReason()).isNull();
        assertThat(watchdog.isArmed()).isFalse();
    }

    @Test
    void anEnvelopeThatCannotBeComputedLeavesItDisarmed() {
        // Better unarmed than armed on a guess: a wrong envelope either cancels good runs
        // or watches nothing.
        watchdog.arm(Double.NaN, MIN_Y, MAX_X, MAX_Y, "33775_25384", null);
        assertThat(watchdog.isArmed()).isFalse();
        report(0, 0);
        report(0, 0);
        report(0, 0);
        assertThat(watchdog.suspectReason()).isNull();
    }

    @Test
    void boundsMayBeGivenInEitherCornerOrder() {
        // The stage transform can have negative scales, so the caller's "top left" is not
        // necessarily the minimum in stage coordinates.
        watchdog.arm(MAX_X, MAX_Y, MIN_X, MIN_Y, "33775_25384", null);
        for (double x = MIN_X; x <= MAX_X; x += 2000) {
            report(x, MIN_Y + 500);
        }
        assertThat(watchdog.suspectReason()).isNull();
    }

    @Test
    void theGracePeriodCoversTheTraverseIntoTheRegion() {
        watchdog.setGraceMillisForTesting(60_000);
        watchdog.arm(MIN_X, MIN_Y, MAX_X, MAX_Y, "33775_25384", null);
        // An 85 mm traverse across the holder reports many positions outside the region.
        for (int i = 0; i < 20; i++) {
            report(35661 - i * 4000, -3883);
        }
        assertThat(watchdog.suspectReason()).isNull();
    }
}
