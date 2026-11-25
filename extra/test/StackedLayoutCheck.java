package org.helioviewer.jhv.timelines.draw;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import javax.swing.JPanel;

import org.helioviewer.jhv.timelines.TimelineLayer;

import org.json.JSONObject;

/**
 * Stacked timeline panels: heights follow the layers' weights, dragging the gap between two
 * panels moves height from one to the other without changing the total, no panel drops below a
 * usable height, and a double-click's equalize puts them back.
 */
public final class StackedLayoutCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static final class Layer extends TimelineLayer {
        private final YAxis axis = new YAxis(0, 1, YAxis.generateScale("linear", "u"));

        @Override public void remove() {}
        @Override public String getName() { return "t"; }
        @Override public Color getDataColor() { return null; }
        @Override public boolean isDownloading() { return false; }
        @Override public boolean hasData() { return false; }
        @Override public JPanel getOptionsPanel() { return null; }
        @Override public boolean isDeletable() { return true; }
        @Override public void draw(Graphics2D g, Rectangle graphArea, TimeAxis timeAxis, Point mousePosition) {}
        @Override public YAxis getYAxis() { return axis; }
        @Override public void fetchData(TimeAxis selectedAxis) {}
        @Override public void serialize(JSONObject jo) {}
    }

    private static int h(GraphGeometry g, int i) {
        return g.getLayerLayouts().get(i).area().height;
    }

    public static void main(String[] args) {
        GraphGeometry g = new GraphGeometry();
        g.setSize(800, 600);
        g.setStacked(true);
        Layer a = new Layer(), b = new Layer(), c = new Layer();
        List<TimelineLayer> layers = List.of(a, b, c);
        g.layout(layers);

        int total = h(g, 0) + h(g, 1) + h(g, 2);
        expect("equal weights give near-equal heights", Math.abs(h(g, 0) - h(g, 1)) <= 1 && Math.abs(h(g, 1) - h(g, 2)) <= 3);

        a.setStackWeight(2);
        g.layout(layers);
        expect("a double weight gets about double the height", Math.abs(h(g, 0) - 2 * h(g, 1)) <= 3);
        expect("the heights still fill the same space", h(g, 0) + h(g, 1) + h(g, 2) == total);

        g.equalizeStrips();
        g.layout(layers);
        int hb = h(g, 1), hc = h(g, 2);
        g.resizeStrips(1, 20);
        g.layout(layers);
        expect("dragging a gap down grows the upper panel and shrinks the lower", h(g, 1) == hb + 20 && h(g, 2) == hc - 20 || Math.abs(h(g, 1) - (hb + 20)) <= 2);
        expect("the drag leaves the total unchanged", Math.abs(h(g, 0) + h(g, 1) + h(g, 2) - total) <= 1);

        g.resizeStrips(1, 10_000);
        g.layout(layers);
        expect("a huge drag leaves the lower panel a usable height", h(g, 2) >= 30);

        Rectangle r = g.getLayerLayouts().get(0).area();
        expect("the gap below panel 0 is found as separator 0", g.separatorAt(new Point(r.x + 10, r.y + r.height + 4)) == 0);
        expect("the middle of a panel is not a separator", g.separatorAt(new Point(r.x + 10, r.y + r.height / 2)) == -1);

        g.equalizeStrips();
        g.layout(layers);
        expect("equalize restores equal heights", Math.abs(h(g, 0) - h(g, 1)) <= 1);

        System.exit(failures > 0 ? 1 : 0);
    }
}
