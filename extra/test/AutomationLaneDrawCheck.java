package org.helioviewer.jhv.timelines;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.BitSet;

import org.helioviewer.jhv.automation.Track;
import org.helioviewer.jhv.timelines.draw.GraphGeometry;
import org.helioviewer.jhv.timelines.draw.TimeAxis;

// Standalone self-check (no test framework in this repo -- see extra/test/AutomationTrackCheck.java
// for the pattern) for the one thing the automation checks did not cover: whether the lane puts
// anything on the screen. AutomationTrackCheck covers the track and AnimateMenuCheck the slider's
// menu, and between the two of them an armed parameter could add its row to the Timeline Layers
// list and draw NOTHING in the plot. Which is what happened on 2026-09-11: the lane wanted 28
// points of graph height, the plot at its default size had 4, and draw() returned before its first
// drawLine. Nothing threw, so "Animate" looked like a gesture that had failed.
//
// The heights are not invented. GraphGeometry.layout takes 46 points off the pane for the axes,
// plus 18 more for every propagated axis, and ChartDrawGraphPane asks for a pane 50 tall; the
// split pane hands it whatever the window leaves over, which is why the same gesture drew a lane
// after a restart and not before one.
//
// What is NOT checked here: that the lane's own text (its name and the two ends of its range)
// appears. A thinned lane drops those on purpose, and a guard that dropped them everywhere is
// visible the moment anyone opens the plot, which is not the failure mode this file exists for.
//
// Build and run:
//   ant
//   CP="bin:$(find lib -name '*.jar' | tr '\n' ':')"
//   javac -cp "$CP" -d extra/test-classes extra/test/AutomationLaneDrawCheck.java
//   java -cp "extra/test-classes:$CP:resources" org.helioviewer.jhv.timelines.AutomationLaneDrawCheck
public final class AutomationLaneDrawCheck {

    private static final long T0 = 1_000_000_000_000L;
    private static final int PANE_W = 600;

    public static void main(String[] args) {
        oneLane();
        twoLanes();
        System.out.println("AutomationLaneDrawCheck: OK");
    }

    private static void oneLane() {
        // The observed failure: the pane at the 50 points it asks for, 4 of them left for the plot.
        assertTrue(inked(lane(), 50, 0) > 0, "a lane draws in a plot at its default height");

        // Every pane from there to a comfortable one, including what two propagated axes leave
        // behind. None may draw nothing, and none may paint outside the plot rectangle: a lane
        // that spilled would draw over the image panel above it.
        for (int paneH = 50; paneH <= 140; paneH += 2) {
            assertTrue(inked(lane(), paneH, 0) > 0, "a lane draws at pane height " + paneH);
            eq(spilled(lane(), paneH, 0), 0, "nothing drawn outside the plot at pane height " + paneH);
            if (paneH >= 100) { // below this, two propagated axes leave the plot its 1px floor
                assertTrue(inked(lane(), paneH, 2) > 0, "a lane draws at pane height " + paneH + " with two propagated axes");
                eq(spilled(lane(), paneH, 2), 0, "nothing spilled at pane height " + paneH + " with two propagated axes");
            }
        }

        // A roomy plot still gets the full-size lane: the thinning only ever applies downwards.
        assertTrue(inked(lane(), 200, 0) > inked(lane(), 50, 0), "a taller plot draws a bigger lane");
        eq(inked(lane(), 200, 0), inked(lane(), 300, 0), "a lane stops growing at LANE_H");
    }

    // Stacking, which is the half of the thinning a single lane cannot exercise: the height is
    // shared between the lanes, so two of them in a plot that fits one must each get half of it
    // rather than both drawing in the same band.
    private static void twoLanes() {
        TimelineLayers model = new TimelineLayers();
        AutomationTimelineLayer first = lane("display.warpLambda");
        AutomationTimelineLayer second = lane("display.diskScale");
        model.add(first);
        model.add(second);
        assertTrue(AutomationTimelineLayer.lanes().size() == 2, "both lanes are registered");

        for (int paneH : new int[]{74, 110, 200}) {
            assertTrue(inked(first, paneH, 0) > 0, "the first of two lanes draws at pane height " + paneH);
            assertTrue(inked(second, paneH, 0) > 0, "the second of two lanes draws at pane height " + paneH);
            eq(spilled(second, paneH, 0), 0, "the second lane stays inside the plot at pane height " + paneH);
            BitSet a = rows(first, paneH), b = rows(second, paneH);
            // Stacked, not interleaved. Not disjoint: a 5px key marker centred on a band's edge
            // reaches 2px into the gap below it, from both sides, by design.
            assertTrue(b.nextSetBit(0) - a.nextSetBit(0) >= 8, "the second lane starts below the first at pane height " + paneH);
            assertTrue(a.length() <= b.nextSetBit(0) + 3, "the first lane ends where the second begins at pane height " + paneH);
            assertTrue(b.length() > a.length(), "and reaches further down the plot at pane height " + paneH);
        }

        model.remove(first);
        model.remove(second);
    }

