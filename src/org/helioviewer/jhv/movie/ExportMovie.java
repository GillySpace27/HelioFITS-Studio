package org.helioviewer.jhv.movie;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

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

    private static @Nullable RecordingSession recordingSession;
    private static @Nullable TimelineFrameSource timelineFrameSource;
    private static boolean mainCanvasVisible = true;

    public static void start(@Nullable Commands.OperationContext context, @Nullable Commands.RecordStartInput input) {
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
            recordingSession = new RecordingSession(context, ViewState.recordingData(), fps);
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

    private static final class RecordingSession {
        private final @Nullable Commands.OperationContext operationContext;
        private final ViewState.RecordingMode mode;
        private final int width;
        private final int height;
        private final boolean deepOutput;
        private final boolean includeTimelines;
        private final ExportWriter writer;
        private final Semaphore exrPermits = new Semaphore(EXR_IN_FLIGHT);

        private @Nullable GLGrab grabber;
        private int bytesPerPixel;
        private int exrFrameIndex;
        private boolean canvasFramePending;
        private boolean stopAfterFrame;

        RecordingSession(@Nullable Commands.OperationContext _operationContext,
                         ViewState.RecordingData recordingData, int fps) {
            operationContext = _operationContext;
            mode = recordingData.mode();
            if (mode == ViewState.RecordingMode.LOOP && !Player.hasActiveImage())
                throw new IllegalStateException("Loop recording requires an active image.");

            ViewState.Size size = recordingData.size();
            width = mode == ViewState.RecordingMode.SHOT ?
                    size.width() : size.width() / MACROBLOCK * MACROBLOCK;

            // A snapshot is always a PNG whatever the movie format is set to, so it qualifies on its
            // own; otherwise the chosen depth says whether the grab has to be deep.
            ExportFormat format = MoviePanel.storedFormat();
            ExportFormat.Depth depth = MoviePanel.storedDepth();
            ExportFormat.Chroma chroma = MoviePanel.storedChroma();
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
                        width, height, fps, false);
            } else {
                writer = new ExportWriter(format, chroma, depth, width, height, fps,
                        MoviePanel.isAllIntra());
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
                ExrWriter frame = ExrCapture.frame(grabber, writer.fps(), ++exrFrameIndex);
                encodeExecutor.execute(() -> {
                    try {
                        writer.encodeExr(frame);
                    } catch (Exception e) {
                        Log.error(e);
                        writer.recordFailure(e);
                    } finally {
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
            notifyStatusChanged();
            encodeExecutor.execute(new CloseWriter(writer, operationContext));
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
                NativeImageFactory.free(timelineImage);
                MappedImageFactory.free(mainImage);
            }
        }
    }

    private record CloseWriter(ExportWriter writer,
                               @Nullable Commands.OperationContext operationContext) implements Runnable {
        @Override
        public void run() {
            try {
                String output = writer.close();
                Commands.notifyRecordingFinished(operationContext, true, "Recording finished.", output);
            } catch (Exception e) {
                Log.error(e);
                String message = e.getMessage() == null || e.getMessage().isBlank() ?
                        "Recording failed." : e.getMessage();
                Commands.notifyRecordingFinished(operationContext, false, message, null);
            }
            System.gc();
        }
    }

    private ExportMovie() {}
}
