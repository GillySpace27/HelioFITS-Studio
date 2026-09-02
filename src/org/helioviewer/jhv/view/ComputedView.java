package org.helioviewer.jhv.view;

import java.awt.EventQueue;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.image.ImageBufferCache;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.image.fourier.FourierFilter;
import org.helioviewer.jhv.image.fourier.SequenceJob;
import org.helioviewer.jhv.image.fourier.SequenceParams;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.io.APIRequest;
import org.helioviewer.jhv.io.DataUri;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.thread.AppThread;
import org.helioviewer.jhv.thread.Task;
import org.helioviewer.jhv.time.JHVTime;

/**
 * A view that serves a computed sequence (a velocity filter, the noise gate) in place of the
 * frames of the view it wraps, and still holds that view: switching the filter off is a swap
 * back, never a reload. Frame times, counts and metadata are the wrapped view's, so the player,
 * the timeline and Layers see nothing new.
 *
 * <p>While the job runs, decode() passes through to the wrapped view and the layer shows source
 * frames; once it is done, the computed frames live in ImageBufferCache under ComputedKey and
 * decode() publishes them through the same EDT hand-off URIView uses. An evicted frame falls
 * back to the source with a status asking for another Apply: re-running is cheaper than pinning
 * gigabytes.
 *
 * <p>frameImage() returns the computed frame once ready, which is what lets a second
 * ComputedView wrap this one (noise gate, then velocity filter) with no extra code.
 *
 * <p>Install through ImageLayer.swapView, never setView: replaceView abolishes the view it
 * replaces, and the view being replaced is the one this wraps.
 */
public final class ComputedView implements View {

    public record ComputedKey(ComputedView view, int frame) {}

    private final View wrapped;
    private final SequenceParams params;
    private final SequenceJob job;
    private final Consumer<String> status;

    private DataHandler dataHandler;
    private Future<?> future;
    private volatile boolean running;
    private volatile boolean ready;
    private volatile double progress;
    private boolean evictionReported;

    public ComputedView(View _wrapped, SequenceParams _params, Consumer<String> _status) {
        wrapped = _wrapped;
        params = _params;
        job = _params.job();
        status = _status;
    }

    public View wrapped() {
        return wrapped;
    }

