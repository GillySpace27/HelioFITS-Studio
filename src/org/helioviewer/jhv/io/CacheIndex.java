package org.helioviewer.jhv.io;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.metadata.FitsMetaData;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;

/**
 * What is already on disk, as datasets rather than as hashes.
 *
 * <p>{@link NetFileCache} is content-addressed: one FITS frame per file, named
 * {@code sha256(uri)}, one-way, with no index. The source URL cannot be recovered from a cached
 * file, so there is no record anywhere of what a given file is except the file itself. That turns
 * out to be the better source anyway, because the FITS header is authoritative about what the data
 * IS, where a URL is only authoritative about where it came from.
 *
 * <p>Reading them is not free. Measured on a real cache of 754 files and 13 GB, a full header pass
 * takes about two and a half seconds, which is fine once and much too long every time a dialog
 * opens. So the result is written out and each record is keyed on the file's name, length and
 * modification time; a later pass re-reads only the files whose key has changed, which after the
 * first scan is whatever has been downloaded since.
 *
 * <p>Reading a file needs SPICE loaded, which is not obvious and is not this class's doing:
 * {@code CommonMetaData} holds {@code Sun.StartEarth} as a static field, so merely loading the
 * metadata classes initialises the ephemeris. In the application that is settled long before any
 * of this can run, since {@code AppInit.loadSpice()} is called before the window is built. It does
 * mean a check cannot exercise {@link #read(File)}, so what the checks cover is the arithmetic
 * either side of it: the overlap classification, the grouping, and the index round trip.
 */
public final class CacheIndex {

    /**
     * One cached frame: where it is, what it belongs to, and when it was observed.
     *
     * <p>The three product cards are kept apart from the dataset key rather than only baked into
     * it, so the dialog can sort on them. Version especially: {@code 0k} and {@code 0l} are two
     * cache entries for one observation, and putting a version column next to a frame count is how
     * you see that you already have the data and only the label moved.
     */
    public record Frame(String fileName, String dataset, String displayName,
                        String level, String typeCode, String version, long time, long bytes) {}

    /** Frames that share an identity, which is what the dialog lists and what loads as one layer. */
    public record Dataset(String key, String displayName, String level, String typeCode, String version,
                          List<Frame> frames, long start, long end, long bytes) {

        public int frameCount() {
            return frames.size();
        }

        /** Median frame spacing in milliseconds, or 0 for a dataset of one. */
        public long cadence() {
            if (frames.size() < 2)
                return 0;
            long[] gaps = new long[frames.size() - 1];
            for (int i = 1; i < frames.size(); i++)
                gaps[i - 1] = frames.get(i).time() - frames.get(i - 1).time();
            java.util.Arrays.sort(gaps);
            return gaps[gaps.length / 2];
        }
    }

    /**
     * How a dataset stands against the master time range.
     *
     * <p>Coverage, not containment, and the difference decides what the colours mean. The question
     * being asked is "can I use this instead of downloading it again", so what matters is whether
     * the dataset reaches across the range that is going to be played. Containment would paint a
     * dataset green for being SMALL, which is the opposite of the answer wanted.
     */
    public enum Overlap {
        /** Spans the whole master range: load it and nothing is missing. */
        COVERS,
        /** Reaches into the range without spanning it. */
        PARTIAL,
        /** Disjoint. Still listed and still loadable: loading one is how you move the range to it. */
        NONE
    }

    /**
     * Where a span stands against another. Pure, so the check can pin the edges, of which there
     * are more than they look.
     *
     * <p>A master range with no duration reports NONE for everything, rather than COVERS for
     * everything. Both are defensible in the abstract and only one is safe on screen: a row of
     * green chips says "you already have all of this", and being wrong about that is the download
     * this whole dialog exists to avoid.
     *
     * <p>Two real spans that meet at a single instant do not overlap. A dataset ending exactly
     * when the master range begins contributes one frame at the boundary and nothing that plays.
     * A dataset that IS a single instant is the exception: it has no span to share, so the same
     * test would grey out a lone cached frame sitting squarely inside the range, which is a wrong
     * label rather than a conservative one.
     */
    public static Overlap overlap(long start, long end, long rangeStart, long rangeEnd) {
        if (rangeEnd <= rangeStart || end < start)
            return Overlap.NONE;
        if (start <= rangeStart && end >= rangeEnd && end > start)
            return Overlap.COVERS;
        long from = Math.max(start, rangeStart);
        long to = Math.min(end, rangeEnd);
        if (from > to)
            return Overlap.NONE;
        if (from == to) // they meet at one instant: a lone frame is in, two spans merely touch
            return start == end ? Overlap.PARTIAL : Overlap.NONE;
        return Overlap.PARTIAL;
    }

