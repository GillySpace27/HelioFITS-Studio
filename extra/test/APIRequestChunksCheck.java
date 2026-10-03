package org.helioviewer.jhv.io;

import java.util.List;

/**
 * Helioviewer builds at most 1000 frames per movie and thins the cadence past that, so a longer
 * request is asked for in pieces (APIRequest.chunks) that the loader joins into one layer.
 *
 * <p>The server's words, from getJPX on 2026-10-02 (AIA 171, two days at 60 s): "Movie cadence has
 * been changed to one image every 172 seconds ... to avoid exceeding the maximum of 1000 frames
 * allowed per request."
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.io.APIRequestChunksCheck
 */
public final class APIRequestChunksCheck {

    private static final long DAY = 86_400_000L;
    private static final long T0 = 1_735_689_600_000L; // 2025-01-01T00:00:00Z
    private static int failures;

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static List<APIRequest> parts(long span, int cadence) {
        return new APIRequest("GSFC", 10, T0, T0 + span, cadence).chunks();
    }

    // Covers the request end to end, in order, with no gaps, every piece under the server's cap.
    private static void covers(APIRequest whole, List<APIRequest> parts, String what) {
        boolean ok = parts.getFirst().startTime() == whole.startTime() && parts.getLast().endTime() == whole.endTime();
        for (int i = 0; i < parts.size(); i++) {
            APIRequest p = parts.get(i);
            ok &= p.cadence() == whole.cadence() && p.sourceId() == whole.sourceId() && p.server().equals(whole.server());
            ok &= (p.endTime() - p.startTime()) / 1000 / p.cadence() + 1 <= 1000;
            if (i > 0)
                ok &= p.startTime() == parts.get(i - 1).endTime();
        }
        expect(ok, what + ": " + parts.size() + " pieces cover the range, each under 1000 frames");
    }

    public static void main(String[] args) {
        List<APIRequest> day = parts(DAY, 60); // 1441 frames
        expect(day.size() == 2, "one day at 60 s is two requests (got " + day.size() + ")");
        covers(new APIRequest("GSFC", 10, T0, T0 + DAY, 60), day, "one day at 60 s");

        List<APIRequest> month = parts(30 * DAY, 1800); // the default cadence: 1441 frames
        expect(month.size() == 2, "thirty days at the default 30 min is two requests (got " + month.size() + ")");

        List<APIRequest> week = parts(7 * DAY, 12); // AIA's native 12 s: 50401 frames
        covers(new APIRequest("GSFC", 10, T0, T0 + 7 * DAY, 12), week, "a week at 12 s");

        expect(parts(2 * 3_600_000L, 60).size() == 1, "two hours at 60 s stays one request");
        expect(parts(7 * DAY, APIRequest.CADENCE_ALL).size() == 1, "\"get all\" stays one request: the server picks the cadence");
        expect(new APIRequest("GSFC", 10, T0, T0, 60).chunks().size() == 1, "a single frame stays one request");

        System.out.println(failures == 0 ? "APIRequestChunksCheck: ok" : "APIRequestChunksCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
