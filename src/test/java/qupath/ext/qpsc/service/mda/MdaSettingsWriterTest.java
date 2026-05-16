package qupath.ext.qpsc.service.mda;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.qpsc.modality.AngleExposure;
import qupath.ext.qpsc.model.SampleSetupResult;

class MdaSettingsWriterTest {

    private static final int RED_ARGB = 0xFFFF0000;
    private static final int GREEN_ARGB = 0xFF00FF00;
    private static final int BLUE_ARGB = 0xFF0000FF;

    @Test
    void widefieldGolden_writesExpectedSequenceAndPositionsFiles(@TempDir Path tmp) throws Exception {
        Path regionDir = tmp.resolve("region01");
        Files.createDirectories(regionDir);

        SampleSetupResult sample =
                new SampleSetupResult("Slide_A", new File("/tmp/projects"), "bf_if_20x", "Olympus 20X", "JAI");

        List<ResolvedChannel> channels = List.of(
                new ResolvedChannel("DAPI", "DAPI (385 nm)", "Channel", "DAPI", 50.0, BLUE_ARGB),
                new ResolvedChannel("FITC", "FITC (488 nm)", "Channel", "FITC", 100.0, GREEN_ARGB),
                new ResolvedChannel("TRITC", "TRITC (555 nm)", "Channel", "TRITC", 200.0, RED_ARGB));

        ZStackSpec z = new ZStackSpec(-2.0, 2.0, 1.0);
        TimeLapseSpec t = null;
        List<TileStagePos> tiles = List.of(
                new TileStagePos("Pos00", 100.0, 200.0, 50.0),
                new TileStagePos("Pos01", 100.0, 400.0, 50.5),
                new TileStagePos("Pos02", 300.0, 200.0, 51.0),
                new TileStagePos("Pos03", 300.0, 400.0, 51.5));

        MdaWriteRequest req = new MdaWriteRequest(
                regionDir,
                "region01",
                "bf_if_20x",
                sample,
                channels,
                List.of(),
                z,
                t,
                tiles,
                new MmStageDevices("XYStage", "ZStage"),
                Map.of());

        MdaWriteResult result = MdaSettingsWriter.write(req);

        assertThat(result.settingsFile()).exists().hasFileName("MDA_region01.txt");
        assertThat(result.positionsFile()).exists().hasFileName("MDA_region01.pos");
        assertThat(result.notesFile()).exists().hasFileName("MDA_NOTES.txt");

        JsonObject settings = parseJsonObject(result.settingsFile());
        assertThat(settings.get("version").getAsInt()).isEqualTo(1);
        assertThat(settings.get("numFrames").getAsInt()).isEqualTo(1);
        assertThat(settings.get("useFrames").getAsBoolean()).isFalse();
        assertThat(settings.get("useChannels").getAsBoolean()).isTrue();
        assertThat(settings.get("useSlices").getAsBoolean()).isTrue();
        assertThat(settings.get("usePositionList").getAsBoolean()).isTrue();
        assertThat(settings.get("useAutofocus").getAsBoolean()).isFalse();
        assertThat(settings.get("save").getAsBoolean()).isTrue();
        assertThat(settings.get("saveMode").getAsString()).isEqualTo("MULTIPAGE_TIFF");
        assertThat(settings.get("channelGroup").getAsString()).isEqualTo("Channel");
        assertThat(settings.get("sliceZStepUm").getAsDouble()).isEqualTo(1.0);
        assertThat(settings.get("sliceZBottomUm").getAsDouble()).isEqualTo(-2.0);
        assertThat(settings.get("sliceZTopUm").getAsDouble()).isEqualTo(2.0);

        JsonArray slices = settings.getAsJsonArray("slices");
        assertThat(slices.size()).isEqualTo(5);
        assertThat(slices.get(0).getAsDouble()).isEqualTo(-2.0);
        assertThat(slices.get(4).getAsDouble()).isEqualTo(2.0);

        JsonArray channelsJson = settings.getAsJsonArray("channels");
        assertThat(channelsJson.size()).isEqualTo(3);
        JsonObject firstCh = channelsJson.get(0).getAsJsonObject();
        assertThat(firstCh.get("channelGroup").getAsString()).isEqualTo("Channel");
        assertThat(firstCh.get("config").getAsString()).isEqualTo("DAPI");
        assertThat(firstCh.get("exposure").getAsDouble()).isEqualTo(50.0);
        assertThat(firstCh.get("doZStack").getAsBoolean()).isTrue();
        assertThat(firstCh.get("useChannel").getAsBoolean()).isTrue();
        assertThat(firstCh.getAsJsonObject("color").get("value").getAsInt()).isEqualTo(BLUE_ARGB);

        JsonObject positions = parseJsonObject(result.positionsFile());
        JsonArray stagePositions = positions.getAsJsonArray("STAGE_POSITIONS");
        assertThat(stagePositions.size()).isEqualTo(4);
        JsonObject firstPos = stagePositions.get(0).getAsJsonObject();
        assertThat(firstPos.get("label").getAsString()).isEqualTo("Pos00");
        assertThat(firstPos.get("defaultXYStage").getAsString()).isEqualTo("XYStage");
        assertThat(firstPos.get("defaultZStage").getAsString()).isEqualTo("ZStage");
        JsonArray devicePositions = firstPos.getAsJsonArray("devicePositions");
        assertThat(devicePositions.size()).isEqualTo(2);

        JsonObject xy = devicePositions.get(0).getAsJsonObject();
        assertThat(xy.get("stageName").getAsString()).isEqualTo("XYStage");
        assertThat(xy.get("numAxes").getAsInt()).isEqualTo(2);
        assertThat(xy.get("x").getAsDouble()).isEqualTo(100.0);
        assertThat(xy.get("y").getAsDouble()).isEqualTo(200.0);

        JsonObject zDev = devicePositions.get(1).getAsJsonObject();
        assertThat(zDev.get("stageName").getAsString()).isEqualTo("ZStage");
        assertThat(zDev.get("numAxes").getAsInt()).isEqualTo(1);
        assertThat(zDev.get("z").getAsDouble()).isEqualTo(50.0);

        String notes = Files.readString(result.notesFile(), StandardCharsets.UTF_8);
        assertThat(notes).contains("Multi-Dimensional Acquisition");
        assertThat(notes).contains("useAutofocus");

        assertNoTmpFiles(regionDir);
    }

