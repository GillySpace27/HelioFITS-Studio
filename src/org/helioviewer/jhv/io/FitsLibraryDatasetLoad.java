package org.helioviewer.jhv.io;

import java.io.File;
import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.time.TimeUtils;

/**
 * One HelioFITS Core dataset, named by its {@code dataset_uid} or {@code dataset_key}, loaded as one image
 * layer from its current files in a time range: what {@code -dataset} on the command line and the
 * {@code hfcore.dataset.load} SAMP message do. The same files the FITS Library tab's Add would load,
 * read the same way (FitsLibrary, read-only).
 */
public final class FitsLibraryDatasetLoad {

    /** The dataset could not be loaded; the message says why in words for a person. */
    public static final class Failed extends Exception {
        Failed(String message) {
            super(message);
        }
    }

    private FitsLibraryDatasetLoad() {}

    /**
     * Load the dataset's files in [start, end] (ISO 8601 UTC, either may be null for open) as one
     * layer. The index is read off the event thread; the layer is created on it. The future fails
     * with {@link Failed} when there is no library, no such dataset, or nothing in the range.
     */
    public static CompletableFuture<ImageLayer> load(String uidOrKey, @Nullable String start, @Nullable String end) {
        CompletableFuture<ImageLayer> out = new CompletableFuture<>();
        long from, to;
        try {
            from = start == null ? Long.MIN_VALUE : parse(start);
            to = end == null ? Long.MAX_VALUE : parse(end);
        } catch (Failed e) {
            out.completeExceptionally(e);
            return out;
        }
        Task.submitBackground(() -> files(FitsLibrary.find(), uidOrKey, from, to), uris ->
                Commands.loadImage(uris).whenComplete((layer, t) -> {
                    if (t != null)
                        out.completeExceptionally(t);
                    else
                        out.complete(layer);
                }), out::completeExceptionally);
        return out;
    }

    /** A time as the command line and SAMP take it: ISO 8601 UTC, with or without a trailing Z. */
    public static long parse(String iso) throws Failed {
        String text = iso.endsWith("Z") ? iso.substring(0, iso.length() - 1) : iso;
        try {
            return TimeUtils.parse(text);
        } catch (RuntimeException e) {
            throw new Failed("Not an ISO 8601 time: " + iso);
        }
    }

    /** The URIs of the dataset's existing current files in [from, to], in time order. Package-private for the check. */
    static List<URI> files(@Nullable FitsLibrary.Location lib, String uidOrKey, long from, long to) throws Failed {
        if (lib == null)
            throw new Failed("No FITS library on this computer.");
        try {
            String key = FitsLibrary.datasetKey(lib, uidOrKey);
            if (key == null)
                throw new Failed("No dataset " + uidOrKey + " in the FITS library.");
            List<File> files = from == Long.MIN_VALUE && to == Long.MAX_VALUE
                    ? FitsLibrary.files(lib, key) : FitsLibrary.files(lib, key, Math.max(from, 0), Math.min(to, MAX_TIME));
            List<URI> uris = new ArrayList<>();
            for (File f : files)
                if (f.isFile()) // gone from the store since the index was written
                    uris.add(f.toURI());
            if (uris.isEmpty())
                throw new Failed("Dataset " + uidOrKey + " has no files in the FITS library for that time range.");
            return uris;
        } catch (SQLException | FitsLibrary.TooNew e) {
            throw new Failed("Could not read the FITS library: " + e.getMessage());
        }
    }

    // 9999-12-31T23:59:59.999: the index writes four-digit years, so this is "no end".
    private static final long MAX_TIME = 253402300799999L;

}
