package org.helioviewer.jhv.movie;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.state.State;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.opengl.GLRenderer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What made an export, written into the export.
 *
 * <p>One block, session(), says which build made the file (buildId, commit, dirty), when, from
 * which data (archive URIs and requests, never a local path) and the whole scene, so the file can
 * be traced and, for a PNG, reopened. Movies get it as ffmpeg's comment (capped, see
 * metadataComment), PNG frames as a tEXt chunk, EXR frames as an attribute. The user's home
 * directory is written as "~" everywhere in it and put back on the way in.
 *
 * <p>The PNG container is tEXt, not iTXt (Gilly's decision A3, 2026-10-02: widest reader support,
 * one container for all PNGs). tEXt is Latin-1, so the JSON is written ASCII-only, every other
 * character as a \\uXXXX escape, which is still the same JSON to any parser.
 *
 * <p>The key reserved for the RHEF stamp is rhef_stamp (decision A2; RHEF owns its format, RH-3).
 * Nothing here writes it; RH-11 adds it to session() beside the existing keys.
 *
 * <p>frame() is the per-frame block ExrCapture used to build inline, moved here unchanged.
 *
 * <p>Every function that can run without a window has a package-private overload taking its
 * inputs, because GLRenderer and the state serializer need one; ProvenanceCheck pins those.
 */
public final class Provenance {

    /** The tEXt keyword in PNG frames, and the EXR attribute name. */
    public static final String PNG_KEYWORD = "hfstudio";
    /** Upper bound on a movie comment in UTF-8 bytes. An estimate: the MP4 comment limit was not verified. */
    static final int METADATA_CAP_BYTES = 16384;

