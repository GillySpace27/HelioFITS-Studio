package org.helioviewer.jhv.movie;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.io.SourceIdentity;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.view.ClipSet;
import org.helioviewer.jhv.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The processing record (hfrecord 1.0, docs/spec/hfrecord-1.schema.json): a sidecar
 * {@code <export file name>.hfrec.json} beside every export that says, per layer, which source
 * frames it read (by SHA-256, with Heliad's identity when the library has them), every step from
 * decoded file to pixel in the order Studio runs them with the values it used, and the session
 * itself under {@code native.studio_session}, verbatim, which is what a rebuild loads.
 *
 * <p>The step order was read from the code, not assumed:
 * <ol>
 * <li>decode (FITSImage.readData, FITSData.decode): the plane is read out of the cube first
 * (readFlatPixels), then BZERO/BSCALE and, for a SOHO LASCO header, OFFSET subtraction, EXPTIME
 * division and the NRL monthly background (subtractBackground); then one pass clips to the range
 * (HV_DMIN/HV_DMAX from the header when present, else the layer's fixed range, else the clipping
 * mode's bounds) and stretches (convertPixels with normalizedMapping).
 * <li>cpu: a sequence filter runs on those clipped, stretched, unfiltered frames
 * (URIView.frameImage asks for filter None); the per-frame filter (MGN, WOW, RHEF) runs last, on the
 * sequence output when there is one (ComputedView applies wrapped.getFilter() on top) and on the
 * decoded frame otherwise (ImageBuffer.WriteBuffer.finish).
 * <li>gpu (the projection shader, then imageCommon.frag getColor): the geometric masks discard
 * first (clipPlanarMasks and the slit, before getColor), then levels with the radial gain inside
 * fetch(), the difference, sharpen (on the difference), the RHEF Upsilon curve, the colour table,
 * the channel mask (display.color multiplies the table's colour), and opacity and blend in the
 * framebuffer blend.
 * </ol>
 *
 * <p>Everything here is a pure function of the session block, the {@link #live} block read on the
 * event thread at export start, and the end-of-export scene, so ProcessingRecordCheck can build a
 * record with no window and validate it against the schema.
 */
public final class ProcessingRecord {

    public static final String SUFFIX = ".hfrec.json";
    static final String FORMAT = "1.0";
    static final int READER_COMPAT = 1;

    // Copied from GillySpace27.github.io heliosoftware/spec/attribution.json (version 1); the credit
    // line comes from its credits map, per instrument (InstrumentCredit). rhef_citation is null there.
    static final String SUITE_DISCLAIMER = "RHEF output is a visualization, not a calibrated radiance.";
    static final String SUITE_PRODUCT = "heliofits-studio";

    private static final String STATE = "org.helioviewer.jhv.state";
    private static final long SOURCE_WAIT_MS = 120_000; // per file, for a frame still being hashed when the export ends

    /** What the export wrote: the schema's output object, less what is computed from the file. */
    public record Output(String kind, String mediaType, @Nullable Integer width, @Nullable Integer height,
                         @Nullable Integer frames, @Nullable Integer fps, @Nullable String codec, @Nullable Integer bitDepth,
                         boolean displayReferred) {

        JSONObject toJson() {
            JSONObject jo = new JSONObject().put("kind", kind).put("media_type", mediaType);
            if (width != null)
                jo.put("width", width);
            if (height != null)
                jo.put("height", height);
            if (frames != null)
                jo.put("frames", frames);
            if (fps != null)
                jo.put("fps", fps);
            if (codec != null)
                jo.put("codec", codec);
            if (bitDepth != null)
                jo.put("bit_depth", bitDepth);
            if (displayReferred)
                jo.put("colorspace", "sRGB");
            return jo.put("display_referred", displayReferred);
        }
    }

    private ProcessingRecord() {}

    // ---- the live half, read on the event thread at export start ---------------------------------

    /**
     * What the scene JSON does not hold, per image layer by id: whether it decodes FITS, the
     * display range the frame on screen was actually clipped to, and which of its frames that is.
     * Also the frame block (projection, viewpoint, camera width). Never throws: a part that cannot
     * be read is left out.
     */
    public static JSONObject live() {
        JSONObject layers = new JSONObject();
        Layers.forEachImageLayer(layer -> {
            try {
                layers.put(layer.getId(), liveLayer(layer));
            } catch (RuntimeException e) {
                Log.warn("Processing record: layer state unreadable for " + layer.getName(), e);
            }
        });
        JSONObject live = new JSONObject().put("layers", layers);
        try {
            live.put("frame", Provenance.frame(0, false));
        } catch (RuntimeException e) { // no window, or no map view yet
            Log.warn("Processing record: no frame block", e);
        }
        return live;
    }

    private static JSONObject liveLayer(ImageLayer layer) {
        View view = layer.getView();
        JSONObject jo = new JSONObject().put("fits", view.hasFITS())
                .put("frame", view.getCurrentFrameNumber()).put("frames", view.getMaximumFrameNumber() + 1);
        String creditKey = InstrumentCredit.key(layer);
        if (creditKey != null)
            jo.put("credit_key", creditKey);
        View.ImageData data = layer.getImageData();
        ImageBuffer.PhysicalScale scale = data == null ? null : data.imageBuffer().physicalScale();
        if (scale != null)
            jo.put("clip", new JSONArray().put(scale.min()).put(scale.max())).put("clip_from", "decoded frame");
        else if (view.hasFITS()) {
            ClipSet.Range r = layer.getProcessingSettings().fitsParameters().clipRange(view.getClipSet());
            if (r != null)
                jo.put("clip", new JSONArray().put(r.lower()).put(r.upper())).put("clip_from", "clipping mode");
        }
        return jo;
    }

    // ---- the record ------------------------------------------------------------------------------

    /**
     * The record for one export. {@code session} is Provenance.session() at export start,
     * {@code live} is {@link #live()} at the same moment, {@code endScene} the portable scene at
     * the end (null for a still), {@code identify} gives a local file's identity. {@code home} puts
     * the session's "~" back to find the files; nothing written holds it.
     */
    static JSONObject build(JSONObject session, JSONObject live, @Nullable JSONObject endScene, Output output,
                            @Nullable String fileName, @Nullable String sha256, String home,
                            Function<URI, SourceIdentity.Entry> identify, String recordId) {
        JSONObject portable = session.optJSONObject("scene");
        JSONObject state = portable == null ? new JSONObject() : portable.optJSONObject(STATE);
        if (state == null)
            state = new JSONObject();
        JSONObject realScene = Provenance.scene(session, home);
        JSONObject realState = realScene == null ? null : realScene.optJSONObject(STATE);
        JSONArray realLayers = realState == null ? null : realState.optJSONArray("imageLayers");
        JSONObject endState = endScene == null ? null : endScene.optJSONObject(STATE);
        boolean movie = !"still".equals(output.kind()) && !"fits_data".equals(output.kind());

        JSONObject out = output.toJson();
        if (fileName != null)
            out.put("file_name", fileName);
        out.put("sha256", sha256 == null ? JSONObject.NULL : sha256);

        JSONObject record = new JSONObject()
                .put("hfrecord", FORMAT)
                .put("reader_compat", READER_COMPAT)
                .put("record_id", recordId)
                .put("created", session.optString("created"))
                .put("producer", producer(session))
                .put("output", out);

        Map<String, List<String>> automation = automationByLayer(state);
        JSONObject compare = state.optJSONObject("compare");
        JSONArray layers = new JSONArray();
        Set<String> caveats = new LinkedHashSet<>();
        boolean anyRhef = false, changed = false;
        List<String> creditKeys = new ArrayList<>();
        JSONArray scenes = state.optJSONArray("imageLayers");
        if (scenes != null)
            for (int i = 0; i < scenes.length(); i++) {
                JSONObject layer = scenes.optJSONObject(i);
                if (layer == null)
                    continue;
                JSONObject real = realLayers == null ? null : realLayers.optJSONObject(i);
                String id = layer.optString("id", "layer-" + i);
                JSONObject liveLayer = live.optJSONObject("layers") == null ? null : live.getJSONObject("layers").optJSONObject(id);
                JSONObject endLayer = endState == null ? null : byId(endState.optJSONArray("imageLayers"), id);
                LayerOut lo = layer(layer, real, liveLayer, endLayer, movie ? automation.getOrDefault(id, List.of()) : List.of(),
                        compare, identify, caveats);
                if (liveLayer != null && liveLayer.has("credit_key") && layer.optBoolean("enabled", true))
                    creditKeys.add(liveLayer.getString("credit_key"));
                anyRhef |= lo.rhef;
                changed |= lo.changed;
                layers.put(lo.json);
            }
        if (endState != null && !java.util.Objects.equals(String.valueOf(state.optJSONObject("compare")), String.valueOf(endState.optJSONObject("compare"))))
            changed = true;
        record.put("layers", layers);
        record.put("scene", scene(state, live, compare, movie, endState));

        if (anyRhef)
            caveats.add(SUITE_DISCLAIMER);
        if (output.displayReferred())
            caveats.add("Display-referred render: pixel values are colours, not measured quantities.");
        if (session.optBoolean("dirty"))
            caveats.add("Made by a build with uncommitted changes: a rebuild is not guaranteed.");
        record.put("caveats", new JSONArray(caveats));
        JSONArray citations = new JSONArray().put(Provenance.JHV_CITATION);
        if (anyRhef)
            citations.put(Provenance.RHEF_CITATION);
        record.put("citations", citations);
        record.put("provenance", suite(session, state, endState, movie, layers, InstrumentCredit.line(creditKeys)));
        record.put("snapshot", new JSONObject().put("taken", "export_start").put("changed_during_export", changed));
        record.put("native", new JSONObject().put("studio_session", session));
        record.put("summary", summary(record));
        return record;
    }

    private record LayerOut(JSONObject json, boolean rhef, boolean changed) {}

    private static LayerOut layer(JSONObject layer, @Nullable JSONObject real, @Nullable JSONObject live, @Nullable JSONObject end,
                                  List<String> tracks, @Nullable JSONObject compare,
                                  Function<URI, SourceIdentity.Entry> identify, Set<String> caveats) {
        JSONObject data = layer.optJSONObject("data");
        if (data == null)
            data = new JSONObject();
        JSONObject p = data.optJSONObject("imageParams");
        if (p == null)
            p = new JSONObject();
        String id = layer.optString("id");
        JSONObject jo = new JSONObject().put("id", id).put("name", layer.optString("name")).put("enabled", layer.optBoolean("enabled", true));

        // -- sources, from the real (home restored) URIs; only identities are written
        JSONArray sources = new JSONArray();
        List<String> hashes = new ArrayList<>();
        JSONObject dataset = null;
        int pending = 0;
        JSONArray uris = real == null || real.optJSONObject("data") == null ? null : real.getJSONObject("data").optJSONArray("uris");
        if (uris != null)
            for (Object o : uris) {
                if (!(o instanceof String u))
                    continue;
                if (!u.startsWith("file:")) {
                    sources.put(new JSONObject().put("source_url", u));
                    continue;
                }
                SourceIdentity.Entry e = null;
                try {
                    e = identify.apply(new URI(u));
                } catch (URISyntaxException ignored) {
                    // not a URI after all: counted as not identified below
                }
                if (e == null) {
                    pending++;
                    continue;
                }
                JSONObject s = new JSONObject().put("sha256", e.sha256()).put("size", e.size()).put("source_url", JSONObject.NULL)
                        .put("in_library", e.heliad() != null);
                if (e.heliad() != null) {
                    if (e.heliad().obsKey() != null)
                        s.put("heliad_obs_key", e.heliad().obsKey());
                    s.put("file_version", e.heliad().fileVersion() == null ? JSONObject.NULL : e.heliad().fileVersion());
                    if (e.heliad().dateObs() != null)
                        s.put("date_obs", e.heliad().dateObs());
                    if (dataset == null) {
                        dataset = new JSONObject().put("dataset_key", e.heliad().datasetKey());
                        if (e.heliad().label() != null)
                            dataset.put("label", e.heliad().label());
                        if (e.heliad().datasetUid() != null)
                            dataset.put("dataset_uid", e.heliad().datasetUid());
                    }
                }
                sources.put(s);
                hashes.add(e.sha256());
            }
        if (pending > 0) {
            jo.put("source_count", sources.length() + pending);
            caveats.add("Some local source frames could not be identified when the export ended (unreadable or moved); see source_count.");
        }
        jo.put("sources", sources);
        if (!hashes.isEmpty() && hashes.size() == sources.length() && pending == 0)
            jo.put("source_set_sha256", setHash(hashes));
        if (dataset != null)
            jo.put("dataset", dataset);
        JSONObject spec;
        if (data.optJSONObject("APIRequest") != null) {
            spec = new JSONObject().put("kind", "helioviewer_api").put("request", data.getJSONObject("APIRequest"));
            caveats.add("A Helioviewer layer has no source file: a rebuild cannot promise the same pixels.");
        } else if (data.optJSONObject("fitsRequest") != null)
            spec = new JSONObject().put("kind", "fits_request").put("request", data.getJSONObject("fitsRequest"));
        else if (dataset != null)
            spec = new JSONObject().put("kind", "heliad_library");
        else if (!hashes.isEmpty())
            spec = new JSONObject().put("kind", "local_files");
        else
            spec = new JSONObject().put("kind", "uri_list");
        jo.put("source_spec", spec);

        // -- steps, in the order the code runs them (see the class comment)
        boolean fits = live == null || live.optBoolean("fits", true);
        String filter = data.optString("filter", "None");
        boolean rhef = "RHEF".equals(filter);
        JSONArray steps = new JSONArray();
        if (fits) {
            steps.put(step("select_plane", "decode", params(p, "plane", "planes")));
            JSONObject fr = data.optJSONObject("fitsRequest");
            if (fr != null && "LASCO".equals(fr.optString("archive")))
                steps.put(step("instrument_correction", "decode", new JSONObject().put("OFFSET", "subtracted").put("EXPTIME", "divided")
                        .put("background", "NRL monthly background subtracted"))
                        .put("automatic", true).put("implementation", "hfstudio-java")
                        .put("caveat", "Applied by the decoder to frames whose header is SOHO LASCO (FITSImage.subtractBackground); a frame whose"
                                + " background could not be fetched is shown uncorrected. Result in DN per second."));
            if (data.has("lascoPointing"))
                steps.put(step("pointing", "decode", new JSONObject().put("lascoPointing", "lent from a header probe")
                        .put("lascoPointingComplete", data.opt("lascoPointingComplete") == null ? JSONObject.NULL : data.opt("lascoPointingComplete")))
                        .put("automatic", true));
            JSONObject clip = step("clip", "decode", params(p, "clippingMode", "clippingMin", "clippingMax"));
            JSONObject resolved = new JSONObject();
            JSONArray used = live == null ? null : live.optJSONArray("clip");
            JSONArray fixed = data.optJSONArray("fixedRange");
            if (used != null && used.length() == 2) {
                resolved.put("low", used.getDouble(0)).put("high", used.getDouble(1));
                resolved.put("from", live.optString("clip_from"));
            } else {
                resolved.put("low", JSONObject.NULL).put("high", JSONObject.NULL);
            }
            if (fixed != null)
                resolved.put("fixedRange", fixed);
            clip.put("resolved", resolved);
            String mode = p.optString("clippingMode", "Percentile05");
            clip.put("caveat", (fixed != null ? "A fixed range shared by every frame overrides the clipping mode. "
                    : "Range".equals(mode) ? "" : mode + " ignores clippingMin/Max; the bounds come from the data. ")
                    + "A header with HV_DMIN/HV_DMAX overrides both. resolved holds the bounds of the frame on screen at export start.");
            steps.put(clip);
            String scaling = p.optString("scalingMode", "Gamma");
            String key = switch (scaling) {
                case "Beta" -> "beta";
                case "Alpha" -> "alpha";
                default -> "gamma";
            };
            steps.put(step("stretch", "decode", params(p, "scalingMode", key)).put("algorithm", scaling).put("implementation", "hfstudio-java"));
        }
        if (data.optJSONObject("sequence") != null)
            steps.put(step("sequence_filter", "cpu", new JSONObject().put("sequence", data.getJSONObject("sequence")))
                    .put("implementation", "hfstudio-java")
                    .put("caveat", "Computed over every frame of the layer from the clipped, stretched, unfiltered frames."));
        if (!"None".equals(filter)) {
            JSONObject f = step("image_filter", "cpu", new JSONObject().put("filter", filter)).put("algorithm", filter).put("implementation", "hfstudio-java");
            if (rhef) {
                f.put("citation", Provenance.RHEF_CITATION).put("caveat", Provenance.RHEF_CAVEAT)
                        .put("stamp", Provenance.rhefStampLine(p));
            }
            steps.put(f);
        }
        steps.put(step("geometric_mask", "gpu", params(p, "innerMask", "outerMask", "slitLeft", "slitRight", "sectorCenter", "sectorWidth")));
        steps.put(step("levels", "gpu", params(p, "brightOffset", "brightScale")));
        steps.put(step("radial_gain", "gpu", params(p, "enhanced")));
        JSONObject diff = step("difference", "gpu", params(p, "differenceMode"));
        String dm = p.optString("differenceMode", "None");
        if ("Running".equals(dm))
            diff.put("reference", new JSONObject().put("kind", "running").put("source_index", JSONObject.NULL).put("layer_id", JSONObject.NULL));
        else if ("Base".equals(dm))
            diff.put("reference", new JSONObject().put("kind", "base").put("source_index", 0).put("layer_id", JSONObject.NULL))
                    .put("caveat", "Base is the layer's first frame (ImageLayer: the frame at the view's first time).");
        if (compare != null && "Difference".equals(compare.optString("mode")) && id.equals(compare.optString("a")))
            diff.put("reference", new JSONObject().put("kind", "compare").put("source_index", JSONObject.NULL)
                    .put("layer_id", compare.optString("b", null) == null ? JSONObject.NULL : compare.getString("b")))
                    .put("caveat", "Compare Difference: this layer minus layer_id in physical units, each stretch undone (CompareDifference).");
        steps.put(diff);
        steps.put(step("sharpen", "gpu", params(p, "sharpen")));
        double ul = p.optDouble("upsilonLow", 1), uh = p.optDouble("upsilonHigh", 1);
        if (ul != 1 || uh != 1)
            steps.put(step("rhef_upsilon", "gpu", params(p, "upsilonLow", "upsilonHigh")).put("algorithm", "RHEF").put("implementation", "hfstudio-glsl")
                    .put("citation", "Gilly & Cranmer (2025), Eq. 2 (as cited in imageCommon.frag)"));
        steps.put(step("colormap", "gpu", params(p, "lut", "invert")));
        steps.put(step("channel_mask", "gpu", params(p, "color")));
        steps.put(step("opacity_blend", "gpu", params(p, "opacity", "blend")));

        // -- what moved during the export: automation tracks, and settings changed while recording
        boolean changed = false;
        Map<String, List<String>> byStep = new LinkedHashMap<>();
        for (String t : tracks)
            byStep.computeIfAbsent(opOf(t.substring(t.lastIndexOf('/') + 1)), k -> new ArrayList<>()).add(t);
        Set<String> userChanged = new LinkedHashSet<>();
        if (end != null) {
            JSONObject ed = end.optJSONObject("data");
            JSONObject ep = ed == null ? null : ed.optJSONObject("imageParams");
            Set<String> automated = new java.util.HashSet<>();
            for (String t : tracks)
                automated.add(t.substring(t.lastIndexOf('/') + 1));
            if (ep != null)
                for (String k : p.keySet())
                    if (!automated.contains(k) && !String.valueOf(p.opt(k)).equals(String.valueOf(ep.opt(k))))
                        userChanged.add(k);
            if (ed != null && (!data.optString("filter").equals(ed.optString("filter"))
                    || !String.valueOf(data.optJSONObject("sequence")).equals(String.valueOf(ed.optJSONObject("sequence")))))
                userChanged.add(!data.optString("filter").equals(ed.optString("filter")) ? "filter" : "sequence");
            changed = !userChanged.isEmpty();
        }
        for (int i = 0; i < steps.length(); i++) {
            JSONObject s = steps.getJSONObject(i);
            String op = s.getString("op");
            List<String> stepTracks = byStep.getOrDefault(op, List.of());
            List<String> changedHere = new ArrayList<>();
            for (String k : userChanged)
                if (op.equals(opOf(k)))
                    changedHere.add(k);
            if (stepTracks.isEmpty() && changedHere.isEmpty())
                continue;
            JSONArray names = new JSONArray();
            for (String t : stepTracks)
                names.put(t.substring(t.lastIndexOf('/') + 1));
            for (String k : changedHere)
                names.put(k);
            s.put("varies", new JSONObject().put("params", names).put("automation", new JSONArray(stepTracks))
                    .put("changed_by_user", !changedHere.isEmpty()));
        }
        jo.put("steps", steps);
        jo.put("native", layer);
        String ack = InstrumentCredit.acknowledgement(live == null ? null : live.optString("credit_key", null));
        jo.put("acknowledgement", ack == null ? JSONObject.NULL : ack);
        if (live != null && live.has("frame"))
            jo.put("shown_frame", new JSONObject().put("frame", live.optInt("frame")).put("frames", live.optInt("frames")));
        return new LayerOut(jo, rhef, changed);
    }

    /** The step a parameter belongs to, for automation tracks and changed settings. */
    static String opOf(String param) {
        return switch (param) {
            case "opacity", "blend" -> "opacity_blend";
            case "sharpen" -> "sharpen";
            case "enhanced" -> "radial_gain";
            case "upsilonLow", "upsilonHigh" -> "rhef_upsilon";
            case "brightOffset", "brightScale" -> "levels";
            case "differenceMode" -> "difference";
            case "lut", "invert" -> "colormap";
            case "color" -> "channel_mask";
            case "innerMask", "outerMask", "slitLeft", "slitRight", "sectorCenter", "sectorWidth" -> "geometric_mask";
            case "clippingMode", "clippingMin", "clippingMax" -> "clip";
            case "scalingMode", "gamma", "beta", "alpha" -> "stretch";
            case "plane", "planes" -> "select_plane";
            case "filter" -> "image_filter";
            case "sequence" -> "sequence_filter";
            default -> "";
        };
    }

    private static JSONObject step(String op, String stage, JSONObject params) {
        return new JSONObject().put("op", op).put("stage", stage).put("params", params);
    }

    private static JSONObject params(JSONObject from, String... keys) {
        JSONObject jo = new JSONObject();
        for (String k : keys)
            if (from.has(k))
                jo.put(k, from.get(k));
        return jo;
    }

    @Nullable
    private static JSONObject byId(@Nullable JSONArray layers, String id) {
        if (layers != null)
            for (Object o : layers)
                if (o instanceof JSONObject l && id.equals(l.optString("id")))
                    return l;
        return null;
    }

    /** Enabled automation tracks on image layers, by layer id: "layer:<id>/<param>" as the session writes them. */
    private static Map<String, List<String>> automationByLayer(JSONObject state) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        JSONObject a = state.optJSONObject("automation");
        JSONArray tracks = a == null ? null : a.optJSONArray("tracks");
        if (tracks != null)
            for (Object o : tracks) {
                if (!(o instanceof JSONObject t) || !t.optBoolean("enabled", true))
                    continue;
                String key = t.optString("key");
                JSONArray keys = t.optJSONArray("keys");
                if (!key.startsWith("layer:") || key.indexOf('/') < 0 || keys == null || keys.isEmpty())
                    continue;
                out.computeIfAbsent(key.substring("layer:".length(), key.indexOf('/')), k -> new ArrayList<>()).add(key);
            }
        return out;
    }

    private static JSONObject scene(JSONObject state, JSONObject live, @Nullable JSONObject compare, boolean movie,
                                    @Nullable JSONObject endState) {
        JSONObject scene = new JSONObject();
        if (state.has("time"))
            scene.put("time", isoZ(String.valueOf(state.get("time"))));
        if (movie && state.has("masterStartTime") && state.has("masterEndTime"))
            scene.put("time_range", new JSONArray().put(isoZ(state.getString("masterStartTime"))).put(isoZ(state.getString("masterEndTime"))));
        JSONObject frame = live.optJSONObject("frame");
        if (frame != null) {
            scene.put("projection", frame.optString("projection"));
            if (frame.optJSONObject("viewpoint") != null)
                scene.put("viewpoint", frame.getJSONObject("viewpoint"));
            scene.put("camera_width", frame.opt("cameraWidth") == null ? JSONObject.NULL : frame.opt("cameraWidth"));
            if (frame.has("warpLambda"))
                scene.put("radial_transform", new JSONObject().put("warpLambda", frame.get("warpLambda"))
                        .put("warpOuterRadiusRsun", frame.opt("warpOuterRadiusRsun")).put("warpFieldRadiusRsun", frame.opt("warpFieldRadiusRsun")));
        }
        JSONArray others = state.optJSONArray("layers");
        JSONArray overlays = new JSONArray();
        if (others != null)
            for (Object o : others)
                if (o instanceof JSONObject l && l.optBoolean("enabled", true))
                    overlays.put(l.optString("name"));
        scene.put("overlays", overlays);
        scene.put("compare", compare == null ? JSONObject.NULL : new JSONObject(compare.toString())
                .put("description", "Studio Compare (HS-11): a and b are layer ids; mode Blink, Swipe (split is the divider's"
                        + " position, 0 to 1, aLeft says which side a keeps) or Difference (a minus b)."));
        JSONObject a = state.optJSONObject("automation");
        JSONArray sceneTracks = new JSONArray();
        if (movie && a != null && a.optJSONArray("tracks") != null)
            for (Object o : a.getJSONArray("tracks"))
                if (o instanceof JSONObject t && t.optBoolean("enabled", true) && !t.optString("key").startsWith("layer:")
                        && t.optJSONArray("keys") != null && !t.getJSONArray("keys").isEmpty())
                    sceneTracks.put(t.optString("key"));
        if (!sceneTracks.isEmpty())
            scene.put("varies", new JSONObject().put("automation", sceneTracks));
        return scene;
    }

    private static JSONObject producer(JSONObject session) {
        return new JSONObject().put("program", AppInfo.programName).put("version", AppInfo.version)
                .put("build_id", session.optString("buildId")).put("commit", session.optString("commit"))
                .put("dirty", session.optBoolean("dirty")).put("stamp", session.optString("writer"));
    }

    /** The suite provenance object (heliosoftware/spec/provenance.md, version 1): seven fields, each a string or null. */
    private static JSONObject suite(JSONObject session, JSONObject state, @Nullable JSONObject endState, boolean movie, JSONArray layers,
                                    String credit) {
        List<String> names = new ArrayList<>();
        for (Object o : layers)
            if (o instanceof JSONObject l && l.optBoolean("enabled", true)) {
                JSONObject d = l.optJSONObject("dataset");
                names.add(d != null && d.has("label") ? d.getString("label") : l.optString("name"));
            }
        JSONObject timeFrom = movie && endState != null ? endState : state;
        Object time = timeFrom.opt("time");
        return new JSONObject()
                .put("product", SUITE_PRODUCT)
                .put("version", AppInfo.version)
                .put("instrument", names.isEmpty() ? "unknown" : String.join(" + ", names))
                .put("observed", time == null ? JSONObject.NULL : secondsZ(String.valueOf(time)))
                .put("credit", credit)
                .put("rhef", session.has("rhef_stamp") ? session.getString("rhef_stamp") : JSONObject.NULL)
                .put("citation", JSONObject.NULL);
    }

    /** A Studio time ("2025-09-21T20:32:00" or with milliseconds) as UTC with a Z. */
    static String isoZ(String time) {
        return time.endsWith("Z") ? time : time + "Z";
    }

    /** YYYY-MM-DDTHH:MM:SSZ, as the suite provenance format wants observed. */
    static String secondsZ(String time) {
        String t = time.endsWith("Z") ? time.substring(0, time.length() - 1) : time;
        return (t.length() > 19 ? t.substring(0, 19) : t) + "Z";
    }

    /** SHA-256 of the sorted hashes joined by newline (the schema's source_set_sha256). */
    static String setHash(List<String> hashes) {
        List<String> sorted = new ArrayList<>(hashes);
        java.util.Collections.sort(sorted);
        return sha256(String.join("\n", sorted).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The plain text a person reads first: program, time, one line per layer, citations. */
    static String summary(JSONObject record) {
        StringBuilder sb = new StringBuilder();
        JSONObject out = record.getJSONObject("output");
        JSONObject scene = record.optJSONObject("scene");
        sb.append(AppInfo.programName).append(' ').append(record.getJSONObject("producer").optString("build_id"))
                .append(", ").append(out.getString("kind").replace('_', ' '));
        if (scene != null && scene.optJSONArray("time_range") != null)
            sb.append(" from ").append(scene.getJSONArray("time_range").getString(0)).append(" to ").append(scene.getJSONArray("time_range").getString(1));
        else if (scene != null && scene.has("time"))
            sb.append(" at ").append(scene.getString("time"));
        sb.append(".\n");
        JSONArray layers = record.getJSONArray("layers");
        for (int i = 0; i < layers.length(); i++) {
            JSONObject l = layers.getJSONObject(i);
            JSONObject d = l.optJSONObject("dataset");
            String filter = "no filter", lut = "", caveat = "";
            List<String> moving = new ArrayList<>();
            for (Object o : l.getJSONArray("steps")) {
                JSONObject s = (JSONObject) o;
                if ("image_filter".equals(s.optString("op")))
                    filter = s.optString("algorithm");
                if ("sequence_filter".equals(s.optString("op")))
                    filter = ("no filter".equals(filter) ? "" : filter + ", ") + "sequence filter";
                if ("colormap".equals(s.optString("op")))
                    lut = s.getJSONObject("params").optString("lut");
                if ("image_filter".equals(s.optString("op")) && "RHEF".equals(s.optString("algorithm")))
                    caveat = " (RHEF output is a visualization, not a calibrated radiance)";
                if (s.optJSONObject("varies") != null)
                    for (Object n : s.getJSONObject("varies").getJSONArray("params"))
                        moving.add(String.valueOf(n));
            }
            int n = l.has("source_count") ? l.getInt("source_count") : l.getJSONArray("sources").length();
            sb.append(i + 1).append(". ").append(d != null && d.has("label") ? d.getString("label") : l.optString("name"))
                    .append(l.optBoolean("enabled", true) ? "" : " (hidden)").append(": ")
                    .append(n).append(n == 1 ? " source frame" : " source frames").append(", ").append(filter);
            if (!lut.isEmpty())
                sb.append(", LUT ").append(lut);
            if (!moving.isEmpty())
                sb.append(", varies: ").append(String.join(", ", moving));
            sb.append(caveat).append('\n');
        }
        JSONObject compare = scene == null ? null : scene.optJSONObject("compare");
        if (compare != null)
            sb.append("Compare: ").append(compare.optString("mode")).append('\n');
        sb.append("Cite: ");
        JSONArray citations = record.optJSONArray("citations");
        List<String> c = new ArrayList<>();
        if (citations != null)
            for (Object o : citations)
                c.add(String.valueOf(o));
        sb.append(String.join("; ", c)).append('.');
        return sb.toString();
    }

    // ---- Send to Python ----------------------------------------------------------------------------

    /**
     * The record of one Send to Python file (io/FitsExport): physical values, not a picture, so
     * the only steps are the decode ones FitsExport applied, and display steps are listed as none
     * rather than as done. {@code entry} is the file's manifest.json entry; its own step texts are
     * kept verbatim under the layer's native.
     */
    static JSONObject buildFitsData(JSONObject session, String layerId, String layerName, @Nullable String creditKey,
                                    @Nullable SourceIdentity.Entry source, JSONObject entry, String fileName, @Nullable String sha256,
                                    String recordId) {
        JSONArray steps = new JSONArray();
        JSONArray texts = entry.optJSONArray("steps");
        if (texts != null)
            for (Object o : texts)
                if (String.valueOf(o).startsWith("subtracted OFFSET="))
                    steps.put(step("instrument_correction", "decode", new JSONObject().put("OFFSET", "subtracted"))
                            .put("automatic", true).put("implementation", "hfstudio-java").put("caveat", String.valueOf(o)
                                    + ". EXPTIME division and the NRL monthly background, which the viewer applies, are not."));
        JSONArray sources = new JSONArray();
        JSONObject layer = new JSONObject().put("id", layerId).put("name", layerName).put("enabled", true);
        String ack = InstrumentCredit.acknowledgement(creditKey);
        if (source != null) {
            JSONObject s = new JSONObject().put("sha256", source.sha256()).put("size", source.size()).put("in_library", source.heliad() != null);
            if (source.heliad() != null) {
                if (source.heliad().obsKey() != null)
                    s.put("heliad_obs_key", source.heliad().obsKey());
                JSONObject d = new JSONObject().put("dataset_key", source.heliad().datasetKey());
                if (source.heliad().label() != null)
                    d.put("label", source.heliad().label());
                if (source.heliad().datasetUid() != null)
                    d.put("dataset_uid", source.heliad().datasetUid());
                layer.put("dataset", d);
            }
            sources.put(s);
            layer.put("source_set_sha256", setHash(List.of(source.sha256())));
        }
        layer.put("source_spec", new JSONObject().put("kind", source != null && source.heliad() != null ? "heliad_library" : "local_files"))
                .put("sources", sources).put("steps", steps).put("native", new JSONObject().put("manifest", entry))
                .put("acknowledgement", ack == null ? JSONObject.NULL : ack);
        JSONObject record = new JSONObject()
                .put("hfrecord", FORMAT)
                .put("reader_compat", READER_COMPAT)
                .put("record_id", recordId)
                .put("created", session.optString("created"))
                .put("producer", producer(session))
                .put("output", new Output("fits_data", "application/fits", null, null, null, null, null, 64, false).toJson()
                        .put("file_name", fileName).put("sha256", sha256 == null ? JSONObject.NULL : sha256))
                .put("layers", new JSONArray().put(layer))
                .put("caveats", new JSONArray().put("Physical values only (BZERO and BSCALE applied): no colour table, filter, clipping or RHEF."))
                .put("citations", new JSONArray().put(Provenance.JHV_CITATION))
                .put("snapshot", new JSONObject().put("taken", "export_start").put("changed_during_export", false))
                .put("native", new JSONObject().put("studio_session", session));
        JSONObject state = session.optJSONObject("scene") == null ? new JSONObject() : session.getJSONObject("scene").optJSONObject(STATE);
        record.put("provenance", suite(session, state == null ? new JSONObject() : state, null, false, record.getJSONArray("layers"),
                        InstrumentCredit.line(creditKey == null ? List.of() : List.of(creditKey)))
                .put("rhef", JSONObject.NULL)); // nothing display-side, RHEF included, is in this file
        record.put("summary", summary(record));
        return record;
    }

    /** The sidecar of one Send to Python file, beside it; logged and skipped on failure. Off the event thread. */
    public static void writeFitsData(Path written, File source, JSONObject session, String layerId, String layerName,
                                     @Nullable String creditKey, JSONObject entry) {
        try {
            JSONObject record = buildFitsData(session, layerId, layerName, creditKey, SourceIdentity.lookup(source.toURI(), SOURCE_WAIT_MS),
                    entry, written.getFileName().toString(), fileHash(written), UUID.randomUUID().toString());
            writeJson(sidecar(written), record);
        } catch (IOException | RuntimeException e) {
            Log.warn("Processing record not written for " + written.getFileName(), e);
        }
    }

    // ---- Send to Python: the copy inside each FITS file, its sidecar, and hfrecord.json -----------

    /** EXTNAME of the extension that holds a Send to Python file's record (Heliad's record.py reads this name). */
    public static final String FITS_EXTNAME = "HFRECORD";
    /** The record of a whole Send to Python export, written beside its manifest.json. */
    public static final String FOLDER_RECORD = "hfrecord.json";
    /** How each HISTORY card of a FITS file's record block starts. */
    public static final String FITS_HISTORY_PREFIX = "HFREC " + FORMAT + " ";

    /** The live identity lookup the export uses: waits for a source frame still being hashed. */
    public static Function<URI, SourceIdentity.Entry> identities() {
        return uri -> SourceIdentity.lookup(uri, SOURCE_WAIT_MS);
    }

    /**
     * The record of one Send to Python file as it goes inside that file, built before the file is
     * written: output.sha256 is null, as the schema says an embedded copy's is. {@code entry} is
     * the file's manifest entry; a copy of it is kept, so later keys added to the entry do not
     * change the record.
     */
    public static JSONObject fitsDataEmbedded(JSONObject session, String layerId, String layerName, @Nullable String creditKey,
                                              File source, JSONObject entry,
                                              String fileName, String recordId, Function<URI, SourceIdentity.Entry> identify) {
        return buildFitsData(session, layerId, layerName, creditKey, identify.apply(source.toURI()), new JSONObject(entry.toString()),
                fileName, null, recordId);
    }

    /**
     * The sidecar of a written Send to Python file: its embedded record with the hash of the file
     * as written. Returns the sidecar's record, or null when it could not be written (logged).
     */
    @Nullable
    public static JSONObject writeFitsDataSidecar(Path written, JSONObject embedded) {
        try {
            JSONObject record = new JSONObject(embedded.toString());
            record.getJSONObject("output").put("sha256", fileHash(written));
            writeJson(sidecar(written), record);
            return record;
        } catch (IOException | RuntimeException e) {
            Log.warn("Processing record not written for " + written.getFileName(), e);
            return null;
        }
    }

    /** The SHA-256 of a Send to Python record's source file, or null when the source could not be identified. */
    @Nullable
    public static String fitsSourceSha256(JSONObject record) {
        JSONArray sources = record.getJSONArray("layers").getJSONObject(0).getJSONArray("sources");
        return sources.isEmpty() ? null : sources.getJSONObject(0).optString("sha256", null);
    }

    /** The FITS ORIGIN value the suite provenance format asks for: product and version. */
    public static String suiteOrigin() {
        return SUITE_PRODUCT + " " + AppInfo.version;
    }

    /** The credit line of one layer's instrument (InstrumentCredit), for the HISTORY cards of a Send to Python file. */
    public static String suiteCredit(@Nullable String creditKey) {
        return InstrumentCredit.line(creditKey == null ? List.of() : List.of(creditKey));
    }

    /**
     * hfrecord.json: one record for a whole Send to Python export, from the sidecar records of its
     * files (in export order). Layers are grouped by id in first-seen order; each holds every
     * source of its files, and each decode op once, with the files it was applied to under
     * resolved.applied_to (the values per file are in that file's own record).
     * output.frame_files lists every file with its hash and record_id.
     */
    static JSONObject buildFitsFolder(JSONObject session, List<JSONObject> records, String recordId) {
        Map<String, JSONObject> layers = new LinkedHashMap<>();
        Map<String, List<String>> hashes = new LinkedHashMap<>();
        Map<String, Map<String, JSONObject>> steps = new LinkedHashMap<>();
        JSONArray files = new JSONArray();
        for (JSONObject r : records) {
            JSONObject out = r.getJSONObject("output");
            String file = out.getString("file_name");
            files.put(new JSONObject().put("file_name", file).put("sha256", out.opt("sha256"))
                    .put("record_id", r.getString("record_id")).put("sidecar", file + SUFFIX));
            JSONObject l = r.getJSONArray("layers").getJSONObject(0);
            String id = l.getString("id");
            JSONObject layer = layers.computeIfAbsent(id, k -> new JSONObject().put("id", id).put("name", l.getString("name"))
                    .put("enabled", true).put("source_spec", new JSONObject().put("kind", "heliad_library"))
                    .put("sources", new JSONArray()).put("native", new JSONObject().put("records", new JSONArray()))
                    .put("acknowledgement", JSONObject.NULL));
            if (!layer.has("dataset") && l.has("dataset"))
                layer.put("dataset", l.getJSONObject("dataset"));
            if (!"heliad_library".equals(l.getJSONObject("source_spec").getString("kind")))
                layer.getJSONObject("source_spec").put("kind", "local_files");
            for (Object o : l.getJSONArray("sources")) {
                JSONObject s = (JSONObject) o;
                layer.getJSONArray("sources").put(s);
                if (s.has("sha256"))
                    hashes.computeIfAbsent(id, k -> new ArrayList<>()).add(s.getString("sha256"));
            }
            layer.getJSONObject("native").getJSONArray("records").put(r.getString("record_id"));
            Map<String, JSONObject> ops = steps.computeIfAbsent(id, k -> new LinkedHashMap<>());
            for (Object o : l.getJSONArray("steps")) {
                JSONObject s = (JSONObject) o;
                JSONObject merged = ops.computeIfAbsent(s.getString("op"), k -> {
                    JSONObject m = new JSONObject(s.toString()).put("resolved", new JSONObject().put("applied_to", new JSONArray()));
                    if (m.has("caveat"))
                        m.put("caveat", "Values differ per file; each file's own record has them. " + m.getString("caveat"));
                    return m;
                });
                merged.getJSONObject("resolved").getJSONArray("applied_to").put(file);
            }
        }
        JSONArray layerArray = new JSONArray();
        for (Map.Entry<String, JSONObject> e : layers.entrySet()) {
            JSONObject layer = e.getValue();
            List<String> h = hashes.get(e.getKey());
            if (h != null)
                layer.put("source_set_sha256", setHash(h));
            layer.put("steps", new JSONArray(steps.get(e.getKey()).values()));
            layerArray.put(layer);
        }
        JSONObject first = records.isEmpty() ? null : records.get(0);
        JSONObject record = new JSONObject()
                .put("hfrecord", FORMAT)
                .put("reader_compat", READER_COMPAT)
                .put("record_id", recordId)
                .put("created", session.optString("created"))
                .put("producer", producer(session))
                .put("output", new Output("fits_data", "application/fits", null, null, records.size(), null, null, 64, false).toJson()
                        .put("frame_files", files))
                .put("layers", layerArray)
                .put("caveats", first == null ? new JSONArray() : first.getJSONArray("caveats"))
                .put("citations", new JSONArray().put(Provenance.JHV_CITATION))
                .put("snapshot", new JSONObject().put("taken", "export_start").put("changed_during_export", false))
                .put("native", new JSONObject().put("studio_session", session));
        if (first != null && first.has("provenance"))
            record.put("provenance", first.getJSONObject("provenance"));
        record.put("summary", summary(record));
        return record;
    }

    /** Writes hfrecord.json into {@code outDir}; returns its file name, or null when it could not be written (logged). */
    @Nullable
    public static String writeFitsFolder(Path outDir, JSONObject session, List<JSONObject> records) {
        try {
            writeJson(outDir.resolve(FOLDER_RECORD), buildFitsFolder(session, records, UUID.randomUUID().toString()));
            return FOLDER_RECORD;
        } catch (IOException | RuntimeException e) {
            Log.warn("Processing record not written for the Send to Python folder " + outDir.getFileName(), e);
            return null;
        }
    }

    // ---- writing ---------------------------------------------------------------------------------

    /** Where the sidecar of {@code export} goes: its full file name plus .hfrec.json, beside it. */
    public static Path sidecar(Path export) {
        return export.resolveSibling(export.getFileName() + SUFFIX);
    }

    /** SHA-256 of a written file. */
    static String fileHash(Path file) throws IOException {
        return SourceIdentity.sha256(file);
    }

    /**
     * Build and write the sidecar for {@code export}; returns its path, or null when it could not
     * be written (logged; an export never fails for want of its record). Runs off the event thread:
     * it hashes the output and may wait for source frames still being identified.
     */
    @Nullable
    public static Path write(Path export, JSONObject session, JSONObject live, @Nullable JSONObject endScene, Output output) {
        try {
            String hash = Files.isRegularFile(export) ? fileHash(export) : null;
            JSONObject record = build(session, live, endScene, output, export.getFileName().toString(), hash,
                    System.getProperty("user.home", ""), uri -> SourceIdentity.lookup(uri, SOURCE_WAIT_MS), UUID.randomUUID().toString());
            if (Files.isDirectory(export))
                record.getJSONObject("output").put("frame_files", frameFiles(export));
            Path side = sidecar(export);
            writeJson(side, record);
            return side;
        } catch (IOException | RuntimeException e) {
            Log.warn("Processing record not written for " + export.getFileName(), e);
            return null;
        }
    }

    /** A frame series' files by name and hash, in name order: the series has no single file to hash. */
    private static JSONArray frameFiles(Path dir) throws IOException {
        JSONArray out = new JSONArray();
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(Files::isRegularFile).filter(f -> !f.getFileName().toString().endsWith(SUFFIX)).sorted().toList())
                out.put(new JSONObject().put("file_name", f.getFileName().toString()).put("sha256", fileHash(f)));
        }
        return out;
    }

    /** The record of {@code from} for a copy at {@code to} (the command line's -out): same pixels, its own name. */
    public static void copySidecar(Path from, Path to) {
        Path side = sidecar(from);
        if (!Files.isRegularFile(side))
            return;
        try {
            JSONObject record = new JSONObject(Files.readString(side, StandardCharsets.UTF_8));
            record.getJSONObject("output").put("file_name", to.getFileName().toString())
                    .put("sha256", fileHash(to));
            writeJson(sidecar(to), record);
        } catch (IOException | RuntimeException e) {
            Log.warn("Processing record not copied beside " + to.getFileName(), e);
        }
    }

    static void writeJson(Path to, JSONObject record) throws IOException {
        Path tmp = to.resolveSibling(to.getFileName() + ".tmp");
        Files.writeString(tmp, record.toString(2) + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ---- the embedded copy (heliosoftware/spec/processing-record.md, "Where each format keeps it") ---

    /** The PNG tEXt keyword, the EXR attribute and the movie metadata tag that hold the embedded copy. */
    public static final String EMBED_KEY = "hfrecord";
    /** The suite provenance object's PNG keyword (heliosoftware/spec/provenance.md). */
    public static final String SUITE_PNG_KEYWORD = "HelioSoftware-Provenance";
    static final String NATIVE_REF_PNG = "png:tEXt:" + Provenance.PNG_KEYWORD;
    static final String NATIVE_REF_EXR = "exr:attr:" + Provenance.PNG_KEYWORD;
    static final String NATIVE_REF_MOVIE = "mp4:comment";

    /**
     * The record for a file that is not written yet, so a copy of it can go inside the file: as
     * write() builds it, with no file name and no hash. writePrepared() adds both once the file is
     * complete. {@code waitForSources} false never blocks: a frame still being hashed is counted in
     * source_count instead (for the EXR copy, built when the recording starts).
     */
    public static JSONObject prepare(JSONObject session, JSONObject live, @Nullable JSONObject endScene, Output output,
                                     String recordId, boolean waitForSources) {
        long wait = waitForSources ? SOURCE_WAIT_MS : 0;
        return build(session, live, endScene, output, null, null, System.getProperty("user.home", ""),
                uri -> SourceIdentity.lookup(uri, wait), recordId);
    }

    /**
     * The copy that goes inside the file it describes: output.sha256 null (a file cannot hold its
     * own hash), no output.file_name or frame_files (sidecars only), and, when {@code nativeRef} is
     * given, native_ref in place of native because the same file already carries the session under
     * that name. Everything else is the record unchanged, record_id included. The input is not changed.
     */
    public static JSONObject embedded(JSONObject record, @Nullable String nativeRef) {
        JSONObject copy = new JSONObject(record.toString());
        JSONObject out = copy.getJSONObject("output");
        out.put("sha256", JSONObject.NULL);
        out.remove("file_name");
        out.remove("frame_files");
        if (nativeRef != null) {
            copy.remove("native");
            copy.put("native_ref", nativeRef);
        }
        return copy;
    }

    /**
     * The shortened copy a movie carries: each layer's sources emptied, with source_count and
     * source_set_sha256 left to match it to the full sidecar, and no frame_map. The schema requires
     * sources, so it stays as an empty list. The input is not changed.
     */
    public static JSONObject shortened(JSONObject embedded) {
        JSONObject copy = new JSONObject(embedded.toString());
        JSONArray layers = copy.optJSONArray("layers");
        if (layers != null)
            for (Object o : layers)
                if (o instanceof JSONObject l) {
                    JSONArray sources = l.optJSONArray("sources");
                    if (!l.has("source_count"))
                        l.put("source_count", sources == null ? 0 : sources.length());
                    l.put("sources", new JSONArray());
                }
        JSONObject scene = copy.optJSONObject("scene");
        if (scene != null)
            scene.remove("frame_map");
        return copy;
    }

    /** The embedded copy for a PNG: native by reference to the hfstudio chunk, ASCII-escaped like it. */
    public static String pngText(JSONObject record) {
        return Provenance.asciiJson(embedded(record, NATIVE_REF_PNG).toString());
    }

    /** The embedded copy for an EXR frame: native by reference to the hfstudio attribute. */
    public static String exrText(JSONObject record) {
        return embedded(record, NATIVE_REF_EXR).toString();
    }

    /**
     * The shortened copy for a movie's hfrecord tag. native is replaced by a reference to the
     * comment only when the comment holds the scene ({@code commentHasScene}); a comment cut down
     * to fit its cap does not, and then the session stays in the copy.
     */
    public static String movieText(JSONObject record, boolean commentHasScene) {
        return shortened(embedded(record, commentHasScene ? NATIVE_REF_MOVIE : null)).toString();
    }

    /** The suite provenance object of a record, as the PNG HelioSoftware-Provenance chunk holds it. */
    public static String suiteText(JSONObject record) {
        JSONObject p = record.optJSONObject("provenance");
        return Provenance.asciiJson((p == null ? new JSONObject() : p).toString());
    }

    /**
     * The sidecar for a record from prepare(), written once {@code export} is complete: its file
     * name and hash (a series directory gets its frame files instead). Returns the sidecar, or
     * null when it could not be written (logged, as write() does).
     */
    @Nullable
    public static Path writePrepared(Path export, JSONObject record) {
        try {
            JSONObject full = new JSONObject(record.toString());
            JSONObject out = full.getJSONObject("output");
            out.put("file_name", export.getFileName().toString());
            out.put("sha256", Files.isRegularFile(export) ? fileHash(export) : JSONObject.NULL);
            if (Files.isDirectory(export))
                out.put("frame_files", frameFiles(export));
            Path side = sidecar(export);
            writeJson(side, full);
            return side;
        } catch (IOException | RuntimeException e) {
            Log.warn("Processing record not written for " + export.getFileName(), e);
            return null;
        }
    }

}
