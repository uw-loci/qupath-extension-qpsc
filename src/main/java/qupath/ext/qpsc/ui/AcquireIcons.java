package qupath.ext.qpsc.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;

/**
 * Drawn icons for the acquisition wizard's two acquire buttons, so each button shows what it
 * acquires: a stage rectangle cut into tiles, or tissue with an annotation drawn on it. Both
 * are about 48 x 36 px and drawn for a dark blue button.
 */
public final class AcquireIcons {

    private AcquireIcons() {}

    /** A dashed rectangle with corner handles, divided into tiles: Bounded Acquisition. */
    public static Node boundingBox() {
        double w = 46;
        double h = 34;
        Group g = new Group();
        Color tileLine = Color.web("#ffffff", 0.35);
        for (int i = 1; i < 3; i++) {
            g.getChildren().add(line(w * i / 3, 0, w * i / 3, h, tileLine, 1));
        }
        g.getChildren().add(line(0, h / 2, w, h / 2, tileLine, 1));

        Rectangle box = new Rectangle(0, 0, w, h);
        box.setFill(Color.web("#ffffff", 0.08));
        box.setStroke(Color.WHITE);
        box.setStrokeWidth(2);
        box.getStrokeDashArray().setAll(6.0, 4.0);
        g.getChildren().add(box);

        double s = 6;
        for (double[] c : new double[][] {{0, 0}, {w, 0}, {0, h}, {w, h}}) {
            Rectangle handle = new Rectangle(c[0] - s / 2, c[1] - s / 2, s, s);
            handle.setFill(Color.WHITE);
            g.getChildren().add(handle);
        }
        return g;
    }

    /** A pink, nucleated tissue fragment with a dashed annotation around part of it: Existing Image. */
    public static Node tissue() {
        Group g = new Group();
        SVGPath tissue = new SVGPath();
        tissue.setContent(
                "M6 18 C2 9 12 2 22 4 C30 0 42 4 44 13 C48 21 42 31 32 31 C24 36 12 34 8 27 C4 25 3 21 6 18 Z");
        tissue.setFill(Color.web("#f2a7c9"));
        tissue.setStroke(Color.web("#c76a9a"));
        tissue.setStrokeWidth(1);
        g.getChildren().add(tissue);

        Color nucleus = Color.web("#5b2d86");
        double[][] nuclei = {
            {14, 12}, {21, 9}, {29, 10}, {37, 13}, {12, 21}, {19, 17}, {26, 19}, {34, 20}, {40, 22}, {16, 27}, {24, 26},
            {31, 27}
        };
        for (double[] n : nuclei) {
            g.getChildren().add(new Circle(n[0], n[1], 1.6, nucleus));
        }

        Rectangle annotation = new Rectangle(17, 7, 20, 18);
        annotation.setFill(Color.TRANSPARENT);
        annotation.setStroke(Color.web("#ffe066"));
        annotation.setStrokeWidth(2);
        annotation.getStrokeDashArray().setAll(4.0, 3.0);
        annotation.setArcWidth(6);
        annotation.setArcHeight(6);
        g.getChildren().add(annotation);
        return g;
    }

    private static Line line(double x1, double y1, double x2, double y2, Color color, double width) {
        Line l = new Line(x1, y1, x2, y2);
        l.setStroke(color);
        l.setStrokeWidth(width);
        l.setStrokeLineCap(StrokeLineCap.BUTT);
        return l;
    }
}
