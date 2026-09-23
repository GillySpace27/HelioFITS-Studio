package org.helioviewer.jhv.layers;

import java.net.SocketTimeoutException;
import java.awt.EventQueue;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.swing.JOptionPane;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.image.ImageProcessingSettings;
import org.helioviewer.jhv.io.APIRequest;
import org.helioviewer.jhv.io.DataUri;
import org.helioviewer.jhv.io.DownloadLayer;
import org.helioviewer.jhv.io.FileUtils;
import org.helioviewer.jhv.io.JSONUtils;
import org.helioviewer.jhv.io.NetFileCache;
import org.helioviewer.jhv.thread.AppThread;
import org.helioviewer.jhv.thread.LatestWorker;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.view.ManyView;
import org.helioviewer.jhv.view.View;
import org.helioviewer.jhv.view.j2k.J2KView;
import org.helioviewer.jhv.layers.filters.PlanePanel;
import org.helioviewer.jhv.view.uri.URIView;

import org.json.JSONArray;
import org.json.JSONObject;

final class ImageLayerLoader {

    private final LatestWorker<DecodedImage> executor = new LatestWorker<>("View-Decoder");
    private final ImageProcessingSettings processingSettings;
    private final Consumer<View> onViewLoaded;
    private final Consumer<View> onPreviewLoaded; // the first frame, while the rest are still arriving
    private final Runnable onUnload;
    private final Consumer<String> statusSink; // load-stage readout; null clears
    private final Consumer<List<URI>> onFailedUris; // URIs that failed during a multi-frame load
    private final Runnable onFrameAdded; // one frame of a streaming load has joined the movie

    private Future<View> loadFuture;
    private Future<?> downloadFuture;
    private int loadGeneration;

    ImageLayerLoader(ImageProcessingSettings _processingSettings,
                     @Nonnull Consumer<View> _onViewLoaded, @Nonnull Consumer<View> _onPreviewLoaded, @Nonnull Runnable _onUnload,
                     @Nonnull Consumer<String> _statusSink, @Nonnull Consumer<List<URI>> _onFailedUris,
                     @Nonnull Runnable _onFrameAdded) {
        processingSettings = _processingSettings;
        onViewLoaded = _onViewLoaded;
        onPreviewLoaded = _onPreviewLoaded;
        onUnload = _onUnload;
        statusSink = _statusSink;
        onFailedUris = _onFailedUris;
        onFrameAdded = _onFrameAdded;
    }

    void load(APIRequest req) {
        cancelLoad();
        int gen = ++loadGeneration;
        loadFuture = Task.submitBackground("request", () -> {
                    statusSink.accept("Querying server…");
                    URI uri = requestAPI(req.toJpipRequest());
                    if (uri == null)
                        return null;
                    statusSink.accept("Opening image stream…");
                    return createView(req, uri);
                },
                result -> onSuccess(result, gen),
                (logContext, t) -> onFailure(t, gen, "Could not load the layer"));
    }

    void load(List<URI> uriList) {
        cancelLoad();
        onFailedUris.accept(List.of()); // clear any stale failures from a previous load
        int gen = ++loadGeneration;
        loadFuture = Task.submitBackground(uriList.toString(),
                () -> loadUri(uriList, view -> EventQueue.invokeLater(() -> onPreview(view, gen))),
                result -> onSuccess(result, gen),
                (logContext, t) -> onFailure(t, gen, failureTitle(uriList)));
    }

