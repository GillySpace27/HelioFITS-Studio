package org.helioviewer.jhv.app.state;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import javax.annotation.Nullable;

import org.json.JSONObject;

/**
 * A scene template's time rule, and the template's description, as the "template" object a
 * template .jhv carries beside the layers (docs/templates.md). Pure: no Swing, no Settings, no clock;
 * the caller passes "now" and, when it knows one, the newest time the scene's data reaches.
 *
 * <ul>
 * <li>{@code fixed}: the dates the scene was saved with, as every session has always opened.
 * <li>{@code latest}: ends at the newest data (or now, when that is not known), starts {@code span} earlier.
 * <li>{@code relative}: ends {@code lag} before now, starts {@code span} earlier.
 * </ul>
 *
 * <p>Parsing is strict: a duration that {@link Duration#parse} refuses, a negative one, a zero span
 * for a moving mode, or a mode not in the list above is an {@link IllegalArgumentException} whose
 * message says which field and what it got.
 */
public final class TemplateTime {

    /** The format version this code writes. A reader takes any version; the file is append-only. */
    public static final int VERSION = 1;

    public enum Mode {
        LATEST, RELATIVE, FIXED;

        /** The word written in the file: "latest", "relative" or "fixed". */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Mode parse(@Nullable String word) {
            if (word != null)
                for (Mode m : values())
                    if (m.word().equals(word))
                        return m;
            throw new IllegalArgumentException("template time mode must be latest, relative or fixed, not \"" + word + "\"");
        }
    }

    /** A time range in epoch milliseconds, start before end. */
    public record Range(long start, long end) {}

    /**
     * What a template file says about itself. {@code thumbnail} is the file name of the PNG beside
     * the .jhv ("" when there is none). {@code span} is zero for a fixed template saved without one.
     */
    public record Meta(String name, String description, Mode mode, Duration span, Duration lag,
                       String thumbnail, int version) {

        public Meta {
            if (name.isBlank())
                throw new IllegalArgumentException("a template needs a name");
            if (span.isNegative())
                throw new IllegalArgumentException("template time span must not be negative, got " + span);
            if (lag.isNegative())
                throw new IllegalArgumentException("template time lag must not be negative, got " + lag);
            if (mode != Mode.FIXED && span.isZero())
                throw new IllegalArgumentException("a " + mode.word() + " template needs a span longer than zero, such as PT2H");
        }

        public Meta withName(String newName) {
            return new Meta(newName, description, mode, span, lag, thumbnail, version);
        }

        public Meta withThumbnail(String newThumbnail) {
            return new Meta(name, description, mode, span, lag, newThumbnail, version);
        }

        public Meta withTime(String newDescription, Mode newMode, Duration newSpan, Duration newLag) {
            return new Meta(name, newDescription, newMode, newSpan, newLag, thumbnail, version);
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("time", new JSONObject().put("mode", mode.word()).put("span", span.toString()).put("lag", lag.toString()))
                    .put("thumbnail", thumbnail)
                    .put("version", version);
        }

        /** Strict: a missing "time" object reads as fixed; anything present must parse. */
        public static Meta fromJson(JSONObject jo) {
            Object rawName = jo.opt("name");
            if (!(rawName instanceof String n) || n.isBlank())
                throw new IllegalArgumentException("a template needs a name");
            JSONObject time = jo.optJSONObject("time");
            Mode mode = time == null ? Mode.FIXED : Mode.parse(time.optString("mode", null));
            Duration span = time == null || !time.has("span") ? Duration.ZERO : duration("span", time.optString("span", null));
            Duration lag = time == null || !time.has("lag") ? Duration.ZERO : duration("lag", time.optString("lag", null));
            return new Meta(n.strip(), jo.optString("description", ""), mode, span, lag,
                    jo.optString("thumbnail", ""), jo.optInt("version", VERSION));
        }
    }

    /** An ISO 8601 duration such as PT2H or P1D; {@code field} names it in the error. */
    public static Duration duration(String field, @Nullable String text) {
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("template time " + field + " is empty; give an ISO 8601 duration such as PT2H");
        try {
            return Duration.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("template time " + field + " \"" + text + "\" is not an ISO 8601 duration such as PT2H or P1D", e);
        }
    }

    /**
     * The range a template opens on, or null for a fixed one (its saved dates stand).
     * {@code newest} is the newest time the scene's data reaches, or {@link Long#MIN_VALUE} when
     * that is not known; it is used by {@code latest} only, and never later than {@code now}.
     */
    @Nullable
    public static Range resolve(Meta meta, long now, long newest) {
        if (meta.mode() == Mode.FIXED)
            return null;
        long end = meta.mode() == Mode.LATEST
                ? newest == Long.MIN_VALUE ? now : Math.min(newest, now)
                : now - meta.lag().toMillis();
        return new Range(end - meta.span().toMillis(), end);
    }

    /** A duration in plain words: "2 h", "1 d 6 h", "30 min", "0 min". */
    public static String words(Duration d) {
        long minutes = d.toMinutes(), days = minutes / 1440, hours = minutes % 1440 / 60, mins = minutes % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0)
            sb.append(days).append(" d");
        if (hours > 0)
            sb.append(sb.isEmpty() ? "" : " ").append(hours).append(" h");
        if (mins > 0 || sb.isEmpty())
            sb.append(sb.isEmpty() ? "" : " ").append(mins).append(" min");
        return sb.toString();
    }

    /** The time rule in plain words, for the chooser and the manager. */
    public static String describe(Meta m) {
        return switch (m.mode()) {
            case FIXED -> "Saved dates";
            case LATEST -> "Newest " + words(m.span()) + " of data";
            case RELATIVE -> words(m.span()) + (m.lag().isZero() ? " ending now" : " ending " + words(m.lag()) + " ago");
        };
    }

    private TemplateTime() {}
}
