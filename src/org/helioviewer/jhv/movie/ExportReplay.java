package org.helioviewer.jhv.movie;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;

import org.helioviewer.jhv.app.state.SessionArchive;
import org.helioviewer.jhv.io.SourceIdentity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Replay an export from its {@code .hfrec} alone: the scene the record carries ({@code native.studio_session}) with the
 * home directory put back, narrowed to one output frame for a movie (the whole range only when asked), its local
 * files found again in the {@code companions.data_archive} beside the record, and a verdict on the picture the
 * replay draws against the one the record describes.
 *
 * <p>Verdicts: IDENTICAL when the hfpix/1 digests agree; EQUIVALENT when the digests differ but the pictures agree
 * within the tolerance below; DIFFERENT when they do not; CANNOT_CHECK when there is nothing comparable (a different
 * size, no digest and no PNG beside the record, a movie frame beyond the first). The target is a pixel-identical
 * picture, not a byte-identical file.
 */
public final class ExportReplay {

    public enum Verdict { IDENTICAL, EQUIVALENT, DIFFERENT, CANNOT_CHECK }

    /** The result of a comparison: the verdict and one plain sentence saying why. */
    public record Outcome(Verdict verdict, String why) {}

    /** A replayable scene: the session to load, and what was done to it, one sentence each. */
    public record Prepared(JSONObject session, List<String> notes, boolean movie, boolean narrowed) {}

    /**
     * The tolerance of an EQUIVALENT picture: none unless the person sets one (system property jhv.replayTolerance, the largest
     * mean absolute difference in levels of 255), because no spread across GPUs has been measured. Without it, pictures whose digests
     * differ are DIFFERENT, with the numbers in the sentence.
     */
    static final String TOLERANCE_PROPERTY = "jhv.replayTolerance";
    static final int OFF_BY = 8; // of 255, only to report how many samples are off by more than this

