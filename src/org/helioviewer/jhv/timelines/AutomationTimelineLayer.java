package org.helioviewer.jhv.timelines;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.JPanel;

import org.helioviewer.jhv.automation.Automation;
import org.helioviewer.jhv.automation.Track;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.plugins.PluginManager;
import org.helioviewer.jhv.plugins.eve.EVEPlugin;
import org.helioviewer.jhv.timelines.draw.DrawController;
import org.helioviewer.jhv.timelines.draw.TimeAxis;
import org.helioviewer.jhv.timelines.draw.YAxis;
import org.helioviewer.jhv.timelines.draw.YAxis.YAxisIdentityScale;

import org.json.JSONObject;

/**
 * The editable lane for one animated parameter, read-only for now: it draws the curve, its keys
 * and the value at the playhead, and carries the panel's enable checkbox and delete column.
 *
 * <p>The lane is the panel's <em>view</em> of a {@link Track}; the track itself lives in
 * {@link Automation} and is stored in the session's "automation" object, not in "timelines".
 * A session opened with the Timelines panel disabled therefore still animates and still records,
 * it just has nowhere to edit the curve. State.saveTimelineState skips these layers for the same
 * reason: two copies of one track would be one copy too many, and the "timelines" array is not
 * read back at all when the plugin is inactive.
 *
 * <p>It draws inside its own horizontal strip of the shared plot rectangle, the way
 * {@link CoverageTimelineLayer} bottom-justifies its coverage rows: clip to graphArea, compute y
 * from a strip index, and do not use GraphGeometry.yMapper, which spans the whole plot height.
 * {@link #showYAxis()} is false because every layer that answers true takes 30 pixels of plot
 * width for its axis, and eight animated parameters would eat 240 of them; the lane prints its own
 * range at its left edge instead.
 */
public final class AutomationTimelineLayer extends AbstractTimelineLayer {

    private static final int LANE_H = 30;      // automation stacks DOWN from the top; coverage fills UP from the bottom
    private static final int LANE_GAP = 4;
    private static final int TOP_OFFSET = 2;
    private static final Color CURVE = new Color(255, 200, 90);
    private static final Color KEY = new Color(255, 235, 190);
    private static final Color BASE = new Color(120, 120, 120, 120);
    private static final Stroke CURVE_STROKE = new BasicStroke(1.6f);

    private final YAxis yAxis = new YAxis(0, 0, new YAxisIdentityScale("Automation"));
    private final Track track;

    public AutomationTimelineLayer(Track _track) {
        track = _track;
        enabled = _track.isEnabled();
    }

    public Track getTrack() {
        return track;
    }

    @Override
    public void setEnabled(boolean _enabled) {
        super.setEnabled(_enabled);
        track.setEnabled(_enabled); // un-ticking the row stops the track applying, leaving the parameter where it was
    }

    @Override
    public void draw(Graphics2D g, Rectangle graphArea, TimeAxis xAxis, Point mousePosition) {
        if (!enabled || track.isEmpty())
            return;

        int index = laneIndex();
        int yTop = graphArea.y + TOP_OFFSET + index * LANE_H;
        int yBot = yTop + LANE_H - LANE_GAP;
        if (yBot > graphArea.y + graphArea.height) // the plot is 50px tall by default; deeper lanes need the splitter dragged
            return;

        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Track.Key k : track.getKeys()) {
            lo = Math.min(lo, k.value());
            hi = Math.max(hi, k.value());
        }
        if (hi - lo < 1e-12) { // a flat track sits in the middle of its lane rather than on an edge
            double pad = Math.max(Math.abs(hi), 1) * 0.5;
            lo -= pad;
            hi += pad;
        }

        Shape savedClip = g.getClip();
        Stroke savedStroke = g.getStroke();
        Font savedFont = g.getFont();
        g.clipRect(graphArea.x, graphArea.y, graphArea.width, graphArea.height); // never spill past the plot

        g.setColor(BASE);
        g.drawLine(graphArea.x, yBot, graphArea.x + graphArea.width, yBot);

        // Sampled per pixel rather than drawn key to key: one loop renders HOLD steps, LINEAR
        // segments and SMOOTH eases correctly, and the clamp outside the key range comes free.
        TimeAxis.Mapper x = xAxis.mapper(graphArea.x, graphArea.width);
        int right = graphArea.x + graphArea.width;
        g.setColor(CURVE);
        g.setStroke(CURVE_STROKE);
        int prevX = Integer.MIN_VALUE, prevY = 0;
        for (int px = graphArea.x; px <= right; px++) {
            double v = track.valueAt(x.toValue(px));
            if (Double.isNaN(v))
                continue;
            int py = yFor(v, lo, hi, yTop, yBot);
            if (prevX != Integer.MIN_VALUE)
                g.drawLine(prevX, prevY, px, py);
            prevX = px;
            prevY = py;
        }

        g.setColor(KEY);
        for (Track.Key k : track.getKeys()) {
            int kx = x.toPixel(k.time());
            if (kx < graphArea.x - 4 || kx > right + 4)
                continue;
            int ky = yFor(k.value(), lo, hi, yTop, yBot);
            g.fillRect(kx - 2, ky - 2, 5, 5);
        }

