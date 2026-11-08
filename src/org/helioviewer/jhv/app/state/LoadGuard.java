package org.helioviewer.jhv.app.state;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.time.TimeUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What a session is about to download, worked out from its JSON before a single layer is built.
 *
 * <p>Opening a session used to start every layer's request with no question asked, so a file that
 * named a month at a high cadence began a download of hundreds of thousands of frames. This reads
 * only what the file says (the request span and cadence, the list of frame URIs) and the cache, so
 * it is cheap and touches no network. Over the limit, the person sees the manifest and decides
 * (LoadGuardDialog); what they postpone is held on its layer, not dropped, so a save keeps it.
 *
 * <p>The limits are Settings, not constants, because they are a tradeoff: {@link #KEY_FRAMES} and
 * {@link #KEY_MB}. Either one trips the guard.
 *
 * <p>Sizes are estimates, and the manifest says so. A streamed JPEG 2000 frame and a FITS mosaic
 * are not the same size and the file does not record either, so each uses a fixed assumption
 * ({@link #JPX_FRAME_BYTES}, {@link #FITS_FRAME_BYTES}); a frame already in the file cache is
 * exactly zero to download. A layer that only remembers an archive query (no URI list) is counted
 * as unknown, because its frame count exists only after the archive answers.
 */
public final class LoadGuard {

    public static final String KEY_FRAMES = "load.confirmFrames";
    public static final String KEY_MB = "load.confirmMB";
    public static final int DEFAULT_FRAMES = 1000;
    public static final int DEFAULT_MB = 1024; // 1 GB

    // Assumed, not measured: ponytail, replace with a per-source figure once the archive's real sizes are recorded.
    static final long JPX_FRAME_BYTES = 1_000_000L;
    // ImageLayerLoader's own comment puts a PUNCH mosaic at "tens of megabytes"; 25 MB is a mid figure.
    static final long FITS_FRAME_BYTES = 25_000_000L;
    // "Get all" (cadence <= 0): the server thins a movie to at most this many frames (APIRequest.chunks).
    static final long SERVER_FRAME_CAP = 1000;

    /** One layer's share of the load. {@code at}: its place in the imageLayers array. {@code unknown}: the frame count is not in the file. */
    public record Item(int at, String name, String source, long frames, long cached, long bytes, boolean unknown) {
        public long toDownload() {
            return frames - cached;
        }
    }

    public record Estimate(List<Item> items) {
        public long frames() {
            return items.stream().mapToLong(Item::toDownload).sum();
        }

        public long bytes() {
            return items.stream().mapToLong(Item::bytes).sum();
        }

        public long cached() {
            return items.stream().mapToLong(Item::cached).sum();
        }

        /** Whether the layer at this place in imageLayers has anything to download. */
        public boolean downloads(int at) {
            return items.stream().anyMatch(i -> i.at() == at && i.toDownload() > 0);
        }

        public boolean over(long maxFrames, long maxBytes) {
            return frames() > maxFrames || bytes() > maxBytes;
        }
    }

    private LoadGuard() {}

    public static long maxFrames() {
        return setting(KEY_FRAMES, DEFAULT_FRAMES);
    }

    public static long maxBytes() {
        return setting(KEY_MB, DEFAULT_MB) * 1_000_000L;
    }

    private static long setting(String key, int fallback) {
        try {
            long v = Long.parseLong(Settings.getProperty(key).trim());
            return v >= 0 ? v : fallback;
        } catch (RuntimeException e) { // unset or not a number
            return fallback;
        }
    }

    /**
     * The estimate for a session's {@code imageLayers} array. {@code cached} says whether a frame's
     * URI is already on this computer (NetFileCache in the application).
     */
    public static Estimate estimate(JSONArray imageLayers, Predicate<URI> cached) {
        List<Item> items = new ArrayList<>();
        if (imageLayers == null)
            return new Estimate(items);
        for (int at = 0; at < imageLayers.length(); at++) {
            if (!(imageLayers.opt(at) instanceof JSONObject entry) || !(entry.optJSONObject("data") instanceof JSONObject data))
                continue;
            String name = entry.optString("name", "Layer " + (items.size() + 1));
            JSONObject api = data.optJSONObject("APIRequest");
            JSONArray uris = data.optJSONArray("uris");
            if (api != null)
                items.add(streamed(at, name, api));
            else if (uris != null)
                items.add(files(at, name, uris, cached));
            else if (data.has("fitsRequest"))
                items.add(new Item(at, name, "archive query", 0, 0, 0, true));
        }
        return new Estimate(items);
    }

    private static Item streamed(int at, String name, JSONObject api) {
        long t = System.currentTimeMillis();
        long start = TimeUtils.optParse(api.optString("startTime"), t - 2 * TimeUtils.DAY_IN_MILLIS);
        long end = TimeUtils.optParse(api.optString("endTime"), t);
        int cadence = api.optInt("cadence", TimeUtils.defaultCadence(start, end));
        long frames = cadence <= 0 ? SERVER_FRAME_CAP : Math.max(1, (end - start) / (cadence * 1000L) + 1);
        return new Item(at, name, "Helioviewer stream, " + cadenceText(cadence), frames, 0, frames * JPX_FRAME_BYTES, false);
    }

    private static Item files(int at, String name, JSONArray uris, Predicate<URI> cached) {
        long remote = 0, here = 0;
        for (Object o : uris) {
            String s = String.valueOf(o);
            if (!s.toLowerCase(Locale.ROOT).startsWith("http")) // a local file is read, never downloaded
                continue;
            remote++;
            if (cached.test(URI.create(s)))
                here++;
        }
        return new Item(at, name, "files", remote, here, (remote - here) * FITS_FRAME_BYTES, false);
    }

    private static String cadenceText(int cadence) {
        return cadence <= 0 ? "every frame" : "every " + TimeUtils.formatDurationSig(cadence * 1000L);
    }

    /** "1.2 GB" or "340 MB". */
    public static String sizeText(long bytes) {
        return bytes >= 1_000_000_000L ? String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)
                : String.format(Locale.ROOT, "%.0f MB", bytes / 1e6);
    }
}
