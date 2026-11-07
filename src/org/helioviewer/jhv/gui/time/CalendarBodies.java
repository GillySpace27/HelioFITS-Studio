package org.helioviewer.jhv.gui.time;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;
import javax.swing.SwingUtilities;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.identify.FieldChoice;
import org.helioviewer.jhv.identify.IdentifySettings;
import org.helioviewer.jhv.identify.TransitIndex;

/**
 * Which bodies are in a field on each day of the month the calendar shows, and the switch that
 * hides days with them: "a date without the Moon". From the ephemeris (TransitIndex), so it
 * answers for days nothing has been downloaded for.
 */
final class CalendarBodies {

    enum Hide {
        NONE("No days"), MOON("Moon days"), ANY("Any body");

        final String label;

        Hide(String _label) {
            label = _label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static final String HIDE_KEY = "identify.calendar.hide";

    /** Short names under a day number; full names are in the cell's tooltip. */
    private static final Map<String, String> SHORT = Map.of("Mercury", "Me", "Venus", "V", "Earth", "E", "Moon", "Mo",
            "Mars", "Ma", "Jupiter", "J", "Saturn", "S", "Uranus", "U", "Neptune", "N", "Pluto", "P");

    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Calendar bodies");
        t.setDaemon(true);
        return t;
    });

    private final Runnable repaint;
    private final Map<String, Map<LocalDate, Set<String>>> cache = new LinkedHashMap<>(16, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<LocalDate, Set<String>>> eldest) {
            return size() > 24;
        }
    };
    @Nullable
    private String pending;

    CalendarBodies(Runnable _repaint) {
        repaint = _repaint;
    }

    static Hide hide() {
        try {
            String v = Settings.getProperty(HIDE_KEY);
            return v == null ? Hide.NONE : Hide.valueOf(v);
        } catch (IllegalArgumentException e) {
            return Hide.NONE;
        }
    }

    /** The bodies in the field on {@code day}, or null when off or not yet computed (a computation is started). */
    @Nullable
    Set<String> on(LocalDate day) {
        Request r = request(FieldChoice.get(), YearMonth.from(day));
        if (r == null)
            return null;
        Map<LocalDate, Set<String>> month = cache.get(r.key());
        if (month == null) {
            start(r);
            return null;
        }
        return month.getOrDefault(day, Set.of());
    }

    /** Whether the hide switch removes this day. */
    boolean hidden(LocalDate day) {
        Hide h = hide();
        if (h == Hide.NONE)
            return false;
        Set<String> b = on(day);
        return b != null && (h == Hide.ANY ? !b.isEmpty() : b.contains("Moon"));
    }

    /** One colour per body for the dots under a day: the Moon white, the planets as the planet markers draw them. */
    static java.awt.Color color(String body) {
        if (body.equals("Moon"))
            return java.awt.Color.WHITE;
        org.helioviewer.jhv.astronomy.SpaceObject o = org.helioviewer.jhv.astronomy.SpaceObject.get(body);
        if (o == null)
            return java.awt.Color.GRAY;
        byte[] c = o.getColor();
        return new java.awt.Color(c[0] & 0xff, c[1] & 0xff, c[2] & 0xff);
    }

    static String glyphs(Set<String> bodies) {
        StringBuilder sb = new StringBuilder();
        for (String b : bodies) {
            if (!sb.isEmpty())
                sb.append(' ');
            sb.append(SHORT.getOrDefault(b, b));
        }
        return sb.toString();
    }

    private record Request(String key, TransitIndex.Observer observer, TransitIndex.Field field, long start, long end) {}

    @Nullable
    private static Request request(FieldChoice f, YearMonth ym) {
        FieldChoice.Resolved r = f.resolve();
        if (r == null)
            return null;
        long start = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long end = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        return new Request(r.key() + ym, r.observer(), r.field(), start, end);
    }

    private void start(Request r) {
        if (r.key().equals(pending))
            return;
        pending = r.key();
        boolean pluto = IdentifySettings.pluto();
        worker.execute(() -> {
            Map<LocalDate, Set<String>> days = TransitIndex.byDay(
                    TransitIndex.compute(r.observer(), r.field(), r.start(), r.end(), 3_600_000, pluto));
            SwingUtilities.invokeLater(() -> {
                cache.put(r.key(), days);
                if (r.key().equals(pending))
                    pending = null;
                repaint.run();
            });
        });
    }
}
