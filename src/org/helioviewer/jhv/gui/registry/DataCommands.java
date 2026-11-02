package org.helioviewer.jhv.gui.registry;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import javax.swing.Timer;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.image.ImageProcessingSettings;
import org.helioviewer.jhv.io.APIRequest;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.FitsLibraryDatasetLoad;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.movie.ProcessingRecord;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.time.TimeUtils;
import org.helioviewer.jhv.view.View;
import org.helioviewer.jhv.view.uri.DecodeDump;

import org.json.JSONArray;
import org.json.JSONObject;

import static org.helioviewer.jhv.gui.registry.CommandRegistry.object;
import static org.helioviewer.jhv.gui.registry.CommandSpec.Effect;

/**
 * The data commands: what a notebook, the heliofits Python client or the MCP server needs to do
 * a task rather than press a menu item. Load a dataset as a layer and wait for its frames, list,
 * reload and remove layers, set a filter, set and read the view, write one frame's decoded values
 * as an hf-decode-dump/1 document, and render, export a still or export a movie at a given size.
 *
 * <p>Ids are camelCase like the rest of the registry and are API: never renamed. Argument names
 * are snake_case, as the Python side spells them. Each command is one add() here; docs/commands.json
 * carries the same schemas to every front end, and the heliofits package copies that file.
 *
 * <p>A layer is named by a small integer, {@code layer_id}, given when it is first seen by one of
 * these commands and kept until the layer is removed; it is not stable across runs of Studio.
 *
 * <p>Loads and exports answer when they are done, through {@link Dispatch.Deferred}: the handler
 * starts the work on the event thread and a timer or completion fulfils the promise later.
 */
final class DataCommands {

    private static final String TIME_DESC = "UTC time, ISO 8601 (2024-05-10T12:00 or 2024-05-10T12:00:00.000)";
    private static final int POLL_MS = 250;

    private static final Map<ImageLayer, Integer> ids = new WeakHashMap<>();
    private static final AtomicInteger nextId = new AtomicInteger(1);

    private DataCommands() {}

