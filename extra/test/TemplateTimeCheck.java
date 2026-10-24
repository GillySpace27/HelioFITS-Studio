package org.helioviewer.jhv.app.state;

import java.time.Duration;

import org.json.JSONObject;

/**
 * A scene template's time rule (TemplateTime): the three modes resolve to the range
 * docs/templates.md promises, bad durations and unknown modes are refused with a message that
 * names the field, and the metadata record survives a trip through its JSON. Pure: no window, no
 * Settings.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.app.state.TemplateTimeCheck
 */
public final class TemplateTimeCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static final long HOUR = 3_600_000L;
    private static final long NOW = 1_790_000_000_000L; // 2026-09-21, an arbitrary fixed "now"

    private static TemplateTime.Meta meta(TemplateTime.Mode mode, String span, String lag) {
        return new TemplateTime.Meta("Test", "", mode, Duration.parse(span), Duration.parse(lag), "", TemplateTime.VERSION);
    }

    /** The message of the IllegalArgumentException {@code r} throws, or null when it throws none. */
    private static String refusal(Runnable r) {
        try {
            r.run();
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    public static void main(String[] args) {
        // fixed: the saved dates stand
        expect("fixed resolves to nothing (the saved dates stand)",
                TemplateTime.resolve(meta(TemplateTime.Mode.FIXED, "PT2H", "PT0S"), NOW, Long.MIN_VALUE) == null);

        // latest: newest data, else now; never later than now
        TemplateTime.Range latest = TemplateTime.resolve(meta(TemplateTime.Mode.LATEST, "PT2H", "PT0S"), NOW, Long.MIN_VALUE);
        expect("latest with no newest known ends now and spans 2 h: " + latest,
                latest != null && latest.end() == NOW && latest.start() == NOW - 2 * HOUR);
        TemplateTime.Range known = TemplateTime.resolve(meta(TemplateTime.Mode.LATEST, "PT2H", "PT0S"), NOW, NOW - 5 * HOUR);
        expect("latest with a newest time ends there: " + known,
                known != null && known.end() == NOW - 5 * HOUR && known.start() == NOW - 7 * HOUR);
        TemplateTime.Range future = TemplateTime.resolve(meta(TemplateTime.Mode.LATEST, "PT2H", "PT0S"), NOW, NOW + HOUR);
        expect("latest never ends after now: " + future, future != null && future.end() == NOW);
        TemplateTime.Range lagIgnored = TemplateTime.resolve(meta(TemplateTime.Mode.LATEST, "PT2H", "PT3H"), NOW, Long.MIN_VALUE);
        expect("latest ignores the lag", lagIgnored != null && lagIgnored.end() == NOW);

        // relative: now minus lag, minus span
        TemplateTime.Range rel = TemplateTime.resolve(meta(TemplateTime.Mode.RELATIVE, "P1D", "PT6H"), NOW, NOW - 100 * HOUR);
        expect("relative ends lag before now and spans a day, whatever the newest data: " + rel,
                rel != null && rel.end() == NOW - 6 * HOUR && rel.start() == NOW - 30 * HOUR);

        // strict parsing
        String bad = refusal(() -> TemplateTime.duration("span", "two hours"));
        expect("a non-ISO duration is refused, naming the field: " + bad, bad != null && bad.contains("span") && bad.contains("two hours"));
        String empty = refusal(() -> TemplateTime.duration("lag", ""));
        expect("an empty duration is refused: " + empty, empty != null && empty.contains("lag"));
        String negative = refusal(() -> meta(TemplateTime.Mode.RELATIVE, "PT2H", "-PT1H"));
        expect("a negative lag is refused: " + negative, negative != null && negative.contains("lag"));
        String zero = refusal(() -> meta(TemplateTime.Mode.LATEST, "PT0S", "PT0S"));
        expect("a zero span for latest is refused: " + zero, zero != null && zero.contains("span"));
        expect("a zero span for fixed is allowed", refusal(() -> meta(TemplateTime.Mode.FIXED, "PT0S", "PT0S")) == null);
        String mode = refusal(() -> TemplateTime.Mode.parse("soon"));
        expect("an unknown mode is refused, naming it: " + mode, mode != null && mode.contains("soon"));
        expect("mode words are lower case only", refusal(() -> TemplateTime.Mode.parse("LATEST")) != null);
        String fromJson = refusal(() -> TemplateTime.Meta.fromJson(new JSONObject("{\"name\":\"x\",\"time\":{\"mode\":\"latest\",\"span\":\"2h\"}}")));
        expect("fromJson refuses a bad span: " + fromJson, fromJson != null && fromJson.contains("span"));
        expect("fromJson refuses a missing name", refusal(() -> TemplateTime.Meta.fromJson(new JSONObject("{\"time\":{\"mode\":\"fixed\"}}"))) != null);
        TemplateTime.Meta noTime = TemplateTime.Meta.fromJson(new JSONObject("{\"name\":\"x\"}"));
        expect("a template with no time object is fixed", noTime.mode() == TemplateTime.Mode.FIXED);

        // plain words
        expect("words: 2 h, 1 d 6 h, 30 min, 0 min",
                "2 h".equals(TemplateTime.words(Duration.ofHours(2))) && "1 d 6 h".equals(TemplateTime.words(Duration.ofHours(30)))
                        && "30 min".equals(TemplateTime.words(Duration.ofMinutes(30))) && "0 min".equals(TemplateTime.words(Duration.ZERO)));
        expect("describe: " + TemplateTime.describe(meta(TemplateTime.Mode.RELATIVE, "PT6H", "PT30M")),
                "6 h ending 30 min ago".equals(TemplateTime.describe(meta(TemplateTime.Mode.RELATIVE, "PT6H", "PT30M")))
                        && "Newest 2 h of data".equals(TemplateTime.describe(meta(TemplateTime.Mode.LATEST, "PT2H", "PT0S"))));

        // round trip
        TemplateTime.Meta m = new TemplateTime.Meta("Sun pizza", "Four slices", TemplateTime.Mode.RELATIVE,
                Duration.ofHours(6), Duration.ofMinutes(30), "Sun pizza.png", TemplateTime.VERSION);
        JSONObject jo = m.toJson();
        expect("toJson writes the documented shape: " + jo,
                "relative".equals(jo.getJSONObject("time").getString("mode")) && "PT6H".equals(jo.getJSONObject("time").getString("span"))
                        && "PT30M".equals(jo.getJSONObject("time").getString("lag")) && jo.getInt("version") == 1
                        && "Sun pizza.png".equals(jo.getString("thumbnail")));
        expect("fromJson(toJson()) is the same record", m.equals(TemplateTime.Meta.fromJson(new JSONObject(jo.toString()))));

        System.out.println(failures == 0 ? "TemplateTimeCheck: ok" : "TemplateTimeCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