    @Nullable
    static Double tolerance() {
        String v = System.getProperty(TOLERANCE_PROPERTY);
        try {
            return v == null ? null : Double.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final String STATE = "org.helioviewer.jhv.state";

    private ExportReplay() {}

    /** Whether a file name is a processing record. */
    public static boolean isRecordName(String name) {
        return ProcessingRecord.isSidecarName(name);
    }

    /** The scene in a record, a new object with the home directory put back, or null when the record carries none. */
    @Nullable
    public static JSONObject sessionOf(JSONObject record) {
        JSONObject nat = record.optJSONObject("native");
        JSONObject session = nat == null ? null : nat.optJSONObject("studio_session");
        if (session == null)
            return null;
        // The writer stores Provenance.session(): the scene is inside "scene" (found by running a replay of a real export)
        JSONObject inner = session.optJSONObject("scene");
        if (inner != null && !session.has(STATE))
            session = inner;
        return (JSONObject) Provenance.rewrite(session, s -> Provenance.expandHome(s, System.getProperty("user.home", "")));
    }

    /** Whether the record describes a movie or a series, which a replay shows one frame of. */
    public static boolean isMovie(JSONObject record) {
        JSONObject out = record.optJSONObject("output");
        return out != null && out.optInt("frames", 1) > 1;
    }

    /** The default scope: the whole range only when asked, or when the writer declared the export meaningful as a whole. */
    public static boolean wholeRange(JSONObject record, boolean asked) {
        JSONObject r = record.optJSONObject("reproducibility");
        return asked || (r != null && "range".equals(r.optString("replay_scope")));
    }

    /**
     * The session to load for a record. A still loads as recorded. A movie loads the one output frame the replay
     * checks (the first), plus the frames its difference needs, unless {@code range}; a layer the frame map does not
     * cover loads whole, and the note says so. Local files missing here are looked for in the data archive named
     * under {@code companions.data_archive} beside {@code sidecar}, checked against its recorded SHA-256 first.
     */
    public static Prepared prepare(JSONObject record, Path sidecar, boolean range, Path unpackRoot) throws IOException {
        JSONObject session = sessionOf(record);
        if (session == null)
            throw new IOException("This record carries no scene (native.studio_session), so it cannot be replayed.");
        List<String> notes = new ArrayList<>();
        boolean movie = isMovie(record);
        boolean narrowed = false;
        if (movie && !wholeRange(record, range)) {
            narrowed = narrow(session, record, notes);
        } else if (movie)
            notes.add("Loaded the whole range of the export.");
        archive(session, record, sidecar, unpackRoot, notes);
        return new Prepared(session, notes, movie, narrowed);
    }

    /** Keep, per layer, the sources of the first output frame and what a difference needs; false when no layer could be narrowed. */
    static boolean narrow(JSONObject session, JSONObject record, List<String> notes) {
        JSONObject state = session.optJSONObject(STATE);
        JSONArray layers = state == null ? null : state.optJSONArray("imageLayers");
        JSONObject map = record.optJSONObject("frame_map");
        JSONObject byLayer = map == null ? null : map.optJSONObject("by_layer");
        if (layers == null || byLayer == null) {
            notes.add("The record has no frame map, so the whole range is loaded.");
            return false;
        }
        boolean any = false;
        for (Object o : layers) {
            if (!(o instanceof JSONObject layer))
                continue;
            String id = layer.optString("id");
            JSONObject data = layer.optJSONObject("data");
            JSONArray uris = data == null ? null : data.optJSONArray("uris");
            JSONArray column = byLayer.optJSONArray(id);
            if (uris == null || column == null || column.isEmpty() || column.isNull(0) || column.optInt(0, -1) >= uris.length()
                    || sourceCount(record, id) != uris.length())
                continue; // loads whole: no uri list, no map for this layer, or nothing shown at the first frame
            JSONObject params = data.optJSONObject("imageParams");
            Set<Integer> keep = framesNeeded(params == null ? "None" : params.optString("differenceMode", "None"),
                    data.optJSONObject("sequence") != null, column.getInt(0), uris.length() - 1);
            JSONArray kept = new JSONArray();
            for (int i : keep)
                kept.put(uris.get(i));
            data.put("uris", kept);
            data.remove("fitsRequest"); // a query would load the whole span again
            any = true;
        }
        notes.add(any ? "Loaded the first output frame only (and what its difference needs); \"Load whole range\" loads them all."
                : "No layer could be narrowed to one frame, so the whole range is loaded.");
        return any;
    }

    /** How many sources the record lists for a layer: the frame map indexes that list, which matches the scene's uris only when the counts agree. */
    private static int sourceCount(JSONObject record, String layerId) {
        JSONArray layers = record.optJSONArray("layers");
        if (layers != null)
            for (Object o : layers)
                if (o instanceof JSONObject l && layerId.equals(l.optString("id"))) {
                    JSONArray sources = l.optJSONArray("sources");
                    return sources == null ? -1 : sources.length();
                }
        return -1;
    }

    /** The source indexes one output frame needs: the one shown, what its difference reads, and every one for a sequence filter. */
    static Set<Integer> framesNeeded(String differenceMode, boolean sequenceFilter, int current, int max) {
        Set<Integer> frames = new TreeSet<>();
        frames.add(current);
        if ("Running".equals(differenceMode))
            frames.add(Math.max(current - 1, 0));
        else if ("Base".equals(differenceMode))
            frames.add(0);
        if (sequenceFilter)
            for (int f = 0; f <= max; f++)
                frames.add(f);
        return frames;
    }

    private static void archive(JSONObject session, JSONObject record, Path sidecar, Path unpackRoot, List<String> notes) throws IOException {
        JSONObject companions = record.optJSONObject("companions");
        JSONObject data = companions == null ? null : companions.optJSONObject("data_archive");
        JSONObject state = session.optJSONObject(STATE);
        if (data == null || state == null)
            return;
        Path dir = sidecar.toAbsolutePath().getParent();
        Path archive = dir == null ? null : dir.resolve(data.optString("file_name"));
        if (archive == null || !Files.isRegularFile(archive)) {
            notes.add("The data archive " + data.optString("file_name") + " is not beside the record.");
            return;
        }
        String want = data.optString("sha256");
        String have = SourceIdentity.sha256(archive);
        if (!want.isEmpty() && !want.equals(have)) {
            notes.add("The data archive " + archive.getFileName() + " is not the one the record names (its SHA-256 differs), so it was not used.");
            return;
        }
        SessionArchive.Reattach r = SessionArchive.reattach(state, null, archive, unpackRoot);
        if (r.missing() > 0)
            notes.add("Data archive " + archive.getFileName() + ": " + r.restored() + " of " + r.missing() + " missing local file(s) found.");
    }

    /**
     * The record's local sources that are here and carry the hash the record names, here and different, and not here:
     * {@code [matching, different, missing]}.
     */
    public static int[] checkSources(JSONObject record, JSONObject session) {
        int[] n = new int[3];
        JSONArray layers = record.optJSONArray("layers");
        if (layers == null)
            return n;
        for (Object o : layers) {
            JSONArray sources = o instanceof JSONObject l ? l.optJSONArray("sources") : null;
            if (sources == null)
                continue;
            for (Object s : sources) {
                if (!(s instanceof JSONObject src) || src.optString("sha256").isEmpty() || src.optString("original_name").isEmpty())
                    continue;
                Path found = findLocal(session, src.optString("original_name"));
                if (found == null)
                    n[2]++;
                else if (src.optString("sha256").equals(hashOf(found)))
                    n[0]++;
                else
                    n[1]++;
            }
        }
        return n;
    }

    private static String hashOf(Path p) {
        try {
            return SourceIdentity.sha256(p);
        } catch (IOException e) {
            return "";
        }
    }

    @Nullable
    private static Path findLocal(JSONObject session, String name) {
        JSONObject state = session.optJSONObject(STATE);
        JSONArray layers = state == null ? null : state.optJSONArray("imageLayers");
        if (layers == null)
            return null;
        for (Object o : layers) {
            JSONObject data = o instanceof JSONObject l ? l.optJSONObject("data") : null;
            JSONArray uris = data == null ? null : data.optJSONArray("uris");
            if (uris == null)
                continue;
            for (Object u : uris) {
                try {
                    URI uri = URI.create(u.toString());
                    if ("file".equals(uri.getScheme())) {
                        Path p = Path.of(uri);
                        if (p.getFileName().toString().equals(name) && Files.isRegularFile(p))
                            return p;
                    }
                } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
                    // not a file URI
                }
            }
        }
        return null;
    }

    /**
     * Compare the replay's record with the original's. {@code originalPicture} and {@code replayPicture} are the PNGs when
     * there are any (null for a movie); they are read only when the digests differ.
     */
    public static Outcome verdict(JSONObject original, JSONObject replay, @Nullable Path originalPicture, @Nullable Path replayPicture) {
        JSONObject a = original.optJSONObject("output"), b = replay.optJSONObject("output");
        if (a == null || b == null)
            return new Outcome(Verdict.CANNOT_CHECK, "One of the records has no output block.");
        if (a.has("width") && b.has("width") && (a.optInt("width") != b.optInt("width") || a.optInt("height") != b.optInt("height")))
            return new Outcome(Verdict.CANNOT_CHECK, "The replay is " + b.optInt("width") + " by " + b.optInt("height") + " pixels and the original "
                    + a.optInt("width") + " by " + a.optInt("height") + ".");
        String da = a.optString("pixel_sha256"), db = b.optString("pixel_sha256");
        if (!da.isEmpty() && da.equals(db))
            return new Outcome(Verdict.IDENTICAL, "The pixel digests agree (hfpix/1 " + da.substring(0, 12) + ").");
        if (originalPicture == null || replayPicture == null || !Files.isRegularFile(originalPicture) || !Files.isRegularFile(replayPicture)) {
            return da.isEmpty() ? new Outcome(Verdict.CANNOT_CHECK, "The record has no pixel digest and its picture is not beside it.")
                    : new Outcome(Verdict.CANNOT_CHECK, "The digests differ, and there is no PNG to compare the pictures with.");
        }
        try {
            return compare(ImageIO.read(originalPicture.toFile()), ImageIO.read(replayPicture.toFile()));
        } catch (IOException | RuntimeException e) {
            return new Outcome(Verdict.CANNOT_CHECK, "A picture could not be read: " + e.getMessage());
        }
    }

    static Outcome compare(@Nullable BufferedImage x, @Nullable BufferedImage y) {
        if (x == null || y == null)
            return new Outcome(Verdict.CANNOT_CHECK, "A picture could not be decoded.");
        if (x.getWidth() != y.getWidth() || x.getHeight() != y.getHeight())
            return new Outcome(Verdict.CANNOT_CHECK, "The pictures differ in size.");
        Raster rx = x.getRaster(), ry = y.getRaster();
        int bands = Math.min(Math.min(rx.getNumBands(), ry.getNumBands()), 3);
        double sx = 255.0 / ((1 << x.getColorModel().getComponentSize(0)) - 1), sy = 255.0 / ((1 << y.getColorModel().getComponentSize(0)) - 1);
        double sum = 0;
        long off = 0, n = 0;
        for (int j = 0; j < x.getHeight(); j++)
            for (int i = 0; i < x.getWidth(); i++)
                for (int b = 0; b < bands; b++) {
                    double d = Math.abs(rx.getSample(i, j, b) * sx - ry.getSample(i, j, b) * sy);
                    sum += d;
                    off += d > OFF_BY ? 1 : 0;
                    n++;
                }
        double mean = n == 0 ? 0 : sum / n, frac = n == 0 ? 0 : (double) off / n;
        String numbers = String.format(Locale.ROOT, "mean absolute difference %.3f of 255, %.2f%% of samples off by more than %d", mean, 100 * frac, OFF_BY);
        Double tol = tolerance();
        return tol != null && mean <= tol
                ? new Outcome(Verdict.EQUIVALENT, "The digests differ but the pictures agree within the tolerance you set (" + tol + " of 255): " + numbers + ".")
                : new Outcome(Verdict.DIFFERENT, "The digests differ; " + numbers + ". No tolerance is set, so this is not called equivalent.");
    }

    /** Parse a record file. */
    public static JSONObject read(Path sidecar) throws IOException {
        try {
            return new JSONObject(Files.readString(sidecar, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(sidecar.getFileName() + " is not a processing record: " + e.getMessage(), e);
        }
    }
}
