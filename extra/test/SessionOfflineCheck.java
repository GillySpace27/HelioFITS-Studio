package org.helioviewer.jhv.app.state;

import org.json.JSONObject;

/**
 * What has to be true of a session file for it to open without the network.
 *
 * <p>The question is not "was the data downloaded" but "does the saved file point at the data".
 * Those come apart: a layer can have its bytes on disk and still serialize an APIRequest, which is
 * a question for a server rather than an answer, and restoring it goes back to the network no
 * matter what is cached. That is why {@link SessionOffline} waits for {@code isLocal()} to flip
 * rather than for a download to finish, and why the save is written twice.
 *
 * <p>{@link State#hasRestorableData} is the shared judgement of what a layer record contains, so
 * this pins the offline reading of it against the same shapes State actually writes.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.app.state.SessionOfflineCheck
 */
public final class SessionOfflineCheck {

    private static int failures;

    public static void main(String[] args) {
        // A server request restores by asking a server. Restorable, and not offline.
        JSONObject apiOnly = new JSONObject().put("APIRequest",
                new JSONObject().put("server", "IAS").put("sourceId", 10));
        expect(State.hasRestorableData(apiOnly), "an APIRequest layer must be restorable at all");
        expect(!isOffline(apiOnly), "an APIRequest layer is NOT offline-restorable");

        // The same layer after a download: DownloadLayer re-points it at the file it wrote, and
        // ImageLayer.serialize then takes the uris branch instead.
        JSONObject downloaded = new JSONObject().put("uris",
                new org.json.JSONArray().put("file:///Users/x/Downloads/foo.jpx"));
        expect(State.hasRestorableData(downloaded), "a downloaded layer must be restorable");
        expect(isOffline(downloaded), "a file: URI needs no network");

        // Direct-URI layers keep their REMOTE addresses and reload through the content-addressed
        // cache. Offline only if the bytes are on disk, which the file itself cannot say, so this
        // is the case SessionOffline has to check against the cache rather than against the JSON.
        JSONObject remote = new JSONObject().put("uris",
                new org.json.JSONArray().put("https://umbra.nascom.nasa.gov/punch/x.fits"));
        expect(State.hasRestorableData(remote), "a remote-URI layer must be restorable");
        expect(!isOffline(remote), "a remote URI is offline only if its bytes are cached, which the "
                + "session file does not record");

        // A query with no URIs is a question too, and re-asking it needs the archive.
        JSONObject query = new JSONObject().put("fitsRequest",
                new JSONObject().put("archive", "PUNCH").put("product", "CAM"));
        expect(State.hasRestorableData(query), "a FITS request must be restorable");
        expect(!isOffline(query), "a bare FITS query is NOT offline-restorable");

        // A layer carrying both restores from the list and can still follow the date afterwards.
        JSONObject both = new JSONObject()
                .put("uris", new org.json.JSONArray().put("file:///Users/x/a.fits"))
                .put("fitsRequest", new JSONObject().put("archive", "PUNCH"));
        expect(isOffline(both), "local URIs alongside a query are still offline-restorable: the "
                + "list is what restore uses, the query is only what lets it follow the date");

        // An empty record is neither, and State already drops those rather than writing husks.
        expect(!State.hasRestorableData(new JSONObject()), "an empty layer record is not restorable");

        if (failures != 0)
            throw new AssertionError(failures + " session-offline failure(s)");
        System.out.println("SessionOfflineCheck: PASS");
    }

    /**
     * Whether this layer record could be restored with the network unplugged, judged from the file
     * alone. Deliberately the strict reading: a remote URI might be cached, but nothing in the
     * session says so, so a session full of them is not one you can promise will open on a plane.
     */
    private static boolean isOffline(JSONObject data) {
        org.json.JSONArray uris = data.optJSONArray("uris");
        if (uris == null || uris.isEmpty())
            return false;
        for (int i = 0; i < uris.length(); i++)
            if (!uris.getString(i).startsWith("file:"))
                return false;
        return true;
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

}
