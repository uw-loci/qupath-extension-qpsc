package qupath.ext.qpsc.display.orientation;

import java.awt.image.BufferedImage;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Finds the other half of an LC-PolScope result: the retardance image for an orientation image
 * or the reverse. One acquisition stitches both from the same tiles, so they have the same size
 * and names that differ only in {@code retardance} / {@code orientation} (the server's output
 * folders). The display-only {@code orientation_color} image is neither.
 */
public final class OrientationPairing {

    /** Which half an image name is. */
    public enum Role {
        ORIENTATION,
        RETARDANCE
    }

    private static final String ORIENTATION = "orientation";
    private static final String RETARDANCE = "retardance";

    private OrientationPairing() {}

    /** The role an image name says it has, if any. */
    public static Optional<Role> roleOf(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("orientation_color") || lower.contains("orientation_colour")) {
            return Optional.empty();
        }
        int o = lower.lastIndexOf(ORIENTATION);
        int r = lower.lastIndexOf(RETARDANCE);
        if (o < 0 && r < 0) {
            return Optional.empty();
        }
        return Optional.of(o > r ? Role.ORIENTATION : Role.RETARDANCE);
    }

    /** The partner's name among {@code names}, or empty when the name has no role or no partner. */
    public static Optional<String> partnerName(String name, Collection<String> names) {
        Optional<Role> role = roleOf(name);
        if (role.isEmpty()) {
            return Optional.empty();
        }
        String lower = name.toLowerCase(Locale.ROOT);
        String from = role.get() == Role.ORIENTATION ? ORIENTATION : RETARDANCE;
        String to = role.get() == Role.ORIENTATION ? RETARDANCE : ORIENTATION;
        int at = lower.lastIndexOf(from);
        String wanted = lower.substring(0, at) + to + lower.substring(at + from.length());
        return names.stream()
                .filter(n -> n.toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }

    /**
     * What the overlay draws for an open image: orientation as band 0 and, when the partner is in
     * the project, retardance as band 1.
     *
     * @param server the tile-caching server to render: orientation alone or orientation + retardance
     * @param opened a server this call opened (the partner), for the caller to close; or null
     * @param description which images are paired, for the window
     */
    public record Source(ImageServer<BufferedImage> server, ImageServer<BufferedImage> opened, String description) {}

    /**
     * Builds the overlay's source for the open image, or empty when it is not an LC-PolScope
     * retardance or orientation image, or is a retardance image without its orientation partner.
     */
    public static Optional<Source> sourceFor(ImageData<BufferedImage> imageData, Project<BufferedImage> project)
            throws Exception {
        if (imageData == null || project == null) {
            return Optional.empty();
        }
        ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
        if (entry == null) {
            return Optional.empty();
        }
        String name = entry.getImageName();
        Optional<Role> role = roleOf(name);
        if (role.isEmpty()) {
            return Optional.empty();
        }
        Collection<String> names = project.getImageList().stream()
                .map(ProjectImageEntry::getImageName)
                .toList();
        Optional<ProjectImageEntry<BufferedImage>> partner = partnerName(name, names)
                .flatMap(p -> project.getImageList().stream()
                        .filter(e -> e.getImageName().equals(p))
                        .findFirst());
        ImageServer<BufferedImage> open = imageData.getServer();
        if (partner.isEmpty()) {
            return role.get() == Role.ORIENTATION
                    ? Optional.of(new Source(
                            new OrientationPairServer(open, null),
                            null,
                            name + " (no retardance image found: colour only)"))
                    : Optional.empty();
        }
        ImageServer<BufferedImage> other = partner.get().getServerBuilder().build();
        if (other.getWidth() != open.getWidth() || other.getHeight() != open.getHeight()) {
            other.close();
            throw new java.io.IOException(String.format(
                    "%s is %d x %d but %s is %d x %d; they are not from the same acquisition",
                    name,
                    open.getWidth(),
                    open.getHeight(),
                    partner.get().getImageName(),
                    other.getWidth(),
                    other.getHeight()));
        }
        ImageServer<BufferedImage> orientation = role.get() == Role.ORIENTATION ? open : other;
        ImageServer<BufferedImage> retardance = role.get() == Role.ORIENTATION ? other : open;
        ImageServer<BufferedImage> both = new OrientationPairServer(orientation, retardance);
        String description = role.get() == Role.ORIENTATION
                ? name + " + " + partner.get().getImageName()
                : partner.get().getImageName() + " + " + name;
        return Optional.of(new Source(both, other, description));
    }
}
