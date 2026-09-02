package org.helioviewer.jhv.movie;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.image.nio.MappedImageFactory;
import org.helioviewer.jhv.image.nio.NativeImageFactory;
import org.helioviewer.jhv.opengl.GLGrab;
import org.helioviewer.jhv.thread.AppThread;

public final class ExportMovie implements Player.Listener {

    public interface StatusListener {
        void recordingStatusChanged();
    }

    private static final ExportMovie instance = new ExportMovie();
    private static final ExecutorService encodeExecutor = Executors.newSingleThreadExecutor(new AppThread.NamedThreadFactory("JHV-EncodeMovie"));
    private static final ArrayList<StatusListener> statusListeners = new ArrayList<>();

    private static ExportWriter exporter;
    private static GLGrab grabber;

    private static ViewState.RecordingMode mode;
    private static boolean recording;
    private static boolean shallStop;
    private static @Nullable Commands.OperationContext operationContext;

    public static BufferedImage EVEImage = null;
    public static int EVEMovieLinePosition = -1;

    public static void disposeMovieWriter(boolean keep) {
        if (exporter != null) {
            if (keep) {
                encodeExecutor.execute(new CloseWriter(exporter));
            } else {
                for (Runnable runnable : encodeExecutor.shutdownNow()) {
                    if (runnable instanceof FrameConsumer frameConsumer) {
                        NativeImageFactory.free(frameConsumer.eveImage());
                        MappedImageFactory.free(frameConsumer.mainImage());
                    }
                }
            }
            exporter = null;
        }
    }

    public static void dispose() {
        if (grabber != null)
            grabber.dispose();
    }

    private static int frameIndex;
    // At most this many layered EXR frames alive at once (one being written, one waiting). A
    // frame is hundreds of megabytes at 4K and the writer is slower than the renderer, so an
    // unbounded queue filled a 30 GB heap in a dozen frames. The GL thread waits instead.
    private static final int EXR_IN_FLIGHT = 2;
    private static java.util.concurrent.Semaphore exrPermits = new java.util.concurrent.Semaphore(EXR_IN_FLIGHT);

    public static void handleMovieExport() {
        BufferedImage screen = null;
        BufferedImage eve = null;
        boolean submitted = false;
        try {
            if (exporter.format() == ExportFormat.EXR) {
                // Rendered layer by layer on this (GL) thread; only the file write is deferred.
                // The EVE strip is a movie decoration and has no place in a data export.
                ExportWriter writer = exporter;
                java.util.concurrent.Semaphore permits = exrPermits;
                permits.acquireUninterruptibly();
                try {
                    ExrWriter frame = ExrCapture.frame(grabber, writer.fps(), ++frameIndex);
                    encodeExecutor.execute(() -> {
                        try {
                            writer.encodeExr(frame);
                        } catch (Exception e) {
                            Log.error(e);
                        } finally {
                            permits.release();
                        }
                    });
                } catch (RuntimeException | Error e) {
                    permits.release(); // the task that would have released it never got queued
                    throw e;
                }
                submitted = true;
                return;
            }
            // Sized to the capture's stride: 6 bytes per pixel once the target is RGBA16F.
            // createRGBImage allocates 3, so a 16-bit frame needs twice the width's worth of
            // bytes, which is what asking for 2w gives without a new factory method.
            int bpp = grabber.bytesPerPixel();
            screen = MappedImageFactory.createRGBImage(bpp == 6 ? 2 * grabber.w : grabber.w, grabber.h);
            grabber.renderFrame(MappedImageFactory.getByteBuffer(screen));
            eve = EVEImage == null ? null : NativeImageFactory.copyImage(EVEImage);
            encodeExecutor.execute(new FrameConsumer(exporter, screen, eve, EVEMovieLinePosition, bpp));
            submitted = true;
        } catch (Exception e) {
            Log.error(e);
        } finally {
            if (!submitted) {
                NativeImageFactory.free(eve);
                MappedImageFactory.free(screen);
            }
            Player.grabDone();
            if (shallStop) {
                grabber.dispose();
                stop();
            }
        }
    }

    private static final int MACROBLOCK = 8;

    public static void start(@Nullable Commands.OperationContext context, @Nullable Commands.RecordStartInput input) {
        if (isRecording()) {
            if (context != null)
                Commands.notifyRecordingFinished(context, false, "Recording already in progress.", null);
            return;
        }

        operationContext = context;
        try {
            if (input != null)
                ViewState.applyRecordStartUpdate(input.mode(), input.size(), input.advanceMode(), input.speed(), input.speedUnit());

            ViewState.PlaybackData playbackData = ViewState.playbackData();
            int fps = playbackData.speedUnit().isRelative() ? playbackData.speed() : Player.FPS_ABSOLUTE;
            startRecording(ViewState.recordingData(), fps);
        } catch (Exception e) {
            Log.error(e);
            recording = false;
            shallStop = false;
            Player.removeFrameListener(instance);
            if (grabber != null) {
                grabber.dispose();
                grabber = null;
            }
            exporter = null;
            notifyStatusChanged();
            String message = e.getMessage() == null || e.getMessage().isBlank() ? "Recording failed." : e.getMessage();
            recordingFinished(false, message, null);
        }
    }

