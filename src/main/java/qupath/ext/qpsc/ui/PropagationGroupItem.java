package qupath.ext.qpsc.ui;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import qupath.lib.projects.ProjectImageEntry;

/**
 * View-model for one row in the Propagation Manager table.
 *
 * <p>Each item bundles a base image with all of its base-like siblings
 * (unflipped + flipped duplicates) and all of its sub-acquisitions.
 * The dialog observes property fields for live status updates during
 * propagation; static fields populate the table columns directly.
 */
public final class PropagationGroupItem {

    private final String baseName;
    private final List<ProjectImageEntry<BufferedImage>> siblings;
    private final List<ProjectImageEntry<BufferedImage>> subAcquisitions;
    private final SimpleStringProperty siblingSummary = new SimpleStringProperty();
    private final SimpleStringProperty subAcquisitionSummary = new SimpleStringProperty();
    private final SimpleBooleanProperty alignmentFound = new SimpleBooleanProperty(false);
    private final SimpleStringProperty status = new SimpleStringProperty("");
    private final SimpleBooleanProperty selected = new SimpleBooleanProperty(false);

    public PropagationGroupItem(
            String baseName,
            List<ProjectImageEntry<BufferedImage>> siblings,
            List<ProjectImageEntry<BufferedImage>> subAcquisitions,
            boolean alignmentFound) {
        this.baseName = baseName;
        this.siblings = siblings != null ? siblings : Collections.emptyList();
        this.subAcquisitions = subAcquisitions != null ? subAcquisitions : Collections.emptyList();
        this.alignmentFound.set(alignmentFound);
        this.siblingSummary.set(buildSiblingSummary(this.siblings));
        this.subAcquisitionSummary.set(this.subAcquisitions.size() + " sub-image(s)");
    }

    private static String buildSiblingSummary(List<ProjectImageEntry<BufferedImage>> siblings) {
        if (siblings.isEmpty()) return "0 siblings";
        return siblings.size() + ": "
                + siblings.stream()
                        .map(ProjectImageEntry::getImageName)
                        .map(PropagationGroupItem::shortLabel)
                        .collect(Collectors.joining(", "));
    }

    private static String shortLabel(String imageName) {
        if (imageName == null) return "";
        if (imageName.contains("(flipped XY)")) return "flipped XY";
        if (imageName.contains("(flipped X)")) return "flipped X";
        if (imageName.contains("(flipped Y)")) return "flipped Y";
        return "unflipped";
    }

    public String getBaseName() { return baseName; }
    public List<ProjectImageEntry<BufferedImage>> getSiblings() { return siblings; }
    public List<ProjectImageEntry<BufferedImage>> getSubAcquisitions() { return subAcquisitions; }
    public SimpleStringProperty siblingSummaryProperty() { return siblingSummary; }
    public SimpleStringProperty subAcquisitionSummaryProperty() { return subAcquisitionSummary; }
    public SimpleBooleanProperty alignmentFoundProperty() { return alignmentFound; }
    public SimpleStringProperty statusProperty() { return status; }

    public boolean isAlignmentFound() { return alignmentFound.get(); }
    public void setStatus(String s) { status.set(s); }

    public SimpleBooleanProperty selectedProperty() { return selected; }
    public boolean isSelected() { return selected.get(); }
    public void setSelected(boolean v) { selected.set(v); }
}
