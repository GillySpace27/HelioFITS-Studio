package org.helioviewer.jhv.app;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.io.FileUtils;
import org.helioviewer.jhv.io.Load;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.movie.ExportMovie;
import org.helioviewer.jhv.movie.Player;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.time.JHVTime;

import org.json.JSONObject;

public final class Commands {

    public record PlaybackInput(
            @Nullable String advanceMode,
            @Nullable String speed,
            @Nullable String speedUnit,
            @Nullable String firstFrame,
            @Nullable String lastFrame) {}

    public record RecordStartInput(
            @Nullable String mode,
            @Nullable String size,
            @Nullable String advanceMode,
            @Nullable String speed,
            @Nullable String speedUnit) {}

    public record OperationContext(Class<?> owner, @Nullable String clientId, @Nullable String requestId,
                                   @Nullable String mtype, @Nullable Completion completion) {
        public OperationContext(Class<?> owner, @Nullable String clientId, @Nullable String requestId,
                                @Nullable String mtype) {
            this(owner, clientId, requestId, mtype, null);
        }

        public void complete(boolean success, String message, @Nullable String output) {
            if (completion != null)
                completion.finished(this, success, message, output);
        }
    }

    @FunctionalInterface
    public interface Completion {
        void finished(OperationContext context, boolean success, String message, @Nullable String output);
    }

    public static void setViewStateRaw(
            @Nullable String projection,
            @Nullable String annotationMode,
            @Nullable String multiview,
            @Nullable String tracking,
            @Nullable String refresh,
            @Nullable String showCorona,
            @Nullable String differentialRotation) {
        ViewState.applyModeUpdateRaw(projection, annotationMode, multiview, tracking, refresh, showCorona,
                differentialRotation);
    }

    public static void setPlayback(@Nullable PlaybackInput input) {
        if (input == null)
            return;
        ViewState.applyPlaybackUpdateRaw(
                input.advanceMode(),
                input.speed(),
                input.speedUnit(),
                input.firstFrame(),
                input.lastFrame());
    }

    public static void setPlaybackRange(int firstFrame, int lastFrame) {
        ViewState.setPlaybackRange(firstFrame, lastFrame);
    }

    // Trim the movie in/out points to the current frame (video-editor style). The playback range
    // is shared, so both the top scrubber and the bottom timeline update together.
    public static void trimStartHere() {
        ViewState.PlaybackData d = ViewState.playbackData();
        int cur = org.helioviewer.jhv.movie.Player.getCurrentFrameNumber();
        setPlaybackRange(Math.min(cur, d.lastFrame()), d.lastFrame());
    }

    public static void trimEndHere() {
        ViewState.PlaybackData d = ViewState.playbackData();
        int cur = org.helioviewer.jhv.movie.Player.getCurrentFrameNumber();
        setPlaybackRange(d.firstFrame(), Math.max(cur, d.firstFrame()));
    }

    public static void trimReset() {
        setPlaybackRange(0, org.helioviewer.jhv.movie.Player.getMaximumFrameNumber());
    }

    public static void play() {
        Player.play();
    }

    public static void pause() {
        Player.pause();
    }

    public static void togglePlayback() {
        Player.toggle();
    }

    public static void seekFrame(int frame) {
        Player.setFrame(frame);
    }

    /** The first frame of the playback range (the trimmed movie). */
    public static void firstFrame() {
        Player.setFrame(ViewState.playbackData().firstFrame());
    }

    /** The last frame of the playback range. */
    public static void lastFrame() {
        Player.setFrame(ViewState.playbackData().lastFrame());
    }

    public static void seekTime(JHVTime time) {
        Player.setTime(time);
    }

    public static void nextFrame() {
        Player.nextFrame();
    }

    public static void previousFrame() {
        Player.previousFrame();
    }

    public static void setRecordingRaw(@Nullable String mode, @Nullable String size) {
        ViewState.applyRecordingUpdateRaw(mode, size);
    }

    /** A sequence filter (velocity filter or noise gate) on a named image layer (the active one when null), from its JSON, or off. */
    public static void setSequenceRaw(@Nullable String layerName, @Nullable String json) {
        org.helioviewer.jhv.layers.ImageLayer layer = org.helioviewer.jhv.layers.Layers.getActiveImageLayer();
        if (layerName != null && !layerName.isBlank()) {
            layer = null;
            for (org.helioviewer.jhv.layers.ImageLayer il : org.helioviewer.jhv.layers.Layers.getImageLayers())
                if (il.getName().equals(layerName) || il.getName().startsWith(layerName)) {
                    layer = il;
                    break;
                }
            if (layer == null) {
                Log.warn("jhv.sequence.set: no image layer named " + layerName);
                return;
            }
        }
        if (layer == null)
            return;
        org.helioviewer.jhv.image.fourier.SequenceParams params = json == null || "off".equalsIgnoreCase(json.strip())
                ? null : org.helioviewer.jhv.image.fourier.SequenceParams.fromJson(new org.json.JSONObject(json));
        layer.setSequence(params);
    }

