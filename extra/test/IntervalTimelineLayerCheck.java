package org.helioviewer.jhv.timelines;

import java.awt.Rectangle;
import java.util.List;

import org.helioviewer.jhv.timelines.draw.GraphGeometry;

import org.json.JSONObject;

/**
 * Event-interval lanes: the request JSON becomes lanes in order of first appearance, a bad
 * interval is refused, the layer survives a state round trip, and the rows it reserves sit above
 * the plot area rather than inside it.
 */
public final class IntervalTimelineLayerCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static final String REQUEST = """
            {"name": "IMAP events", "intervals": [
              {"lane": "ICME", "start": "2026-04-24T00:00:00", "end": "2026-04-25T12:00:00", "label": "1"},
              {"lane": "SIR", "start": "2026-04-26T00:00:00", "end": "2026-04-26T12:00:00", "label": "2"},
              {"lane": "ICME", "start": "2026-04-27T00:00:00", "end": "2026-04-27T08:00:00", "label": "2"}]}
            """;

    public static void main(String[] args) {
        if (System.getProperty("user.timezone") == null)
            System.setProperty("user.timezone", "UTC");

        IntervalTimelineLayer layer = IntervalTimelineLayer.fromJson(new JSONObject(REQUEST));
        expect("three intervals read", layer.getIntervals().size() == 3);
        expect("lanes in order of first appearance", layer.getLanes().equals(List.of("ICME", "SIR")));
        expect("a lane's interval has the time it was given", layer.getIntervals().getFirst().end() - layer.getIntervals().getFirst().start() == 36 * 3_600_000L);

        JSONObject saved = new JSONObject();
        layer.serialize(saved);
        IntervalTimelineLayer back = (IntervalTimelineLayer) IntervalTimelineLayer.deserialize(saved);
        expect("a state round trip keeps every interval", back.getIntervals().equals(layer.getIntervals()) && back.getName().equals("IMAP events"));

        boolean refused = false;
        try {
            IntervalTimelineLayer.fromJson(new JSONObject("{\"intervals\": [{\"lane\": \"X\", \"start\": \"2026-04-25T00:00:00\", \"end\": \"2026-04-24T00:00:00\"}]}"));
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        expect("an interval ending before it starts is refused", refused);

        GraphGeometry g = new GraphGeometry();
        g.setSize(800, 500);
        g.layout(List.of(layer));
        Rectangle band = g.reservedArea(layer);
        expect("the layer reserves rows", band != null && band.height == layer.reservedTop() && layer.reservedTop() > 0);
        expect("the rows end where the plot begins", band != null && band.y + band.height <= g.area().y);

        expect("the same request read twice is an equal layer, so it is not added twice",
                IntervalTimelineLayer.fromJson(new JSONObject(REQUEST)).equals(layer));
        expect("overlapping events are all named in the readout", layer.getStringValue(java.time.Instant.parse("2026-04-24T06:00:00Z").toEpochMilli()).equals("ICME 1"));
        expect("a name cannot open an HTML tag", !IntervalTimelineLayer.fromJson(new JSONObject("{\"name\": \"<html><img src=x>\", \"intervals\": []}")).getName().contains("<"));

        layer.setEnabled(false);
        g.layout(List.of(layer));
        expect("a hidden layer gives the space back", g.reservedArea(layer) == null);

        System.exit(failures > 0 ? 1 : 0);
    }
}
