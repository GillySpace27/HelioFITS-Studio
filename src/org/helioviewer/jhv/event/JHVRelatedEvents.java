package org.helioviewer.jhv.event;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.time.Interval;

public class JHVRelatedEvents {

    private static final Colors.Data eventColors = new Colors.Data();

    private final ArrayList<JHVEvent> events = new ArrayList<>();
    private final HashMap<Integer, JHVEvent> eventsById = new HashMap<>();
    private final List<JHVEvent.Link> associations = new ArrayList<>();
    private final Color color;

    private Interval interval;
    private boolean highlighted;

    public JHVRelatedEvents(JHVEvent event) {
        this(event, eventColors.getNextColor());
    }

    public JHVRelatedEvents(JHVEvent event, Color _color) {
        color = _color;
        addEvent(event);
        interval = new Interval(event.start, event.end);
    }

    public List<JHVEvent> getEvents() {
        return events;
    }

    public long getEnd() {
        return interval.end();
    }

    public long getStart() {
        return interval.start();
    }

    public Color getColor() {
        return color;
    }

    public boolean isHighlighted() {
        return highlighted;
    }

    boolean highlight(boolean isHighlighted) {
        if (isHighlighted == highlighted)
            return false;
        highlighted = isHighlighted;
        return true;
    }

    public JHVEvent getClosestTo(long timestamp) {
        for (JHVEvent event : events) {
            if (event.start <= timestamp && timestamp <= event.end) return event;
        }
        return events.isEmpty() ? null : events.getFirst();
    }

    public List<JHVEvent> getAssociatedEvents(JHVEvent event) {
        int id = event.getUniqueID();
        List<JHVEvent> result = new ArrayList<>();
        for (JHVEvent.Link link : associations) {
            int target;
            if (link.firstId() == id)
                target = link.secondId();
            else if (link.secondId() == id)
                target = link.firstId();
            else
                continue;

            JHVEvent found = eventsById.get(target);
            if (found != null)
                result.add(found);
        }
        return result;
    }

    void addAssociation(JHVEvent.Link link) {
        if (!associations.contains(link))
            associations.add(link);
    }

    List<JHVEvent.Link> getAssociations() {
        return associations;
    }

    private void addEvent(JHVEvent event) {
        events.add(event);
        eventsById.put(event.getUniqueID(), event);
    }

    void swapEvent(JHVEvent event) {
        events.removeIf(e -> e.getUniqueID() == event.getUniqueID());
        eventsById.put(event.getUniqueID(), event);
        events.add(event);
        long start = Long.MAX_VALUE, end = Long.MIN_VALUE;
        for (JHVEvent evt : events) {
            start = Math.min(start, evt.start);
            end = Math.max(end, evt.end);
        }
        interval = new Interval(start, end);
    }

    void merge(JHVRelatedEvents found) {
        events.addAll(found.events);
        eventsById.putAll(found.eventsById);
        found.associations.forEach(this::addAssociation);
        interval = new Interval(Math.min(interval.start(), found.interval.start()), Math.max(interval.end(), found.interval.end()));
    }

}
