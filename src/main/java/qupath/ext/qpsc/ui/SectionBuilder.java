package qupath.ext.qpsc.ui;

import javafx.scene.Node;
import javafx.scene.control.TitledPane;

/**
 * Utility for creating consistently styled collapsible {@link TitledPane} sections.
 *
 * <p>Copied (not imported) from the image-export-toolkit's {@code quiet.ui.SectionBuilder}
 * idiom, which is package-private there and therefore not reachable across extensions.
 * Stateless factory: the caller drops the returned pane into its own {@code VBox}.
 */
public final class SectionBuilder {

    private SectionBuilder() {}

    /**
     * Builds a collapsible, full-width titled section.
     *
     * @param title    the section header text
     * @param expanded whether the section is expanded initially
     * @param content  the node hosted inside the section
     * @return a configured {@link TitledPane}
     */
    public static TitledPane section(String title, boolean expanded, Node content) {
        TitledPane tp = new TitledPane(title, content);
        tp.setExpanded(expanded);
        tp.setAnimated(false);
        tp.setCollapsible(true);
        tp.setMaxWidth(Double.MAX_VALUE);
        return tp;
    }
}
