package org.helioviewer.jhv.movie;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.display.HdrTransfer;
import org.helioviewer.jhv.gui.component.MoviePanel;
import org.helioviewer.jhv.image.nio.MappedImageFactory;
import org.helioviewer.jhv.image.nio.NativeImageFactory;
import org.helioviewer.jhv.opengl.GLGrab;
import org.helioviewer.jhv.thread.AppThread;

import org.json.JSONObject;

public final class ExportMovie {

    public interface StatusListener {
        void recordingStatusChanged();
    }

    @FunctionalInterface
    public interface TimelineFrameSource {
        @Nullable
        TimelineFrame getFrame();
    }

    public record TimelineFrame(BufferedImage image, int movieLinePosition) {}

    private static final int MACROBLOCK = 8;
    // At most this many layered EXR frames alive at once (one being written, one waiting). A
    // frame is hundreds of megabytes at 4K and the writer is slower than the renderer, so an
    // unbounded queue filled a 30 GB heap in a dozen frames. The GL thread waits instead.
    private static final int EXR_IN_FLIGHT = 2;
    private static final ExecutorService encodeExecutor = AppThread.createIdleExecutor("HFS-EncodeMovie", 1);
    private static final ArrayList<StatusListener> statusListeners = new ArrayList<>();
    // For the footer: frames handed to the encoder, frames it has finished, and whether the file is
    // still being written. Capture ends before encoding does, so "recording" alone went quiet for the
    // longest part of a long export (Gilly, 2026-10-20).
    private static final AtomicInteger framesQueued = new AtomicInteger();
    private static final AtomicInteger framesEncoded = new AtomicInteger();
    private static final AtomicBoolean writing = new AtomicBoolean();

    private static @Nullable RecordingSession recordingSession;
    private static @Nullable TimelineFrameSource timelineFrameSource;
    private static boolean mainCanvasVisible = true;

    public static void start(@Nullable Commands.OperationContext context, @Nullable Commands.RecordStartInput input) {
        start(context, input, null, null);
    }

    /**
     * Quick Share: one recording in {@code mode} with {@code once}'s encoding, applied to this recording
     * only. Nothing is written to the person's recording mode, size, format or preset, so there is nothing
     * to put back afterwards.
     */
    public static void startOnce(@Nullable Commands.OperationContext context, ExportPreset once, ViewState.RecordingMode mode) {
        start(context, null, once, mode);
    }

    /** The encoding a recording uses: {@code once} when given, else the settings the person has chosen. Reads, never writes. */
    static Encoding encodingFor(@Nullable ExportPreset once) {
        return once == null
                ? new Encoding(MoviePanel.storedFormat(), MoviePanel.storedChroma(), MoviePanel.storedDepth(), MoviePanel.isAllIntra())
                : new Encoding(once.format(), once.chroma(), once.depth(), once.allIntra());
    }

    /** Quick Share's long side: HD is where every phone, chat preview and slide deck is happy, and the file stays sendable. */
    static final int SHARE_LONG_SIDE = 1920;

    /**
     * The person's recording data for one Quick Share: the mode swapped and the long side capped at
     * {@link #SHARE_LONG_SIDE}. The person's own size is read, never written.
     */
    static ViewState.RecordingData shareData(ViewState.RecordingData current, ViewState.RecordingMode mode) {
        return new ViewState.RecordingData(mode, current.aspect(), Math.min(current.longSide(), SHARE_LONG_SIDE));
    }

    record Encoding(ExportFormat format, ExportFormat.Chroma chroma, ExportFormat.Depth depth, boolean allIntra) {}

    private static void start(@Nullable Commands.OperationContext context, @Nullable Commands.RecordStartInput input,
                              @Nullable ExportPreset once, @Nullable ViewState.RecordingMode onceMode) {
        if (isRecording()) {
            if (context != null)
                Commands.notifyRecordingFinished(context, false, "Recording already in progress.", null);
            return;
        }

        try {
            if (input != null)
                ViewState.applyRecordStartUpdate(input.mode(), input.size(), input.advanceMode(), input.speed(), input.speedUnit());

            ViewState.PlaybackData playbackData = ViewState.playbackData();
            int fps = playbackData.speedUnit().isRelative() ? playbackData.speed() : Player.FPS_ABSOLUTE;
            ViewState.RecordingData data = onceMode == null ? ViewState.recordingData() : shareData(ViewState.recordingData(), onceMode);
            framesQueued.set(0);
            framesEncoded.set(0);
            recordingSession = new RecordingSession(context, data, fps, encodingFor(once));
            notifyStatusChanged();
            recordingSession.start();
        } catch (Exception e) {
            Log.error(e);
            HdrTransfer.capture = HdrTransfer.Curve.NONE;
            RecordingSession failedSession = recordingSession;
            recordingSession = null;
            if (failedSession != null)
                failedSession.disposeGrabber();
            notifyStatusChanged();
            String message = e.getMessage() == null || e.getMessage().isBlank() ? "Recording failed." : e.getMessage();
            Commands.notifyRecordingFinished(context, false, message, null);
        }
    }

