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

        // A native-FITS layer restores from either half of what it now writes. The query alone has
        // to count: a layer saved while its query was still running has no URI list yet, and
        // rejecting it would drop the layer exactly the way the husk did.
        assertRestorable(new JSONObject().put("fitsRequest",
                new JSONObject().put("archive", "PUNCH").put("product", "CAM")), true, "fitsRequest alone");
        assertRestorable(new JSONObject().put("fitsRequest", new JSONObject())
                .put("uris", new JSONArray().put("file:/a.fits")), true, "query and list together");

        assertBaseNameIsNotLiveness();

        System.out.println("SessionStateCheck: PASS");
    }

    // The other way a restore lost a layer, and the one that survived the husk fix. State's
    // post-restore prune calls ImageLayer.unload on every layer it restored, so unload's test for
    // "this one never loaded" decides what gets deleted. That test used to be
    // view.getBaseName() == null, which is true for a perfectly good ManyView: getBaseName is a
    // View default returning null and ManyView does not override it, because a stack of frames has
    // no one file to name. So every multi-file layer -- a restored PUNCH movie above all -- was
    // deleted the instant it finished loading, and the next autosave wrote the deletion to disk.
    // If ManyView ever does declare getBaseName, this fails and the reasoning above needs redoing.
    private static void assertBaseNameIsNotLiveness() {
        try {
            Class<?> declarer = org.helioviewer.jhv.view.ManyView.class.getMethod("getBaseName").getDeclaringClass();
            if (declarer != org.helioviewer.jhv.view.View.class)
                throw new AssertionError("ManyView.getBaseName now declared by " + declarer.getName()
                        + "; ImageLayer.unload's liveness test may need revisiting");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("View.getBaseName is gone", e);
        }
    }

    private static void assertRestorable(JSONObject data, boolean expected, String what) {
        boolean got = State.hasRestorableData(data);
        if (got != expected)
            throw new AssertionError(what + ": expected hasRestorableData=" + expected + ", got " + got);
    }

}