    private static final String RHEF_CAVEAT = "not a calibrated radiance";
    // Copied, not typed from memory: README.md:61 and the vault's projects/rhef.md:6-7.
    private static final String JHV_CITATION = "Müller et al. (2017), Astronomy & Astrophysics, https://doi.org/10.1051/0004-6361/201730893";
    private static final String RHEF_CITATION = "Gilly & Cranmer (2025), Solar Physics, https://doi.org/10.1007/s11207-025-02578-x";

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] TEXT = {'t', 'E', 'X', 't'};
    private static final byte[] IEND_TYPE = {'I', 'E', 'N', 'D'};
    private static final byte[] IEND = {0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    private static final Pattern HOME_REF = Pattern.compile("^(file:/*)?~(?=[/\\\\]|$)");

    // ---- the frame block (ExrCapture's "jhv" attribute) ------------------------------------

    /** The EXR "jhv" attribute for one frame, as ExrCapture wrote it inline; ExrCapture adds "layers". */
    public static JSONObject frame(int index, boolean opaque) {
        MapView mv = GLRenderer.getMapView();
        Position viewpoint = mv.viewpoint();
        Viewport[] viewports = Display.getViewports();
        double[] warp = mv.isHelioradial() || mv.isHelioradialUnrolled()
                ? new double[]{Display.getWarpLambda(), Display.effectiveWarpOuterRadius(), Display.fullWarpFieldRadius()}
                : null;
        return frame(index, opaque, viewpoint.time.toString(), viewpoint.getLocation(),
                Math.toDegrees(viewpoint.lon), Math.toDegrees(viewpoint.lat), viewpoint.distance,
                Display.mode.toString(), Display.gridType.toString(),
                viewports.length > 0 ? mv.cameraWidth(viewports[0]) : JSONObject.NULL, viewports.length, warp);
    }

    static JSONObject frame(int index, boolean opaque, String time, @Nullable String location, double lonDeg, double latDeg,
                            double distanceRsun, String projection, String gridType, Object cameraWidth, int viewports,
                            @Nullable double[] warp) {
        JSONObject frame = new JSONObject()
                .put("writer", writer())
                .put("frame", index)
                .put("time", time)
                .put("projection", projection)
                .put("gridType", gridType)
                .put("viewpoint", new JSONObject()
                        .put("location", location)
                        .put("lonDeg", lonDeg)
                        .put("latDeg", latDeg)
                        .put("distanceRsun", distanceRsun))
                .put("cameraWidth", cameraWidth) // solar radii in the Sun-centred projections, strip units when unrolled
                .put("viewports", viewports)
                .put("whiteBackground", opaque)
                .put("alpha", "premultiplied")
                .put("colorspace", "R,G,B and overlay colours are linear (sRGB EOTF applied to the display-referred render); .Y and .V are data, untouched");
        if (warp != null)
            frame.put("warpLambda", warp[0]).put("warpOuterRadiusRsun", warp[1])
                    .put("warpFieldRadiusRsun", warp[2]); // the warp's own extent; the crop only cuts it
        return frame;
    }

    private static String writer() {
        return AppInfo.stamp();
    }

    // ---- the session block ------------------------------------------------------------------

    /** Build, time, sources and the scene as save() would write it. Call on the thread that may serialize state. */
    public static JSONObject session() {
        return session(State.snapshot(), System.getProperty("user.home", ""), Instant.now());
    }

    static JSONObject session(JSONObject scene, String home, Instant created) {
        JSONObject portable = (JSONObject) rewrite(scene, s -> stripHome(s, home));
        JSONObject session = new JSONObject()
                .put("writer", writer())
                .put("buildId", AppInfo.buildId())
                .put("commit", AppInfo.commit())
                .put("dirty", AppInfo.dirty())
                .put("created", created.truncatedTo(ChronoUnit.SECONDS).toString())
                .put("scene", portable);

        JSONArray sources = new JSONArray();
        boolean rhef = false;
        JSONObject state = portable.optJSONObject("org.helioviewer.jhv.state");
        JSONArray layers = state == null ? null : state.optJSONArray("imageLayers");
        if (layers != null)
            for (Object o : layers) {
                if (!(o instanceof JSONObject layer))
                    continue;
                JSONObject data = layer.optJSONObject("data");
                if (data == null)
                    continue;
                JSONArray uris = new JSONArray();
                JSONArray stored = data.optJSONArray("uris");
                if (stored != null)
                    for (Object u : stored)
                        if (u instanceof String s && !s.startsWith("file:")) // archive addresses only, never a local copy
                            uris.put(s);
                JSONObject source = new JSONObject().put("layer", layer.optString("name")).put("uris", uris);
                if (data.optJSONObject("APIRequest") != null)
                    source.put("request", data.getJSONObject("APIRequest"));
                if (data.optJSONObject("fitsRequest") != null)
                    source.put("query", data.getJSONObject("fitsRequest"));
                sources.put(source);
                rhef |= "RHEF".equals(data.optString("filter"));
            }
        session.put("sources", sources);
        if (rhef)
            session.put("caveat", RHEF_CAVEAT);
        return session;
    }

    /** The scene inside a provenance block with the home directory put back, or null when it has none. */
    @Nullable
    public static JSONObject scene(JSONObject provenance) {
        return scene(provenance, System.getProperty("user.home", ""));
    }

    @Nullable
    static JSONObject scene(JSONObject provenance, String home) {
        JSONObject scene = provenance.optJSONObject("scene");
        return scene == null ? null : (JSONObject) rewrite(scene, s -> expandHome(s, home));
    }

    /** Every string in a JSON tree passed through f, as a new tree; the input is not changed. */
    public static Object rewrite(Object node, UnaryOperator<String> f) {
        if (node instanceof JSONObject o) {
            JSONObject out = new JSONObject();
            for (String key : o.keySet())
                out.put(key, rewrite(o.get(key), f));
            return out;
        }
        if (node instanceof JSONArray a) {
            JSONArray out = new JSONArray();
            for (Object v : a)
                out.put(rewrite(v, f));
            return out;
        }
        return node instanceof String s ? f.apply(s) : node;
    }

    /** The home directory written as "~" wherever it ends at a separator; "/Users/abc" leaves "/Users/abcd" alone. */
    public static String stripHome(String s, String home) {
        if (home.length() < 2)
            return s;
        String out = s.replaceAll(Pattern.quote(home) + "(?=[/\\\\]|$)", "~");
        String slashed = home.replace('\\', '/'); // Windows homes appear with forward slashes inside file: URIs
        return slashed.equals(home) ? out : out.replaceAll(Pattern.quote(slashed) + "(?=/|$)", "~");
    }

    /** The inverse of stripHome for the forms it writes: "~/x", "file:~/x", "file://~/x". */
    static String expandHome(String s, String home) {
        Matcher m = HOME_REF.matcher(s);
        if (!m.find())
            return s;
        String prefix = m.group(1) == null ? "" : m.group(1);
        return prefix + (prefix.isEmpty() ? home : home.replace('\\', '/')) + s.substring(m.end());
    }

    // ---- movies -----------------------------------------------------------------------------

    /** session() for a movie's comment: whole when it fits, else without the scene, else the build alone. */
    public static String metadataComment() {
        return metadataComment(session());
    }

    static String metadataComment(JSONObject session) {
        return metadataComment(session, METADATA_CAP_BYTES);
    }

    static String metadataComment(JSONObject session, int capBytes) {
        String whole = session.toString();
        if (whole.getBytes(StandardCharsets.UTF_8).length <= capBytes)
            return whole;
        JSONObject lighter = new JSONObject(whole); // a copy
        lighter.remove("scene");
        String noScene = lighter.toString();
        if (noScene.getBytes(StandardCharsets.UTF_8).length <= capBytes)
            return noScene;
        lighter.remove("sources");
        return lighter.toString();
    }

    // ---- PNG ----------------------------------------------------------------------------------

    /**
     * Adds json as a tEXt chunk just before IEND. Only the last 12 bytes are rewritten, so a large
     * frame costs what the chunk costs. A file that does not end in IEND (not a PNG, or trailing
     * data) is refused with an IOException and left as it was.
     */
    public static void writePngChunk(Path png, JSONObject json) throws IOException {
        byte[] chunk = textChunk(PNG_KEYWORD, asciiJson(json.toString()));
        try (FileChannel ch = FileChannel.open(png, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long at = ch.size() - IEND.length;
            if (at < PNG_SIGNATURE.length)
                throw new IOException("Not a PNG ending in IEND: " + png);
            ByteBuffer tail = ByteBuffer.allocate(IEND.length);
            while (tail.hasRemaining() && ch.read(tail, at + tail.position()) > 0) {
                // read the last 12 bytes
            }
            if (!Arrays.equals(tail.array(), IEND))
                throw new IOException("Not a PNG ending in IEND: " + png);
            writeFully(ch, ByteBuffer.wrap(chunk), at);
            writeFully(ch, ByteBuffer.wrap(IEND), at + chunk.length);
        }
    }

    private static void writeFully(FileChannel ch, ByteBuffer b, long at) throws IOException {
        while (b.hasRemaining())
            at += ch.write(b, at);
    }

    /**
     * The same JSON with every character outside printable ASCII written as a \\uXXXX escape (a
     * surrogate pair as two), because a tEXt chunk is Latin-1 and a raw byte above 0x7E would be
     * read differently by different tools. Outside string literals the JSON text is already ASCII.
     */
    static String asciiJson(String json) {
        StringBuilder sb = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c < 0x20 || c > 0x7E)
                sb.append(String.format("\\u%04x", (int) c));
            else
                sb.append(c);
        }
        return sb.toString();
    }

    static byte[] textChunk(String keyword, String text) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.writeBytes(keyword.getBytes(StandardCharsets.ISO_8859_1));
        data.write(0); // end of keyword
        data.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1)); // ASCII by construction, see asciiJson
        byte[] body = data.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(TEXT);
        crc.update(body);
        return ByteBuffer.allocate(12 + body.length)
                .putInt(body.length).put(TEXT).put(body).putInt((int) crc.getValue())
                .array();
    }

    /** The JSON in this program's tEXt chunk, or null: not a PNG, no such chunk, a bad CRC or text that is not JSON. */
    @Nullable
    public static JSONObject readPngChunk(Path png) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(png)))) {
            if (!Arrays.equals(in.readNBytes(PNG_SIGNATURE.length), PNG_SIGNATURE))
                return null;
            while (true) {
                int length = in.readInt();
                byte[] type = in.readNBytes(4);
                if (length < 0 || type.length < 4 || Arrays.equals(type, IEND_TYPE))
                    return null;
                if (!Arrays.equals(type, TEXT)) {
                    in.skipNBytes(length + 4L); // data and CRC
                    continue;
                }
                byte[] body = in.readNBytes(length);
                int stored = in.readInt();
                CRC32 crc = new CRC32();
                crc.update(type);
                crc.update(body);
                if (body.length != length || (int) crc.getValue() != stored)
                    continue; // a damaged chunk is not trusted
                String text = textOf(body, PNG_KEYWORD);
                if (text != null)
                    return new JSONObject(text);
            }
        } catch (EOFException | JSONException e) {
            return null; // truncated, or ours but not JSON
        }
    }

    @Nullable
    private static String textOf(byte[] body, String keyword) {
        int k = indexOfZero(body);
        if (k < 0 || !keyword.equals(new String(body, 0, k, StandardCharsets.ISO_8859_1)))
            return null; // another program's keyword
        return new String(body, k + 1, body.length - k - 1, StandardCharsets.ISO_8859_1);
    }

    private static int indexOfZero(byte[] b) {
        for (int i = 0; i < b.length; i++)
            if (b[i] == 0)
                return i;
        return -1;
    }

    // ---- words for people -------------------------------------------------------------------

    /** A block to paste into a paper or a slide note: what made the file, from what, and what to cite. */
    public static String citation(JSONObject session) {
        StringBuilder sb = new StringBuilder()
                .append(AppInfo.programName).append(' ').append(session.optString("buildId")).append('\n')
                .append("Source: ").append(AppInfo.sourceURL).append(" (commit ").append(session.optString("commit")).append(")\n")
                .append("Created: ").append(session.optString("created")).append('\n');
        JSONArray sources = session.optJSONArray("sources");
        if (sources != null && !sources.isEmpty()) {
            sb.append("Data:\n");
            for (Object o : sources) {
                if (!(o instanceof JSONObject s))
                    continue;
                sb.append("  ").append(s.optString("layer"));
                JSONArray uris = s.optJSONArray("uris");
                if (uris != null && !uris.isEmpty())
                    sb.append(": ").append(uris.optString(0)).append(uris.length() > 1 ? " and " + (uris.length() - 1) + " more" : "");
                else if (s.optJSONObject("request") != null)
                    sb.append(": ").append(s.getJSONObject("request"));
                sb.append('\n');
            }
        }
        sb.append("Cite: ").append(JHV_CITATION).append('\n');
        if (session.has("caveat"))
            sb.append("RHEF: ").append(RHEF_CITATION).append("; RHEF output is ").append(session.optString("caveat")).append('\n');
        JSONObject compact = new JSONObject(session.toString());
        compact.remove("scene");
        return sb.append("Provenance: ").append(compact).toString();
    }

    /** The burned-in credit under the timestamp: the program, the master layer, and the RHEF caveat when it applies. */
    public static String creditLine(String layerName, boolean rhef) {
        StringBuilder sb = new StringBuilder("Made with ").append(AppInfo.programName);
        if (!layerName.isBlank())
            sb.append(" | ").append(layerName);
        if (rhef)
            sb.append(" | RHEF: ").append(RHEF_CAVEAT);
        return sb.toString();
    }

    private Provenance() {}

}
