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
import java.nio.ShortBuffer;

import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageBufferCache;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.image.fourier.ComputedCache;
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

    /** filter is the per-frame filter applied on top; None is the job's own output. */
    public record ComputedKey(ComputedView view, int frame, ImageFilter.Type filter) {}

    private final View wrapped;
    private final SequenceParams params;
    private final SequenceJob job;
    private final Consumer<String> status;

    private DataHandler dataHandler;
    private Future<?> future;
    private volatile boolean running;
    private volatile boolean ready;
    private volatile double progress;
    private volatile boolean fromCache;
    @Nullable
    private volatile FourierFilter.Spectrum cachedSpectrum; // a hit's spectrum; the job never ran

    /**
     * The job's output, owned here rather than left in ImageBufferCache.
     *
     * <p>The cache is a fixed 8 GiB shared by every layer, and it already holds the source frames
     * this was computed from. A 245-frame PUNCH mosaic movie is 245 x 4096 x 4096 x 2 B = 8.2 GB
     * of output on its own, so putting it in there evicted it while the job was still writing it:
     * the filter finished, reported success, and had nothing left to show. Nothing about that is
     * transient, so "Apply again" could never fix it.
     *
     * <p>These are off-heap buffers with a Cleaner registered, so dropping this array is what
     * frees them. The cache still holds the per-frame-filtered variants, which are derived and
     * cost one pass to rebuild when they are evicted.
     */
    @Nullable
    private DecodedImage[] computed;

    /**
     * One frame from the live preview, shown in place of the computed one until the next real run.
     *
     * <p>Dragging a band in the spectrum dialog cannot re-run the filter over the movie, so it
     * re-runs it over one frame on a coarse grid and puts the answer here. It is deliberately not
     * in the frame array and not in the cache: it is an approximation of one frame, and the moment
     * a real run lands or the dialog closes it has to be gone.
     */
    @Nullable
    private volatile DecodedImage previewImage;
    private volatile int previewFrame = -1;

    /** Show this frame in place of the computed one. The per-frame filter is applied here, so it is like for like. */
    public void setPreview(int frame, @Nullable DecodedImage image) {
        ImageFilter.Type filter = wrapped.getFilter();
        previewImage = image != null && filter != ImageFilter.Type.None
                ? filtered(image, filter, wrapped.getMetaData(wrapped.getFrameTime(frame))) : image;
        previewFrame = image == null ? -1 : frame;
        DisplayController.render(1);
    }

    public void clearPreview() {
        setPreview(-1, null);
    }

    /** Frames whose per-frame filter pass is in flight; see decode() for why this is bounded. */
    private final java.util.Set<Integer> filtering = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int MAX_FILTERING = 2;

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
        return cachedSpectrum != null ? cachedSpectrum : job.spectrum();
    }

    public void start() {
        if (running)
            return;
        running = true;
        ready = false;
        progress = 0;
        status.accept("Fourier filter: starting");
        long started = System.currentTimeMillis();
        Log.info("Fourier filter started: " + params.describe());
        // The progress goes to the layer's status line rather than into a widget: the button is a
        // glyph now, with no room to write a number in, and the status line is already the place
        // this layer says what it is busy with.
        fromCache = false;
        future = Task.submit("sequence filter", () -> {
                    // The same filter over the same frames has been run before: map its files
                    // instead of computing again. See ComputedCache for what the key covers.
                    ComputedCache.Hit hit = ComputedCache.load(wrapped, params);
                    if (hit != null) {
                        fromCache = true;
                        return hit;
                    }
                    DecodedImage[] fresh = job.run(wrapped, status, p -> {
                        progress = p;
                        status.accept(String.format("Fourier filter %.0f%%", 100 * p));
                    });
                    status.accept("Fourier filter: caching");
                    return ComputedCache.store(wrapped, params, fresh, job.spectrum());
                },
                hit -> {
                    DecodedImage[] frames = hit.frames();
                    cachedSpectrum = hit.spectrum();
                    install(frames);
                    Log.info(String.format("Fourier filter ready: %d frames in %.1f s%s, %s", frames.length, (System.currentTimeMillis() - started) / 1000.,
                            fromCache ? " from the cache" : "", params.describe()));
                    DisplayController.render(1);
                },
                (ctx, t) -> {
                    running = false;
                    status.accept(null);
                    if (AppThread.isInterrupted(t))
                        return;
                    Log.error(t);
                    Message.err("Fourier filter failed", params.describe() + ": " + t.getMessage());
                });
    }

    /** The job's result becomes what decode() serves. Package-private so a check can install frames without a worker. */
    void install(DecodedImage[] frames) {
        running = false;
        previewImage = null; // the real thing has landed
        previewFrame = -1;
        computed = frames;
        ready = true;
        status.accept(null);
    }

    /** Cancels a running job and drops the computed frames; the wrapped view is untouched. */
    public void dispose() {
        if (future != null)
            future.cancel(true);
        running = false;
        ready = false;
        computed = null; // the Cleaner on each buffer reclaims the native memory
        previewImage = null;
        previewFrame = -1;
        ImageBufferCache.invalidateIf(k -> k instanceof ComputedKey ck && ck.view() == this);
    }

    @Override
    public void decode(Position viewpoint, double pixFactor, float factor) {
        int frame = wrapped.getCurrentFrameNumber();
        DecodedImage live = previewImage;
        if (live != null && previewFrame == frame) {
            publish(live, frame, viewpoint);
            return;
        }
        DecodedImage[] frames = computed;
        if (ready && frames != null && frame < frames.length && frames[frame] != null) {
            ImageFilter.Type filter = wrapped.getFilter();
            DecodedImage image = filter == ImageFilter.Type.None
                    ? frames[frame] : ImageBufferCache.get(new ComputedKey(this, frame, filter));
            if (image == null) {
                // The per-frame filter on top of the computed frame, the way URIView applies it to a
                // decoded one: off the EDT, the plain computed frame shown meanwhile.
                //
                // Bounded, and one per frame. A filter pass over a 4096 x 4096 frame allocates some
                // 300 MB of heap for its working arrays, and this used to submit a fresh task on
                // every decode that missed the cache. Playing a 245-frame movie with RHEF on top,
                // whose filtered variants the 8 GiB cache cannot hold beside the sources, missed on
                // every frame, and a few seconds of playback had dozens of those passes in flight
                // at once: java.lang.OutOfMemoryError, Java heap space, on 2026-09-05. A frame that
                // cannot be filtered right now is shown plain and asked for again next time round.
                DecodedImage plain = frames[frame];
                if (filtering.add(frame)) {
                    if (filtering.size() > MAX_FILTERING) {
                        filtering.remove(frame);
                    } else {
                        MetaData meta = wrapped.getMetaData(wrapped.getFrameTime(frame));
                        Task.submit("sequence filter " + filter, () -> filtered(plain, filter, meta),
                                result -> {
                                    filtering.remove(frame);
                                    if (wrapped.getFilter() == filter) { // not changed in flight
                                        ImageBufferCache.put(new ComputedKey(this, frame, filter), result);
                                        DisplayController.render(1);
                                    }
                                },
                                (ctx, t) -> {
                                    filtering.remove(frame);
                                    Log.error(t);
                                });
                    }
                }
                image = plain;
            }
            publish(image, frame, viewpoint);
            return;
        }
        wrapped.decode(viewpoint, pixFactor, factor);
    }

    private void publish(DecodedImage image, int frame, Position viewpoint) {
        image.imageBuffer().protectFromExplicitFree();
        ImageData data = new ImageData(image.imageBuffer(), wrapped.getMetaData(wrapped.getFrameTime(frame)), image.region(), viewpoint);
        EventQueue.invokeLater(() -> {
            if (dataHandler != null)
                dataHandler.handleData(data);
            else
                image.imageBuffer().allowExplicitFree();
        });
    }

    // A computed frame through a per-frame filter: the same ImageFilter the decoders use.
    private static DecodedImage filtered(DecodedImage plain, ImageFilter.Type filter, MetaData meta) {
        ImageBuffer src = plain.imageBuffer();
        if (src.format != ImageBuffer.Format.Gray16F)
            return plain;
        short[] data = new short[src.width * src.height];
        ((ShortBuffer) src.buffer).get(0, data);
        ImageBuffer out = ImageBuffer.fromShorts(src.width, src.height, ImageBuffer.Format.Gray16F, data, ImageFilter.of(filter, plain.region(), meta));
        out.setPhysicalScale(src.physicalScale());
        return new DecodedImage(out, plain.region());
    }

    /** The job's own output (no per-frame filter), so a second computed view can build on it. */
    @Nullable
    @Override
    public DecodedImage frameImage(int frame) {
        DecodedImage[] frames = computed;
        if (ready && frames != null && frame < frames.length && frames[frame] != null)
            return frames[frame];
        return wrapped.frameImage(frame);
    }

    /** The source frame's identity plus this filter, so a filter on top of this one is keyed apart from one on the source. */
    @Nullable
    @Override
    public String frameKey(int frame) {
        String inner = wrapped.frameKey(frame);
        return inner == null ? null : inner + '|' + params.toJson();
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