    public SequenceParams params() {
        return params;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isRunning() {
        return running;
    }

    public double progress() {
        return progress;
    }

    @Nullable
    public FourierFilter.Spectrum spectrum() {
        return job.spectrum();
    }

    public void start() {
        if (running)
            return;
        running = true;
        ready = false;
        progress = 0;
        status.accept("Sequence filter: starting");
        long started = System.currentTimeMillis();
        Log.info("Sequence filter started: " + params.describe());
        future = Task.submit("sequence filter", () -> job.run(wrapped, status, p -> progress = p),
                frames -> {
                    install(frames);
                    Log.info(String.format("Sequence filter ready: %d frames in %.1f s, %s", frames.length, (System.currentTimeMillis() - started) / 1000., params.describe()));
                    DisplayController.render(1);
                },
                (ctx, t) -> {
                    running = false;
                    status.accept(null);
                    if (AppThread.isInterrupted(t))
                        return;
                    Log.error(t);
                    Message.err("Sequence filter failed", params.describe() + ": " + t.getMessage());
                });
    }

    /** The job's result becomes what decode() serves. Package-private so a check can install frames without a worker. */
    void install(DecodedImage[] frames) {
        running = false;
        for (int i = 0; i < frames.length; i++)
            if (frames[i] != null)
                ImageBufferCache.put(new ComputedKey(this, i), frames[i]);
        ready = true;
        evictionReported = false;
        status.accept(null);
    }

    /** Cancels a running job and drops the computed frames; the wrapped view is untouched. */
    public void dispose() {
        if (future != null)
            future.cancel(true);
        running = false;
        ready = false;
        ImageBufferCache.invalidateIf(k -> k instanceof ComputedKey ck && ck.view() == this);
    }

    @Override
    public void decode(Position viewpoint, double pixFactor, float factor) {
        int frame = wrapped.getCurrentFrameNumber();
        if (ready) {
            DecodedImage image = ImageBufferCache.get(new ComputedKey(this, frame));
            if (image != null) {
                image.imageBuffer().protectFromExplicitFree();
                ImageData data = new ImageData(image.imageBuffer(), wrapped.getMetaData(wrapped.getFrameTime(frame)), image.region(), viewpoint);
                EventQueue.invokeLater(() -> {
                    if (dataHandler != null)
                        dataHandler.handleData(data);
                    else
                        image.imageBuffer().allowExplicitFree();
                });
                return;
            }
            if (!evictionReported) {
                evictionReported = true;
                status.accept("Sequence filter output evicted from cache: Apply again");
            }
        }
        wrapped.decode(viewpoint, pixFactor, factor);
    }

    @Nullable
    @Override
    public DecodedImage frameImage(int frame) {
        if (ready) {
            DecodedImage image = ImageBufferCache.get(new ComputedKey(this, frame));
            if (image != null)
                return image;
        }
        return wrapped.frameImage(frame);
    }

    @Override
    public void setDataHandler(DataHandler _dataHandler) {
        dataHandler = _dataHandler;
        wrapped.setDataHandler(_dataHandler);
    }

    @Override
    public void abolish() {
        dispose();
        wrapped.abolish();
    }

    @Override
    public void clearCache() {
        wrapped.clearCache();
    }

    @Override
    public boolean isDownloading() {
        return running || wrapped.isDownloading();
    }

    // Everything below is the wrapped view's.

    @Nullable
    @Override
    public APIRequest getAPIRequest() {
        return wrapped.getAPIRequest();
    }

    @Override
    public void setRange(double min, double max) {
        wrapped.setRange(min, max);
    }

    @Override
    public void setFilter(ImageFilter.Type t) {
        wrapped.setFilter(t);
    }

    @Override
    public ImageFilter.Type getFilter() {
        return wrapped.getFilter();
    }

    @Nullable
    @Override
    public String getBaseName() {
        return wrapped.getBaseName();
    }

    @Nullable
    @Override
    public DataUri.Format getFormat() {
        return wrapped.getFormat();
    }

    @Nullable
    @Override
    public int[] getNativeSize() {
        return wrapped.getNativeSize();
    }

    @Nullable
    @Override
    public LUT getDefaultLUT() {
        return wrapped.getDefaultLUT();
    }

    @Override
    public boolean isMultiFrame() {
        return wrapped.isMultiFrame();
    }

    @Override
    public int getCurrentFrameNumber() {
        return wrapped.getCurrentFrameNumber();
    }

    @Override
    public int getMaximumFrameNumber() {
        return wrapped.getMaximumFrameNumber();
    }

    @Override
    public boolean isComplete() {
        return wrapped.isComplete();
    }

    @Nullable
    @Override
    public AtomicBoolean getFrameCompletion(int frame) {
        return wrapped.getFrameCompletion(frame);
    }

    @Override
    public JHVTime getFrameTime(int frame) {
        return wrapped.getFrameTime(frame);
    }

    @Override
    public JHVTime getFirstTime() {
        return wrapped.getFirstTime();
    }

    @Override
    public JHVTime getLastTime() {
        return wrapped.getLastTime();
    }

    @Override
    public boolean setNearestFrame(JHVTime time) {
        return wrapped.setNearestFrame(time);
    }

    @Override
    public JHVTime getNearestTime(JHVTime time) {
        return wrapped.getNearestTime(time);
    }

    @Override
    public JHVTime getLowerTime(JHVTime time) {
        return wrapped.getLowerTime(time);
    }

    @Override
    public JHVTime getHigherTime(JHVTime time) {
        return wrapped.getHigherTime(time);
    }

    @Override
    public MetaData getMetaData(JHVTime time) {
        return wrapped.getMetaData(time);
    }

    @Nonnull
    @Override
    public String getXMLMetaData() {
        return wrapped.getXMLMetaData();
    }

}