        g.setFont(savedFont.deriveFont(Font.PLAIN, 9f));
        int baseline = yTop + g.getFontMetrics().getAscent();
        g.setColor(CURVE);
        g.drawString(getName(), graphArea.x + 2, baseline);
        // The lane's own range, in place of a y-axis. Both ends are static, which is the point:
        // this layer draws into the plot's CACHED image, which is rebuilt only when something
        // sets DrawController's redraw flag. A time change does not -- the movie line is
        // composited over the cached image afterwards -- so a live "value at the playhead"
        // printed here showed the value from whenever the plot last happened to redraw. Measured
        // 2026-09-08: 0.99 displayed while the track's actual value was 0.33, and unchanged
        // across two seeks. Forcing a repaint per frame would redraw every timeline layer,
        // including a radio spectrogram, on every frame. The movie line crossing the curve is
        // the live readout; the number belongs in the selected-row options panel, which Swing
        // repaints on its own.
        g.setColor(BASE);
        g.drawString(format(hi), graphArea.x + 2, baseline + 9);
        g.drawString(format(lo), graphArea.x + 2, yBot - 1);

        g.setClip(savedClip);
        g.setStroke(savedStroke);
        g.setFont(savedFont);
    }

    private static int yFor(double v, double lo, double hi, int yTop, int yBot) {
        double f = (v - lo) / (hi - lo);
        return (int) Math.round(yBot - f * (yBot - yTop));
    }

    private static String format(double v) {
        return Math.abs(v) >= 1000 || (v != 0 && Math.abs(v) < 0.01) ? String.format("%.3g", v) : String.format("%.3f", v);
    }

    // Registration order among the automation lanes only, so adding a spectrogram or a band curve
    // does not shuffle the parameter lanes down the plot.
    private int laneIndex() {
        int i = 0;
        for (TimelineLayer tl : TimelineLayers.get()) {
            if (tl == this)
                return i;
            if (tl instanceof AutomationTimelineLayer)
                i++;
        }
        return i;
    }

    @Override
    public String getName() {
        return Automation.labelFor(track.paramKey);
    }

    @Override
    public void remove() {
        Automation.remove(track.paramKey); // the delete column removes the track, not just its view
    }

    @Override
    public void serialize(JSONObject jo) {} // stored in the session's "automation" object instead

    @Override
    public YAxis getYAxis() {
        return yAxis;
    }

    @Override
    public void fetchData(TimeAxis selectedAxis) {} // nothing to fetch: the curve is the data

    @Nullable
    @Override
    public Color getDataColor() {
        return CURVE;
    }

    @Override
    public boolean isDownloading() {
        return false;
    }

    @Nullable
    @Override
    public JPanel getOptionsPanel() {
        return null; // read-only for now; the interpolation default and "flatten to constant" land here
    }

    @Override
    public boolean hasData() {
        return !track.isEmpty();
    }

    @Override
    public boolean isDeletable() {
        return true;
    }

    @Override
    public boolean showYAxis() {
        return false;
    }

    /** Every automation lane currently in the panel, for keeping the lanes and the tracks in step. */
    public static List<AutomationTimelineLayer> lanes() {
        return TimelineLayers.get().stream()
                .filter(AutomationTimelineLayer.class::isInstance)
                .map(AutomationTimelineLayer.class::cast)
                .toList();
    }

    // -- the slider's menu -------------------------------------------------------------------
    //
    // Both halves of arming live here rather than in Automation, because only half of arming is
    // about the model: the track is the animation, the lane is the panel's view of it, and the
    // panel is optional. A build with the Timelines plugin inactive still arms the parameter and
    // still records the curve; it just has nowhere to draw it. State.load makes the same split.

    /** Starts animating the parameter, shows its lane, and opens the plot if it was folded away. */
    public static void arm(String paramKey) {
        Track track = Automation.arm(paramKey, Player.getTime().milli);
        if (track == null)
            return; // already animated, or nothing answers to that key
        if (PluginManager.isActive(EVEPlugin.class)) {
            Timelines.getLayers().add(new AutomationTimelineLayer(track));
            // Otherwise the only feedback for "Animate" is a menu that closed. The lane is the
            // whole promise of the gesture, and it is drawn in a pane that is folded by default.
            MainFrame.getMainContentPanel().revealPlugins();
        }
        DrawController.drawRequest();
    }

    /** A key at the playhead holding the parameter's current value, plus the redraw to show it. */
    public static void writeKeyAndRedraw(String paramKey) {
        if (Automation.writeKey(paramKey, Player.getTime().milli))
            DrawController.drawRequest(); // the plot is a cached image; a new key dirties nothing on its own
    }

    /** Stops animating it: the track and its lane both go, and the parameter stays where it is. */
    public static void disarm(String paramKey) {
        Automation.remove(paramKey);
        for (AutomationTimelineLayer lane : lanes())
            if (lane.track.paramKey.equals(paramKey))
                Timelines.getLayers().remove(lane);
        DrawController.drawRequest();
    }

}
