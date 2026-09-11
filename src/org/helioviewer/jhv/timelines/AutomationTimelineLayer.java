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
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

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
    @Nullable private double[] frozen; // the vertical scale held still for the duration of a drag
    @Nullable private JPanel options;

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

        Strip s = strip(graphArea);
        if (s == null) // the plot is 50px tall by default; deeper lanes need the splitter dragged
            return;
        int yTop = s.yTop(), yBot = s.yBot();
        double[] r = range();
        double lo = r[0], hi = r[1];

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

    /** This lane's own horizontal band of the shared plot rectangle, or null when it will not fit. */
    private record Strip(int yTop, int yBot) {}

    @Nullable
    private Strip strip(Rectangle graphArea) {
        int yTop = graphArea.y + TOP_OFFSET + laneIndex() * LANE_H;
        int yBot = yTop + LANE_H - LANE_GAP;
        return yBot > graphArea.y + graphArea.height ? null : new Strip(yTop, yBot);
    }

    /**
     * The lane's vertical scale as {lo, hi}: the track's own extremes, or the range frozen at the
     * start of a drag.
     *
     * <p>Frozen, because the scale is derived from the keys and a drag moves a key. Recomputed
     * live, dragging a key upward would raise the maximum, which rescales the lane, which moves
     * the key back down under the cursor: the curve squirms and the point will not follow the
     * mouse. The frozen range is padded, so a drag can still push a key past the old extremes,
     * and the lane rescales once on release.
     */
    private double[] range() {
        if (frozen != null)
            return frozen;
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
        return new double[]{lo, hi};
    }

    private static int yFor(double v, double lo, double hi, int yTop, int yBot) {
        double f = (v - lo) / (hi - lo);
        return (int) Math.round(yBot - f * (yBot - yTop));
    }

    private static double valueFor(int py, double lo, double hi, int yTop, int yBot) {
        double f = (yBot - py) / (double) (yBot - yTop);
        return lo + Math.clamp(f, 0, 1) * (hi - lo);
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
        if (options == null)
            options = buildOptions();
        return options;
    }

    /**
     * The selected row's panel: the value at the playhead, and a way back to a constant.
     *
     * <p>This is where the live number lives, and the reason it is here rather than drawn in the
     * lane. The lane draws into the plot's cached image, which is rebuilt only when something sets
     * DrawController's redraw flag, and a time change does not: the movie line is composited over
     * the cached image afterwards. A number printed in the lane therefore showed whatever the
     * value had been when the plot last happened to redraw (measured 2026-09-08: 0.99 displayed
     * while the track's value was 0.33, unchanged across two seeks). A JLabel repaints itself.
     *
     * <p>The timer runs only while the panel is on screen, which is only while this row is the
     * selected one, so there is nothing to unregister when the lane goes.
     */
    private JPanel buildOptions() {
        JPanel panel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 8, 2));
        JLabel value = new JLabel(" ");
        panel.add(new JLabel("At playhead:"));
        panel.add(value);

        JButton flatten = new JButton("Flatten to constant");
        flatten.setToolTipText("Replace the curve with its value at the playhead");
        flatten.addActionListener(e -> {
            long t = Player.getTime().milli;
            double v = track.valueAt(t);
            if (!Double.isNaN(v)) {
                track.flatten(t, v);
                frozen = null;
                DrawController.drawRequest();
            }
        });
        panel.add(flatten);

        Timer tick = new Timer(200, e -> {
            double v = track.valueAt(Player.getTime().milli);
            value.setText(Double.isNaN(v) ? "\u2014" : format(v));
        });
        tick.setRepeats(true);
        panel.addAncestorListener(new javax.swing.event.AncestorListener() {
            @Override
            public void ancestorAdded(javax.swing.event.AncestorEvent e) {
                tick.start();
            }

            @Override
            public void ancestorRemoved(javax.swing.event.AncestorEvent e) {
                tick.stop();
            }

            @Override
            public void ancestorMoved(javax.swing.event.AncestorEvent e) {}
        });
        return panel;
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

    // -- editing --------------------------------------------------------------------------------
    //
    // Every gesture on a lane is gated by a hit test taken at PRESS time, synchronously, from the
    // press point. Both halves of that matter.
    //
    // At press time, because ChartDrawGraphPane gives any unmodified press DragMode.MOVIELINE and
    // then seeks the movie on every drag event. Deciding later that the press was really on a key
    // would already have scrubbed the movie out from under it.
    //
    // Synchronously, because the plot's existing notion of what is under the mouse is not fresh
    // enough to drag with: EventTimelineLayer computes it as a side effect of draw, and the graph
    // image is only rebuilt when DrawController's flag is flushed by the UITimer's 10 Hz poll, so
    // a hit test built the same way would trail the cursor by up to 100 ms.

    private static final int GRAB = 4; // pixels; how near a key or a curve counts as being on it

    /** What a press landed on: a key of {@code lane} by index, or its curve when {@code keyIndex} is -1. */
    public record Hit(AutomationTimelineLayer lane, int keyIndex) {
        public boolean onKey() {
            return keyIndex >= 0;
        }
    }

    /** The lane and key under {@code p}, or null if the press was not on one. */
    @Nullable
    public static Hit hitTest(Point p) {
        Rectangle graphArea = DrawController.getGeometry().area();
        TimeAxis.Mapper x = DrawController.selectedAxis.mapper(graphArea.x, graphArea.width);
        for (AutomationTimelineLayer lane : lanes()) {
            if (!lane.enabled || lane.track.isEmpty())
                continue;
            Strip s = lane.strip(graphArea);
            if (s == null || p.y < s.yTop() - GRAB || p.y > s.yBot() + GRAB)
                continue;
            double[] r = lane.range();

            List<Track.Key> keys = lane.track.getKeys();
            for (int i = 0; i < keys.size(); i++) {
                Track.Key k = keys.get(i);
                if (Math.abs(x.toPixel(k.time()) - p.x) <= GRAB
                        && Math.abs(yFor(k.value(), r[0], r[1], s.yTop(), s.yBot()) - p.y) <= GRAB)
                    return new Hit(lane, i);
            }
            double v = lane.track.valueAt(x.toValue(p.x));
            if (!Double.isNaN(v) && Math.abs(yFor(v, r[0], r[1], s.yTop(), s.yBot()) - p.y) <= GRAB)
                return new Hit(lane, -1);
        }
        return null;
    }

    /**
     * A drag in progress on one lane.
     *
     * <p>A press on a key moves that key in time and value. A press on the curve between two keys
     * moves the whole segment in value, both of its bounding keys together, and leaves their times
     * alone: sliding a segment sideways would be a different edit with no obvious meaning, since
     * the two keys would have to pass their neighbours.
     */
    public static final class Drag {

        private final AutomationTimelineLayer lane;
        private final int[] indices;      // the key, or the segment's two bounding keys
        private final boolean moveInTime; // only a press on a key moves anything in time
        private final double[] startValues;
        private final int startY;

        private Drag(AutomationTimelineLayer _lane, int[] _indices, boolean _moveInTime, int _startY) {
            lane = _lane;
            indices = _indices;
            moveInTime = _moveInTime;
            startY = _startY;
            startValues = new double[indices.length];
            List<Track.Key> keys = lane.track.getKeys();
            for (int i = 0; i < indices.length; i++)
                startValues[i] = keys.get(indices[i]).value();
        }

        /**
         * @param snap whether the key's time lands on the nearest frame. Shift turns it off: the
         *             curve is only ever sampled at frame times, so an unsnapped key is a value
         *             the renderer will never be asked for, which is occasionally what you want
         *             while shaping a curve and never what you want when you are done.
         */
        public void update(Point p, boolean snap) {
            Rectangle graphArea = DrawController.getGeometry().area();
            Strip s = lane.strip(graphArea);
            if (s == null)
                return;
            double[] r = lane.range();
            double dv = valueFor(p.y, r[0], r[1], s.yTop(), s.yBot())
                    - valueFor(startY, r[0], r[1], s.yTop(), s.yBot());

            if (moveInTime) {
                TimeAxis.Mapper x = DrawController.selectedAxis.mapper(graphArea.x, graphArea.width);
                long t = x.toValue(Math.clamp(p.x, graphArea.x, graphArea.x + graphArea.width));
                if (snap)
                    t = Player.snapToFrame(t);
                // moveKey re-sorts, so the index is only valid until the next move: follow it.
                indices[0] = lane.track.moveKey(indices[0], t, clampToRange(startValues[0] + dv, r));
            } else {
                for (int i = 0; i < indices.length; i++)
                    lane.track.shiftValue(indices[i], clampToRange(startValues[i] + dv, r));
            }
            // The plot is a cached image and an edit dirties nothing on its own. This redraws
            // every timeline layer, a radio spectrogram included, once per mouse event: the price
            // of a curve that follows the cursor, and the reason GRAB is generous enough that a
            // drag rarely has to be restarted.
            DrawController.drawRequest();
        }

        public void end() {
            lane.frozen = null; // the lane rescales to the new extremes, once
            DrawController.drawRequest();
        }

        private static double clampToRange(double v, double[] r) {
            return Math.clamp(v, r[0], r[1]);
        }
    }

    /** Freezes the lane's scale and works out what this press is dragging. Null if it drags nothing. */
    @Nullable
    public static Drag beginDrag(Hit hit, Point p) {
        AutomationTimelineLayer lane = hit.lane();
        double[] r = lane.range();
        double pad = (r[1] - r[0]) * 0.25; // room to push a key past the old extremes without rescaling under it
        lane.frozen = new double[]{r[0] - pad, r[1] + pad};

        if (hit.onKey())
            return new Drag(lane, new int[]{hit.keyIndex()}, true, p.y);

        int[] segment = lane.segmentAt(p);
        if (segment == null) {
            lane.frozen = null;
            return null;
        }
        return new Drag(lane, segment, false, p.y);
    }

    /**
     * The keys bounding the curve under {@code p}: two of them, or one in the flat regions before
     * the first key and after the last, where the curve is that key's value clamped.
     */
    @Nullable
    private int[] segmentAt(Point p) {
        Rectangle graphArea = DrawController.getGeometry().area();
        long t = DrawController.selectedAxis.mapper(graphArea.x, graphArea.width).toValue(p.x);
        List<Track.Key> keys = track.getKeys();
        if (keys.isEmpty())
            return null;
        if (t <= keys.getFirst().time())
            return new int[]{0};
        if (t >= keys.getLast().time())
            return new int[]{keys.size() - 1};
        for (int i = 0; i + 1 < keys.size(); i++)
            if (keys.get(i).time() <= t && t < keys.get(i + 1).time())
                return new int[]{i, i + 1};
        return null;
    }

    /** Double-click on the curve: a key there, holding the value the curve already has. */
    public static void insertKeyAt(Hit hit, Point p) {
        AutomationTimelineLayer lane = hit.lane();
        Rectangle graphArea = DrawController.getGeometry().area();
        long t = Player.snapToFrame(DrawController.selectedAxis.mapper(graphArea.x, graphArea.width).toValue(p.x));
        double v = lane.track.valueAt(t);
        if (Double.isNaN(v))
            return;
        // The interpolation of the segment it is being inserted into, so splitting a segment does
        // not change the shape of the curve it was split out of.
        Track.Interp interp = Track.Interp.LINEAR;
        List<Track.Key> keys = lane.track.getKeys();
        for (Track.Key k : keys)
            if (k.time() <= t)
                interp = k.interp();
        lane.track.put(new Track.Key(t, v, interp));
        DrawController.drawRequest();
    }

    /** Double-click on a key: gone, unless it is the last one holding the track up. */
    public static void deleteKey(Hit hit) {
        if (hit.lane().track.removeKey(hit.keyIndex()))
            DrawController.drawRequest();
    }

    /** Right-click on a key: how the segment leaving it reaches the next one. */
    public static void setInterp(Hit hit, Track.Interp interp) {
        hit.lane().track.setInterp(hit.keyIndex(), interp);
        DrawController.drawRequest();
    }

    public Track.Interp interpAt(int keyIndex) {
        return track.getKeys().get(keyIndex).interp();
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
