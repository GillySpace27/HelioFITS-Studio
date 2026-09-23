package org.helioviewer.jhv.app;

import org.json.JSONObject;

/**
 * windows-live.json maps pid to window name, and only a clean exit removes an entry. A window that
 * is killed or crashes leaves its pid behind forever, so the file grows without bound and, because
 * the OS recycles pids, a future process can inherit a dead session's name. Session.pruneDead runs
 * on every read and every write of that file; this checks it keeps the living and drops the rest.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.SessionLivePruneCheck
 */
public final class SessionLivePruneCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        String me = String.valueOf(ProcessHandle.current().pid());
        String dead = "4000000000"; // above every platform's pid range, so no such process exists

        JSONObject map = new JSONObject();
        map.put(me, "ReversingComet");
        map.put(dead, "CONNECT 9-22");
        map.put("not-a-pid", "garbage");

        JSONObject pruned = Session.pruneDead(map);

        expect("this process survives the prune", pruned.has(me));
        expect("and keeps its own window name", "ReversingComet".equals(pruned.optString(me, "")));
        expect("a pid with no process is dropped", !pruned.has(dead));
        expect("a key that is not a pid is dropped too", !pruned.has("not-a-pid"));
        expect("so only the live entry remains", pruned.length() == 1);
        expect("pruning again changes nothing", Session.pruneDead(pruned).length() == 1);
        expect("an empty map stays empty", Session.pruneDead(new JSONObject()).length() == 0);

        System.out.println(failures == 0 ? "SessionLivePruneCheck: PASS" : "SessionLivePruneCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private SessionLivePruneCheck() {}

}
