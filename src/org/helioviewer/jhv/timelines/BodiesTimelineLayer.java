package org.helioviewer.jhv.timelines;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.helioviewer.jhv.identify.FieldChoice;
import org.helioviewer.jhv.identify.IdentifySettings;
import org.helioviewer.jhv.identify.TransitIndex;
import org.helioviewer.jhv.timelines.draw.DrawController;
import org.helioviewer.jhv.timelines.draw.TimeAxis;
import org.helioviewer.jhv.timelines.draw.YAxis;

import org.json.JSONObject;

/**
 * A row per body showing when it is in a field of view: the Moon passing through PUNCH, a planet
 * through LASCO C3. From the ephemeris (TransitIndex), so it shows for times with no data loaded.
 * The field is the one the calendar marks (FieldChoice); off until one is chosen.
 */
// has to be public for state restore
public final class BodiesTimelineLayer extends TimelineLayer {

    private static final Color COLOR = new Color(200, 200, 140);
    private static final int LANE_GAP = 4;
    private static final int TOP_OFFSET = 4;
    private static final long MAX_SPAN = 2L * 366 * 86_400_000;

    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Bodies in field");
        t.setDaemon(true);
        return t;
    });

    @Nullable
    private String shownKey;
    @Nullable
    private String pendingKey;
    private Map<String, List<TransitIndex.Interval>> shown = Map.of();
    private final JPanel options = new JPanel(new FlowLayout(FlowLayout.LEADING));

    public BodiesTimelineLayer() {
        enabled = false; // a choice to make, not a default track
        JComboBox<FieldChoice> field = new JComboBox<>(FieldChoice.values());
        field.setSelectedItem(FieldChoice.get());
        field.setToolTipText("The field whose bodies this track shows; the date picker's calendar uses the same one");
        field.addActionListener(e -> {
            FieldChoice.set((FieldChoice) field.getSelectedItem());
            DrawController.drawRequest();
        });
        IdentifySettings.addListener(() -> {
            if (field.getSelectedItem() != FieldChoice.get())
                field.setSelectedItem(FieldChoice.get());
        });
        options.add(new JLabel("Field:"));
        options.add(field);
    }

    public static TimelineLayer deserialize(JSONObject jo) { // for state restore
        BodiesTimelineLayer l = new BodiesTimelineLayer();
        l.enabled = jo.optBoolean("enabled", false);
        return l;
    }

    @Override
    public void serialize(JSONObject jo) {
        jo.put("enabled", enabled);
    }

    @Override
    public void draw(Graphics2D g, Rectangle graphArea, TimeAxis xAxis, Point mousePosition) {
        if (!enabled)
            return;
        FieldChoice.Resolved r = FieldChoice.get().resolve();
        if (r == null)
            return;
        long start = xAxis.start(), end = xAxis.end();
        if (end <= start || end - start > MAX_SPAN)
            return;
        // Recompute for the window, widened by half a window either side so a small pan reuses it
        long span = end - start;
        long step = Math.max(60_000, span / 600);
        String key = r.key() + ':' + (start - span / 2) / step + ':' + (end + span / 2) / step + ':' + step;
        if (!key.equals(shownKey) && !key.equals(pendingKey)) {
            pendingKey = key;
            long a = start - span / 2, b = end + span / 2;
            boolean pluto = IdentifySettings.pluto();
            worker.execute(() -> {
                Map<String, List<TransitIndex.Interval>> m = TransitIndex.compute(r.observer(), r.field(), a, b, step, pluto);
                SwingUtilities.invokeLater(() -> {
                    if (!key.equals(pendingKey))
                        return;
                    shown = m;
                    shownKey = key;
                    pendingKey = null;
                    DrawController.drawRequest();
                });
            });
        }

        TimeAxis.Mapper x = xAxis.mapper(graphArea.x, graphArea.width);
        Stroke savedStroke = g.getStroke();
        Font savedFont = g.getFont();
        java.awt.Shape savedClip = g.getClip();
        g.clipRect(graphArea.x, graphArea.y, graphArea.width, graphArea.height);
        g.setFont(savedFont.deriveFont(Font.PLAIN, 9f));
        int labelH = g.getFontMetrics().getHeight();
        int barH = org.helioviewer.jhv.timelines.draw.DrawConstants.getBarHeight();
        int rowH = labelH + barH + LANE_GAP;
        int right = graphArea.x + graphArea.width;
        int lane = 0;
        g.setStroke(new BasicStroke(Math.max(2f, barH - 1)));
        for (Map.Entry<String, List<TransitIndex.Interval>> e : shown.entrySet()) {
            boolean visible = false;
            for (TransitIndex.Interval i : e.getValue())
                if (i.end() >= start && i.start() <= end)
                    visible = true;
            if (!visible)
                continue;
            int yTop = graphArea.y + TOP_OFFSET + lane * rowH;
            int yBar = yTop + labelH + barH / 2;
            lane++;
            g.setColor(COLOR);
            g.drawString(e.getKey() + " in " + FieldChoice.get(), graphArea.x + 2, yTop + labelH - 2);
            for (TransitIndex.Interval i : e.getValue()) {
                int x0 = Math.max(graphArea.x, x.toPixel(i.start()));
                int x1 = Math.min(right, Math.max(x0 + 1, x.toPixel(i.end())));
                if (x1 >= graphArea.x && x0 <= right)
                    g.drawLine(x0, yBar, x1, yBar);
            }
        }
        g.setStroke(savedStroke);
        g.setFont(savedFont);
        g.setClip(savedClip);
    }

    @Override
    public YAxis getYAxis() {
        return null;
    }

    @Override
    public void fetchData(TimeAxis selectedAxis) {} // computed in draw, for the window on show

    @Override
    public void remove() {}

    @Override
    public String getName() {
        return "Bodies in Field";
    }

    @Nullable
    @Override
    public Color getDataColor() {
        return null;
    }

    @Override
    public boolean isDownloading() {
        return pendingKey != null;
    }

    @Nullable
    @Override
    public JPanel getOptionsPanel() {
        return options;
    }

    @Override
    public boolean hasData() {
        return FieldChoice.get() != FieldChoice.OFF;
    }

    @Override
    public boolean isDeletable() {
        return false;
    }
}