    /** What fraction of the master range this span reaches, 0 to 1, for the row's own words. */
    public static double coverage(long start, long end, long rangeStart, long rangeEnd) {
        if (rangeEnd <= rangeStart)
            return 0;
        long from = Math.max(start, rangeStart);
        long to = Math.min(end, rangeEnd);
        return to <= from ? 0 : (to - from) / (double) (rangeEnd - rangeStart);
    }

    // -- scanning --------------------------------------------------------------------------

    private static final String INDEX_NAME = "cacheIndex.json";
    private static final int INDEX_VERSION = 2; // bump to force a full re-read after a format change
    private static final int MAX_HDUS = 4; // an identity card is in the first image HDU or not there

    private static File indexFile() {
        return new File(Directories.SETTINGS.getFile(), INDEX_NAME);
    }

    /**
     * Every cached frame, reading only what has changed since the last pass.
     *
     * @param progress called with the number of files read so far, for a scan that has to be shown
     */
    public static List<Frame> scan(@Nullable IntConsumer progress) {
        Map<String, Frame> known = read();
        Map<String, Frame> found = new LinkedHashMap<>();
        File[] files = Directories.FILECACHE.getFile().listFiles(File::isFile);
        if (files == null)
            return List.of();
        int done = 0;
        for (File file : files) {
            String key = stamp(file);
            Frame cached = known.get(key);
            found.put(key, cached != null ? cached : read(file));
            if (progress != null)
                progress.accept(++done);
        }
        found.values().removeIf(java.util.Objects::isNull);
        write(found);
        return List.copyOf(found.values());
    }

    /** Name, length and modification time: a file that differs in any of them has to be re-read. */
    private static String stamp(File file) {
        return file.getName() + ':' + file.length() + ':' + file.lastModified();
    }