    public static void shallStop() {
        if (recordingSession != null)
            recordingSession.requestStop();
    }

    public static boolean isRecording() {
        return recordingSession != null;
    }

    /** What the footer says about the movie being made, from capture to the file closing; null when none is. */
    @Nullable
    public static String progress() {
        int queued = framesQueued.get(), encoded = framesEncoded.get();
        RecordingSession session = recordingSession;
        if (session != null) {
            int total = session.mode == ViewState.RecordingMode.LOOP ? Player.getMaximumFrameNumber() + 1 : 0;
            return "Recording movie: frame " + queued + (total > 0 ? " of " + total : "")
                    + (queued - encoded > 1 ? " (" + encoded + " encoded)" : "");
        }
        if (!writing.get())
            return null;
        return encoded < queued ? "Encoding movie: " + encoded + " of " + queued + " frames" : "Writing the movie file\u2026";
    }

    static boolean beginPlaybackFrame() {
        return recordingSession == null || recordingSession.beginPlaybackFrame();
    }

    static void playbackFrameReady(boolean last) {
        if (recordingSession != null)
            recordingSession.playbackFrameReady(last);
    }

    public static void renderedFrame() {
        if (recordingSession != null)
            recordingSession.renderedFrame();
    }

    public static void setMainCanvasVisible(boolean visible) {
        if (mainCanvasVisible == visible)
            return;
        mainCanvasVisible = visible;
        if (recordingSession != null)
            recordingSession.canvasVisibilityChanged();
    }

    public static void setTimelineFrameSource(@Nullable TimelineFrameSource source) {
        timelineFrameSource = source;
    }

    public static void addStatusListener(StatusListener listener) {
        if (!statusListeners.contains(listener)) {
            statusListeners.add(listener);
            listener.recordingStatusChanged();
        }
    }

    public static void removeStatusListener(StatusListener listener) {
        statusListeners.remove(listener);
    }

    private static void notifyStatusChanged() {
        statusListeners.forEach(StatusListener::recordingStatusChanged);
    }

    private static @Nullable TimelineFrame getTimelineFrame() {
        return timelineFrameSource == null ? null : timelineFrameSource.getFrame();
    }

    public static void dispose() {
        if (recordingSession != null)
            recordingSession.disposeGrabber();
    }

    public static void shutdown() {
        recordingSession = null;
        for (Runnable runnable : encodeExecutor.shutdownNow()) {
            if (runnable instanceof FrameConsumer frameConsumer) {
                NativeImageFactory.free(frameConsumer.timelineImage);
                MappedImageFactory.free(frameConsumer.mainImage);
            }
        }
    }

    // The provenance for every file one recording writes, taken once at its start on the caller's
    // thread, which is the one the state serializer runs on. A recording must not fail for want of
    // it, so a scene that cannot be serialized records none and says so in the log.
    @Nullable
    private static JSONObject takeProvenance() {
        try {
            return Provenance.session();
        } catch (RuntimeException e) {
            Log.warn("Export provenance unavailable", e);
            return null;
        }
    }

    private static final class RecordingSession {
        private final @Nullable Commands.OperationContext operationContext;
        private final ViewState.RecordingMode mode;
        private final int width;
        private final int height;
        private final boolean deepOutput;
        private final boolean includeTimelines;
        private final ExportWriter writer;
        private final @Nullable JSONObject provenance;
        private final @Nullable String exrProvenance;
        private final JSONObject live; // ProcessingRecord.live() beside the provenance, same moment
        private final String recordId = java.util.UUID.randomUUID().toString(); // shared by the sidecar and every embedded copy
        private @Nullable String exrRecord; // the EXR frames' embedded copy, built once at the first frame
        private @Nullable JSONObject endScene; // the scene as the recording ended, for "changed during export"
        private final Semaphore exrPermits = new Semaphore(EXR_IN_FLIGHT);

        private @Nullable GLGrab grabber;
        private int bytesPerPixel;
        private int exrFrameIndex;
        private boolean canvasFramePending;
        private boolean stopAfterFrame;

