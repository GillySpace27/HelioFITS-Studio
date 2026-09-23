package org.helioviewer.jhv.view;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.time.JHVTime;
import org.helioviewer.jhv.time.TimeMap;

public class ManyView implements View {

    private record FrameInfo(View view, JHVTime timeView, int idxView) {}

    /**
     * Published whole and replaced whole, never edited in place.
     *
     * <p>Frames arrive while the movie is being drawn and scrubbed, and a TimeMap is a TreeMap
     * with an index array beside it: a reader crossing a write can see a half-linked tree, or an
     * index that does not match the keys it is indexing. Rebuilding a fresh map and swapping the
     * reference costs a copy of a few hundred entries per batch of arrivals and leaves every
     * reader holding something internally consistent. Each method below snapshots it once.
     */
    private volatile TimeMap<FrameInfo> frameMap;
    private volatile boolean hasFITS;
    private volatile @Nullable ClipSet clipSet;
    private final @Nullable View firstView;
    @Nullable
    private View.DataHandler dataHandler; // remembered, so frames arriving later are wired up too
    private volatile int targetFrame;

    /**
     * The median stops moving once this many frames have been seen.
     *
     * <p>The display range is the median over the frames, so while they are still arriving it
     * changes with every batch, and everything already on screen is re-stretched under the
     * viewer. A handful of frames settles the median well enough; letting it follow all forty-five
     * is a movie that visibly shifts brightness for as long as the download lasts.
     */
    private static final int CLIP_SAMPLE = 8;
    private int clipFrames; // how many frames the current clipSet was computed over

    public ManyView(List<View> views) throws IOException {
        if (views.isEmpty())
            throw new IOException("Empty list of views");
        firstView = views.getFirst();

        TimeMap<FrameInfo> map = new TimeMap<>();
        hasFITS = views.stream().anyMatch(View::hasFITS);
        views.forEach(v -> putDates(map, v));
        map.buildIndex();
        frameMap = map;
        recomputeClipSet(map);
        // unused J2KViews should be abolished by their reaper
    }

    /**
     * Take frames that arrived after this view was published.
     *
     * <p>A movie used to appear all at once: every frame was built before the wrapper existed, so
     * a forty-five frame PUNCH load was minutes of one still image with a counter under it. The
     * wrapper is published on the first frame now and grows, which is what puts each frame into
     * the transport and the coverage timeline as it lands.
     *
     * <p>Call from one thread only (the loader marshals to the EDT). Readers need no lock.
     */
    public void addFrames(List<View> views) {
        if (views.isEmpty())
            return;

        TimeMap<FrameInfo> map = new TimeMap<>();
        map.putAll(frameMap);
        // The playhead is an index into a map ordered by time, so a frame that lands out of order
        // renumbers every frame after it. Remember where the viewer is by its moment, not its
        // number, or a download reshuffles the picture under them.
        JHVTime at = frameMap.key(targetFrame);
        for (View v : views) {
            hasFITS |= v.hasFITS();
            putDates(map, v);
            if (dataHandler != null)
                v.setDataHandler(dataHandler);
        }
        map.buildIndex();
        frameMap = map;
        targetFrame = map.nearestIndex(at);
        if (clipFrames < CLIP_SAMPLE)
            recomputeClipSet(map);
    }

    private void recomputeClipSet(TimeMap<FrameInfo> map) {
        List<ClipSet> clipSets = new ArrayList<>();
        for (FrameInfo frameInfo : map.values()) {
            clipSets.add(frameInfo.view.getClipSet());
        }
        clipFrames = clipSets.size();
        clipSet = ClipSet.median(clipSets);
    }

    private static void putDates(TimeMap<FrameInfo> map, View v) {
        if (v instanceof ManyView manyView) {
            map.putAll(manyView.frameMap);
            return;
        }
        int m = v.getMaximumFrameNumber();
        for (int i = 0; i <= m; i++) {
            JHVTime t = v.getFrameTime(i);
            map.put(t, new FrameInfo(v, t, i));
        }
    }

    @Override
    public void abolish() {
        frameMap.values().forEach(frameInfo -> frameInfo.view.abolish());
    }

