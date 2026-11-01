package org.helioviewer.jhv.io;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.helioviewer.jhv.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.CharacterData;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Use FITS for a Helioviewer layer: the FITS files behind the JPEG 2000 frames it streams.
 *
 * <p>Each frame Helioviewer serves carries the FITS header it was made from. This sends those
 * headers to HelioFITS Core's {@code layer.upgrade} (docs/api.md in the HelioFITS Core repository), which
 * reads them with ingest's own rules and answers, per frame, whether the library already keeps the
 * FITS file and, for the rest, the fetch that would get them at the layer's own cadence. HelioFITS Core
 * does the fetching; Studio opens the files it then holds as a new layer and leaves the
 * Helioviewer layer as it is.
 */
public final class UseFits {

    /** The keywords sent per frame: those HelioFITS Core's ingest reads (fitsmeta.INDEXED_KEYWORDS) for
     *  what a file is and when, plus HMI's CONTENT, and LASCO's TIME_OBS (its pictures date with DATE_OBS plus TIME_OBS), FILTER and POLAR. */
    static final List<String> KEYS = List.of(
            "OBSRVTRY", "TELESCOP", "INSTRUME", "DETECTOR", "WAVELNTH", "WAVEUNIT", "WAVE_STR",
            "LEVEL", "LVL_NUM", "TYPECODE", "OBSCODE", "FILEVRSN", "DATE-OBS", "DATE_OBS",
            "DATE-BEG", "DATE-END", "DATE-AVG", "TIME-OBS", "TIME_OBS", "TIMESYS", "BTYPE", "CONTENT", "FILTER", "POLAR");

    private static final Duration PLAN_WAIT = Duration.ofMinutes(2);
    private static final Duration CALL_WAIT = Duration.ofSeconds(30);
    private static final long POLL_MS = 3000;
    private static final Set<String> ENDED = Set.of("done", "partial", "failed", "cancelled");

    /** No HelioFITS Core could be started (none packaged with Studio, no Cache Manager and no HFCORE_CORE). */
    public static final class NoCore extends IOException {
        private static final long serialVersionUID = 1L;
        /** Why there is none, in words for a dialog. */
        public static final String TEXT = "Use FITS needs the FITS library engine (HelioFITS Core), and this Studio has none: it was not packaged with one, "
                + "no HelioFITS Cache Manager is installed and HFCORE_CORE is not set. Studio's macOS release carries its own; elsewhere, set "
                + "HFCORE_CORE to a Python that has HelioFITS Core (heliofits-core) installed.";

        NoCore() {
            super(TEXT);
        }
    }

    /** The core answered that it has no {@code layer.upgrade}: it is older than this Studio. */
    public static final class TooOld extends IOException {
        private static final long serialVersionUID = 1L;

        TooOld() {
            super("The program serving your FITS library is older than this Studio and cannot answer Use FITS. "
                    + "If the HelioFITS Cache Manager (or Heliad) is open, it serves the library for every program: quit it or update it, then try again.");
        }
    }

    private UseFits() {}

    /** Whether the layer's view streams Helioviewer frames, the only kind this upgrades. */
    public static boolean applies(@Nullable View view) {
        return view != null && view.getAPIRequest() != null;
    }

    /**
     * The keywords of one frame's metadata XML that KEYS names, read as XMLMetaDataContainer reads
     * them (the first element of that name under {@code meta}); empty when there is none.
     */
    static JSONObject header(DocumentBuilder builder, @Nullable String xml) {
        JSONObject out = new JSONObject();
        if (xml == null || xml.equals(View.EMPTY_METAXML))
            return out;
        Element meta;
        try {
            builder.reset();
            meta = (Element) builder.parse(new InputSource(new StringReader(xml))).getElementsByTagName("meta").item(0);
        } catch (SAXException | IOException e) { // a frame whose metadata cannot be read: nothing to send
            return out;
        }
        if (meta == null)
            return out;
        for (String key : KEYS) {
            NodeList nodes = meta.getElementsByTagName(key);
            for (int i = 0; i < nodes.getLength(); i++)
                if (nodes.item(i).getFirstChild() instanceof CharacterData cd) {
                    String value = cd.getData().trim();
                    if (!value.isEmpty())
                        out.put(key, value);
                    break;
                }
        }
        return out;
    }

    /** One header per frame, in frame order. */
    public static JSONArray headers(View view) throws IOException {
        DocumentBuilder builder = builder();
        JSONArray out = new JSONArray();
        for (int i = 0; i <= view.getMaximumFrameNumber(); i++)
            out.put(header(builder, view.getXMLMetaData(i)));
        return out;
    }

