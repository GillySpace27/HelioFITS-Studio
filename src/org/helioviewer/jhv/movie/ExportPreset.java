package org.helioviewer.jhv.movie;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.io.Directories;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * A named point on the quality ladder: codec, colour sampling, bit depth and keyframing in one
 * choice, so the question at record time is "what is this recording FOR" rather than four
 * independent technical ones.
 *
 * <p>The built-in rungs run from exact to universally playable, and each gives up one specific
 * thing to the next. The name says the destination and the description says the cost, because the
 * costs are what actually distinguish them and none of them is visible in a file listing:
 *
 * <ol>
 * <li>Archive: nothing given up. Bit-exact, and enormous.
 * <li>Publication figures: the same fidelity, as stills rather than a movie.
 * <li>Dome projection: lossy, but full colour resolution and every frame independent.
 * <li>Presentation: gives up colour resolution, keeps 10-bit gradients.
 * <li>Share anywhere: gives up depth as well, and in exchange plays on anything.
 * </ol>
 *
 * <p>User presets live alongside the built-ins in one JSON file under Settings/. A built-in cannot
 * be deleted, but saving over its name shadows it, which is the cheapest way to let someone keep
 * "Dome projection" as a name while disagreeing with what it means on their projector.
 */
public record ExportPreset(String name, String description, ExportFormat format,
                           ExportFormat.Chroma chroma, ExportFormat.Depth depth, boolean allIntra,
                           boolean builtIn) {

    private static final String FILE_NAME = "export-presets.json";

    /** Shown when the controls do not match any preset, so an edited setting never lies about itself. */
    public static final String CUSTOM = "Custom";

    private static final List<ExportPreset> BUILT_IN = List.of(
            new ExportPreset("Archive (exact)",
                    "Bit-for-bit identical to what was rendered: no colour conversion, no quantization, nothing thrown away. "
                            + "The only setting whose output can be called unaltered. Very large, and plays in VLC rather than QuickTime.",
                    ExportFormat.FFV1, ExportFormat.Chroma.RGB, ExportFormat.Depth.SIXTEEN, true, true),
            new ExportPreset("Publication figures",
                    "One lossless 16-bit PNG per frame, for figures and page layout. The same fidelity as Archive, "
                            + "delivered as stills you can drop into a paper rather than as a movie.",
                    ExportFormat.PNG, ExportFormat.Chroma.RGB, ExportFormat.Depth.SIXTEEN, true, true),
            new ExportPreset("Dome projection",
                    "Lossy, but keeps full colour resolution and makes every frame independent, which is what survives "
                            + "a very large bright screen and frame-exact scrubbing. Large files, and decoded in software.",
                    ExportFormat.H265HQ, ExportFormat.Chroma.YUV444, ExportFormat.Depth.TEN, true, true),
            new ExportPreset("Presentation",
                    "Gives up colour resolution to halve the file, and keeps 10 bits so gradients stay free of banding "
                            + "on a projector. The sensible default for a talk.",
                    ExportFormat.H265HQ, ExportFormat.Chroma.YUV420, ExportFormat.Depth.TEN, false, true),
            new ExportPreset("Share anywhere",
                    "Gives up bit depth as well, and in exchange plays on every phone, browser and slide deck without "
                            + "asking. Expect visible banding in smooth corona and softened colour edges.",
                    ExportFormat.H264, ExportFormat.Chroma.YUV420, ExportFormat.Depth.EIGHT, false, true));

    /** User presets, keyed by name and ordered by insertion. Loaded once, written on every change. */
    @Nullable private static Map<String, ExportPreset> user;

    public static List<ExportPreset> all() {
        load();
        // A user preset sharing a built-in's name replaces it in place, so the ladder keeps its
        // order instead of growing a duplicate rung with the same label.
        List<ExportPreset> list = new ArrayList<>(BUILT_IN.size() + user.size());
        for (ExportPreset p : BUILT_IN)
            list.add(user.getOrDefault(p.name, p));
        for (ExportPreset p : user.values())
            if (BUILT_IN.stream().noneMatch(b -> b.name.equals(p.name)))
                list.add(p);
        return list;
    }

    @Nullable
    public static ExportPreset byName(String name) {
        for (ExportPreset p : all())
            if (p.name.equals(name))
                return p;
        return null;
    }

    /** The preset these settings correspond to, or null when they match none of them. */
    @Nullable
    public static ExportPreset matching(ExportFormat format, ExportFormat.Chroma chroma,
                                        ExportFormat.Depth depth, boolean allIntra) {
        for (ExportPreset p : all())
            if (p.format == format && p.chroma == chroma && p.depth == depth && p.allIntra == allIntra)
                return p;
        return null;
    }

    /** Whether this name is one of the built-in rungs, and so cannot be deleted, only shadowed. */
    public static boolean isBuiltInName(String name) {
        return BUILT_IN.stream().anyMatch(p -> p.name.equals(name));
    }

    public static void save(ExportPreset preset) {
        load();
        user.put(preset.name, preset);
        write();
    }

    /** Removes a user preset. A shadowed built-in reverts to its original rather than disappearing. */
    public static void delete(String name) {
        load();
        if (user.remove(name) != null)
            write();
    }

    public JSONObject toJson() {
        return new JSONObject()
                .put("name", name)
                .put("description", description)
                .put("format", format.name())
                .put("chroma", chroma.name())
                .put("depth", depth.name())
                .put("allIntra", allIntra);
    }

    @Nullable
    private static ExportPreset fromJson(JSONObject jo) {
        try {
            ExportFormat format = ExportFormat.valueOf(jo.getString("format"));
            ExportFormat.Chroma chroma = format.clamp(ExportFormat.Chroma.valueOf(jo.getString("chroma")));
            ExportFormat.Depth depth = format.clamp(chroma, ExportFormat.Depth.valueOf(jo.getString("depth")));
            String name = jo.getString("name").strip();
            if (name.isEmpty() || CUSTOM.equals(name))
                return null;
            return new ExportPreset(name, jo.optString("description", ""), format, chroma, depth,
                    jo.optBoolean("allIntra", false), false);
        } catch (RuntimeException e) { // one bad entry must not cost the rest of the file
            Log.warn("Skipping unreadable export preset: " + e.getMessage());
            return null;
        }
    }

    private static File file() {
        return new File(Directories.SETTINGS.getFile(), FILE_NAME);
    }

    private static void load() {
        if (user != null)
            return;
        user = new LinkedHashMap<>();

        File f = file();
        if (!f.isFile())
            return;
        try (var reader = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            JSONArray arr = new JSONObject(new JSONTokener(reader)).optJSONArray("presets");
            if (arr == null)
                return;
            for (Object o : arr)
                if (o instanceof JSONObject jo) {
                    ExportPreset p = fromJson(jo);
                    if (p != null)
                        user.put(p.name, p);
                }
        } catch (IOException | RuntimeException e) {
            Log.error("Could not read " + FILE_NAME, e);
        }
    }

    private static void write() {
        JSONArray arr = new JSONArray();
        user.values().forEach(p -> arr.put(p.toJson()));
        // Written whole, not appended: the file is small and rewriting it is the only way a delete
        // can take effect at all.
        try {
            Files.writeString(file().toPath(), new JSONObject().put("presets", arr).toString(2),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            Log.error("Could not write " + FILE_NAME, e);
        }
    }

    @Override
    public String toString() {
        return name;
    }

}
