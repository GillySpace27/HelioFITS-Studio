package org.helioviewer.jhv.timelines;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.helioviewer.jhv.automation.Track;
import org.helioviewer.jhv.timelines.draw.DrawConstants;
import org.helioviewer.jhv.timelines.draw.GraphGeometry;
import org.helioviewer.jhv.timelines.draw.TimeAxis;

// The animation lanes get a band of their own, above the shared plot rectangle, so they never draw
// over the HEK event bands (which stack down from the top of GraphGeometry.area) or the coverage
// rows (which stack up from its bottom). Before 2026-10-08 the lanes stacked down from the top of
// that same rectangle, on top of the HEK bars.
//
// What is checked: the band and the shared rectangle are disjoint at every pane height; the band
// takes no room when nothing is animated, so the plot is laid out exactly as before; the panel's
// minimum height grows by the band; and every pixel a lane draws, at every pane height, lands in
// the band and none in the shared rectangle.
//
// Not checked: the HEK and coverage layers' own drawing, which this change does not touch; they
// draw inside GraphGeometry.area as before, and the band is outside it.
public final class AutomationBandLayoutCheck {

    private static final long T0 = 1_000_000_000_000L;
    private static final int PANE_W = 600;
    private static int failures;

    public static void main(String[] args) {
        noLanes();
        lanesTakeTheirOwnBand();
        lanesDrawOnlyInTheirBand();
        System.out.println(failures == 0 ? "AutomationBandLayoutCheck: OK" : "AutomationBandLayoutCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void noLanes() {
        GraphGeometry g = geometry(200, List.of());
        check(g.automationArea().isEmpty(), "no band when nothing is animated");
        check(g.area().y == DrawConstants.GRAPH_TOP_SPACE, "the plot starts where it always did with no lanes");
        check(g.minimumHeight() == geometry(200, List.of()).minimumHeight(), "minimum height is unchanged with no lanes");
    }

    private static void lanesTakeTheirOwnBand() {
        int base = geometry(400, List.of()).minimumHeight();
        for (int n = 1; n <= 3; n++) {
            List<TimelineLayer> layers = lanes(n);
            GraphGeometry roomy = geometry(400, layers);
            Rectangle band = roomy.automationArea(), area = roomy.area();
            check(band.height == AutomationTimelineLayer.bandHeight(n), n + " lane(s): the band gets its full height in a roomy pane");
            check(band.y + band.height <= area.y, n + " lane(s): the band sits above the shared plot");
            check(roomy.minimumHeight() >= base + AutomationTimelineLayer.bandHeight(n) - 1,
                    n + " lane(s): the minimum height makes room for the band");

            GraphGeometry atMinimum = geometry(roomy.minimumHeight(), layers);
            check(atMinimum.automationArea().height == AutomationTimelineLayer.bandHeight(n),
                    n + " lane(s): at the minimum height the band is still full size");
            check(atMinimum.area().height >= 20, n + " lane(s): at the minimum height the shared plot keeps room for HEK and coverage");

            for (int paneH = 50; paneH <= 300; paneH += 5) { // 50: what ChartDrawGraphPane asks for at least
                GraphGeometry g = geometry(paneH, layers);
                check(!g.automationArea().intersects(g.area()), n + " lane(s): band and shared plot are disjoint at pane height " + paneH);
                check(!g.automationArea().isEmpty(), n + " lane(s): a band exists at pane height " + paneH);
            }
        }
        // An unticked lane reserves nothing, the same as the drawing skips it.
        List<TimelineLayer> two = lanes(2);
        two.getFirst().setEnabled(false);
        check(geometry(400, two).automationArea().height == AutomationTimelineLayer.bandHeight(1), "an unticked lane takes no room");
    }

    private static void lanesDrawOnlyInTheirBand() {
        TimelineLayers model = new TimelineLayers();
        List<TimelineLayer> layers = lanes(2);
        layers.forEach(model::add);
        try {
            for (int paneH = 50; paneH <= 300; paneH += 10) {
                GraphGeometry g = geometry(paneH, layers);
                for (TimelineLayer layer : layers) {
                    int[] inBandAndArea = paint((AutomationTimelineLayer) layer, g);
                    String what = ((AutomationTimelineLayer) layer).getTrack().paramKey + " at pane height " + paneH;
                    check(inBandAndArea[0] > 0, "draws in its band: " + what);
                    check(inBandAndArea[1] == 0, "draws nothing in the shared plot (HEK, coverage): " + what);
                }
            }
        } finally {
            for (TimelineLayer layer : layers)
                model.remove(layer);
        }
    }

    /** {pixels inked inside the band, pixels inked inside the shared plot}. */
    private static int[] paint(AutomationTimelineLayer lane, GraphGeometry g) {
        BufferedImage image = new BufferedImage(PANE_W, g.size().height + 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D gr = image.createGraphics();
        gr.setColor(Color.BLACK);
        gr.fillRect(0, 0, image.getWidth(), image.getHeight());
        lane.draw(gr, g.automationArea(), new TimeAxis(T0 - 1000, T0 + 3000), null);
        gr.dispose();
        int band = 0, area = 0;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                if ((image.getRGB(x, y) & 0xFFFFFF) != 0) {
                    if (g.automationArea().contains(x, y))
                        band++;
                    if (g.area().contains(x, y))
                        area++;
                }
        return new int[]{band, area};
    }

    private static GraphGeometry geometry(int paneHeight, List<TimelineLayer> layers) {
        GraphGeometry g = new GraphGeometry();
        g.setSize(PANE_W, paneHeight);
        g.layout(layers);
        return g;
    }

    private static List<TimelineLayer> lanes(int n) {
        String[] keys = {"display.warpLambda", "display.diskScale", "display.opacity"};
        ArrayList<TimelineLayer> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Track t = new Track(keys[i]);
            t.put(new Track.Key(T0, 0, Track.Interp.LINEAR));
            t.put(new Track.Key(T0 + 2000, 1, Track.Interp.LINEAR));
            list.add(new AutomationTimelineLayer(t));
        }
        return list;
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private AutomationBandLayoutCheck() {}
}