    private static void startRecording(ViewState.RecordingData recordingData, int fps) {
        shallStop = false;
        frameIndex = 0;
        exrPermits = new java.util.concurrent.Semaphore(EXR_IN_FLIGHT); // fresh per recording: a cancelled one never releases

        int scrw = 1;
        int scrh = 0;
        if (EVEImage != null) {
            scrw = Math.max(1, EVEImage.getWidth());
            scrh = EVEImage.getHeight();
        }

        ViewState.Size size = recordingData.size();
        int width = size.width();
        int height = size.height();
        boolean internal = size.internal();

        mode = recordingData.mode();
        int canvasWidth = mode == ViewState.RecordingMode.SHOT ? width : (width / MACROBLOCK) * MACROBLOCK;
        int sh = (int) (scrh / (double) scrw * canvasWidth + .5);
        int canvasHeight = internal ? height - sh : height;
        int exportHeight = mode == ViewState.RecordingMode.SHOT ? canvasHeight + sh : ((canvasHeight + sh) / MACROBLOCK) * MACROBLOCK;

        canvasHeight = exportHeight - sh;
        // A snapshot is always a PNG whatever the movie format is set to, so it qualifies on its
        // own; otherwise the chosen depth says whether the grab has to be deep. That used to be
        // "is it a frame series", on the grounds that an mp4 would need a 10/12-bit pixel format
        // and a matching profile; the depth control is that decision, made per recording.
        ExportFormat format = org.helioviewer.jhv.gui.component.MoviePanel.storedFormat();
        ExportFormat.Chroma chroma = org.helioviewer.jhv.gui.component.MoviePanel.storedChroma();
        ExportFormat.Depth depth = org.helioviewer.jhv.gui.component.MoviePanel.storedDepth();
        boolean deepOutput = mode == ViewState.RecordingMode.SHOT || format.wantsHighBitDepth(depth);
        grabber = new GLGrab(canvasWidth, canvasHeight, deepOutput);

        if (mode == ViewState.RecordingMode.SHOT) {
            // A snapshot is a PNG regardless, so it takes PNG's own fixed depth and sampling.
            exporter = new ExportWriter(ExportFormat.PNG, ExportFormat.Chroma.RGB, ExportFormat.Depth.SIXTEEN,
                    canvasWidth, exportHeight, fps, false); // a single still
            shallStop = true;

            recording = true;
            notifyStatusChanged();
            DisplayController.render(1);
        } else {
            exporter = new ExportWriter(format, chroma, depth, canvasWidth, exportHeight, fps,
                    org.helioviewer.jhv.gui.component.MoviePanel.isAllIntra());

            recording = true;
            notifyStatusChanged();

            if (mode == ViewState.RecordingMode.LOOP) {
                Player.addFrameListener(instance);
                Commands.seekFrame(0);
                Commands.play();
            }
        }
    }

    private static void stop() {
        recording = false;
        notifyStatusChanged();
        if (mode == ViewState.RecordingMode.LOOP) {
            Player.removeFrameListener(instance);
        }

        try {
            disposeMovieWriter(true);
        } catch (Exception e) {
            Log.error(e);
            exporter = null;
            String message = e.getMessage() == null || e.getMessage().isBlank() ? "Recording failed." : e.getMessage();
            recordingFinished(false, message, null);
        }
    }

    private static void recordingFinished(boolean success, String message, @Nullable String output) {
        Commands.notifyRecordingFinished(operationContext, success, message, output);
        operationContext = null;
    }

    // loop mode only
    @Override
    public void frameChanged(int frame, boolean last) {
        if (last)
            shallStop = true;
    }

    public static void shallStop() {
        if (!isRecording())
            return;
        shallStop = true;
        DisplayController.display(); // force detach
    }

    public static boolean isRecording() {
        return recording;
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

    private record FrameConsumer(ExportWriter exportWriter, BufferedImage mainImage, BufferedImage eveImage,
                                 int movieLinePosition, int bytesPerPixel) implements Runnable {
        @Override
        public void run() {
            try {
                exportWriter.encode(mainImage, eveImage, movieLinePosition, bytesPerPixel);
            } catch (Exception e) {
                Log.error(e);
            } finally {
                NativeImageFactory.free(eveImage);
                MappedImageFactory.free(mainImage);
            }
        }
    }

    private record CloseWriter(ExportWriter exportWriter) implements Runnable {
        @Override
        public void run() {
            try {
                String output = exportWriter.close();
                recordingFinished(true, "Recording finished.", output);
            } catch (Exception e) {
                Log.error(e);
                String message = e.getMessage() == null || e.getMessage().isBlank() ? "Recording failed." : e.getMessage();
                recordingFinished(false, message, null);
            }
            System.gc();
        }
    }

    private ExportMovie() {}
}
