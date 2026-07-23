package org.helioviewer.jhv.layers;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.io.APIRequest;
import org.helioviewer.jhv.io.DataUri;
import org.helioviewer.jhv.io.DataUri.Format.Image;
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
import org.helioviewer.jhv.view.uri.URIView;

import org.json.JSONArray;
import org.json.JSONObject;

final class ImageLayerLoader {

    private final LatestWorker<DecodedImage> executor = new LatestWorker<>("View-Decoder");
    private final Consumer<View> onViewLoaded;
    private final Runnable onUnload;
    private final Consumer<String> statusSink; // load-stage readout; null clears
    private final Consumer<List<URI>> onFailedUris; // URIs that failed during a multi-frame load

    private Future<View> loadFuture;
    private Future<?> downloadFuture;
    private int loadGeneration;

    ImageLayerLoader(@Nonnull Consumer<View> _onViewLoaded, @Nonnull Runnable _onUnload, @Nonnull Consumer<String> _statusSink,
                      @Nonnull Consumer<List<URI>> _onFailedUris) {
        onViewLoaded = _onViewLoaded;
        onUnload = _onUnload;
        statusSink = _statusSink;
        onFailedUris = _onFailedUris;
    }

    void load(APIRequest req) {
        cancelLoad();
        int gen = ++loadGeneration;
        loadFuture = Task.submit("request", () -> {
                    statusSink.accept("Contacting server\u2026");
                    URI uri = requestAPI(req.toJpipRequest());
                    if (uri == null)
                        return null;
                    statusSink.accept("Opening image stream\u2026");
                    return createView(req, uri);
                },
                result -> onSuccess(result, gen),
                (logContext, t) -> onFailure(t, gen));
    }

    void load(List<URI> uriList) {
        cancelLoad();
        onFailedUris.accept(List.of()); // clear any stale failures from a previous load
        int gen = ++loadGeneration;
        loadFuture = Task.submit(uriList.toString(), () -> loadUri(uriList),
                result -> onSuccess(result, gen),
                (logContext, t) -> onFailure(t, gen));
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
        executor.abolish();
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

    private void onFailure(Throwable t, int gen) {
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
        Message.err("Error getting the data", t.getMessage());
    }

    private View loadUri(List<URI> uriList) throws Exception {
        int total = uriList.size();
        if (total == 1) {
            statusSink.accept("Connecting\u2026");
            return createView(null, uriList.getFirst());
        } else {
            // ponytail: frame-count granularity only; per-file byte progress needs NetFileCache changes
            statusSink.accept("Connecting \u2014 0/" + total + " frames\u2026");
            java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
            List<URI> failed = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            List<View> views = uriList.parallelStream().map(uri -> {
                try {
                    View v = createView(null, uri);
                    statusSink.accept("Retrieving \u2014 " + done.incrementAndGet() + "/" + total + " frames\u2026");
                    return v;
                } catch (Exception e) {
                    Log.warn(uri.toString(), e);
                    failed.add(uri); // remembered so the layer can report it as retryable, not just absent
                    statusSink.accept("Retrieving \u2014 " + done.incrementAndGet() + "/" + total + " frames\u2026");
                    return null;
                }
            }).filter(Objects::nonNull).toList();
            statusSink.accept("Assembling " + views.size() + " frames\u2026");
            onFailedUris.accept(failed);
            return new ManyView(views);
        }
    }

    private View createView(APIRequest req, URI uri) throws Exception {
        DataUri dataUri = NetFileCache.get(uri);
        return switch (dataUri.format()) {
            case Image.JPIP, Image.JP2, Image.JPX -> new J2KView(executor, req, dataUri);
            case Image.FITS, Image.PNG, Image.JPEG -> new URIView(executor, dataUri);
            case Image.ZIP -> loadZip(dataUri.uri());
            default -> throw new Exception("Unknown image type");
        };
    }

    private View loadZip(URI uriZip) throws Exception {
        List<URI> uriList = FileUtils.unZip(uriZip);
        return loadUri(uriList);
    }

    @Nullable
    private static URI requestAPI(String url) throws Exception {
        try {
            return parseAPIResponse(JSONUtils.get(new URI(url)));
        } catch (SocketTimeoutException e) {
            Log.error("Socket timeout while requesting JPIP URL", e);
            Message.err("Socket timeout", "Socket timeout while requesting JPIP URL.");
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
            Message.warn("Warning", message);
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
