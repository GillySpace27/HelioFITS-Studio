package org.helioviewer.jhv.app.state;

import org.json.JSONArray;
import org.json.JSONObject;

// Standalone self-check (no test framework in this repo -- see extra/test/LUTLabelsCheck.java for
// the pattern). Guards the rule that lost sessions: an image layer is only worth writing to a
// state file if the file can reload it. A layer saved while its view was still loading used to
// serialize to {}, and restoring that husk produced a layer with nothing to load, which was then
// pruned -- so reverting to a session emptied it. State.hasRestorableData is the predicate that
// both the writer and the reader consult; if it ever says yes to an empty object again, the
// husk-and-prune cycle comes back.
public final class SessionStateCheck {

    public static void main(String[] args) {
        // The husk. This is exactly what ImageLayer.serialize wrote for a mid-load layer.
        assertRestorable(new JSONObject(), false, "empty data");

        // Display settings alone cannot reload anything; they are not a reason to keep the layer.
        assertRestorable(new JSONObject().put("imageParams", new JSONObject().put("red", true)),
                false, "imageParams without a source");

        // A direct-URI layer (PUNCH FITS and friends) restores from its URI list.
        assertRestorable(new JSONObject().put("uris", new JSONArray().put("file:/tmp/a.fits")),
                true, "uris");

        // An empty list is still nothing to load.
        assertRestorable(new JSONObject().put("uris", new JSONArray()), false, "empty uris");

        // A server layer restores from its request.
        assertRestorable(new JSONObject().put("APIRequest", new JSONObject().put("sourceId", 10)),
                true, "APIRequest");

        // Belt and braces: a request present but empty still names a source to retry.
        assertRestorable(new JSONObject().put("APIRequest", new JSONObject()), true, "empty APIRequest object");

        System.out.println("SessionStateCheck: PASS");
    }

    private static void assertRestorable(JSONObject data, boolean expected, String what) {
        boolean got = State.hasRestorableData(data);
        if (got != expected)
            throw new AssertionError(what + ": expected hasRestorableData=" + expected + ", got " + got);
    }

}