    @Test
    void ppmGolden_writesPositionsOnlyMda(@TempDir Path tmp) throws Exception {
        Path regionDir = tmp.resolve("region_ppm");
        Files.createDirectories(regionDir);

        SampleSetupResult sample =
                new SampleSetupResult("Slide_PPM", new File("/tmp/projects"), "ppm_20x_1", "Olympus 20X POL", "JAI");

        List<AngleExposure> angles = List.of(
                new AngleExposure(-7.0, 500.0),
                new AngleExposure(-3.0, 500.0),
                new AngleExposure(0.0, 800.5),
                new AngleExposure(3.0, 500.0),
                new AngleExposure(7.0, 500.0));

        List<TileStagePos> tiles = List.of(
                new TileStagePos("Pos00", 0.0, 0.0, 10.0),
                new TileStagePos("Pos01", 200.0, 0.0, 10.0),
                new TileStagePos("Pos02", 400.0, 0.0, 10.0));

        MdaWriteRequest req = new MdaWriteRequest(
                regionDir,
                "region_ppm",
                "ppm_20x_1",
                sample,
                List.of(),
                angles,
                null,
                null,
                tiles,
                new MmStageDevices("XYStage", "ZStage"),
                Map.of());

        MdaWriteResult result = MdaSettingsWriter.write(req);

        JsonObject settings = parseJsonObject(result.settingsFile());
        assertThat(settings.get("useChannels").getAsBoolean()).isFalse();
        assertThat(settings.getAsJsonArray("channels").size()).isZero();
        assertThat(settings.get("channelGroup").getAsString()).isEmpty();
        assertThat(settings.get("usePositionList").getAsBoolean()).isTrue();

        JsonObject positions = parseJsonObject(result.positionsFile());
        assertThat(positions.getAsJsonArray("STAGE_POSITIONS").size()).isEqualTo(3);

        String notes = Files.readString(result.notesFile(), StandardCharsets.UTF_8);
        assertThat(notes).contains("polarization");
        assertThat(notes).contains("-7.0");
        assertThat(notes).contains("7.0");

        assertNoTmpFiles(regionDir);
    }

    private static JsonObject parseJsonObject(Path file) throws Exception {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        JsonElement el = JsonParser.parseString(json);
        return el.getAsJsonObject();
    }

    private static void assertNoTmpFiles(Path dir) throws Exception {
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> tmpFiles = entries.filter(p -> p.getFileName().toString().endsWith(".tmp"))
                    .toList();
            assertThat(tmpFiles).isEmpty();
        }
    }
}
