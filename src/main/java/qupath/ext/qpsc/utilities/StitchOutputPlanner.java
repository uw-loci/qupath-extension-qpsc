package qupath.ext.qpsc.utilities;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Predicts, before a stitch job runs, which project entries it will produce and what they will
 * be called, so {@link ProjectEntryReservation} can add them to the project up front.
 *
 * <p>Pure: every fact comes in through {@link Inputs}. The naming rules are the ones
 * {@code TileProcessingUtilities.stitchImagesAndUpdateProject} and the merged-channel path in
 * {@code StitchingHelper} apply when the file lands; a prediction that turns out wrong costs
 * only a placeholder entry, which the job drops when it finishes (the real file is imported
 * the ordinary way). OME-TIFF only: a zarr job gets an empty plan.
 */
public final class StitchOutputPlanner {

    private StitchOutputPlanner() {}

    /** What kind of output an entry stands for. */
    public enum Kind {
        /** One angle of a multi-angle (PPM) acquisition. */
        ANGLE,
        /** The one file of a single-angle or angle-less acquisition. */
        SINGLE,
        /** A post-processing output the server wrote beside the tiles (biref, sum, retardance, ...). */
        DERIVED,
        /** One channel written as its own file (split, or the only one left to merge). */
        CHANNEL,
        /** The multichannel merge of the non-split channels. */
        MERGED
    }

    /**
     * One planned entry.
     *
     * @param key the stitch target token the import site will present: the angle sub-folder
     *     ("7.0"), the derived folder ("7.0.biref", "retardance"), the channel id, or
     *     {@link #MERGED_KEY}; for an angle-less single stitch, the annotation name
     * @param fileName the predicted file name, with extension
     * @param angleSuffix the angle / channel token recorded in the entry's metadata; null for none
     * @param kind what it is
     */
    public record ReservedOutput(String key, String fileName, String angleSuffix, Kind kind) {}

    /** The key of the merged multichannel entry. */
    public static final String MERGED_KEY = "merged";

    /**
     * Everything the plan needs.
     *
     * @param displayName the sample / source image name used for file names
     * @param modeWithIndex the imaging-mode folder, e.g. {@code ppm_20x_3}
     * @param annotationName the region name (may carry a temp-folder prefix, which is stripped)
     * @param extension {@code .ome.tif}; anything else gives an empty plan
     * @param angleSubdirs the angle tick strings of a multi-angle job, one for a single-angle
     *     job, empty for a job with no angle folder
     * @param angleSuffix maps a numeric tick string to the modality's file suffix; null for
     *     a non-numeric token
     * @param derivedDirs names of the post-processing folders present beside the tiles
     * @param channelIds the channel ids of a channel-based job, in library order; empty otherwise
     * @param splitChannelIds channels written as their own files
     * @param mergedModality the modality token the merged file uses (the registry's short prefix)
     * @param takenNames file and entry names already in use (disk and project)
     */
    public record Inputs(
            String displayName,
            String modeWithIndex,
            String annotationName,
            String extension,
            List<String> angleSubdirs,
            Function<String, String> angleSuffix,
            List<String> derivedDirs,
            List<String> channelIds,
            Set<String> splitChannelIds,
            String mergedModality,
            Set<String> takenNames) {}

    /**
     * @param in the facts
     * @return the entries the job will produce, in import order; empty for zarr output
     */
    public static List<ReservedOutput> plan(Inputs in) {
        if (in.extension() == null || !in.extension().equals(".ome.tif")) {
            return List.of();
        }
        String[] parts = ImageNameGenerator.parseImagingMode(in.modeWithIndex());
        String modality = parts[0];
        String objective = parts[1];
        int startIndex = ImageNameGenerator.extractImageIndex(in.modeWithIndex());
        String annotation = ImageNameGenerator.sanitizeForFilename(
                TileProcessingUtilities.extractOriginalRegionName(in.annotationName()));

        // Plan once at the acquisition's index; if any name is taken, bump the index for the
        // whole job (the files of one job share the index) and plan again.
        for (int index = startIndex; index < startIndex + 1000; index++) {
            List<ReservedOutput> plan = planAt(in, modality, objective, annotation, index);
            boolean clash = false;
            for (ReservedOutput r : plan) {
                if (in.takenNames() != null && in.takenNames().contains(r.fileName())) {
                    clash = true;
                    break;
                }
            }
            if (!clash) {
                return plan;
            }
        }
        return List.of();
    }