    private static AutomationTimelineLayer lane() {
        return lane("display.warpLambda");
    }

    private static AutomationTimelineLayer lane(String paramKey) {
        Track t = new Track(paramKey);
        t.put(new Track.Key(T0, 0, Track.Interp.LINEAR));
        t.put(new Track.Key(T0 + 2000, 1, Track.Interp.LINEAR));
        return new AutomationTimelineLayer(t);
    }

    // Stands in for a propagated band: it adds only a bottom axis row, which is what layout(n, 1) used to model.
    private static final TimelineLayer PROPAGATED = new TimelineLayer() {
        @Override public void remove() {}
        @Override public String getName() { return "propagated"; }
        @Override public java.awt.Color getDataColor() { return null; }
        @Override public boolean isDownloading() { return false; }
        @Override public boolean hasData() { return false; }
        @Override public javax.swing.JPanel getOptionsPanel() { return null; }
        @Override public boolean isDeletable() { return false; }
        @Override public boolean hasYAxis() { return false; }
        @Override public void draw(java.awt.Graphics2D g, Rectangle graphArea, TimeAxis timeAxis, java.awt.Point mousePosition) {}
        @Override public org.helioviewer.jhv.timelines.draw.YAxis getYAxis() { return null; }
        @Override public void fetchData(TimeAxis selectedAxis) {}
        @Override public void serialize(org.json.JSONObject jo) {}
        @Override public boolean isPropagated() { return true; }
    };

    private static Rectangle area(int paneHeight, int propagatedAxes) {
        GraphGeometry geometry = new GraphGeometry();
        geometry.setSize(PANE_W, paneHeight);
        geometry.layout(java.util.Collections.nCopies(propagatedAxes, PROPAGATED));
        return geometry.area();
    }

    /** The lane, drawn on black; every pixel it touched. */
    private static BufferedImage painted(AutomationTimelineLayer lane, int paneHeight, int propagatedAxes) {
        Rectangle graphArea = area(paneHeight, propagatedAxes);
        BufferedImage image = new BufferedImage(PANE_W, Math.max(paneHeight, graphArea.y + graphArea.height) + 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        lane.draw(g, graphArea, new TimeAxis(T0 - 1000, T0 + 3000), null);
        g.dispose();
        return image;
    }

    /** Pixels the lane painted inside the plot rectangle. */
    private static int inked(AutomationTimelineLayer lane, int paneHeight, int propagatedAxes) {
        return count(lane, paneHeight, propagatedAxes, true);
    }

    /** Pixels it painted outside it. */
    private static int spilled(AutomationTimelineLayer lane, int paneHeight, int propagatedAxes) {
        return count(lane, paneHeight, propagatedAxes, false);
    }

    private static int count(AutomationTimelineLayer lane, int paneHeight, int propagatedAxes, boolean inside) {
        Rectangle graphArea = area(paneHeight, propagatedAxes);
        BufferedImage image = painted(lane, paneHeight, propagatedAxes);
        int n = 0;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                if ((image.getRGB(x, y) & 0xFFFFFF) != 0 && graphArea.contains(x, y) == inside)
                    n++;
        return n;
    }

    /** The rows the lane painted anything in. */
    private static BitSet rows(AutomationTimelineLayer lane, int paneHeight) {
        BufferedImage image = painted(lane, paneHeight, 0);
        BitSet set = new BitSet();
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                if ((image.getRGB(x, y) & 0xFFFFFF) != 0) {
                    set.set(y);
                    break;
                }
        return set;
    }

    private static void eq(int got, int want, String what) {
        if (got != want)
            throw new AssertionError(what + ": expected " + want + ", got " + got);
    }

    private static void assertTrue(boolean ok, String what) {
        if (!ok)
            throw new AssertionError(what);
    }

    private AutomationLaneDrawCheck() {}
}