    @Override
    public void clearCache() {
        frameMap.values().forEach(frameInfo -> frameInfo.view.clearCache());
    }

    @Override
    public void setFilter(ImageFilter.Type t) {
        frameMap.values().forEach(frameInfo -> frameInfo.view.setFilter(t));
    }

    @Override
    public void setRange(double min, double max) {
        frameMap.values().forEach(frameInfo -> frameInfo.view.setRange(min, max));
    }

    @Override
    public ImageFilter.Type getFilter() {
        return frameMap.indexedValue(0).view.getFilter();
    }

    @Override
    public void decode(Position viewpoint, double pixFactor, float factor, @Nullable ClipSet.Range clipRange) {
        // indexedValue clamps through key(), so a targetFrame left over from a shorter map is
        // pulled into range rather than reaching past the end of the index.
        frameMap.indexedValue(targetFrame).view.decode(viewpoint, pixFactor, factor, clipRange);
    }

    @Nullable
    @Override
    public LUT getDefaultLUT() {
        return frameMap.indexedValue(0).view.getDefaultLUT();
    }

    @Nullable
    @Override
    public ClipSet getClipSet() {
        return clipSet;
    }

    @Override
    public boolean hasFITS() {
        return hasFITS;
    }

    @Nullable
    @Override
    public org.helioviewer.jhv.io.DataUri.Format getFormat() {
        // The frames of one layer come from one query, so the first speaks for all of them.
        return firstView == null ? null : firstView.getFormat();
    }

    @Override
    public boolean isMultiFrame() {
        return frameMap.maxIndex() > 0;
    }

    @Override
    public int getCurrentFrameNumber() {
        return targetFrame;
    }

    @Override
    public int getMaximumFrameNumber() {
        return frameMap.maxIndex();
    }

    @Override
    public void setDataHandler(View.DataHandler _dataHandler) {
        dataHandler = _dataHandler; // kept, so frames added later are wired up the same way
        frameMap.values().forEach(frameInfo -> frameInfo.view.setDataHandler(_dataHandler));
    }

    @Nullable
    @Override
    public AtomicBoolean getFrameCompletion(int frame) {
        FrameInfo frameInfo = frameMap.indexedValue(frame);
        return frameInfo.view.getFrameCompletion(frameInfo.idxView);
    }

    @Nullable
    @Override
    public org.helioviewer.jhv.image.DecodedImage frameImage(int frame) {
        FrameInfo frameInfo = frameMap.indexedValue(frame);
        return frameInfo.view.frameImage(frameInfo.idxView);
    }

    @Nullable
    @Override
    public String frameKey(int frame) {
        FrameInfo frameInfo = frameMap.indexedValue(frame);
        return frameInfo.view.frameKey(frameInfo.idxView);
    }

    @Override
    public JHVTime getFrameTime(int frame) {
        return frameMap.key(frame);
    }

    @Override
    public JHVTime getFirstTime() {
        return frameMap.firstKey();
    }

    @Override
    public JHVTime getLastTime() {
        return frameMap.lastKey();
    }

    @Override
    public boolean setNearestFrame(JHVTime time) {
        TimeMap<FrameInfo> map = frameMap;
        int frame = map.nearestIndex(time);
        FrameInfo frameInfo = map.indexedValue(frame);
        if (frameInfo.view.setNearestFrame(frameInfo.timeView)) {
            targetFrame = frame;
            return true;
        }
        return false;
    }

    @Override
    public JHVTime getNearestTime(JHVTime time) {
        return frameMap.nearestKey(time);
    }

    @Override
    public JHVTime getLowerTime(JHVTime time) {
        return frameMap.lowerKey(time);
    }

    @Override
    public JHVTime getHigherTime(JHVTime time) {
        return frameMap.higherKey(time);
    }

    @Override
    public MetaData getMetaData(JHVTime time) {
        FrameInfo frameInfo = frameMap.nearestValue(time);
        return frameInfo.view.getMetaData(frameInfo.timeView);
    }

    @Nonnull
    @Override
    public String getXMLMetaData() {
        return frameMap.indexedValue(targetFrame).view.getXMLMetaData();
    }

}
