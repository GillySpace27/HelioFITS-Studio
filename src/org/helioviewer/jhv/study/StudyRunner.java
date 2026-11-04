package org.helioviewer.jhv.study;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import javax.annotation.Nullable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.time.JHVTime;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Export set for stills: every enabled variant in order is applied with Commands.setView, drawn, shot
 * through the ordinary Screenshot path, copied into one run folder with a sidecar, and the view put back.
 * Runs on the event thread, one step at a time; {@link #cancel} keeps the files already written.
 */
public final class StudyRunner {

    public interface Listener {
        void progress(int done, int total, String name);

        void finished(Result result);
    }

    /** One picture to make: a row, an optional master-layer time (WP7), and extra name fields (a swept column). */
    public record Step(Variant variant, int index, @Nullable Long timeMilli, Map<String, String> extraFields) {}

    public record Output(String name, String variant, String sha256, long bytes, boolean skipped) {}

    public record Result(Path folder, List<Output> outputs, boolean cancelled, @Nullable String error) {
        public boolean ok() {
            return error == null && !cancelled;
        }
    }

    private static final long DEFAULT_SETTLE_MS = 3000; // as CliExport: the image is drawn once, decoding goes on a moment longer
    private static final long VIEW_SETTLE_MS = 800; // a view change alone redraws from what is already decoded

    private final boolean confirmed;
    private final Study study;
    private final Path folder;
    private final String sceneSha;
    private final Listener listener;
    private final List<Step> steps;
    private final List<Output> outputs = new ArrayList<>();
    private final JSONObject sceneView;
    private volatile boolean cancelled;
    private int next;
    private boolean firstSettle = true;

    /**
     * @param folderOverride the run folder, or null to expand the study's folder pattern
     * @param sceneSha       hash of the .jhv the scene was loaded from; falls back to the study's, or "" when unknown
     */
    public StudyRunner(Study study, @Nullable Path folderOverride, @Nullable String sceneSha, Listener listener) {
        this(study, folderOverride, sceneSha, listener, false);
    }

    /** @param confirmed the user agreed to a run above the limit {@link Sweep#runLimit()} */
    public StudyRunner(Study study, @Nullable Path folderOverride, @Nullable String sceneSha, Listener listener, boolean confirmed) {
        this.confirmed = confirmed;
        this.study = study;
        this.listener = listener;
        this.sceneSha = sceneSha != null ? sceneSha : study.sceneSha256() != null ? study.sceneSha256() : "";
        this.sceneView = Commands.getView(); // what every row inherits, and what is put back at the end
        this.steps = stillsOn(study) ? plan(study) : List.of();
        this.folder = folderOverride != null ? folderOverride : expandFolder(study, System.currentTimeMillis());
    }

    /** The steps a study makes: its enabled variants, times or swept values in order (see {@link Sweep#steps}). */
    public static List<Step> plan(Study study) {
        return Sweep.steps(study);
    }

    /** Stills are the default output; a study that names only a movies block makes only movies. */
    static boolean stillsOn(Study study) {
        return study.output().stills() != null || study.output().movies() == null;
    }

    /** One step per enabled variant, in order: what the movies make, whatever the sweep. */
    static List<Step> variantSteps(Study study) {
        List<Step> l = new ArrayList<>();
        List<Variant> vs = study.enabledVariants();
        for (int i = 0; i < vs.size(); i++)
            l.add(new Step(vs.get(i), i + 1, null, Map.of()));
        return l;
    }

    public int total() {
        return steps.size();
    }

    public Path folder() {
        return folder;
    }

    public void cancel() {
        cancelled = true;
        MovieRunner m = movies;
        if (m != null)
            m.cancel();
    }

    private volatile MovieRunner movies;

    public void start() {
        if (!confirmed) {
            try {
                Sweep.checkLimit(steps.size(), Sweep.runLimit());
            } catch (IllegalArgumentException e) {
                finish(e.getMessage());
                return;
            }
        }
        try {
            Files.createDirectories(folder);
            StudyIO.write(study, folder.resolve("job.hfstudy.json")); // the job as it ran
        } catch (IOException e) {
            finish(e.getMessage());
            return;
        }
        step();
    }

    private void step() {
        if (cancelled || next >= steps.size()) {
            finish(null);
            return;
        }
        Step st = steps.get(next);
        listener.progress(next, steps.size(), st.variant().name());
        JSONObject resolved = st.variant().resolve(sceneView);
        String name = baseName(study, st, resolved, steps.size(), Player.getTime().milli);
        Path png = folder.resolve(name + ".png");
        if (st.timeMilli() != null)
            Commands.seekTime(new JHVTime(st.timeMilli()));
        Commands.setView(resolved);
        long time = Player.getTime().milli;
        String print = fingerprint(sceneSha, resolved, time);
        if (skip(study.output().overwrite(), png, print)) {
            outputs.add(describe(png, st, true));
            advance();
            return;
        }
        long settle = firstSettle ? Long.getLong("jhv.exportSettleMs", DEFAULT_SETTLE_MS) : Long.getLong("jhv.studySettleMs", VIEW_SETTLE_MS);
        firstSettle = false;
        Timer t = new Timer((int) settle, e -> shoot(st, resolved, png, print, time));
        t.setRepeats(false);
        t.start();
    }

    private void shoot(Step st, JSONObject resolved, Path png, String print, long time) {
        if (cancelled) {
            finish(null);
            return;
        }
        Commands.OperationContext context = new Commands.OperationContext(StudyRunner.class, null, null, "export.png", (c, ok, message, output) ->
                SwingUtilities.invokeLater(() -> {
                    if (!ok || output == null) {
                        finish("Variant \"" + st.variant().name() + "\": " + message);
                        return;
                    }
                    try {
                        Files.copy(Path.of(output.contains("%04d") ? String.format(output, 1) : output), png, StandardCopyOption.REPLACE_EXISTING);
                        Output o = describe(png, st, false);
                        if (!"none".equals(study.output().record()))
                            Files.writeString(sidecar(png), sidecarJson(st, resolved, sceneSha, time, print, o).toString(2) + "\n", StandardCharsets.UTF_8);
                        outputs.add(o);
                    } catch (IOException | RuntimeException ex) {
                        finish("Could not write " + png.getFileName() + ": " + ex.getMessage());
                        return;
                    }
                    advance();
                }));
        Commands.recordStart(context, new Commands.RecordStartInput("SHOT", null, null, null, null));
    }

    private void advance() {
        next++;
        step();
    }

    private boolean finished;

    private void finish(@Nullable String error) {
        if (finished)
            return;
        finished = true;
        Commands.restoreView(sceneView);
        if (error == null && !cancelled && MovieBlock.of(study) != null) { // the movies take it from here and write the one manifest
            movies = new MovieRunner(study, folder, sceneSha, outputs, listener);
            movies.start();
            return;
        }
        if (error == null) {
            try {
                Files.writeString(folder.resolve("manifest.hfstudy.json"),
                        manifest(study, sceneSha, outputs).toString(2) + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                error = "Could not write the manifest: " + e.getMessage();
            }
        }
        if (error != null)
            Log.error("Study failed: " + error);
        listener.progress(outputs.size(), steps.size(), "");
        listener.finished(new Result(folder, List.copyOf(outputs), cancelled, error));
    }

    // ---- pure parts, shared with StudyRunnerCheck ----

    /** The run folder from the study's pattern; {scene} is the scene file's name and {date} today, UTC. */
    public static Path expandFolder(Study study, long nowMilli) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("scene", sceneName(study));
        f.put("date", NamePattern.date(nowMilli));
        f.put("time", NamePattern.time(nowMilli));
        String p = NamePattern.expand(study.output().folder(), f);
        if (p.startsWith("~"))
            p = System.getProperty("user.home") + p.substring(1);
        return Path.of(p);
    }

    static String sceneName(Study study) {
        String n = study.scene().isEmpty() ? "scene" : Path.of(study.scene()).getFileName().toString();
        return n.endsWith(".jhv") ? n.substring(0, n.length() - 4) : n;
    }

    /** The fields a name pattern can use for one picture. */
    static Map<String, String> fields(Study study, Step st, JSONObject resolved, int count, long timeMilli) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("scene", sceneName(study));
        f.put("variant", st.variant().name());
        f.put("time", NamePattern.time(timeMilli));
        f.put("date", NamePattern.date(timeMilli));
        f.put("index", NamePattern.index(st.index(), count));
        put(f, "projection", resolved.opt("projection"));
        put(f, "lambda", resolved.opt("warpLambda"));
        JSONObject cam = resolved.optJSONObject("camera");
        if (cam != null) {
            put(f, "fov", cam.opt("fov"));
            put(f, "zoom", cam.opt("zoom"));
        }
        f.putAll(st.extraFields());
        return f;
    }

    private static void put(Map<String, String> f, String k, @Nullable Object v) {
        if (v != null && !JSONObject.NULL.equals(v))
            f.put(k, v.toString());
    }

    static String baseName(Study study, Step st, JSONObject resolved, int count, long timeMilli) {
        long t = st.timeMilli() != null ? st.timeMilli() : timeMilli;
        Sweep sw = study.sweep();
        return NamePattern.expand(study.output().name(), fields(study, st, resolved, count, t), sw == null ? null : sw.sweptField());
    }

    /** Sorted-key rendering, so equal values give equal text whatever the map order was. */
    static String canon(@Nullable Object o) {
        if (o instanceof JSONObject jo) {
            StringBuilder sb = new StringBuilder("{");
            for (String k : new TreeSet<>(jo.keySet()))
                sb.append(JSONObject.quote(k)).append(':').append(canon(jo.get(k))).append(',');
            return sb.append('}').toString();
        }
        if (o instanceof JSONArray ja) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < ja.length(); i++)
                sb.append(canon(ja.get(i))).append(',');
            return sb.append(']').toString();
        }
        return JSONObject.valueToString(o);
    }

    /** What skip-if-unchanged compares: the scene hash, the resolved view and the time. */
    static String fingerprint(String sceneSha, JSONObject resolved, long timeMilli) {
        return sha256(sceneSha + "\n" + canon(resolved) + "\n" + timeMilli);
    }

    static boolean skip(Overwrite policy, Path png, String print) {
        if (!Files.exists(png))
            return false;
        switch (policy) {
            case NEVER:
                return true;
            case SKIP_IF_UNCHANGED:
                try {
                    return print.equals(new JSONObject(Files.readString(sidecar(png))).optJSONObject("study").optString("fingerprint"));
                } catch (IOException | RuntimeException e) { // no sidecar, or not ours: make it again
                    return false;
                }
            default:
                return false;
        }
    }

    static Path sidecar(Path png) {
        String n = png.getFileName().toString();
        return png.resolveSibling(n.substring(0, n.length() - 4) + ".hfrec.json");
    }

    static JSONObject sidecarJson(Step st, JSONObject resolved, String sceneSha, long time, String print, Output o) {
        JSONObject s = new JSONObject();
        s.put("variant", st.variant().name());
        s.put("override", st.variant().override());
        s.put("resolved", resolved);
        s.put("sceneSha256", sceneSha);
        s.put("time", time);
        s.put("fingerprint", print);
        JSONObject jo = new JSONObject();
        jo.put("hfrec", 1);
        jo.put("file", o.name());
        jo.put("sha256", o.sha256());
        jo.put("study", s);
        return jo;
    }

    static JSONObject manifest(Study study, String sceneSha, List<Output> outputs) {
        JSONObject jo = new JSONObject();
        jo.put("hfstudy", Study.VERSION);
        jo.put("job", study.toJson());
        jo.put("sceneSha256", sceneSha);
        JSONArray a = new JSONArray();
        for (Output o : outputs)
            a.put(new JSONObject().put("name", o.name()).put("sha256", o.sha256()).put("bytes", o.bytes())
                    .put("variant", o.variant()).put("skipped", o.skipped())
                    .put("kind", o.name().endsWith(".png") ? "still" : "movie"));
        jo.put("outputs", a);
        return jo;
    }

    static Output describe(Path png, Step st, boolean skipped) {
        try {
            return new Output(png.getFileName().toString(), st.variant().name(), StudyIO.sha256(png), Files.size(png), skipped);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The exact line that reruns this job headless, for "Copy command". */
    public static String commandLine(Study study, @Nullable Path jobFile) {
        return "./run.sh -state " + shellQuote(study.scene()) + " -study " + shellQuote(jobFile == null ? "job.hfstudy.json" : jobFile.toString())
                + " -out " + shellQuote(expandFolder(study, System.currentTimeMillis()).toString());
    }

    public static String commandLine(Study study) {
        return commandLine(study, null);
    }

    private static String shellQuote(String s) {
        return s.matches("[A-Za-z0-9_./~:=+-]+") ? s : "'" + s.replace("'", "'\\''") + "'";
    }
}
