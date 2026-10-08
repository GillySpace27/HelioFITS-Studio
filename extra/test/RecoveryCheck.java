package org.helioviewer.jhv.app;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.helioviewer.jhv.app.state.State;

import org.json.JSONObject;

/**
 * Crash safety: which dead runs count against a session, what autosave compares, where its copies
 * go, and that queued session writes land in order with nothing left behind.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.app.RecoveryCheck
 */
public final class RecoveryCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static JSONObject run(String file, String phase, int restoreCrashes) {
        return new JSONObject().put("file", file).put("phase", phase).put("restoreCrashes", restoreCrashes).put("started", 1);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-recovery").toString()); // never the real settings

        // A live run is not a crash; a dead one of another session is not this one's.
        JSONObject map = new JSONObject().put("100", run("/s/a.jhv", "running", 0)).put("200", run("/s/b.jhv", "running", 0))
                .put("300", run("/s/a.jhv", "running", 0));
        Set<String> alive = Set.of("100");
        Recovery.Prior prior = Recovery.takeDead(map, "/s/a.jhv", (pid, e) -> alive.contains(pid));
        expect("a dead run of this session is a crash", prior.crashed() && !prior.whileRestoring());
        expect("and is taken off the record, the live one and the other session's kept", !map.has("300") && map.has("100") && map.has("200"));
        expect("a crash after the session had loaded is not a restore crash", prior.restoreCrashes() == 0 && !prior.restoreLoop());

        // Deaths while reopening count up, one after another; the limit stops the reopen.
        Recovery.Prior once = Recovery.takeDead(new JSONObject().put("400", run("/s/a.jhv", "restoring", 0)), "/s/a.jhv", (p, e) -> false);
        expect("the first death while reopening counts 1, got " + once.restoreCrashes(), once.whileRestoring() && once.restoreCrashes() == 1 && !once.restoreLoop());
        Recovery.Prior twice = Recovery.takeDead(new JSONObject().put("500", run("/s/a.jhv", "restoring", 1)), "/s/a.jhv", (p, e) -> false);
        expect("the second in a row reaches the limit, got " + twice.restoreCrashes(), twice.restoreCrashes() == Recovery.RESTORE_CRASH_LIMIT && twice.restoreLoop());
        Recovery.Prior clean = Recovery.takeDead(new JSONObject(), "/s/a.jhv", (p, e) -> false);
        expect("no record is no crash", !clean.crashed() && clean.restoreCrashes() == 0);

        // The playhead alone is not a change worth a write; anything else is.
        JSONObject a = new JSONObject().put("org.helioviewer.jhv.state", new JSONObject().put("time", "2026-01-01T00:00:00").put("projection", "Orthographic"));
        JSONObject b = new JSONObject().put("org.helioviewer.jhv.state", new JSONObject().put("time", "2026-01-02T00:00:00").put("projection", "Orthographic"));
        JSONObject c = new JSONObject().put("org.helioviewer.jhv.state", new JSONObject().put("time", "2026-01-02T00:00:00").put("projection", "Latitudinal"));
        expect("a playhead move alone compares equal", Recovery.withoutPlayhead(a).equals(Recovery.withoutPlayhead(b)));
        expect("a projection change does not", !Recovery.withoutPlayhead(b).equals(Recovery.withoutPlayhead(c)));
        expect("and the snapshot keeps its playhead for the write", "2026-01-02T00:00:00".equals(b.getJSONObject("org.helioviewer.jhv.state").optString("time")));

        // Copies: one per slot, wrapping, and two files of one name in two folders never share one.
        Set<String> names = new HashSet<>();
        for (long slot = 0; slot < 2L * Recovery.SLOTS; slot++)
            names.add(Recovery.slotName(new File("/x/session.jhv"), slot));
        expect("the copies wrap at " + Recovery.SLOTS + " slots, got " + names.size(), names.size() == Recovery.SLOTS);
        expect("same name, other folder, other copies",
                !Recovery.slotName(new File("/x/session.jhv"), 0).equals(Recovery.slotName(new File("/y/session.jhv"), 0)));

        // Queued writes land in order: the last one taken is the one on disk, and no temp is left.
        Path dir = Files.createTempDirectory("hfs-recovery-write");
        for (int i = 0; i < 20; i++)
            State.write(new JSONObject().put("n", i), dir.toString(), "s.jhv");
        Path target = dir.resolve("s.jhv");
        long deadline = System.currentTimeMillis() + 10_000;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            last = Files.isRegularFile(target) ? Files.readString(target) : "";
            if (last.contains("\"n\":19"))
                break;
            Thread.sleep(20);
        }
        expect("twenty queued writes end with the last one, got " + last, new JSONObject(last).optInt("n") == 19);
        try (var listing = Files.list(dir)) {
            expect("and leave only the session file behind", listing.map(p -> p.getFileName().toString()).toList().equals(java.util.List.of("s.jhv")));
        }

        System.out.println(failures == 0 ? "RecoveryCheck: ok" : "RecoveryCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