    /** A parser that reads no DTD and no external entity, as XMLUtils's does. */
    static DocumentBuilder builder() throws IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler()); // a bad frame is reported by its empty header, not on stderr
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IOException("no XML parser for frame metadata", e);
        }
    }

    /** {@code layer.upgrade}: the plan, or with dryRun false the plan with each group's queued job. */
    public static JSONObject plan(Path root, JSONArray headers, boolean dryRun) throws IOException {
        JSONObject params = new JSONObject().put("headers", headers).put("dry_run", dryRun);
        JSONObject out;
        try {
            out = FitsCoreIngest.call(root, "layer.upgrade", params, PLAN_WAIT);
        } catch (FitsCoreIngest.RpcError e) {
            throw explained(e);
        }
        if (out == null)
            throw new NoCore();
        return out;
    }

    /** A core's error, as TooOld when the core has no layer.upgrade. */
    static IOException explained(FitsCoreIngest.RpcError e) {
        return e.code == FitsCoreIngest.RpcError.METHOD_NOT_FOUND ? new TooOld() : e;
    }

    /** How many frames have the given status in a plan's counts. */
    public static int count(JSONObject plan, String status) {
        JSONObject counts = plan.optJSONObject("counts");
        return counts == null ? 0 : counts.optInt(status);
    }

    /** The library's FITS files for the frames that have one, in frame order, each once, still on disk. */
    public static List<URI> files(JSONObject plan) {
        Set<String> paths = new LinkedHashSet<>();
        JSONArray frames = plan.optJSONArray("frames");
        if (frames != null)
            for (int i = 0; i < frames.length(); i++) {
                JSONObject f = frames.optJSONObject(i);
                if (f != null && "in_library".equals(f.optString("status")) && !f.optString("path").isEmpty())
                    paths.add(f.optString("path"));
            }
        List<URI> out = new ArrayList<>();
        for (String p : paths) {
            File file = new File(p);
            if (file.isFile())
                out.add(file.toURI());
        }
        return out;
    }

    /** The ids of the jobs a plan queued. */
    public static List<Integer> jobs(JSONObject plan) {
        List<Integer> out = new ArrayList<>();
        JSONArray groups = plan.optJSONArray("groups");
        if (groups != null)
            for (int i = 0; i < groups.length(); i++) {
                JSONObject job = groups.optJSONObject(i) == null ? null : groups.optJSONObject(i).optJSONObject("job");
                if (job != null && job.has("job_id"))
                    out.add(job.getInt("job_id"));
            }
        return out;
    }

    /** What a plan found, in words for the dialog. */
    public static String summary(JSONObject plan) {
        StringBuilder sb = new StringBuilder();
        JSONArray frames = plan.optJSONArray("frames");
        int n = frames == null ? 0 : frames.length();
        sb.append(n).append(n == 1 ? " frame: " : " frames: ")
                .append(count(plan, "in_library")).append(" already in the FITS library, ")
                .append(count(plan, "missing")).append(" missing");
        if (count(plan, "rejected") > 0)
            sb.append(", ").append(count(plan, "rejected")).append(" set aside in the library");
        if (count(plan, "unreadable") > 0)
            sb.append(", ").append(count(plan, "unreadable")).append(" without a usable header");
        sb.append('.');
        JSONArray groups = plan.optJSONArray("groups");
        if (groups != null)
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.getJSONObject(i);
                JSONObject fetch = g.optJSONObject("fetch");
                if (fetch == null)
                    continue;
                JSONObject q = fetch.optJSONObject("query");
                int missing = g.optJSONObject("counts") == null ? 0 : g.getJSONObject("counts").optInt("missing");
                sb.append("\n\n").append(g.optString("label", "?")).append(": fetch ").append(missing)
                        .append(missing == 1 ? " frame" : " frames").append(" through ").append(fetch.optString("source", "?"));
                if (q != null) {
                    sb.append(", ").append(q.optString("start", "?")).append(" to ").append(q.optString("end", "?")).append(" UTC");
                    if (q.has("sample_s") && !q.isNull("sample_s"))
                        sb.append(", one per ").append(seconds(q.getDouble("sample_s")));
                }
                sb.append('.');
                if (fetch.optBoolean("inferred"))
                    sb.append(" The query is inferred from the frames' headers, so the archive may hold more or fewer frames.");
            }
        return sb.toString();
    }

    private static String seconds(double s) {
        if (s >= 3600 && s % 3600 == 0)
            return (long) (s / 3600) + " h";
        if (s >= 60 && s % 60 == 0)
            return (long) (s / 60) + " min";
        return (s == Math.rint(s) ? String.valueOf((long) s) : String.valueOf(s)) + " s";
    }

    /**
     * Wait until every job has ended, asking the core for each one every few seconds, and return
     * them as {@code download.get} gives them. {@code progress} hears a line per round.
     */
    public static List<JSONObject> waitFor(Path root, List<Integer> ids, Consumer<String> progress) throws IOException {
        List<JSONObject> ended = new ArrayList<>();
        List<Integer> waiting = new ArrayList<>(ids);
        while (!waiting.isEmpty()) {
            StringBuilder line = new StringBuilder();
            for (Integer id : List.copyOf(waiting)) {
                JSONObject job = FitsCoreIngest.call(root, "download.get", new JSONObject().put("job_id", id), CALL_WAIT);
                if (job == null)
                    throw new NoCore();
                if (job.isEmpty() || ENDED.contains(job.optString("status"))) { // empty: the core no longer has it
                    waiting.remove(id);
                    ended.add(job);
                    continue;
                }
                line.append(line.isEmpty() ? "" : "; ").append(job.optString("phase", job.optString("status")));
                if (job.has("files_total") && !job.isNull("files_total"))
                    line.append(' ').append(job.optInt("files_done")).append('/').append(job.optInt("files_total"));
            }
            if (waiting.isEmpty())
                break;
            progress.accept(line.toString());
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("waiting for HelioFITS Core");
            }
        }
        return ended;
    }

}
