package qupath.ext.qpsc.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import qupath.ext.qpsc.utilities.StitchOutputPlanner.Inputs;
import qupath.ext.qpsc.utilities.StitchOutputPlanner.Kind;
import qupath.ext.qpsc.utilities.StitchOutputPlanner.ReservedOutput;

/** The planner predicts the entries a stitch job produces, with the import sites' names. */
class StitchOutputPlannerTest {

    /** PPM's suffix rule: a tick becomes its own string with the sign kept (7.0 -> "7.0"). */
    private static final Function<String, String> PPM_SUFFIX = t -> {
        try {
            double v = Double.parseDouble(t);
            return v == 0 ? "0.0" : String.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    };

    private static Inputs ppm(List<String> angles, List<String> derived, Set<String> taken) {
        return new Inputs(
                "L004",
                "ppm_20x_3",
                "Tissue_1",
                ".ome.tif",
                angles,
                PPM_SUFFIX,
                derived,
                List.of(),
                Set.of(),
                null,
                taken);
    }

    @Test
    void ppmFiveAnglesPlusBirefAndSum() {
        List<ReservedOutput> plan = StitchOutputPlanner.plan(
                ppm(List.of("-7.0", "-3.5", "0.0", "3.5", "7.0"), List.of("7.0.biref", "7.0.sum"), Set.of()));
        assertEquals(7, plan.size());
        assertEquals(
                List.of("-7.0", "-3.5", "0.0", "3.5", "7.0", "7.0.biref", "7.0.sum"),
                plan.stream().map(ReservedOutput::key).toList());
        assertEquals(Kind.ANGLE, plan.get(0).kind());
        assertEquals(Kind.DERIVED, plan.get(5).kind());
        assertEquals("7.0_biref", plan.get(5).angleSuffix());
        assertEquals("7.0_sum", plan.get(6).angleSuffix());
        String expected = ImageNameGenerator.generateImageName("L004", 3, "ppm", "20x", "Tissue_1", "7.0", ".ome.tif");
        assertEquals(expected, plan.get(4).fileName());
        assertTrue(plan.stream().map(ReservedOutput::fileName).distinct().count() == 7, "names are distinct");
    }

    @Test
    void lcPolScopeStatesMergeAndDerivedOutputsAreTheirOwnEntries() {
        Inputs in = new Inputs(
                "L004",
                "lcpolscope_20x_1",
                "Tissue_1",
                ".ome.tif",
                List.of(),
                PPM_SUFFIX,
                List.of("retardance", "orientation", "orientation_color"),
                List.of("state0", "state1", "state2", "state3", "state4"),
                Set.of(),
                "lcps",
                Set.of());
        List<ReservedOutput> plan = StitchOutputPlanner.plan(in);
        assertEquals(4, plan.size());
        assertEquals(StitchOutputPlanner.MERGED_KEY, plan.get(0).key());
        assertEquals(Kind.MERGED, plan.get(0).kind());
        assertEquals(
                ImageNameGenerator.generateImageName("L004", 1, "lcps", "20x", "Tissue_1", null, ".ome.tif"),
                plan.get(0).fileName());
        assertEquals("retardance", plan.get(1).key());
        assertEquals("retardance", plan.get(1).angleSuffix(), "a non-numeric derived folder keeps its name");
        assertEquals(Kind.DERIVED, plan.get(3).kind());
    }

    @Test
    void ifMergedPlusTwoSplitChannels() {
        Inputs in = new Inputs(
                "S1",
                "fluorescence_10x_2",
                "Region_A",
                ".ome.tif",
                List.of(),
                PPM_SUFFIX,
                List.of(),
                List.of("DAPI", "FITC", "TRITC", "Cy5"),
                Set.of("DAPI", "Cy5"),
                "fl",
                Set.of());
        List<ReservedOutput> plan = StitchOutputPlanner.plan(in);
        assertEquals(
                List.of("DAPI", "Cy5", StitchOutputPlanner.MERGED_KEY),
                plan.stream().map(ReservedOutput::key).toList());
        assertEquals(Kind.CHANNEL, plan.get(0).kind());
        assertEquals("DAPI", plan.get(0).angleSuffix());
    }

    @Test
    void aSingleLeftoverChannelIsItsOwnEntryNotAMerge() {
        Inputs in = new Inputs(
                "S1",
                "fluorescence_10x_2",
                "Region_A",
                ".ome.tif",
                List.of(),
                PPM_SUFFIX,
                List.of(),
                List.of("DAPI", "FITC"),
                Set.of("DAPI"),
                "fl",
                Set.of());
        List<ReservedOutput> plan = StitchOutputPlanner.plan(in);
        assertEquals(
                List.of("DAPI", "FITC"), plan.stream().map(ReservedOutput::key).toList());
        assertTrue(plan.stream().allMatch(r -> r.kind() == Kind.CHANNEL));
    }

    @Test
    void brightfieldSingleStitchIsKeyedByTheAnnotationWhenThereIsNoAngleFolder() {
        Inputs in = new Inputs(
                "S1",
                "bf_10x_1",
                "_temp_run/Region_A",
                ".ome.tif",
                List.of(),
                PPM_SUFFIX,
                List.of(),
                List.of(),
                Set.of(),
                null,
                Set.of());
        List<ReservedOutput> plan = StitchOutputPlanner.plan(in);
        assertEquals(1, plan.size());
        assertEquals(
                "_temp_run/Region_A", plan.get(0).key(), "the key is the raw annotation name the import site sees");
        assertEquals(
                ImageNameGenerator.generateImageName("S1", 1, "bf", "10x", "Region_A", null, ".ome.tif"),
                plan.get(0).fileName(),
                "the file name strips the temp prefix");
        assertEquals(Kind.SINGLE, plan.get(0).kind());
        // One angle folder: keyed by the tick.
        Inputs one = new Inputs(
                "S1",
                "ppm_10x_1",
                "Region_A",
                ".ome.tif",
                List.of("7.0"),
                PPM_SUFFIX,
                List.of(),
                List.of(),
                Set.of(),
                null,
                Set.of());
        assertEquals("7.0", StitchOutputPlanner.plan(one).get(0).key());
    }

    @Test
    void aTakenNameBumpsTheIndexForTheWholeJob() {
        String takenName = ImageNameGenerator.generateImageName("L004", 3, "ppm", "20x", "Tissue_1", "7.0", ".ome.tif");
        List<ReservedOutput> plan = StitchOutputPlanner.plan(ppm(List.of("-7.0", "7.0"), List.of(), Set.of(takenName)));
        assertEquals(2, plan.size());
        String bumped = ImageNameGenerator.generateImageName("L004", 4, "ppm", "20x", "Tissue_1", "-7.0", ".ome.tif");
        assertEquals(bumped, plan.get(0).fileName(), "every file of the job moves to the next index");
    }

    @Test
    void zarrOutputIsNotReserved() {
        Inputs in = new Inputs(
                "L004",
                "ppm_20x_3",
                "Tissue_1",
                ".ome.zarr",
                List.of("7.0"),
                PPM_SUFFIX,
                List.of(),
                List.of(),
                Set.of(),
                null,
                Set.of());
        assertTrue(StitchOutputPlanner.plan(in).isEmpty());
    }
}
