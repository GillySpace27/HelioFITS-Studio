package org.helioviewer.jhv.io;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What Refresh Data tells the person after Heliad's template.refresh answers (HS-25): a count per
 * outcome, and every queued dataset with the span it is fetching. A Helioviewer layer is reported
 * as refreshed when the template opens, not as a failure.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.io.TemplateRefreshCheck
 */
public final class TemplateRefreshCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        JSONArray reply = new JSONArray()
                .put(new JSONObject().put("layer", "SDO AIA 193").put("status", "queued")
                        .put("start", "2025-09-21T15:00:00.840").put("end", "2025-09-21T17:00:00.840"))
                .put(new JSONObject().put("layer", "SDO AIA 171").put("status", "up_to_date"))
                .put(new JSONObject().put("layer", "AIA 304 (Helioviewer)").put("status", "skipped"))
                .put(new JSONObject().put("layer", "SDO AIA 211").put("status", "queued")
                        .put("start", "2025-09-21T15:00:12.000").put("end", "2025-09-21T17:00:12.000"));
        String text = TemplateRefresh.summary(reply);
        expect("counts each outcome once, in the order met: " + text.lines().findFirst().orElse(""),
                text.startsWith("2 queued for download, 1 up to date, 1 not from the Heliad library"));
        expect("names every queued layer with its span",
                text.contains("SDO AIA 193: 2025-09-21T15:00:00.840 to 2025-09-21T17:00:00.840")
                        && text.contains("SDO AIA 211: 2025-09-21T15:00:12.000 to 2025-09-21T17:00:12.000"));
        expect("says to open the template again once downloads finish", text.contains("Open the template again"));
        String quiet = TemplateRefresh.summary(new JSONArray().put(new JSONObject().put("layer", "a").put("status", "up_to_date")));
        expect("nothing queued says nothing about downloads: " + quiet, quiet.equals("1 up to date."));
        expect("an empty reply says the template shows no image layers",
                TemplateRefresh.summary(new JSONArray()).equals("The template shows no image layers."));
        expect("a status from a newer Heliad is shown as it is",
                TemplateRefresh.summary(new JSONArray().put(new JSONObject().put("status", "paused"))).equals("1 paused."));

        System.out.println(failures == 0 ? "TemplateRefreshCheck: PASS" : "TemplateRefreshCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private TemplateRefreshCheck() {}

}
