package org.helioviewer.jhv.timelines;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.swing.JPanel;

import org.helioviewer.jhv.timelines.draw.DrawConstants;
import org.helioviewer.jhv.timelines.draw.DrawController;
import org.helioviewer.jhv.timelines.draw.TimeAxis;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Labelled lanes of time intervals above the stacked plot: one lane per kind of event (say ICME,
 * SIR, HSS), each interval a coloured bar with an optional short label inside it. They share the
 * plot's time axis, so an event lines up with the curves below it.
 *
 * <p>Loaded from a JSON request under "org.helioviewer.jhv.request.intervals" (see
 * docs/timeline-intervals.md); has to be public for state restore.
 */
public final class IntervalTimelineLayer extends TimelineLayer {

    public record Interval(String lane, long start, long end, String label) {}

    private static final int LANE_H = 14;
    private static final int LANE_GAP = 2;
    private static final Color[] PALETTE = {
            new Color(214, 40, 130), new Color(232, 150, 40), new Color(120, 80, 200),
            new Color(40, 150, 190), new Color(90, 170, 70), new Color(200, 70, 60)};

    private final String name;
    private final List<Interval> intervals;
    private final List<String> lanes = new ArrayList<>(); // first appearance order
    private final Map<String, Color> colors = new LinkedHashMap<>();

    public IntervalTimelineLayer(String _name, List<Interval> _intervals) {
        name = _name;
        intervals = List.copyOf(_intervals);
        for (Interval i : intervals) {
            if (!colors.containsKey(i.lane)) {
                colors.put(i.lane, PALETTE[lanes.size() % PALETTE.length]);
                lanes.add(i.lane);
            }
        }
    }

    /** From the request JSON: {"name": "...", "intervals": [{"lane", "start", "end", "label"?}]}, times ISO 8601. */
    public static IntervalTimelineLayer fromJson(JSONObject jo) {
        JSONArray ja = jo.optJSONArray("intervals");
        if (ja == null)
            throw new IllegalArgumentException("Missing intervals: " + jo.optString("name"));
        List<Interval> list = new ArrayList<>();
        for (int i = 0; i < ja.length(); i++) {
            JSONObject o = ja.getJSONObject(i);
            long start = parseTime(o.optString("start"));
            long end = parseTime(o.optString("end"));
            if (start == Long.MIN_VALUE || end == Long.MIN_VALUE || end < start)
                throw new IllegalArgumentException("Bad interval " + i + " in " + jo.optString("name") + ": " + o);
            list.add(new Interval(o.optString("lane", "Events"), start, end, o.optString("label", "")));
        }
        return new IntervalTimelineLayer(jo.optString("name", "Intervals"), list);
    }

    // UTC: "2026-04-24T00:00:00", the same with a trailing Z, or a bare date. Plain java.time, no SPICE needed.
    private static long parseTime(String s) {
        try {
            if (s.endsWith("Z"))
                return java.time.Instant.parse(s).toEpochMilli();
            if (s.length() == 10)
                return java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
            return java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        } catch (java.time.format.DateTimeParseException e) {
            return Long.MIN_VALUE;
        }
    }

    public static TimelineLayer deserialize(JSONObject jo) { // for state restore
        return fromJson(jo);
    }

    @Override
    public void serialize(JSONObject jo) {
        jo.put("name", name);
        JSONArray ja = new JSONArray();
        for (Interval i : intervals) {
            JSONObject o = new JSONObject().put("lane", i.lane).put("start", java.time.Instant.ofEpochMilli(i.start).toString()).put("end", java.time.Instant.ofEpochMilli(i.end).toString());
            if (!i.label.isEmpty())
                o.put("label", i.label);
            ja.put(o);
        }
        jo.put("intervals", ja);
    }

    public List<Interval> getIntervals() {
        return intervals;
    }

    public List<String> getLanes() {
        return lanes;
    }

    @Override
    public int reservedTop() {
        return enabled && !lanes.isEmpty() ? lanes.size() * (LANE_H + LANE_GAP) + LANE_GAP : 0;
    }

    @Override
    public void draw(Graphics2D g, Rectangle graphArea, TimeAxis timeAxis, Point mousePosition) {
        Rectangle band = DrawController.getGeometry().reservedArea(this);
        if (!enabled || band == null || band.isEmpty())
            return;

        java.awt.Shape clip = g.getClip();
        g.setClip(new Rectangle(0, band.y, band.x + band.width + DrawConstants.GRAPH_RIGHT_SPACE, band.height)); // the lane names sit left of the plot
        g.setFont(DrawConstants.font);
        FontMetrics fm = g.getFontMetrics();
        TimeAxis.Mapper mapper = DrawController.getGeometry().xMapper(timeAxis);

        int y = band.y + LANE_GAP;
        for (String lane : lanes) {
            Color color = colors.get(lane);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 28));
            g.fillRect(band.x, y, band.width, LANE_H);
            g.setColor(color);
            g.drawString(lane, Math.max(2, band.x - fm.stringWidth(lane) - 4), y + LANE_H - 3);
            for (Interval i : intervals) {
                if (!i.lane.equals(lane) || i.end < timeAxis.start() || i.start > timeAxis.end())
                    continue;
                int x1 = Math.max(band.x, mapper.toPixel(i.start));
                int x2 = Math.min(band.x + band.width, mapper.toPixel(i.end));
                int w = Math.max(2, x2 - x1); // a short event still shows
                g.setColor(color);
                g.fillRect(x1, y, w, LANE_H);
                if (!i.label.isEmpty() && fm.stringWidth(i.label) + 4 <= w) {
                    g.setColor(Color.WHITE);
                    g.drawString(i.label, x1 + (w - fm.stringWidth(i.label)) / 2, y + LANE_H - 3);
                }
            }
            y += LANE_H + LANE_GAP;
        }
        g.setClip(clip);
    }

    /** The interval under the pointer, for the value readout. */
    @Nullable
    @Override
    public String getStringValue(long ts) {
        for (Interval i : intervals) {
            if (ts >= i.start && ts <= i.end)
                return i.lane + (i.label.isEmpty() ? "" : " " + i.label);
        }
        return null;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void remove() {}

    @Override
    @Nullable
    public Color getDataColor() {
        return colors.isEmpty() ? null : colors.values().iterator().next();
    }

    @Override
    public boolean isDownloading() {
        return false;
    }

    @Override
    public boolean hasData() {
        return !intervals.isEmpty();
    }

    @Override
    @Nullable
    public JPanel getOptionsPanel() {
        return null;
    }

    @Override
    public boolean isDeletable() {
        return true;
    }

    @Override
    public void fetchData(TimeAxis selectedAxis) {}

}