        RecordingSession(@Nullable Commands.OperationContext _operationContext,
                         ViewState.RecordingData recordingData, int fps, Encoding encoding) {
            operationContext = _operationContext;
            provenance = takeProvenance();
            exrProvenance = provenance == null ? null : provenance.toString();
            live = ProcessingRecord.live();
            mode = recordingData.mode();
            if (mode == ViewState.RecordingMode.LOOP && !Player.hasActiveImage())
                throw new IllegalStateException("Loop recording requires an active image.");

            ViewState.Size size = recordingData.size();
            width = mode == ViewState.RecordingMode.SHOT ?
                    size.width() : size.width() / MACROBLOCK * MACROBLOCK;

            // A snapshot is always a PNG whatever the movie format is set to, so it qualifies on its
            // own; otherwise the chosen depth says whether the grab has to be deep.
            ExportFormat format = encoding.format();
            ExportFormat.Depth depth = encoding.depth();
            ExportFormat.Chroma chroma = encoding.chroma();
            deepOutput = mode == ViewState.RecordingMode.SHOT || format.wantsHighBitDepth(depth);
            bytesPerPixel = deepOutput ? 6 : 3;

            boolean exr = mode != ViewState.RecordingMode.SHOT && format == ExportFormat.EXR;
            if (exr && !mainCanvasVisible)
                throw new IllegalStateException("The image canvas is not available.");
            // Read once, so a panel opened or closed mid-recording cannot change what goes into
            // frames already sized. The strip is a movie decoration and has no place in a data export.
            includeTimelines = !exr && MoviePanel.isTimelinesInRecording();

            TimelineFrame timelineFrame = timelineFrame();
            if (!mainCanvasVisible && timelineFrame == null)
                throw new IllegalStateException("The timeline is not available.");

            int timelineHeight = timelineFrame == null ? 0 :
                    scaledHeight(timelineFrame.image, width);
            int outputHeight = size.internal() ? size.height() :
                    mainCanvasVisible ? size.height() + timelineHeight : timelineHeight;
            if (mode != ViewState.RecordingMode.SHOT)
                outputHeight = outputHeight / MACROBLOCK * MACROBLOCK;
            height = outputHeight;

            if (mode == ViewState.RecordingMode.SHOT) {
                // A snapshot is a PNG regardless, so it takes PNG's own fixed depth and sampling.
                writer = new ExportWriter(ExportFormat.PNG, ExportFormat.Chroma.RGB, ExportFormat.Depth.SIXTEEN,
                        width, height, fps, false, provenance);
            } else {
                writer = new ExportWriter(format, chroma, depth, width, height, fps,
                        encoding.allIntra(), provenance);
            }
        }

        void start() {
            if (mode == ViewState.RecordingMode.SHOT) {
                stopAfterFrame = true;
                if (mainCanvasVisible) {
                    canvasFramePending = true;
                    DisplayController.render(1);
                } else {
                    captureFrame();
                }
            } else {
                // What the frames are encoded into, for exactly as long as this recording lasts.
                HdrTransfer.capture = writer.format().hdrCurve();
                if (mode == ViewState.RecordingMode.LOOP) {
                    Commands.seekFrame(0);
                    Commands.play();
                }
            }
        }

        boolean beginPlaybackFrame() {
            if (canvasFramePending)
                return false;
            canvasFramePending = mainCanvasVisible;
            return true;
        }

        void playbackFrameReady(boolean last) {
            if (mode == ViewState.RecordingMode.LOOP && last)
                stopAfterFrame = true;
            if (!mainCanvasVisible)
                captureFrame();
        }

        void renderedFrame() {
            if (!mainCanvasVisible || (mode != ViewState.RecordingMode.FREE && !canvasFramePending))
                return;
            canvasFramePending = false;
            captureFrame();
        }

        void canvasVisibilityChanged() {
            if (!mainCanvasVisible && (canvasFramePending || stopAfterFrame)) {
                canvasFramePending = false;
                captureFrame();
            }
        }

        void requestStop() {
            stopAfterFrame = true;
            if (mainCanvasVisible) {
                canvasFramePending = true;
                DisplayController.display();
            } else {
                captureFrame();
            }
        }

        // With the canvas hidden the timeline is the whole picture, so the checkbox does not apply.
        private @Nullable TimelineFrame timelineFrame() {
            return includeTimelines || !mainCanvasVisible ? getTimelineFrame() : null;
        }

