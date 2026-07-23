package org.helioviewer.jhv.timelines;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.JPanel;
import javax.swing.Timer;

import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.timelines.draw.DrawController;
import org.helioviewer.jhv.timelines.draw.TimeAxis;
import org.helioviewer.jhv.timelines.draw.YAxis;
import org.helioviewer.jhv.timelines.draw.YAxis.YAxisIdentityScale;
import org.helioviewer.jhv.view.View;

import org.json.JSONObject;

// A timeline track per loaded image layer showing frame coverage across time, so it is easy to
// see which datasets have frames where. Three states, distinguished by line style:
//   solid  = frame present and downloaded (available),
//   dashed = a frame that was requested but failed to download — known to exist, retryable
//            (a short tick at its parsed timestamp, drawn over any gap it falls inside),
//   dotted = a gap in the archive itself (unavailable — no data was ever requested there).
// has to be public for state restore
public final class CoverageTimelineLayer extends AbstractTimelineLayer {

    private static final Color GAP_COLOR = new Color(120, 120, 120);
    
    private static final Stroke DASHED = new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{4f, 3f}, 0f);
    private static final Stroke DOTTED = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{1f, 4f}, 0f);
    private static final Color[] PALETTE = {
            new Color(80, 170, 255), new Color(255, 170, 60), new Color(120, 220, 120),
            new Color(230, 110, 230), new Color(240, 220, 90), new Color(240, 100, 100)};
    private static final int LANE_GAP = 4;    // tighter than the SWEK bands: coverage rows pack close
    private static final int BOTTOM_OFFSET = 6; // margin below the lowest coverage row

    private final YAxis yAxis = new YAxis(0, 0, new YAxisIdentityScale("Coverage"));

    // While any layer is still downloading, coverage changes as frames arrive; nudge a redraw.
    private final Timer repaint = new Timer(1000, e -> {
        if (enabled && anyDownloading())
            DrawController.drawRequest();
    });

    public CoverageTimelineLayer() {
        repaint.setRepeats(true);
        repaint.start();
    }

    public static AbstractTimelineLayer deserialize(JSONObject ignore) { // for state restore
        return new CoverageTimelineLayer();
    }

    @Override
    public void serialize(JSONObject jo) {}

    private static boolean anyDownloading() {
        for (ImageLayer layer : Layers.getImageLayers())
            if (layer.isDownloading())
                return true;
        return false;
    }

    @Override
    public void draw(Graphics2D g, Rectangle graphArea, TimeAxis xAxis, Point mousePosition) {
        if (!enabled)
            return;
        List<ImageLayer> layers = Layers.getImageLayers();
        if (layers.isEmpty())
            return;

        TimeAxis.Mapper x = xAxis.mapper(graphArea.x, graphArea.width);
        Stroke savedStroke = g.getStroke();
        Font savedFont = g.getFont();
        java.awt.Shape savedClip = g.getClip();
        g.clipRect(graphArea.x, graphArea.y, graphArea.width, graphArea.height); // never spill past the plot
        g.setFont(savedFont.deriveFont(Font.PLAIN, 9f));
        int labelH = g.getFontMetrics().getHeight();
        int barH = org.helioviewer.jhv.timelines.draw.DrawConstants.getBarHeight();
        int rowH = labelH + barH + LANE_GAP; // label sits directly above its bar; the pair moves together
        int right = graphArea.x + graphArea.width;
        int bottom = graphArea.y + graphArea.height;

        // Coverage lives in its own band at the BOTTOM of the plot, filling upward, so it no longer
        // overlaps the SWEK event bands that fill from the top down. Bottom-justify the whole block.
        int enabledCount = 0;
        for (ImageLayer layer : layers)
            if (layer.isEnabled())
                enabledCount++;
        int blockTop = bottom - BOTTOM_OFFSET - enabledCount * rowH;

        int lane = 0;
        for (ImageLayer layer : layers) {
            if (!layer.isEnabled())
                continue;
            int yTop = blockTop + lane * rowH;
            int yBar = yTop + labelH + barH / 2;
            Color color = PALETTE[lane % PALETTE.length];
            lane++;

            View view = layer.getView();
            int max = view.getMaximumFrameNumber();
            long[] times = new long[max + 1];
            for (int i = 0; i <= max; i++)
                times[i] = view.getFrameTime(i).milli;
            long cadence = medianCadence(times);

            // dataset label directly above its own bar so the two always align
            g.setColor(color);
            g.drawString(layer.getName(), graphArea.x + 2, yTop + labelH - 2);

            if (max == 0) { // single frame: a downloaded/missing tick
                drawSegment(g, x.toPixel(times[0]) - 1, x.toPixel(times[0]) + 1, yBar, color, isComplete(view, 0), graphArea.x, right);
                continue;
            }
            for (int i = 0; i < max; i++) {
                int x0 = x.toPixel(times[i]);
                int x1 = x.toPixel(times[i + 1]);
                if (x1 < graphArea.x || x0 > right) // off-screen
                    continue;
                long dt = times[i + 1] - times[i];
                if (cadence > 0 && dt > cadence * 1.8) { // gap larger than cadence: unavailable
                    g.setColor(GAP_COLOR);
                    g.setStroke(DOTTED);
                    g.drawLine(Math.max(x0, graphArea.x), yBar, Math.min(x1, right), yBar);
                } else {
                    drawSegment(g, x0, x1, yBar, color, isComplete(view, i), graphArea.x, right);
                }
            }

            // Failed downloads: known to exist (were requested), just not retrieved — distinct
            // from a genuine archive gap, drawn as short dashed ticks over the (dotted) gap.
            for (long t : failedTimes(layer)) {
                int fx = x.toPixel(t);
                if (fx < graphArea.x || fx > right)
                    continue;
                g.setColor(color);
                g.setStroke(DASHED);
                g.drawLine(fx, yBar - barH / 2, fx, yBar + barH / 2);
            }
        }
        g.setStroke(savedStroke);
        g.setFont(savedFont);
        g.setClip(savedClip);
    }

    // Best-effort timestamp for each failed URI: PUNCH (and most solar-archive) filenames embed a
    // compact yyyyMMddHHmmss timestamp; URIs that don't match one are silently skipped rather than
    // guessed at.
    private static final java.util.regex.Pattern TIMESTAMP_14 = java.util.regex.Pattern.compile("(\\d{14})");

    private static List<Long> failedTimes(ImageLayer layer) {
        List<Long> out = new java.util.ArrayList<>();
        for (java.net.URI uri : layer.getFailedUris()) {
            java.util.regex.Matcher m = TIMESTAMP_14.matcher(uri.getPath() != null ? uri.getPath() : uri.toString());
            if (!m.find())
                continue;
            try {
                java.time.LocalDateTime t = java.time.LocalDateTime.parse(m.group(1), java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                out.add(t.toInstant(java.time.ZoneOffset.UTC).toEpochMilli());
            } catch (Exception ignore) {
                // unparsable timestamp substring — skip rather than misplace the marker
            }
        }
        return out;
    }

    // Available (downloaded) → solid bar; present-but-not-downloaded → dashed line.
    private static void drawSegment(Graphics2D g, int x0, int x1, int yMid, Color color, boolean complete, int left, int right) {
        int a = Math.max(x0, left);
        int b = Math.min(Math.max(x1, x0 + 1), right);
        g.setColor(color);
        if (complete) {
            g.setStroke(new BasicStroke(Math.max(2f, org.helioviewer.jhv.timelines.draw.DrawConstants.getBarHeight() - 1)));
        } else {
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 150));
            g.setStroke(DASHED);
        }
        g.drawLine(a, yMid, b, yMid);
    }

    private static boolean isComplete(View view, int frame) {
        java.util.concurrent.atomic.AtomicBoolean done = view.getFrameCompletion(frame);
        return done != null && done.get();
    }

    private static long medianCadence(long[] times) {
        if (times.length < 2)
            return 0;
        long[] diffs = new long[times.length - 1];
        for (int i = 1; i < times.length; i++)
            diffs[i - 1] = times[i] - times[i - 1];
        Arrays.sort(diffs);
        return diffs[diffs.length / 2];
    }

    @Override
    public YAxis getYAxis() {
        return yAxis;
    }

    @Override
    public void fetchData(TimeAxis selectedAxis) {} // data comes from the loaded image layers

    @Override
    public void remove() {
        repaint.stop();
    }

    @Override
    public String getName() {
        return "Dataset Coverage";
    }

    @Nullable
    @Override
    public Color getDataColor() {
        return null;
    }

    @Override
    public boolean isDownloading() {
        return false;
    }

    @Nullable
    @Override
    public JPanel getOptionsPanel() {
        return null;
    }

    @Override
    public boolean hasData() {
        return !Layers.getImageLayers().isEmpty();
    }

    @Override
    public boolean isDeletable() {
        return false; // a built-in default track; always present
    }

    @Override
    public boolean showYAxis() {
        return false;
    }

}