    private static List<ReservedOutput> planAt(
            Inputs in, String modality, String objective, String annotation, int index) {
        List<ReservedOutput> out = new ArrayList<>();
        String ext = in.extension();
        if (in.channelIds() != null && !in.channelIds().isEmpty()) {
            List<String> merge = new ArrayList<>();
            for (String cid : in.channelIds()) {
                if (in.splitChannelIds() != null && in.splitChannelIds().contains(cid)) {
                    out.add(new ReservedOutput(
                            cid,
                            ImageNameGenerator.generateImageName(
                                    in.displayName(), index, modality, objective, annotation, cid, ext),
                            cid,
                            Kind.CHANNEL));
                } else {
                    merge.add(cid);
                }
            }
            if (merge.size() == 1) {
                String cid = merge.get(0);
                out.add(new ReservedOutput(
                        cid,
                        ImageNameGenerator.generateImageName(
                                in.displayName(), index, modality, objective, annotation, cid, ext),
                        cid,
                        Kind.CHANNEL));
            } else if (merge.size() >= 2) {
                String mergedModality = in.mergedModality() != null ? in.mergedModality() : modality;
                out.add(new ReservedOutput(
                        MERGED_KEY,
                        ImageNameGenerator.generateImageName(
                                in.displayName(), index, mergedModality, objective, annotation, null, ext),
                        null,
                        Kind.MERGED));
            }
            addDerived(in, out, modality, objective, annotation, index);
            return out;
        }
        if (in.angleSubdirs() != null && in.angleSubdirs().size() > 1) {
            for (String tick : in.angleSubdirs()) {
                String suffix = suffixFor(in, tick);
                out.add(new ReservedOutput(
                        tick,
                        ImageNameGenerator.generateImageName(
                                in.displayName(), index, modality, objective, annotation, suffix, ext),
                        suffix,
                        Kind.ANGLE));
            }
            addDerived(in, out, modality, objective, annotation, index);
            return out;
        }
        // Single file: one angle folder, or the annotation folder itself.
        if (in.angleSubdirs() != null && in.angleSubdirs().size() == 1) {
            String tick = in.angleSubdirs().get(0);
            String suffix = suffixFor(in, tick);
            out.add(new ReservedOutput(
                    tick,
                    ImageNameGenerator.generateImageName(
                            in.displayName(), index, modality, objective, annotation, suffix, ext),
                    suffix,
                    Kind.SINGLE));
        } else {
            out.add(new ReservedOutput(
                    in.annotationName(),
                    ImageNameGenerator.generateImageName(
                            in.displayName(), index, modality, objective, annotation, null, ext),
                    null,
                    Kind.SINGLE));
        }
        addDerived(in, out, modality, objective, annotation, index);
        return out;
    }

    private static void addDerived(
            Inputs in, List<ReservedOutput> out, String modality, String objective, String annotation, int index) {
        if (in.derivedDirs() == null) {
            return;
        }
        for (String dir : in.derivedDirs()) {
            String suffix = derivedSuffix(in, dir);
            out.add(new ReservedOutput(
                    dir,
                    ImageNameGenerator.generateImageName(
                            in.displayName(), index, modality, objective, annotation, suffix, in.extension()),
                    suffix,
                    Kind.DERIVED));
        }
    }

    /** The modality's suffix for a numeric tick, else the tick itself. */
    private static String suffixFor(Inputs in, String tick) {
        String s = in.angleSuffix() != null ? in.angleSuffix().apply(tick) : null;
        return s != null ? s : tick;
    }

    /**
     * The suffix of a derived folder, as the import site derives it: {@code 7.0.biref} is the
     * angle's suffix plus {@code _biref}, {@code 7.0.sum} likewise, anything else (LC-PolScope's
     * {@code retardance}) is used as it is.
     */
    static String derivedSuffix(Inputs in, String dir) {
        for (String tail : new String[] {".biref", ".sum"}) {
            if (dir.endsWith(tail)) {
                String base = dir.substring(0, dir.length() - tail.length());
                String baseSuffix = in.angleSuffix() != null ? in.angleSuffix().apply(base) : null;
                return baseSuffix != null ? baseSuffix + "_" + tail.substring(1) : dir.replace(".", "_");
            }
        }
        return dir;
    }
}
