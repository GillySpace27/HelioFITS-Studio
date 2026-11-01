package org.helioviewer.jhv.gui.dialog;

import java.nio.file.Files;
import java.nio.file.Path;

import org.helioviewer.jhv.app.state.TemplateTime;
import org.helioviewer.jhv.time.TimeUtils;
import org.json.JSONObject;

/**
 * Ask templates: the "ask" rule parses, opens nothing on its own, and once a date is given moves
 * every layer query to the span before it and becomes fixed; the built-in coronagraph ladder is one.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.gui.dialog.AskTemplateCheck
 */
public final class AskTemplateCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        try {
            JSONObject session = new JSONObject(Files.readString(Path.of("resources/templates/coronagraph-ladder.jhv")));
            JSONObject state = session.getJSONObject("org.helioviewer.jhv.state");
            TemplateTime.Meta meta = TemplateTime.Meta.fromJson(state.getJSONObject("template"));
            expect("the ladder is an ask template with a one-day span", meta.mode() == TemplateTime.Mode.ASK && meta.span().toHours() == 24);
            expect("an ask template resolves to nothing on its own", TemplateTime.resolve(meta, 0, Long.MIN_VALUE) == null);
            expect("the ladder is Helioradial at lambda 0", state.getString("projection").equals("Helioradial") && state.getDouble("warpLambda") == 0);
            boolean queries = true;
            for (Object o : state.getJSONArray("imageLayers")) {
                JSONObject data = ((JSONObject) o).getJSONObject("data");
                queries &= !data.has("uris") && (data.has("fitsRequest") || data.has("APIRequest"));
            }
            expect("every layer is a query and none a saved file list", queries && state.getJSONArray("imageLayers").length() == 4);

            long end = TimeUtils.parse("2026-02-01T12:00:00");
            JSONObject pointed = TemplateChooser.pointed(session, meta, end - 86_400_000L, end);
            JSONObject ps = pointed.getJSONObject("org.helioviewer.jhv.state");
            expect("pointing moves the master range to the chosen start and end",
                    TimeUtils.parse(ps.getString("masterEndTime")) == end
                            && TimeUtils.parse(ps.getString("masterStartTime")) == end - 86_400_000L);
            JSONObject punch = ps.getJSONArray("imageLayers").getJSONObject(0).getJSONObject("data").getJSONObject("fitsRequest");
            JSONObject aia = ps.getJSONArray("imageLayers").getJSONObject(3).getJSONObject("data").getJSONObject("APIRequest");
            expect("pointing moves the PUNCH query and the AIA request too", TimeUtils.parse(punch.getString("endTime")) == end
                    && TimeUtils.parse(aia.getString("startTime")) == end - 86_400_000L);
            expect("a pointed copy is fixed, so loading it asks nothing more",
                    TemplateTime.Meta.fromJson(ps.getJSONObject("template")).mode() == TemplateTime.Mode.FIXED);
            expect("the template itself is untouched", state.getJSONObject("template").getJSONObject("time").getString("mode").equals("ask"));
            expect("the chooser says the dates are asked", TemplateTime.describe(meta).contains("Dates you choose"));
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            expect("no exception: " + t, false);
        }
        System.out.println(failures == 0 ? "AskTemplateCheck: ok" : "AskTemplateCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
