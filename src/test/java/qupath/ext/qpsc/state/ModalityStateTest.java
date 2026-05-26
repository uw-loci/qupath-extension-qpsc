package qupath.ext.qpsc.state;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ModalityState}. Uses the package-private DI constructor
 * with fake suppliers so the test does not require a loaded QuPath / config
 * context.
 */
class ModalityStateTest {

    private static Set<String> setOf(String... values) {
        Set<String> s = new LinkedHashSet<>();
        for (String v : values) s.add(v);
        return s;
    }

    @Test
    void bootstrapsFromInitialValueSource() {
        ModalityState s = new ModalityState(
                () -> "Brightfield",
                v -> {
                    /* no-op */
                },
                () -> setOf("Brightfield", "Fluorescence"));
        assertEquals("Brightfield", s.getModality());
    }

    @Test
    void setModalityFiresListenerOnChange() {
        ModalityState s = new ModalityState(() -> "Brightfield", v -> {}, () -> setOf("Brightfield", "Fluorescence"));
        AtomicInteger fires = new AtomicInteger(0);
        AtomicReference<String> last = new AtomicReference<>();
        s.modalityProperty().addListener((obs, oldV, newV) -> {
            fires.incrementAndGet();
            last.set(newV);
        });
        s.setModality("Fluorescence");
        assertEquals(1, fires.get());
        assertEquals("Fluorescence", last.get());
    }

    @Test
    void setModalityIsIdempotentOnEqualValue() {
        ModalityState s = new ModalityState(() -> "Brightfield", v -> {}, () -> setOf("Brightfield"));
        AtomicInteger fires = new AtomicInteger(0);
        s.modalityProperty().addListener((obs, oldV, newV) -> fires.incrementAndGet());
        s.setModality("Brightfield"); // identity -- must not fire
        s.setModality("Brightfield");
        assertEquals(0, fires.get());
    }

    @Test
    void persistsEveryEffectiveChange() {
        AtomicReference<String> persisted = new AtomicReference<>("Brightfield");
        ModalityState s = new ModalityState(persisted::get, persisted::set, () -> setOf("Brightfield", "Fluorescence"));
        s.setModality("Fluorescence");
        assertEquals("Fluorescence", persisted.get());
        s.setModality("Fluorescence"); // identity -- persistence sink not called again
        assertEquals("Fluorescence", persisted.get());
        s.setModality("Brightfield");
        assertEquals("Brightfield", persisted.get());
    }

    @Test
    void persistenceFailureDoesNotBreakInMemoryPropagation() {
        ModalityState s = new ModalityState(
                () -> "Brightfield",
                v -> {
                    throw new RuntimeException("disk full");
                },
                () -> setOf("Brightfield", "Fluorescence"));
        AtomicInteger fires = new AtomicInteger(0);
        s.modalityProperty().addListener((obs, oldV, newV) -> fires.incrementAndGet());
        // Should NOT throw -- persistence failure is logged + swallowed.
        s.setModality("Fluorescence");
        assertEquals(1, fires.get());
        // The in-memory value still reflects the change.
        assertEquals("Fluorescence", s.modalityProperty().get());
    }

    @Test
    void getModalityClampsWhenPersistedValueIsInvalidForActiveConfig() {
        AtomicReference<String> persisted = new AtomicReference<>("PPM"); // not in scope
        ModalityState s = new ModalityState(persisted::get, persisted::set, () -> setOf("Brightfield", "Fluorescence"));
        // Read clamps + persists.
        assertEquals("Brightfield", s.getModality());
        assertEquals("Brightfield", persisted.get());
        // Subsequent reads are stable, listeners do not re-fire.
        AtomicInteger fires = new AtomicInteger(0);
        s.modalityProperty().addListener((obs, oldV, newV) -> fires.incrementAndGet());
        assertEquals("Brightfield", s.getModality());
        assertEquals(0, fires.get());
    }

    @Test
    void getModalityReturnsCurrentWhenValidityIsUnknown() {
        // Empty validity (config not loaded) -- do NOT clamp; return persisted as-is.
        AtomicReference<String> persisted = new AtomicReference<>("Anything");
        ModalityState s = new ModalityState(persisted::get, persisted::set, Set::of);
        assertEquals("Anything", s.getModality());
        assertEquals("Anything", persisted.get(), "no clamp = no persistence write");
    }

    @Test
    void getValidModalitiesReflectsProvider() {
        Set<String> live = setOf("Brightfield");
        ModalityState s = new ModalityState(() -> "Brightfield", v -> {}, () -> live);
        assertEquals(setOf("Brightfield"), s.getValidModalities());
    }

    @Test
    void allowsNullModalityWithoutCrashing() {
        // PersistentPreferences can return null/empty on a fresh install.
        ModalityState s = new ModalityState(() -> null, v -> {}, Set::of);
        assertNull(s.modalityProperty().get());
        assertNull(s.getModality()); // no validity, no clamp, returns null
    }
}
