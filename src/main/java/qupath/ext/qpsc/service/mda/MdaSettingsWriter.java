package qupath.ext.qpsc.service.mda;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.qpsc.modality.AngleExposure;

public final class MdaSettingsWriter {

    private static final Logger logger = LoggerFactory.getLogger(MdaSettingsWriter.class);
    private static final int DEFAULT_GREY_ARGB = -16777216;

    private MdaSettingsWriter() {}

    /**
     * Writes three Micro-Manager-compatible files into {@code req.regionDir()}:
     * {@code MDA_<region>.txt} (SequenceSettings JSON), {@code MDA_<region>.pos}
     * (PositionList JSON), and {@code MDA_NOTES.txt} (plaintext provenance).
     *
     * <p>Channel resolution is the caller's responsibility: pass already-resolved
     * {@link ResolvedChannel} entries carrying the MM ConfigGroup, preset, and
     * exposure. PPM acquisitions ({@code modalityBaseName} starts with "ppm")
     * write a positions-only MDA -- channels are intentionally omitted and the
     * notes file documents the polarization-angle sequence instead.
     *
     * <p>All three files are written atomically via {@code .tmp} -> {@code move}
     * with {@code REPLACE_EXISTING}, so partial writes never leave a half-formed
     * file behind for MM to choke on.
     *
     * @param req fully-populated write request
     * @return paths to the three written files
     * @throws IOException if any file write fails
     */
    public static MdaWriteResult write(MdaWriteRequest req) throws IOException {
        if (req == null) {
            throw new IllegalArgumentException("MdaWriteRequest must be non-null");
        }
        if (req.regionDir() == null) {
            throw new IllegalArgumentException("regionDir must be non-null");
        }
        Files.createDirectories(req.regionDir());

        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .serializeNulls()
                .create();

        boolean ppm = isPpm(req.modalityBaseName());

        MdaSequenceSettings settings = buildSequenceSettings(req, ppm);
        MdaPositionList positions = buildPositionList(req);
        String notes = buildNotes(req, ppm);

        Path settingsFile = req.regionDir().resolve("MDA_" + req.regionName() + ".txt");
        Path positionsFile = req.regionDir().resolve("MDA_" + req.regionName() + ".pos");
        Path notesFile = req.regionDir().resolve("MDA_NOTES.txt");

        writeAtomic(settingsFile, gson.toJson(settings));
        writeAtomic(positionsFile, gson.toJson(positions));
        writeAtomic(notesFile, notes);

        return new MdaWriteResult(settingsFile, positionsFile, notesFile);
    }

    private static boolean isPpm(String modalityBaseName) {
        return modalityBaseName != null && modalityBaseName.toLowerCase().startsWith("ppm");
    }

    private static MdaSequenceSettings buildSequenceSettings(MdaWriteRequest req, boolean ppm) {
        ZStackSpec z = req.zStack();
        boolean useSlices = z != null && z.stepUm() > 0;
        List<Double> slices = useSlices ? enumerateSlices(z) : List.of();
        double zStep = z != null ? z.stepUm() : 0.0;
        double zBottom = z != null ? Math.min(z.startUm(), z.endUm()) : 0.0;
        double zTop = z != null ? Math.max(z.startUm(), z.endUm()) : 0.0;

        TimeLapseSpec t = req.timeLapse();
        int numFrames = t != null ? Math.max(1, t.timepoints()) : 1;
        double intervalMs = t != null ? Math.max(0.0, t.intervalSec() * 1000.0) : 0.0;
        boolean useFrames = numFrames > 1;

        List<MdaChannelSpec> channels;
        boolean useChannels;
        String channelGroup;
        if (ppm) {
            channels = List.of();
            useChannels = false;
            channelGroup = "";
        } else {
            channels = buildChannels(req.channels(), useSlices, req.channelGroupOverrides());
            useChannels = !channels.isEmpty();
            channelGroup = channels.isEmpty() ? "" : channels.get(0).channelGroup();
        }

        String prefix = "MDA_" + req.regionName();
        String root = req.regionDir().toAbsolutePath().toString();
        String comment = buildComment(req, ppm);

        return new MdaSequenceSettings(
                1,
                numFrames,
                intervalMs,
                useFrames,
                false,
                null,
                slices,
                useSlices,
                false,
                true,
                zStep,
                zBottom,
                zTop,
                false,
                channels,
                useChannels,
                channelGroup,
                false,
                false,
                true,
                false,
                0,
                true,
                "MULTIPAGE_TIFF",
                root,
                prefix,
                comment,
                0,
                true,
                20000,
                false,
                0);
    }