        private void captureFrame() {
            if (recordingSession != this)
                return;

            BufferedImage mainImage = null;
            BufferedImage timelineImage = null;
            boolean submitted = false;
            try {
                if (writer.format() == ExportFormat.EXR) {
                    captureExr();
                } else {
                    TimelineFrame timelineFrame = timelineFrame();
                    int timelineHeight = timelineFrame == null ? 0 : timelineHeight(timelineFrame.image);
                    if (mainCanvasVisible) {
                        int mainHeight = height - timelineHeight;
                        ensureGrabber(mainHeight);
                        // Sized to the capture's stride: 6 bytes per pixel once the target is RGBA16F.
                        // createRGBImage allocates 3, so a 16-bit frame needs twice the width's worth of
                        // bytes, which is what asking for 2w gives without a new factory method.
                        bytesPerPixel = grabber.bytesPerPixel();
                        mainImage = MappedImageFactory.createRGBImage(bytesPerPixel == 6 ? 2 * grabber.w : grabber.w, grabber.h);
                        grabber.renderFrame(MappedImageFactory.getByteBuffer(mainImage));
                    } else if (timelineFrame == null) {
                        throw new IllegalStateException("The timeline is not available.");
                    }

                    int movieLinePosition = -1;
                    if (timelineFrame != null) {
                        timelineImage = NativeImageFactory.copyImage(timelineFrame.image);
                        movieLinePosition = timelineFrame.movieLinePosition;
                    }
                    framesQueued.incrementAndGet();
                    encodeExecutor.execute(new FrameConsumer(writer, mainImage, timelineImage, movieLinePosition, bytesPerPixel));
                    submitted = true;
                }
            } catch (Exception e) {
                Log.error(e);
                writer.recordFailure(e);
            } finally {
                if (!submitted) {
                    NativeImageFactory.free(timelineImage);
                    MappedImageFactory.free(mainImage);
                }
            }

            if (stopAfterFrame)
                finish();
        }

        // Rendered layer by layer on this (GL) thread; only the file write is deferred.
        private void captureExr() {
            if (!mainCanvasVisible)
                throw new IllegalStateException("The image canvas is not available.");
            ensureGrabber(height);
            exrPermits.acquireUninterruptibly();
            try {
                ExrWriter frame = ExrCapture.frame(grabber, writer.fps(), ++exrFrameIndex, exrProvenance);
                String embedded = exrRecord();
                if (embedded != null)
                    frame.attribute(ProcessingRecord.EMBED_KEY, embedded);
                framesQueued.incrementAndGet();
                encodeExecutor.execute(() -> {
                    try {
                        writer.encodeExr(frame);
                    } catch (Exception e) {
                        Log.error(e);
                        writer.recordFailure(e);
                    } finally {
                        framesEncoded.incrementAndGet();
                        exrPermits.release();
                    }
                });
            } catch (RuntimeException | Error e) {
                exrPermits.release(); // the task that would have released it never got queued
                throw e;
            }
        }

        private void ensureGrabber(int grabberHeight) {
            if (grabber != null && grabber.w == width && grabber.h == grabberHeight)
                return;
            if (grabber != null)
                grabber.dispose();
            grabber = new GLGrab(width, grabberHeight, deepOutput);
        }

        private int timelineHeight(BufferedImage image) {
            if (!mainCanvasVisible)
                return height;
            return Math.min(scaledHeight(image, width), height - 1);
        }

        private static int scaledHeight(BufferedImage image, int width) {
            return (int) (image.getHeight() / (double) image.getWidth() * width + .5);
        }

        private void finish() {
            if (recordingSession != this)
                return;
            recordingSession = null;
            HdrTransfer.capture = HdrTransfer.Curve.NONE;
            disposeGrabber();
            writing.set(true);
            notifyStatusChanged();
            if (mode != ViewState.RecordingMode.SHOT)
                endScene = takeEndScene();
            encodeExecutor.execute(new CloseWriter(writer, operationContext, record()));
        }

        /**
         * The record copy every EXR frame carries. Frames are files written during the recording,
         * so it is built at the first frame, from the start of the export: no frame count, no end
         * scene, and a source frame still being hashed is counted, not waited for (this is the GL
         * thread). The sidecar, written at the end, is the complete record; both share record_id.
         */
        @Nullable
        private String exrRecord() {
            if (exrRecord != null || provenance == null)
                return exrRecord;
            try {
                ProcessingRecord.Output output = new ProcessingRecord.Output("exr_series", "image/x-exr", width, height, null,
                        writer.fps(), ExportFormat.EXR.toString(), 16, true);
                exrRecord = ProcessingRecord.exrText(ProcessingRecord.prepare(provenance, live, null, output, recordId, false));
            } catch (RuntimeException e) {
                Log.warn("Processing record not embedded in the EXR frames", e);
                exrRecord = "";
            }
            return exrRecord.isEmpty() ? null : exrRecord;
        }