    /** The mode block plus the camera: what a study row inherits. */
    public static JSONObject getView() {
        return org.helioviewer.jhv.display.StudyView.get();
    }

    /**
     * Apply a partial view (mode keys, camera, layers: name to boolean) without an undo step or a
     * dirty session. Pair with {@link #restoreView}.
     */
    public static void setView(JSONObject partial) {
        SceneUndo.hold();
        org.helioviewer.jhv.display.StudyView.set(partial);
    }

    public static void restoreView(JSONObject full) {
        org.helioviewer.jhv.display.StudyView.restore(full);
        SceneUndo.release();
    }

    public static void clearViewOverride() {
        org.helioviewer.jhv.display.StudyView.clearOverride();
    }

    /** study.run: queue a job file (its JSON text); the completion fires when the run ends, output is the run folder. */
    public static void studyRun(@Nullable OperationContext context, String jobJson) {
        try {
            org.helioviewer.jhv.study.Study study = org.helioviewer.jhv.study.Study.fromJson(new JSONObject(jobJson));
            org.helioviewer.jhv.study.JobQueue.Job job = org.helioviewer.jhv.study.StudyJobs.study(study, null, null, false);
            if (context != null)
                job.whenEnded(() -> context.complete(job.state() == org.helioviewer.jhv.study.JobQueue.State.DONE, job.message(),
                        job.folder() == null ? null : job.folder().toString()));
        } catch (RuntimeException e) {
            if (context != null)
                context.complete(false, "Not a study job: " + e.getMessage(), null);
        }
    }

    /** study.status: every queued, running and finished job as JSON. */
    public static JSONObject studyStatus() {
        return org.helioviewer.jhv.study.JobQueue.INSTANCE.statusJson();
    }

    public static void recordStart(@Nullable OperationContext context, @Nullable RecordStartInput input) {
        ExportMovie.start(context, input);
    }

    public static void recordStop() {
        ExportMovie.shallStop();
    }

    public static void loadState(URI uri) {
        Load.state(uri);
    }

    public static void loadState(@Nullable OperationContext context, URI uri) {
        Load.state(context, uri);
    }

    public static void loadState(@Nullable OperationContext context, String json) {
        Load.state(context, json);
    }

    public static void loadRequest(URI uri) {
        Load.request(uri);
    }

    public static void loadRequest(String json) {
        Load.request(json);
    }

    public static void loadSunJSON(URI uri) {
        Load.sunJSON(uri);
    }

    public static void loadSunJSON(String json) {
        Load.sunJSON(json);
    }

    public static CompletableFuture<ImageLayer> loadImage(URI uri) {
        return loadImage(List.of(uri));
    }

    public static CompletableFuture<ImageLayer> loadImage(List<URI> uris) {
        return loadImage(uris, null);
    }