    /**
     * One file's identity, or null if it does not have one.
     *
     * <p>The identity cards are looked for through the first few HDUs rather than only in the
     * primary, because a tile-compressed frame keeps its header on the binary table that holds the
     * compressed image; roughly a third of the frames measured were of that kind.
     */
    @Nullable
    private static Frame read(File file) {
        try (Fits fits = new Fits(file)) {
            for (int i = 0; i < MAX_HDUS; i++) {
                BasicHDU<?> hdu = fits.readHDU();
                if (hdu == null)
                    break;
                Header header = hdu.getHeader();
                if (!header.containsKey("TELESCOP") && !header.containsKey("INSTRUME"))
                    continue;
                FitsHeaderContainer container = new FitsHeaderContainer(header);
                FitsMetaData.Observation obs = FitsMetaData.observation(container);
                String level = container.getString("LEVEL").orElse("");
                String typeCode = container.getString("TYPECODE").orElse("");
                String version = container.getString("FILEVRSN").or(() -> container.getString("FILE_VRSN")).orElse("");
                return new Frame(file.getName(), datasetKey(obs.displayName(), level, typeCode, version),
                        obs.displayName(), level, typeCode, version, obs.time().milli, file.length());
            }
        } catch (Exception e) { // a truncated or half-written file is not an error worth a dialog
            Log.warn("Cache scan skipped " + file.getName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * What makes two frames the same dataset.
     *
     * <p>The application's own display name carries the mission, instrument and detector, and is
     * what the layer will be called, so it leads. It is not enough on its own: PUNCH's clear and
     * polarized mosaics are both "WFI+NFI Mosaic" and are different products, and the file version
     * is the whole point of this dialog, since {@code 0k} and {@code 0l} are two cache entries for
     * one observation. Those three cards are appended where a file has them.
     */
    static String datasetKey(String displayName, String level, String typeCode, String version) {
        StringBuilder sb = new StringBuilder(displayName);
        String tidyLevel = level(level);
        if (!tidyLevel.isEmpty())
            sb.append(" · ").append(tidyLevel);
        if (!typeCode.isEmpty())
            sb.append(" · ").append(typeCode);
        if (!version.isEmpty())
            sb.append(" · v").append(version);
        return sb.toString();
    }

    /**
     * A processing level as a short label, or nothing.
     *
     * <p>LEVEL is not a standard card and instruments use it however they like. PUNCH writes
     * {@code '3'}, which wants an L in front of it. Proba-3 writes {@code 'L3'}, which does not,
     * and got one anyway: the first real scan produced "ASPIICS · LL3". GOES SUVI writes the whole
     * of "National Aeronautics and Space Administration (NASA) L1b", which is a provenance
     * statement rather than a level, and turned a dataset name into a paragraph.
     *
     * <p>So: an L is added only when one is missing, and anything too long to be a level code is
     * not treated as one. Dropping it costs nothing, because the display name it would have been
     * appended to already carries the mission and instrument.
     */
    static String level(String level) {
        String tidy = level.trim();
        if (tidy.isEmpty() || tidy.length() > MAX_LEVEL_LENGTH)
            return "";
        return tidy.charAt(0) == 'L' || tidy.charAt(0) == 'l' ? tidy : "L" + tidy;
    }

    private static final int MAX_LEVEL_LENGTH = 6; // "L1b" and "3" are levels; a sentence is not

    /** The frames gathered into datasets, each ordered in time, the largest first. */
    public static List<Dataset> group(List<Frame> frames) {
        Map<String, List<Frame>> byKey = new LinkedHashMap<>();
        for (Frame frame : frames)
            byKey.computeIfAbsent(frame.dataset(), k -> new ArrayList<>()).add(frame);

        List<Dataset> out = new ArrayList<>(byKey.size());
        byKey.forEach((key, list) -> {
            list.sort(Comparator.comparingLong(Frame::time));
            long bytes = 0;
            for (Frame frame : list)
                bytes += frame.bytes();
            Frame first = list.getFirst();
            out.add(new Dataset(key, first.displayName(), first.level(), first.typeCode(), first.version(),
                    List.copyOf(list), first.time(), list.getLast().time(), bytes));
        });
        out.sort(Comparator.comparingLong(Dataset::bytes).reversed());
        return out;
    }

    // -- the stored index ------------------------------------------------------------------

    /** Stamp to frame, as last written. An unreadable or outdated index simply means a full scan. */
    static Map<String, Frame> read() {
        Map<String, Frame> out = new LinkedHashMap<>();
        Path path = indexFile().toPath();
        if (!Files.isReadable(path))
            return out;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JSONObject root = new JSONObject(new JSONTokener(reader));
            if (root.optInt("version") != INDEX_VERSION)
                return out;
            JSONArray entries = root.optJSONArray("frames");
            for (int i = 0; entries != null && i < entries.length(); i++) {
                JSONObject o = entries.getJSONObject(i);
                out.put(o.getString("stamp"), new Frame(o.getString("file"), o.getString("dataset"),
                        o.getString("name"), o.optString("level"), o.optString("type"), o.optString("version"),
                        o.getLong("time"), o.getLong("bytes")));
            }
        } catch (Exception e) {
            Log.warn("Cache index unreadable, rescanning: " + e.getMessage());
            return new LinkedHashMap<>();
        }
        return out;
    }

    static void write(Map<String, Frame> frames) {
        JSONArray entries = new JSONArray();
        frames.forEach((stamp, frame) -> entries.put(new JSONObject()
                .put("stamp", stamp)
                .put("file", frame.fileName())
                .put("dataset", frame.dataset())
                .put("name", frame.displayName())
                .put("level", frame.level())
                .put("type", frame.typeCode())
                .put("version", frame.version())
                .put("time", frame.time())
                .put("bytes", frame.bytes())));
        JSONObject root = new JSONObject().put("version", INDEX_VERSION).put("frames", entries);
        try (BufferedWriter writer = Files.newBufferedWriter(indexFile().toPath(), StandardCharsets.UTF_8)) {
            root.write(writer);
        } catch (Exception e) { // losing the index costs a rescan, never data
            Log.warn("Could not write the cache index: " + e.getMessage());
        }
    }

    private CacheIndex() {}

}