        /** What the sidecar needs once the file is closed; null when there is no provenance to build it from. */
        @Nullable
        private RecordInput record() {
            if (provenance == null)
                return null;
            boolean shot = mode == ViewState.RecordingMode.SHOT;
            ExportFormat format = writer.format();
            String kind = shot ? "still" : format == ExportFormat.EXR ? "exr_series" : format == ExportFormat.PNG ? "frame_series" : "movie";
            String media = format == ExportFormat.EXR ? "image/x-exr" : format.isSeries() || shot ? "image/png"
                    : format.extension.endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
            int frames = format == ExportFormat.EXR ? exrFrameIndex : framesQueued.get();
            ProcessingRecord.Output output = new ProcessingRecord.Output(kind, media, width, height, shot ? 1 : frames,
                    shot ? null : writer.fps(), shot ? "png" : format.toString(), shot ? 16 : format == ExportFormat.EXR ? 16 : bytesPerPixel == 6 ? 16 : 8,
                    true);
            return new RecordInput(provenance, live, endScene, output, recordId);
        }

        void disposeGrabber() {
            if (grabber != null) {
                grabber.dispose();
                grabber = null;
            }
        }
    }

    private record FrameConsumer(ExportWriter writer, @Nullable BufferedImage mainImage,
                                 @Nullable BufferedImage timelineImage,
                                 int movieLinePosition, int bytesPerPixel) implements Runnable {
        @Override
        public void run() {
            try {
                writer.encode(mainImage, timelineImage, movieLinePosition, bytesPerPixel);
            } catch (Exception e) {
                Log.error(e);
                writer.recordFailure(e);
            } finally {
                framesEncoded.incrementAndGet();
                NativeImageFactory.free(timelineImage);
                MappedImageFactory.free(mainImage);
            }
        }
    }

    /** The scene when a movie ends, for the record's "changed during export". Never fails the recording. */
    @Nullable
    private static JSONObject takeEndScene() {
        try {
            return org.helioviewer.jhv.app.state.State.snapshot();
        } catch (RuntimeException e) {
            Log.warn("Export end scene unavailable", e);
            return null;
        }
    }

    /** Everything ProcessingRecord.write needs besides the written file. */
    record RecordInput(JSONObject session, JSONObject live, @Nullable JSONObject endScene, ProcessingRecord.Output output,
                       String recordId) {

        /** The record before the file is written, so the file can carry a copy; null when it cannot be built (logged). */
        @Nullable
        JSONObject prepare() {
            try {
                return ProcessingRecord.prepare(session, live, endScene, output, recordId, true);
            } catch (RuntimeException e) {
                Log.warn("Processing record not built before the export closed", e);
                return null;
            }
        }
    }

    /**
     * The sidecar for what close() returned: a file, frame N of a series (a still), or a series'
     * directory. Off the event thread, on the encoder, after the file is complete.
     */
    static void writeRecord(@Nullable String output, @Nullable RecordInput in) {
        writeRecord(output, in, null);
    }

    /** As above; {@code prepared}, when given, is the record the file already carries a copy of, completed and written as the sidecar. */
    static void writeRecord(@Nullable String output, @Nullable RecordInput in, @Nullable JSONObject prepared) {
        if (output == null || in == null)
            return;
        java.nio.file.Path path = java.nio.file.Path.of(output.contains("%04d") ? String.format(output, 1) : output);
        if (output.contains("%04d") && !"still".equals(in.output().kind()))
            path = path.getParent(); // a series is its directory
        if (prepared != null)
            ProcessingRecord.writePrepared(path, prepared);
        else
            ProcessingRecord.write(path, in.session(), in.live(), in.endScene(), in.output());
    }

    private record CloseWriter(ExportWriter writer,
                               @Nullable Commands.OperationContext operationContext,
                               @Nullable RecordInput record) implements Runnable {
        @Override
        public void run() {
            try {
                // EXR frames took their copy when captured; the sidecar is still built here, at the end.
                JSONObject prepared = record == null ? null : record.prepare();
                String output = writer.close(writer.format() == ExportFormat.EXR ? null : prepared);
                writeRecord(output, record, prepared); // before the report, so a caller that copies the file finds its record
                Commands.notifyRecordingFinished(operationContext, true, "Recording finished.", output);
            } catch (Exception e) {
                Log.error(e);
                String message = e.getMessage() == null || e.getMessage().isBlank() ?
                        "Recording failed." : e.getMessage();
                Commands.notifyRecordingFinished(operationContext, false, message, null);
            } finally {
                writing.set(false);
            }
            System.gc();
        }
    }

    private ExportMovie() {}
}