    static void register() {
        CommandRegistry.add(new CommandSpec("layerAdd",
                "Add an image layer for a dataset over a time range and wait for its frames", Effect.WRITE,
                object("{\"source\":{\"type\":\"string\",\"enum\":[\"jp2\",\"fits-library\"],"
                        + "\"description\":\"jp2: Helioviewer pictures over JPIP; fits-library: the HelioFITS Core cache on this computer\"},"
                        + "\"dataset\":{\"type\":\"string\",\"description\":\"jp2: the dataset as the server lists it, e.g. 'AIA 193' (with observatory); fits-library: a Core dataset key or uid\"},"
                        + "\"observatory\":{\"type\":\"string\",\"description\":\"jp2 only: the observatory, e.g. 'SDO'\"},"
                        + "\"start\":{\"type\":\"string\",\"description\":\"" + TIME_DESC + "\"},"
                        + "\"end\":{\"type\":\"string\",\"description\":\"" + TIME_DESC + "\"},"
                        + "\"cadence_s\":{\"type\":\"integer\",\"minimum\":1,\"description\":\"jp2 only: wanted spacing in seconds; omit for the server's default\"},"
                        + "\"wait\":{\"type\":\"string\",\"enum\":[\"all\",\"first\",\"none\"],"
                        + "\"description\":\"Answer when every frame is loaded (default), when the first is shown, or at once\"}}",
                        "source", "dataset", "start", "end"),
                out("{\"layer_id\":{\"type\":\"integer\"},\"frames\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"},\"loading\":{\"type\":\"boolean\"}}"),
                DataCommands::layerAdd, () -> true));
        CommandRegistry.add(new CommandSpec("layerList", "The image layers in the scene, with their ids", Effect.READ,
                CommandRegistry.object("{}"),
                out("{\"layers\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}}"),
                args -> {
                    JSONArray list = new JSONArray();
                    for (ImageLayer layer : Layers.getImageLayers())
                        list.put(describe(layer));
                    return new JSONObject().put("layers", list);
                }, () -> true));
        CommandRegistry.add(new CommandSpec("layerRemove", "Remove an image layer from the scene", Effect.DESTRUCTIVE,
                object("{\"layer_id\":{\"type\":\"integer\",\"description\":\"From layerAdd or layerList\"}}", "layer_id"),
                CommandRegistry.object("{}"), args -> {
                    Layers.remove(layer(args));
                    return new JSONObject();
                }, () -> true));
        CommandRegistry.add(new CommandSpec("layerReload",
                "Read a layer's frames again; a FITS library layer queries the cache afresh, so files added since are picked up", Effect.WRITE,
                object("{\"layer_id\":{\"type\":\"integer\",\"description\":\"From layerAdd or layerList\"},"
                        + "\"wait\":{\"type\":\"string\",\"enum\":[\"all\",\"first\",\"none\"],\"description\":\"As layerAdd\"}}", "layer_id"),
                out("{\"layer_id\":{\"type\":\"integer\"},\"frames\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"},\"loading\":{\"type\":\"boolean\"}}"),
                DataCommands::layerReload, () -> true));
        CommandRegistry.add(new CommandSpec("filterSet", "Set a layer's image filter and its FITS display parameters", Effect.WRITE,
                object("{\"layer_id\":{\"type\":\"integer\",\"description\":\"From layerAdd or layerList\"},"
                        + "\"filter\":{\"type\":\"string\",\"enum\":[\"none\",\"mgn\",\"wow\",\"rhef\"],\"description\":\"The filter\"},"
                        + "\"params\":{\"type\":\"object\",\"description\":\"The layer's image parameters by the names a saved session uses: "
                        + "clippingMode, clippingMin, clippingMax, scalingMode, gamma, beta, alpha, plane, subtractBackground, and the display settings\"}}",
                        "layer_id"),
                CommandRegistry.object("{}"), args -> {
                    ImageLayer layer = layer(args);
                    if (args.has("filter"))
                        layer.setFilter(filter(args.getString("filter")));
                    if (args.has("params"))
                        layer.applyImageParams(args.getJSONObject("params"));
                    Layers.fireLayerUpdated(layer);
                    return new JSONObject();
                }, () -> true));
        CommandRegistry.add(new CommandSpec("viewSet", "Set the view: projection, zoom, Box-Cox lambda, tracking, corona", Effect.WRITE,
                object("{\"projection\":{\"type\":\"string\",\"enum\":[\"orthographic\",\"hpc\",\"helioradial\",\"helioradialUnrolled\",\"latitudinal\"]},"
                        + "\"zoom\":{\"type\":\"number\",\"exclusiveMinimum\":0,\"description\":\"Zoom by this factor, above 1 closer\"},"
                        + "\"warp_lambda\":{\"type\":\"number\",\"minimum\":-1,\"maximum\":1,\"description\":\"Box-Cox radial lambda of the helioradial projections\"},"
                        + "\"tracking\":{\"type\":\"boolean\"},\"show_corona\":{\"type\":\"boolean\"},\"differential_rotation\":{\"type\":\"boolean\"},"
                        + "\"multiview\":{\"type\":\"boolean\"}}"),
                CommandRegistry.object("{}"), args -> {
                    if (args.has("projection"))
                        ViewState.setProjection(projection(args.getString("projection")));
                    if (args.has("warp_lambda"))
                        ViewState.setWarpLambda(args.getDouble("warp_lambda"));
                    if (args.has("tracking"))
                        ViewState.setTracking(args.getBoolean("tracking"));
                    if (args.has("show_corona"))
                        ViewState.setShowCorona(args.getBoolean("show_corona"));
                    if (args.has("differential_rotation"))
                        ViewState.setDifferentialRotation(args.getBoolean("differential_rotation"));
                    if (args.has("multiview"))
                        ViewState.setMultiview(args.getBoolean("multiview"));
                    if (args.has("zoom")) {
                        double zoom = args.getDouble("zoom");
                        if (!(zoom > 0))
                            throw new IllegalArgumentException("zoom must be above 0");
                        DisplayController.zoomBy(zoom);
                    }
                    return new JSONObject();
                }, () -> true));
        CommandRegistry.add(new CommandSpec("viewGet", "The current view", Effect.READ, CommandRegistry.object("{}"),
                out("{\"projection\":{\"type\":\"string\"},\"warp_lambda\":{\"type\":\"number\"},\"zoom\":{\"type\":\"number\"},"
                        + "\"tracking\":{\"type\":\"boolean\"},\"show_corona\":{\"type\":\"boolean\"},\"differential_rotation\":{\"type\":\"boolean\"},"
                        + "\"multiview\":{\"type\":\"boolean\"},\"surface_model\":{\"type\":\"string\"},\"annotation_mode\":{\"type\":\"string\"}}"),
                args -> {
                    ViewState.ModeData mode = ViewState.modeData();
                    return new JSONObject()
                            .put("projection", lower(mode.projection().name()))
                            .put("warp_lambda", mode.warpLambda())
                            .put("zoom", Display.getActiveViewport().zoom)
                            .put("tracking", mode.tracking())
                            .put("show_corona", mode.showCorona())
                            .put("differential_rotation", mode.differentialRotation())
                            .put("multiview", mode.multiview())
                            .put("surface_model", mode.surfaceModel().name())
                            .put("annotation_mode", mode.annotationMode().name());
                }, () -> true));
        CommandRegistry.add(new CommandSpec("frameGet",
                "Write one FITS frame's decoded values, as the layer shows them before any colour table, to an hf-decode-dump/1 document and return its path",
                Effect.READ,
                object("{\"layer_id\":{\"type\":\"integer\",\"description\":\"From layerAdd or layerList\"},"
                        + "\"frame\":{\"type\":\"integer\",\"minimum\":0,\"description\":\"Frame number, 0 is the first; default the frame on screen\"}}", "layer_id"),
                out("{\"path\":{\"type\":\"string\"},\"format\":{\"type\":\"string\"},\"frame\":{\"type\":\"integer\"},\"time\":{\"type\":\"string\"}}"),
                DataCommands::frameGet, () -> true));
        CommandRegistry.add(new CommandSpec("previewRender",
                "Render the scene as an export would, to a PNG in Studio's cache (not among the exports), and return its path", Effect.READ,
                object("{\"long_side\":{\"type\":\"integer\",\"minimum\":16,\"description\":\"Pixels on the long side; omit for the export size in the settings\"}}"),
                out("{\"path\":{\"type\":\"string\"}}"),
                args -> record(ViewState.RecordingMode.SHOT, args.optInt("long_side", 0), null), () -> true));
        CommandRegistry.add(new CommandSpec("exportStill", "Export the frame on screen as a PNG, with its processing record beside it", Effect.WRITE,
                object("{\"path\":{\"type\":\"string\",\"description\":\"Where to write the PNG\"},"
                        + "\"long_side\":{\"type\":\"integer\",\"minimum\":16,\"description\":\"Pixels on the long side; omit for the export size in the settings\"}}", "path"),
                out("{\"path\":{\"type\":\"string\"}}"),
                args -> record(ViewState.RecordingMode.SHOT, args.optInt("long_side", 0), Path.of(args.getString("path"))), () -> true));
        CommandRegistry.add(new CommandSpec("exportMovie",
                "Export one loop of the loaded frames as a movie in the format the settings choose, with its processing record beside it", Effect.WRITE,
                object("{\"path\":{\"type\":\"string\",\"description\":\"Where to write the movie\"},"
                        + "\"long_side\":{\"type\":\"integer\",\"minimum\":16,\"description\":\"Pixels on the long side; omit for the export size in the settings\"}}", "path"),
                out("{\"path\":{\"type\":\"string\"},\"frames\":{\"type\":\"integer\"}}"),
                args -> record(ViewState.RecordingMode.LOOP, args.optInt("long_side", 0), Path.of(args.getString("path"))), () -> true));
    }

    // layers --------------------------------------------------------------------------------------

    private static JSONObject layerAdd(JSONObject args) {
        String source = args.getString("source");
        String wait = args.optString("wait", "all");
        String start = args.getString("start"), end = args.getString("end");
        CompletableFuture<JSONObject> done = new CompletableFuture<>();
        if (source.equals("jp2")) {
            JSONObject request = new JSONObject().put("dataset", args.getString("dataset")).put("startTime", start).put("endTime", end);
            if (args.has("observatory"))
                request.put("observatory", args.getString("observatory"));
            if (args.has("cadence_s"))
                request.put("cadence", args.getInt("cadence_s"));
            APIRequest req = APIRequest.fromRequestJson(request); // IllegalArgumentException when nothing matches
            ImageLayer layer = ImageLayer.create(null);
            layer.load(req);
            awaitLoad(layer, wait, done);
        } else {
            String dataset = args.getString("dataset");
            FitsLibraryDatasetLoad.load(dataset, start, end).whenComplete((layer, failed) -> {
                if (failed != null)
                    done.completeExceptionally(new IllegalArgumentException(message(failed)));
                else if (layer == null)
                    done.completeExceptionally(new IllegalArgumentException("nothing to load"));
                else {
                    rememberLibraryQuery(layer, dataset, start, end);
                    awaitLoad(layer, wait, done);
                }
            });
        }
        return new Dispatch.Deferred(done);
    }

    private static JSONObject layerReload(JSONObject args) {
        ImageLayer layer = layer(args);
        String wait = args.optString("wait", "all");
        CompletableFuture<JSONObject> done = new CompletableFuture<>();
        LibraryQuery query = libraryQueries.get(layer);
        if (query != null) {
            FitsLibraryDatasetLoad.uris(query.dataset(), query.start(), query.end()).whenComplete((uris, failed) -> {
                if (failed != null) {
                    done.completeExceptionally(new IllegalArgumentException(message(failed)));
                    return;
                }
                layer.load(uris);
                awaitLoad(layer, wait, done);
            });
        } else if (layer.getView().getAPIRequest() != null) {
            throw new IllegalArgumentException("a jp2 layer streams from its server and has nothing to reload");
        } else {
            layer.reloadSources();
            awaitLoad(layer, wait, done);
        }
        return new Dispatch.Deferred(done);
    }

    /** A FITS library layer's query, kept so layerReload can ask the cache again. */
    private record LibraryQuery(String dataset, @Nullable String start, @Nullable String end) {}

    private static final Map<ImageLayer, LibraryQuery> libraryQueries = new WeakHashMap<>();

    /**
     * Fulfil {@code done} with the layer's description once it has loaded as far as {@code wait}
     * asks, or fail it when the layer leaves the scene first (an empty answer removes it).
     */
    private static void awaitLoad(ImageLayer layer, String wait, CompletableFuture<JSONObject> done) {
        if (wait.equals("none")) {
            done.complete(describe(layer));
            return;
        }
        Timer timer = new Timer(POLL_MS, null);
        timer.addActionListener(e -> {
            if (!Layers.getImageLayers().contains(layer)) {
                timer.stop();
                done.completeExceptionally(new IllegalStateException("the layer was removed before it loaded (no data in range?)"));
            } else if (wait.equals("first") ? layer.hasPixels() : layer.hasPixels() && layer.isViewLoadFinished()) {
                timer.stop();
                done.complete(describe(layer));
            }
        });
        timer.start();
    }

    static synchronized int id(ImageLayer layer) {
        return ids.computeIfAbsent(layer, l -> nextId.getAndIncrement());
    }

    private static synchronized ImageLayer layer(JSONObject args) {
        int wanted = args.getInt("layer_id");
        for (ImageLayer layer : Layers.getImageLayers())
            if (ids.containsKey(layer) && ids.get(layer) == wanted)
                return layer;
        throw new IllegalArgumentException("no layer with id " + wanted + "; layerList gives the ids");
    }

    static JSONObject describe(ImageLayer layer) {
        View view = layer.getView();
        boolean pixels = layer.hasPixels();
        JSONObject o = new JSONObject()
                .put("layer_id", id(layer))
                .put("name", layer.getName())
                .put("frames", pixels ? view.getMaximumFrameNumber() + 1 : 0)
                .put("loading", layer.isLoadingView() || !layer.isViewLoadFinished())
                .put("active", layer == Layers.getActiveImageLayer())
                .put("source", view.getAPIRequest() != null ? "jp2" : view.hasFITS() ? "fits" : "file")
                .put("filter", lower(layer.getFilter().name()));
        if (pixels) {
            o.put("start", TimeUtils.format(view.getFirstTime().milli));
            o.put("end", TimeUtils.format(view.getLastTime().milli));
            o.put("frame", view.getCurrentFrameNumber());
        }
        return o;
    }

    static void rememberLibraryQuery(ImageLayer layer, String dataset, @Nullable String start, @Nullable String end) {
        synchronized (DataCommands.class) {
            libraryQueries.put(layer, new LibraryQuery(dataset, start, end));
        }
    }

    // frames --------------------------------------------------------------------------------------

    private static JSONObject frameGet(JSONObject args) {
        ImageLayer layer = layer(args);
        View view = layer.getView();
        if (!layer.hasPixels())
            throw new IllegalStateException("the layer has no frames yet");
        int frame = args.has("frame") ? args.getInt("frame") : view.getCurrentFrameNumber();
        if (frame > view.getMaximumFrameNumber())
            throw new IllegalArgumentException("frame " + frame + " is beyond the last frame, " + view.getMaximumFrameNumber());
        String key = view.frameKey(frame);
        File file = localFile(key);
        if (file == null || !view.hasFITS())
            throw new IllegalArgumentException("frameGet reads FITS files on this computer; this layer's frame is " + (key == null ? "not a file" : key));
        ImageProcessingSettings.FITSParameters p = layer.getProcessingSettings().fitsParameters();
        String time = TimeUtils.format(view.getFrameTime(frame).milli);
        Path outDir = Path.of(Directories.CACHE.getPath(), "Dumps");
        CompletableFuture<JSONObject> done = new CompletableFuture<>();
        Task.submitBackground(() -> {
            Files.createDirectories(outDir);
            return DecodeDump.write(outDir, file, p.plane(), p.subtractBackground(), p.background(), lower(layer.getFilter().name()));
        }, path -> done.complete(new JSONObject().put("path", path.toString()).put("format", DecodeDump.FORMAT).put("frame", frame).put("time", time)),
                done::completeExceptionally);
        return new Dispatch.Deferred(done);
    }

    @Nullable
    private static File localFile(@Nullable String key) {
        if (key == null)
            return null;
        try {
            URI uri = new URI(key);
            return "file".equals(uri.getScheme()) ? new File(uri) : null;
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    // exports -------------------------------------------------------------------------------------

    /**
     * One recording, answered when the file is written. With {@code target} the export is copied
     * there with its processing record; without, the written file is moved into the cache (a
     * preview is not an export to keep) and its record goes with it.
     */
    private static JSONObject record(ViewState.RecordingMode mode, int longSide, @Nullable Path target) {
        ImageLayer active = Layers.getActiveImageLayer();
        if (active == null || !active.hasPixels())
            throw new IllegalStateException("nothing to render: no image layer with frames");
        if (ExportMovie.isRecording())
            throw new IllegalStateException("a recording is already in progress");
        CompletableFuture<JSONObject> done = new CompletableFuture<>();
        Commands.OperationContext context = new Commands.OperationContext(DataCommands.class, null, null, "api.export", (c, ok, message, output) -> {
            if (!ok || output == null) {
                done.completeExceptionally(new IllegalStateException(message == null || message.isBlank() ? "the recording failed" : message));
                return;
            }
            try {
                Path written = Path.of(output.contains("%04d") ? String.format(output, 1) : output);
                Path out;
                if (target != null) {
                    out = target.toAbsolutePath();
                    if (out.getParent() != null)
                        Files.createDirectories(out.getParent());
                    Files.copy(written, out, StandardCopyOption.REPLACE_EXISTING);
                    ProcessingRecord.copySidecar(written, out);
                } else {
                    Path previews = Path.of(Directories.CACHE.getPath(), "Previews");
                    Files.createDirectories(previews);
                    out = previews.resolve(written.getFileName());
                    Files.move(written, out, StandardCopyOption.REPLACE_EXISTING);
                    Path sidecar = ProcessingRecord.sidecar(written);
                    if (Files.exists(sidecar))
                        Files.move(sidecar, ProcessingRecord.sidecar(out), StandardCopyOption.REPLACE_EXISTING);
                }
                JSONObject result = new JSONObject().put("path", out.toString());
                if (mode != ViewState.RecordingMode.SHOT) {
                    ImageLayer layer = Layers.getActiveImageLayer();
                    if (layer != null && layer.hasPixels())
                        result.put("frames", layer.getView().getMaximumFrameNumber() + 1);
                }
                done.complete(result);
            } catch (IOException | RuntimeException e) {
                done.completeExceptionally(new IllegalStateException("could not place the export: " + e.getMessage()));
            }
        });
        ExportMovie.startSized(context, mode, longSide);
        return new Dispatch.Deferred(done);
    }

    // small helpers -------------------------------------------------------------------------------

    private static JSONObject out(String properties) {
        return new JSONObject().put("type", "object").put("properties", new JSONObject(properties));
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static ImageFilter.Type filter(String name) {
        for (ImageFilter.Type t : ImageFilter.Type.values())
            if (lower(t.name()).equals(name))
                return t;
        throw new IllegalArgumentException("no filter '" + name + "'");
    }

    private static MapMode projection(String name) {
        for (MapMode m : MapMode.values())
            if (lower(m.name()).equals(lower(name)))
                return m;
        throw new IllegalArgumentException("no projection '" + name + "'");
    }

    private static String message(Throwable t) {
        Throwable cause = t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
        return cause.getMessage() == null ? String.valueOf(cause) : cause.getMessage();
    }
}