    public static CompletableFuture<ImageLayer> loadImage(List<URI> uris, @Nullable JSONObject imageParams) {
        CompletableFuture<ImageLayer> future = new CompletableFuture<>();
        if (uris.isEmpty()) {
            future.complete(null);
            return future;
        }

        Task.submitBackground(() -> FileUtils.resolveURIList(uris), resolved -> {
            if (resolved.isEmpty()) {
                future.complete(null);
                return;
            }

            try {
                ImageLayer layer = ImageLayer.create(null);
                layer.applyImageParams(imageParams);
                layer.load(resolved);
                future.complete(layer);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        }, future::completeExceptionally);
        return future;
    }

    public static void loadCDF(URI uri) {
        Load.cdf(uri);
    }

    public static void loadCDF(List<URI> uris) {
        Load.cdf(uris);
    }

    public static void loadVOTable(URI uri) {
        Load.votable(uri);
    }

    public static void loadHapi(URI uri) {
        Load.hapi(uri);
    }

    public static void loadHapi(List<URI> uris) {
        Load.hapi(uris);
    }

    public static void zoomIn() {
        DisplayController.zoomIn();
    }

    public static void zoomOut() {
        DisplayController.zoomOut();
    }

    public static void zoomFit() {
        DisplayController.zoomFit();
    }

    public static void zoomOneToOne() {
        DisplayController.zoomOneToOne();
    }

    public static void resetView() {
        DisplayController.resetView();
    }

    // Zoom, warp and crop as they stood when a session file was last loaded or explicitly saved in this run
    // (autosave does not count, or / would restore whatever was on screen a moment ago); null before either.
    // Crop is not in the session file, so it is remembered here rather than read back from the file.
    private static final java.util.concurrent.atomic.AtomicReference<double[]> savedScaling = new java.util.concurrent.atomic.AtomicReference<>();

    /** Zoom, warp and crop to remember as "last saved": on a session load completing and on an explicit save. */
    public static void rememberScaling() {
        savedScaling.set(new double[]{org.helioviewer.jhv.display.Display.getActiveViewport().zoom,
                org.helioviewer.jhv.display.Display.getWarpLambda(), org.helioviewer.jhv.display.Display.getWarpOuterRadius()});
    }

    /** The defaults {@link #resetScaling} writes, as {zoom, warp lambda, crop radius}. */
    public static final double[] DEFAULT_SCALING = {1, 1, 0};

    /** What the / key goes back to: {@code saved} when there is one, else the defaults. */
    public static double[] scalingTarget(@Nullable double[] saved) {
        return saved != null ? saved.clone() : DEFAULT_SCALING.clone();
    }

    /**
     * The / canvas key (Gilly, 2026-11-20): zoom, warp and crop back to the last saved session's values,
     * or to the defaults when nothing has been loaded or saved in this run. R stays the fit-zoom reset.
     * Returns whether saved values were used.
     */
    public static boolean restoreScaling() {
        double[] saved = savedScaling.get();
        if (saved == null) {
            resetScaling();
            return false;
        }
        double[] t = scalingTarget(saved);
        org.helioviewer.jhv.display.CMETracker.stop();
        ViewState.setWarpLambda(t[1]);
        org.helioviewer.jhv.display.Display.setWarpOuterRadius(t[2]);
        for (org.helioviewer.jhv.display.Viewport vp : org.helioviewer.jhv.display.Display.getViewports())
            vp.zoom = t[0];
        DisplayController.display();
        return true;
    }

    /**
     * Zoom, warp and crop back to their defaults, the Projection palette's Reset view (and / before any save):
     * warp off (lambda 1, the identity; 0 is the hard logarithmic warp), crop auto, magnification 1x. CME
     * tracking lets go first, or its next tick would write over them.
     */
    public static void resetScaling() {
        org.helioviewer.jhv.display.CMETracker.stop();
        ViewState.setWarpLambda(1);
        org.helioviewer.jhv.display.Display.setWarpOuterRadius(0);
        org.helioviewer.jhv.display.Display.resetViewportZoom();
        DisplayController.display();
    }

    public static void resetViewAxis() {
        DisplayController.resetViewAxis();
    }

    /**
     * Flip the grid's master switch, the Grid row's checkbox, the way that checkbox does. Returns the
     * new state, or null when there is no grid layer.
     */
    @Nullable
    public static Boolean toggleGrid() {
        org.helioviewer.jhv.layers.GridLayer grid = org.helioviewer.jhv.layers.Layers.getGridLayer();
        if (grid == null)
            return null;
        grid.setEnabled(!grid.isEnabled());
        org.helioviewer.jhv.layers.Layers.fireLayerUpdated(grid); // the sidebar row and the Grid palette follow
        DisplayController.render(1);
        return grid.isEnabled();
    }

    /** The projections the palette lists, in its order (the sky is a checkbox there, not one of them). */
    public static List<org.helioviewer.jhv.display.MapMode> listedProjections() {
        return java.util.Arrays.stream(org.helioviewer.jhv.display.MapMode.values())
                .filter(m -> m != org.helioviewer.jhv.display.MapMode.ObserverSky).toList();
    }

    /**
     * Pick a projection as the palette's radio does: with the sky on, a base that can host it changes what the
     * sky is drawn on and the view stays in the sky; any other change is a projection switch.
     */
    public static void selectProjection(org.helioviewer.jhv.display.MapMode el) {
        selectProjection(el, () -> {});
    }

    /** As {@link #selectProjection(org.helioviewer.jhv.display.MapMode)}; {@code afterSkyBase} runs once a sky base change is applied. */
    public static void selectProjection(org.helioviewer.jhv.display.MapMode el, Runnable afterSkyBase) {
        if (ViewState.getProjection() == org.helioviewer.jhv.display.MapMode.ObserverSky && el.hostsSky()) {
            org.helioviewer.jhv.display.ProjectionTransition.requestChange(() -> {
                org.helioviewer.jhv.display.Display.setSkyBase(el);
                afterSkyBase.run();
            });
        } else
            ViewState.setProjection(el);
    }

    /** Turn "Render in 3D" on or off; the new state. It only shows in Helioradial. */
    public static boolean toggleRender3D() {
        boolean on = !org.helioviewer.jhv.display.Display.isHelioradial3D();
        org.helioviewer.jhv.display.Display.setHelioradial3D(on);
        DisplayController.render(1);
        return on;
    }

    public static void rotateView90(@Nullable String axis) {
        DisplayController.rotateView90(axis);
    }

    public static void notifyLoadStateFinished(@Nullable OperationContext context, boolean success, String message) {
        if (context != null)
            context.complete(success, message, null);
    }

    public static void notifyRecordingFinished(@Nullable OperationContext context, boolean success, String message,
                                               @Nullable String output) {
        if (context != null)
            context.complete(success, message, output);
    }

    private Commands() {}
}
