package org.helioviewer.jhv.movie;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.state.SessionArchive;
import org.helioviewer.jhv.app.state.State;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * "Bundle source data with export": beside an export, {@code <export file name>.data.zip} holding the local
 * files the scene reads, in the format {@link SessionArchive} writes for File &gt; Export Session with Data (so
 * the same reader opens it). The export's {@code .hfrec} names it and its SHA-256 under
 * {@code companions.data_archive}; no .jhv is written beside the export, the archive's own session.jhv is the scene.
 *
 * <p>One timestamp by default: a still packs the files of the frames it shows plus the frames those depend on
 * (the previous frame for a running difference, the first for a base difference, every frame for a sequence
 * filter); a movie packs the layers' files, which are the range it recorded. Archive layers have no local
 * file and download again on the other computer, as with Export Session with Data; Helioviewer layers have no
 * file at all.
 */
public final class ExportBundle {

    /** The setting behind the Record panel's checkbox; off unless chosen, like "Every frame a keyframe". */
    public static final String KEY = "video.bundleData";
    private static final String STATE = "org.helioviewer.jhv.state";

    private ExportBundle() {}

    public static boolean enabled() {
        return "true".equals(Settings.getProperty(KEY));
    }

    /** What one export packs: the scene as State.snapshot() took it, and the files of a still ({@code scope} null: all of them). */
    public record Plan(JSONObject scene, @Nullable Set<URI> scope, boolean still) {}

    /** What writing it came to: the record's {@code companions} to add, or a one-line reason there is none. */
    public record Result(@Nullable JSONObject companions, @Nullable String note) {}

    /**
     * The plan for an export about to start, or null when bundling is off ({@code override} true or false
     * wins over the setting, for a script) or the scene cannot be read. Call it where the export starts, on
     * the thread the state serializer runs on.
     */
    @Nullable
    public static Plan plan(boolean still, @Nullable Boolean override) {
        if (!(override == null ? enabled() : override))
            return null;
        try {
            JSONObject scene = State.snapshot();
            return new Plan(scene, still ? stillScope(scene) : null, still);
        } catch (RuntimeException e) {
            Log.warn("Bundle source data: the scene could not be read, no archive will be written", e);
            return null;
        }
    }

    /**
     * What the Record panel shows beside the checkbox: the files and bytes an export would pack now. The scene and
     * the frames are read here, on the event thread; the returned task only reads file sizes and may run anywhere.
     */
    public static java.util.concurrent.Callable<SessionArchive.Estimate> estimateTask(boolean still) {
        JSONObject scene = State.snapshot();
        Set<URI> scope = still ? stillScope(scene) : null;
        return () -> SessionArchive.estimate(scene, scope);
    }

    /** The frames of a layer one still needs: the one shown, what its difference needs, and every frame for a sequence filter. */
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

    /** The files of the frames a still needs, as URIs; only those that are also local files get packed. */
    static Set<URI> stillScope(JSONObject scene) {
        Set<URI> out = new LinkedHashSet<>();
        JSONObject state = scene.optJSONObject(STATE);
        JSONArray layers = state == null ? null : state.optJSONArray("imageLayers");
        if (layers == null)
            return out;
        Map<String, ImageLayer> live = new HashMap<>();
        Layers.forEachImageLayer(l -> live.put(l.getId(), l));
        for (Object o : layers) {
            if (!(o instanceof JSONObject layer) || !layer.optBoolean("enabled", true))
                continue;
            ImageLayer l = live.get(layer.optString("id"));
            if (l == null)
                continue;
            JSONObject data = layer.optJSONObject("data");
            JSONObject params = data == null ? null : data.optJSONObject("imageParams");
            View view = l.getView();
            for (int f : framesNeeded(params == null ? "None" : params.optString("differenceMode", "None"),
                    data != null && data.optJSONObject("sequence") != null, view.getCurrentFrameNumber(), view.getMaximumFrameNumber())) {
                try {
                    out.add(URI.create(view.frameKey(f)));
                } catch (IllegalArgumentException e) {
                    // a frame key that is not a file's URI (a J2K frame) names nothing to pack
                }
            }
        }
        return out;
    }

    /** Where the archive for {@code export} goes: beside it, the export's full file name plus .data.zip. */
    public static Path archivePath(Path export) {
        return export.resolveSibling(export.getFileName() + SessionArchive.SUFFIX);
    }

    /** Write the archive for a finished export. Off the event thread. Never throws: an export does not fail for its extras. */
    public static Result write(Plan plan, Path export) {
        Path archive = archivePath(export);
        try {
            SessionArchive.Bundle b = SessionArchive.exportData(plan.scene(), archive, plan.scope());
            if (b == null)
                return new Result(null, "No layer reads a local file, so no data archive was written.");
            JSONObject data = new JSONObject().put("file_name", archive.getFileName().toString()).put("sha256", b.sha256())
                    .put("size", b.archiveBytes()).put("scope", plan.still() ? "one" : "range");
            return new Result(new JSONObject().put("data_archive", data), null);
        } catch (IOException | RuntimeException e) {
            Log.warn("Bundle source data: archive not written beside " + export.getFileName(), e);
            return new Result(null, "The data archive was not written: " + e.getMessage());
        }
    }
}