    // A file the user picked by hand is best named in the title; a remote load has no name the
    // user would recognize, so it stays generic.
    private static String failureTitle(List<URI> uriList) {
        if (uriList.size() == 1) {
            URI uri = uriList.getFirst();
            if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null)
                return "Could not open " + new java.io.File(uri.getPath()).getName();
        }
        return "Could not load the layer";
    }

    /**
     * Put the first frame on screen while the rest are still arriving.
     *
     * <p>Deliberately not the status text: the load is still running and still counting frames.
     * The preview is a view of its own rather than a frame borrowed from the movie being built,
     * because installing a view abolishes the one it replaces, and a ManyView abolishes every
     * frame it holds; sharing one would destroy a frame the finished movie still needs. Loading
     * that frame a second time costs a cache hit and one decode.
     */
    private void onPreview(View preview, int gen) {
        if (gen != loadGeneration) { // superseded while it was in flight
            preview.abolish();
            return;
        }
        onPreviewLoaded.accept(preview);
    }

    boolean isLoading() {
        return loadFuture != null;
    }

    void clearLoadFuture() {
        loadFuture = null;
    }

    void startDownload(APIRequest req, ImageLayer layer, String baseName, DownloadLayer.Progress progress) {
        cancelDownload();
        downloadFuture = DownloadLayer.submit(req, layer, baseName, progress);
    }

    void cancelLoad() {
        loadGeneration++; // Invalidate any pending callbacks
        if (loadFuture != null) {
            loadFuture.cancel(true);
            loadFuture = null;
        }
    }

    void cancelDownload() {
        if (downloadFuture != null) {
            downloadFuture.cancel(true);
            downloadFuture = null;
        }
    }

    void abolish() {
        cancelLoad();
        cancelDownload();
        executor.dispose();
    }

    private void onSuccess(View result, int gen) {
        statusSink.accept(null);
        if (gen != loadGeneration) {
            if (result != null) {
                result.abolish();
            }
            return;
        }
        if (result != null) {
            onViewLoaded.accept(result);
        } else {
            onUnload.run();
        }
    }

    private void onFailure(Throwable t, int gen, String title) {
        statusSink.accept(null);
        if (gen != loadGeneration) {
            return;
        }
        if (AppThread.isInterrupted(t)) {
            Log.warn(t);
            return;
        }
        onUnload.run();

        Log.errorStack(t);
        Message.err(title, t.getMessage() == null ? "See the log for details." : t.getMessage(), t);
    }

    private View loadUri(List<URI> uriList, Consumer<View> preview) throws Exception {
        int total = uriList.size();
        if (total == 1) {
            statusSink.accept(CONNECTING);
            View only = createView(null, uriList.getFirst());
            if (!configurePlanes(only))
                return only;
            only.abolish(); // its clip set was sampled on the plane we are leaving
            return createView(null, uriList.getFirst());
        } else {
            // Counted apart: a frame read back off the disk and a frame pulled over the wire are
            // different events, and a resume is mostly the first.
            java.util.concurrent.atomic.AtomicInteger downloaded = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger cached = new java.util.concurrent.atomic.AtomicInteger();
            long startNanos = System.nanoTime();
            java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
            // When the wire last moved, so a stall can be named as one. The archive goes quiet
            // for minutes at a time and a frozen "Downloading" is indistinguishable from a hang.
            java.util.concurrent.atomic.AtomicLong lastByte = new java.util.concurrent.atomic.AtomicLong(startNanos);
            java.util.function.LongConsumer onBytes = n -> {
                bytes.addAndGet(n);
                lastByte.set(System.nanoTime());
            };
            // Frame counts alone stand still for as long as one frame takes, which at the archive's
            // slow end is minutes; beside a spinner that reads as a hang. The ticker republishes
            // megabytes and a rate twice a second, so the readout moves whenever the wire does.
            javax.swing.Timer ticker = new javax.swing.Timer(500, e -> statusSink.accept(
                    progressText(downloaded.get(), cached.get(), total, bytes.get(), startNanos, lastByte.get())));
            List<URI> failed = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            ManyView movie = null;
            List<URI> rest = uriList;
            try {
                ticker.start();
                statusSink.accept(CONNECTING);
                // The first frame is the seed of the movie rather than a throwaway preview: the
                // wrapper goes on the layer now and the rest are added to it as they land, so the
                // transport and the coverage timeline fill in during the download instead of
                // everything appearing at the end. Failure here is not worth reporting; the same
                // URI is tried again below, on the all-at-once path.
                try {
                    long[] seedBytes = {0};
                    java.util.function.LongConsumer seedSink = n -> {
                        seedBytes[0] += n;
                        onBytes.accept(n);
                    };
                    View first = createView(null, uriList.getFirst(), seedSink);
                    if (configurePlanes(first)) {
                        first.abolish(); // its clip set was sampled on the plane we are leaving
                        first = createView(null, uriList.getFirst(), seedSink);
                    }
                    movie = new ManyView(List.of(first));
                    // The seed counts like any other frame: off the disk, or off the wire.
                    (seedBytes[0] > 0 ? downloaded : cached).incrementAndGet();
                    rest = uriList.subList(1, total); // the seed is in; do not fetch it twice
                    preview.accept(movie);
                } catch (Exception ignore) {
                }

                if (movie == null) { // no seed: build them all and wrap at the end, as before
                    List<View> views = rest.parallelStream().map(uri -> {
                        try {
                            View v = createView(null, uri, onBytes);
                            downloaded.incrementAndGet();
                            return v;
                        } catch (Exception e) {
                            Log.warn(uri.toString(), e);
                            failed.add(uri);
                            downloaded.incrementAndGet();
                            return null;
                        }
                    }).filter(Objects::nonNull).toList();
                    statusSink.accept("Assembling " + views.size() + " frames…");
                    onFailedUris.accept(failed);
                    reportShortfall(failed, total);
                    return new ManyView(views);
                }

                ManyView growing = movie;
                fetchFrames(rest, uri -> {
                    // Whether THIS frame crossed the wire. NetFileCache reports bytes only for a
                    // URI it actually fetches, so silence means it came off the disk. A frame
                    // another thread is fetching at the same moment also reports nothing and is
                    // counted as cached; distinct URIs make that vanishingly rare.
                    long[] got = {0};
                    try {
                        View v = createView(null, uri, n -> {
                            got[0] += n;
                            onBytes.accept(n);
                        });
                        // One thread writes the map; every reader of it is lock-free. The layer is
                        // told on the same hop, so the transport and the timeline move together.
                        EventQueue.invokeLater(() -> {
                            growing.addFrames(List.of(v));
                            onFrameAdded.run();
                        });
                    } catch (Exception e) {
                        Log.warn(uri.toString(), e);
                        failed.add(uri); // remembered so the layer can report it as retryable, not just absent
                    }
                    (got[0] > 0 ? downloaded : cached).incrementAndGet();
                });
            } finally {
                ticker.stop();
            }
            onFailedUris.accept(failed);
            reportShortfall(failed, total);
            return movie;
        }
    }

    /**
     * How many frames are fetched at once.
     *
     * <p>Not the common pool, which on this machine is fifteen. A PUNCH mosaic is tens of
     * megabytes and the archive divides its bandwidth between whatever is in flight, so fifteen
     * concurrent frames spend a minute all half-downloaded and then complete together: the
     * megabytes climb the whole time while the frame count sits still and the movie does not
     * grow. Four streams land a frame every few seconds at much the same aggregate rate, which is
     * the difference between a movie filling in and a progress bar.
     *
     * <p>ponytail: a fixed number, not a measured one. Raise it if a fast archive is visibly
     * under-used.
     */
    private static final int FRAME_FETCHERS = 4;

    /**
     * Run {@code fetch} over every URI, at most {@link #FRAME_FETCHERS} at a time.
     *
     * <p>Its own pool rather than parallelStream's common one, both for the width and because a
     * cancelled load has to stop the downloads it started: interrupting this thread shuts the
     * pool down under it.
     */
    private static void fetchFrames(List<URI> uris, Consumer<URI> fetch) throws Exception {
        java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(FRAME_FETCHERS);
        try {
            pool.submit(() -> uris.parallelStream().forEach(fetch)).get();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            pool.shutdown();
        }
    }

    /**
     * The shortfall itself is durable and belongs with the layer's other durable facts: the
     * readout in the manage panel reads it off getFailedUris. This is only the record in the log,
     * which is where the individual URIs already are.
     */
    private static void reportShortfall(List<URI> failed, int total) {
        if (!failed.isEmpty())
            Log.warn(failed.size() + " of " + total + " frames did not arrive; Refresh retries them");
    }

    static final String CONNECTING = "Connecting\u2026";
    /** How long the wire has to stay quiet before the readout stops claiming to be downloading. */
    private static final double STALL_SECONDS = 5;

    /**
     * What the load is actually doing, in the verb it is doing it in.
     *
     * <p>Frames off the disk and frames off the wire are not the same event and a single counter
     * hid the difference: a restored session walks from 0 to 45 either way, so a resume that was
     * almost entirely cache looked exactly like starting over. The cached count is split out, and
     * a load that has touched the network only for some of its frames says both numbers.
     *
     * <p>The line is about thirty-six characters wide before the row clips it, which is why the
     * megabytes give way to the cached count once there is one. They are alternatives, not a
     * shortage: while frames are coming off the disk the cached number is what is moving, and
     * while they are coming off the wire the megabytes are.
     *
     * <p>"Waiting on host" is not decoration. This archive goes quiet for minutes at a time, and
     * a frozen "Downloading" beside a spinner is indistinguishable from a hung application.
     */
    private static String progressText(int downloaded, int cached, int total,
                                       long bytes, long startNanos, long lastByteNanos) {
        int done = downloaded + cached;
        if (done == 0 && bytes == 0)
            return CONNECTING;
        String megabytes = String.format("%.0f MB", bytes / 1e6);
        if (bytes == 0) // nothing has crossed the wire; this is the cache being read back
            return "Restoring " + done + "/" + total + " from cache";
        String counts = done + "/" + total;
        if ((System.nanoTime() - lastByteNanos) / 1e9 > STALL_SECONDS)
            return "Waiting on host \u00b7 " + counts + " \u00b7 " + megabytes;
        if (cached > 0)
            return "Downloading " + counts + " \u00b7 " + cached + " cached";
        double seconds = (System.nanoTime() - startNanos) / 1e9;
        String rate = seconds >= 1 ? String.format(" \u00b7 %.1f MB/s", bytes / 1e6 / seconds) : "";
        return "Downloading " + counts + " \u00b7 " + megabytes + rate;
    }

    /**
     * Record what the file holds, and ask which of its images the layer should show.
     *
     * <p>PUNCH's polarized products are cubes: PTM and CTM carry B, pB and pBp at 4096 x 4096, and
     * a layer is one of those rather than all three. Until this existed the file was refused
     * outright for having three axes.
     *
     * <p>Asked here, once, on the first frame: the rest of a movie's frames are built in parallel
     * and every one of them would otherwise put up its own dialog. It is asked only when the
     * layer is looking at something new -- an unchanged list of layer names means the same
     * product, so switching planes from the options panel re-reads the frames in silence rather
     * than asking again for the answer it was just given. Cancelling keeps the current plane.
     *
     * @return whether the answer moved the plane, so the caller knows to build the frame again
     */
    private boolean configurePlanes(View view) {
        List<String> labels = view instanceof URIView uriView ? uriView.planes() : List.of();
        boolean sameProduct = labels.equals(processingSettings.planes());
        EventQueue.invokeLater(() -> processingSettings.setPlanes(labels)); // Swing state, EDT
        if (sameProduct || labels.size() < 2)
            return false;

        int current = Math.clamp(processingSettings.fitsParameters().plane(), 0, labels.size() - 1);
        PlanePanel.PlaneOption[] options = new PlanePanel.PlaneOption[labels.size()];
        for (int i = 0; i < options.length; i++)
            options[i] = new PlanePanel.PlaneOption(i, labels.get(i));

        int[] chosen = {current};
        try {
            EventQueue.invokeAndWait(() -> {
                Object picked = JOptionPane.showInputDialog(MainFrame.get(),
                        "This file holds " + options.length + " images. Which one should the layer show?",
                        "Choose an image", JOptionPane.QUESTION_MESSAGE, null, options, options[current]);
                if (picked instanceof PlanePanel.PlaneOption option)
                    chosen[0] = option.index();
            });
        } catch (InterruptedException e) {
            // The layer was removed or superseded while the dialog was up (a session restore at
            // startup does this to a -load layer). The load is being abandoned, so there is
            // nothing left to ask about; whatever replaces it will ask for itself.
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            Log.errorStack("Could not ask which plane to show", e); // headless: keep the current plane
            return false;
        }
        if (chosen[0] == current)
            return false;

        processingSettings.setPlane(chosen[0]);
        return true;
    }

    private View createView(APIRequest req, URI uri) throws Exception {
        return createView(req, uri, bytes -> {});
    }

    private View createView(APIRequest req, URI uri, java.util.function.LongConsumer onBytes) throws Exception {
        DataUri dataUri = NetFileCache.get(uri, onBytes);
        return switch (dataUri.format()) {
            case JPIP, JP2, JPX -> new J2KView(executor, req, dataUri, processingSettings);
            case FITS, PNG, JPEG -> new URIView(executor, dataUri, processingSettings);
            case ZIP -> loadZip(dataUri.uri());
            default -> throw new Exception("Unknown image type");
        };
    }

    private View loadZip(URI uriZip) throws Exception {
        List<URI> uriList = FileUtils.unZip(uriZip);
        // No preview: a zip is already on disk, so there is no download to wait through, and this
        // runs inside a createView that is itself building someone else's view.
        return loadUri(uriList, view -> view.abolish());
    }

    @Nullable
    private static URI requestAPI(String url) throws Exception {
        try {
            return parseAPIResponse(JSONUtils.get(new URI(url)));
        } catch (SocketTimeoutException e) {
            Log.error("Socket timeout while requesting JPIP URL", e);
            Message.err("Connection timed out", "The server did not respond while loading the layer. Try again in a moment.", e);
        } catch (Exception e) {
            throw new Exception("Invalid response for " + url, e);
        }
        return null;
    }

    @Nullable
    private static URI parseAPIResponse(JSONObject data) throws Exception {
        if (!data.isNull("frames")) {
            JSONArray arr = data.getJSONArray("frames");
            data.put("frames", arr.length()); // don't log timestamps, modifies input
        }
        Log.info(data.toString());

        String message = data.optString("message", null);
        if (message != null) {
            Message.warn("Server Message", message);
        }
        String error = data.optString("error", null);
        if (error != null) {
            Log.error(error);
            Message.err("Error getting the data", error);
            return null;
        }
        return new URI(data.getString("uri"));
    }
}
