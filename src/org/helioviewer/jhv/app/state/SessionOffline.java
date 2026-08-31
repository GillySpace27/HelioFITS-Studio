package org.helioviewer.jhv.app.state;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.io.DownloadLayer;
import org.helioviewer.jhv.io.NetFileCache;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.thread.EDTTimer;
import org.helioviewer.jhv.thread.Task;

/**
 * Make a saved session open without the network.
 *
 * <p>A session file records where its data came from, not the data. That is the right design (a
 * .jhv stays a few kilobytes and can be mailed), but it means the file is only as durable as the
 * archive behind it, and a talk given on a conference wifi is exactly when that matters.
 *
 * <p>Two different things have to be true, because layers reach their data two different ways:
 *
 * <ul>
 * <li>A server-request layer (Helioviewer, streamed over JPIP) serializes an APIRequest, which is a
 * question rather than an answer. Restoring it asks the server again. Downloading it writes one
 * .jpx and, through {@link DownloadLayer}, re-points the layer at that local file, after which the
 * layer serializes a file: URI instead and the session is self-contained.
 * <li>A direct-URI layer (PUNCH and the FITS archives) serializes the remote URIs and reloads them
 * through {@link NetFileCache}, which is content-addressed and never evicts. Those restore offline
 * already, PROVIDED every frame actually reached the cache. A layer still loading, or one whose
 * frames failed, has gaps.
 * </ul>
 *
 * <p>So this saves twice: once immediately, so an interrupted download still leaves the session it
 * would have written anyway, and once more when the data has landed, which is the save that records
 * the local paths. The second write is the point; the first is insurance.
 */
public final class SessionOffline {

    /** How long to wait for the data before giving up and leaving the first save standing. */
    private static final long TIMEOUT_MS = 30 * 60_000;
    private static final int POLL_MS = 500;

    public static boolean isEnabled() {
        return !"false".equals(Settings.getProperty("state.offlineSessions"));
    }

    public static void setEnabled(boolean enabled) {
        Settings.setProperty("state.offlineSessions", Boolean.toString(enabled));
    }

    /** Layers that would have to ask a server again to restore. */
    private static List<ImageLayer> serverLayers() {
        List<ImageLayer> out = new ArrayList<>();
        for (ImageLayer image : Layers.getImageLayers())
            if (!image.isLocal())
                out.add(image);
        return out;
    }

    /** Remote frame URIs whose bytes are not on disk yet, across every direct-URI layer. */
    private static Set<URI> uncachedUris() {
        Set<URI> out = new LinkedHashSet<>();
        for (ImageLayer image : Layers.getImageLayers()) {
            if (!image.isLocal())
                continue;
            for (URI uri : image.getSourceUris()) {
                String scheme = uri.getScheme();
                if (scheme == null || "file".equalsIgnoreCase(scheme))
                    continue;
                if (!NetFileCache.cachedFile(uri).isFile())
                    out.add(uri);
            }
        }
        return out;
    }

    /**
     * Save, and then bring the data down so the file stops needing the network.
     *
     * <p>Returns immediately. The work runs in the background and the session is rewritten when it
     * finishes, which is what turns the recorded server requests into local paths.
     */
    public static void save(String dir, String file) {
        State.saveNow(dir, file);
        if (!isEnabled())
            return;

        List<ImageLayer> layers = serverLayers();
        Set<URI> uris = uncachedUris();
        if (layers.isEmpty() && uris.isEmpty())
            return; // already self-contained: every layer is local and every frame is cached

        Log.info("Making " + file + " self-contained: " + layers.size() + " layer(s) to download, "
                + uris.size() + " frame(s) to cache");
        for (ImageLayer layer : layers)
            layer.startDownload(new SilentProgress());
        for (URI uri : uris)
            Task.submit("cache " + uri,
                    () -> NetFileCache.get(uri),
                    Task::doNothing,
                    (context, t) -> Log.warn("Could not cache " + context, t));

        watch(dir, file, layers, uris);
    }

    /**
     * Wait for the data, then write the session again.
     *
     * <p>Polls rather than chaining callbacks, deliberately. A downloaded layer is not finished
     * when its bytes arrive: DownloadLayer then calls load() with the local file, which builds a
     * new view asynchronously, and only after that does the layer serialize a file: URI. Polling
     * asks the question that actually matters, "would this save be self-contained now", rather
     * than assembling it from three separate completion signals.
     */
    private static void watch(String dir, String file, List<ImageLayer> layers, Set<URI> uris) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        EDTTimer[] holder = new EDTTimer[1];
        holder[0] = new EDTTimer(POLL_MS, () -> {
            boolean layersDone = true;
            for (ImageLayer layer : layers)
                // isLocal() flips when the view has been rebuilt from the downloaded file, which
                // is the same condition serialize() branches on. Removed layers count as done:
                // there is nothing left to wait for.
                if (Layers.getImageLayers().contains(layer) && !layer.isLocal())
                    layersDone = false;

            boolean urisDone = true;
            for (URI uri : uris)
                if (!NetFileCache.cachedFile(uri).isFile())
                    urisDone = false;

            if (layersDone && urisDone) {
                holder[0].stop();
                State.saveNow(dir, file);
                Log.info("Session " + file + " is now self-contained");
                Message.warn("Session saved offline",
                        "\"" + file + "\" now points at local copies and will open without the network.");
            } else if (System.currentTimeMillis() > deadline) {
                holder[0].stop();
                Log.warn("Gave up making " + file + " self-contained; the saved session still needs the network");
            }
        });
        holder[0].start();
    }

    // The layer panel's own progress bar already reports a download the user started there. This
    // one was started by a save, so it reports through the log and the final message instead of
    // reaching into a panel that may not be showing the layer at all.
    private record SilentProgress() implements DownloadLayer.Progress {
        @Override
        public void progress(int percent) {}

        @Override
        public void done() {}

        @Override
        public void success(String path) {
            Log.info("Downloaded for offline session: " + path);
        }
    }

    private SessionOffline() {}
}