    private static List<Double> enumerateSlices(ZStackSpec z) {
        double start = z.startUm();
        double end = z.endUm();
        double step = z.stepUm();
        if (step <= 0) {
            return List.of(start);
        }
        // Use absolute step direction matching start->end so reversed ranges enumerate downward.
        double signedStep = end >= start ? step : -step;
        int n = (int) Math.round((end - start) / signedStep) + 1;
        if (n < 1) {
            n = 1;
        }
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(start + i * signedStep);
        }
        return out;
    }

    private static List<MdaChannelSpec> buildChannels(
            List<ResolvedChannel> resolved, boolean useSlices, Map<String, String> groupOverrides) {
        if (resolved == null || resolved.isEmpty()) {
            return List.of();
        }
        List<MdaChannelSpec> out = new ArrayList<>(resolved.size());
        for (ResolvedChannel rc : resolved) {
            String group = rc.group();
            if (groupOverrides != null && groupOverrides.containsKey(rc.id())) {
                group = groupOverrides.get(rc.id());
            }
            MdaColor color = new MdaColor(rc.colorArgb() == 0 ? DEFAULT_GREY_ARGB : rc.colorArgb(), 0);
            out.add(new MdaChannelSpec(
                    group == null ? "" : group,
                    rc.preset() == null ? "" : rc.preset(),
                    rc.exposureMs(),
                    0.0,
                    useSlices,
                    color,
                    0,
                    true,
                    ""));
        }
        return out;
    }

    private static MdaPositionList buildPositionList(MdaWriteRequest req) {
        List<TileStagePos> tiles = req.tiles() == null ? List.of() : req.tiles();
        MmStageDevices dev = req.stageDevices();
        String xy = dev != null && dev.xyStage() != null ? dev.xyStage() : "XYStage";
        String z = dev != null && dev.zStage() != null ? dev.zStage() : "ZStage";

        List<MdaMultiStagePosition> list = new ArrayList<>(tiles.size());
        for (TileStagePos tile : tiles) {
            List<MdaStagePosition> devicePositions = List.of(
                    new MdaStagePosition(xy, tile.xUm(), tile.yUm(), 0.0, 2),
                    new MdaStagePosition(z, 0.0, 0.0, tile.zUm(), 1));
            list.add(new MdaMultiStagePosition(
                    tile.label() == null ? "" : tile.label(), xy, z, 0, 0, Map.of(), devicePositions));
        }
        return new MdaPositionList(list);
    }

    private static String buildComment(MdaWriteRequest req, boolean ppm) {
        StringBuilder sb = new StringBuilder();
        sb.append("QPSC MDA export. ");
        if (req.sample() != null) {
            sb.append("Sample=").append(safe(req.sample().sampleName())).append(", ");
            sb.append("modality=").append(safe(req.sample().modality())).append(", ");
            sb.append("objective=").append(safe(req.sample().objective())).append(", ");
            sb.append("detector=").append(safe(req.sample().detector())).append(". ");
        }
        sb.append("Region=").append(safe(req.regionName())).append(". ");
        sb.append("Tiles=").append(req.tiles() == null ? 0 : req.tiles().size()).append(". ");
        if (ppm) {
            sb.append("PPM positions-only (channels intentionally omitted). ");
        }
        sb.append("useAutofocus=false (QPSC streaming AF runs server-side, not via MM AutofocusManager).");
        return sb.toString();
    }

    private static String buildNotes(MdaWriteRequest req, boolean ppm) {
        StringBuilder sb = new StringBuilder();
        sb.append("MDA export generated by QPSC on ")
                .append(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .append("\n\n");

        sb.append("How to load in Micro-Manager 2.0\n");
        sb.append("--------------------------------\n");
        sb.append("1. Open the Multi-Dimensional Acquisition window. Click Load... and select\n");
        sb.append("   MDA_").append(req.regionName()).append(".txt (in this folder).\n");
        sb.append("2. Open the Stage Position List window. Click Load... and select\n");
        sb.append("   MDA_").append(req.regionName()).append(".pos (in this folder).\n");
        sb.append("3. The two windows are independent: loading the .txt does NOT load the .pos.\n\n");

        sb.append("Autofocus\n");
        sb.append("---------\n");
        sb.append("useAutofocus is set to false. QPSC's per-tile streaming autofocus runs\n");
        sb.append("server-side and does not map to MM's AutofocusManager. If you want MM-side\n");
        sb.append("autofocus, configure it in the MDA window after loading.\n\n");

        if (ppm) {
            sb.append("PPM modality\n");
            sb.append("------------\n");
            sb.append("This region was acquired by a polarization-modulation (PPM) modality.\n");
            sb.append("The .txt file has useChannels=false on purpose: PPM rotates a polarizer\n");
            sb.append("instead of switching MM ConfigGroup presets, so there is no MM channel\n");
            sb.append("mapping that round-trips correctly. The polarization angles QPSC used\n");
            sb.append("are listed below for reference; reproduce them manually in MM if needed.\n\n");
            sb.append("Polarization angles (ticks, exposure ms):\n");
            List<AngleExposure> angles = req.angleExposures() == null ? List.of() : req.angleExposures();
            for (AngleExposure ae : angles) {
                sb.append("  ")
                        .append(ae.ticks())
                        .append(", ")
                        .append(ae.exposureMs())
                        .append("\n");
            }
            sb.append("\n");
        } else {
            List<ResolvedChannel> chs = req.channels() == null ? List.of() : req.channels();
            if (!chs.isEmpty()) {
                Set<String> distinctGroups = new LinkedHashSet<>();
                for (ResolvedChannel rc : chs) {
                    if (rc.group() != null && !rc.group().isBlank()) {
                        distinctGroups.add(rc.group());
                    }
                }
                if (distinctGroups.size() > 1) {
                    sb.append("Channel group caveat\n");
                    sb.append("--------------------\n");
                    sb.append("Selected channels span multiple MM ConfigGroups: ")
                            .append(String.join(", ", distinctGroups))
                            .append("\n");
                    sb.append("MM's SequenceSettings only supports a single channelGroup. The writer\n");
                    sb.append("used the first channel's group (")
                            .append(chs.get(0).group())
                            .append("). Edit the .txt manually if you need a different group.\n\n");
                }
            }
        }

        TimeLapseSpec t = req.timeLapse();
        int tp = t == null ? 1 : Math.max(1, t.timepoints());
        if (tp == 1) {
            sb.append("Time lapse\n");
            sb.append("----------\n");
            sb.append("timepoints=1 (single timepoint). MM will run a single frame.\n\n");
        } else {
            sb.append("Time lapse\n");
            sb.append("----------\n");
            sb.append("timepoints=")
                    .append(tp)
                    .append(", interval=")
                    .append(t.intervalSec())
                    .append(" sec.\n\n");
        }

        MmStageDevices dev = req.stageDevices();
        boolean fallback = dev == null
                || dev.xyStage() == null
                || dev.zStage() == null
                || "XYStage".equals(dev.xyStage())
                || "ZStage".equals(dev.zStage());
        if (fallback) {
            sb.append("Stage device names\n");
            sb.append("------------------\n");
            sb.append("The .pos file uses ")
                    .append(dev == null ? "XYStage" : dev.xyStage())
                    .append(" / ")
                    .append(dev == null ? "ZStage" : dev.zStage())
                    .append(" as the XY/Z stage device names.\n");
            sb.append("If your MM hardware config uses different names, either add an\n");
            sb.append("mm_stage_devices: block to the microscope YAML or edit the .pos file by hand.\n\n");
        }

        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            // ATOMIC_MOVE can fail across filesystems (e.g. tmpfs <-> ext4 in tests). Fall back
            // to a non-atomic replace so the write still completes deterministically.
            logger.debug(
                    "Atomic move failed for {}, falling back to non-atomic replace: {}",
                    target,
                    atomicFailed.getMessage());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
