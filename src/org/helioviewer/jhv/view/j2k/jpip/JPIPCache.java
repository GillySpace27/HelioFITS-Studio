package org.helioviewer.jhv.view.j2k.jpip;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

import org.helioviewer.jhv.view.j2k.opj.DataBinCache;

/**
 * The data bins of a JPIP session, held in Java.
 *
 * <p>This was Kakadu's cache, which both accumulated the bins and rebuilt codestreams from them.
 * The accumulating is {@link DataBinCache} and the rebuilding is
 * {@link org.helioviewer.jhv.view.j2k.opj.Codestream}; what stays here is the shape the rest of
 * the JPIP code already speaks: segments in, and a snapshot that the disk cache can keep and a
 * later session can replay.
 */
public class JPIPCache {

    private final DataBinCache bins = new DataBinCache();

    // Frames the disk cache holds a copy of, which are the only ones the budget may drop: a
    // dropped frame comes back through J2KReader.restoreFrame, and a frame nobody saved would not.
    private final Set<Integer> restorable = ConcurrentHashMap.newKeySet();
    private volatile int playhead;
    private volatile IntConsumer onEvicted = frame -> {};

    /** The bins themselves, for the source and for the codestream rebuild. */
    public DataBinCache bins() {
        return bins;
    }

    public void markRestorable(int frame) {
        restorable.add(frame);
    }

    boolean isRestorable(int frame) {
        return restorable.contains(frame);
    }

    /** The frame the viewer is on; the budget drops what is farthest from it. */
    public void setPlayhead(int frame) {
        playhead = frame;
    }

    int playhead() {
        return playhead;
    }

    /** Told after a frame's bins are dropped, so its complete flags can be reset and the reader restores it next time. */
    public void onEvicted(IntConsumer listener) {
        onEvicted = listener;
    }

    /** Drop one saved frame's bins (not the metadata) and say how many bytes that freed. */
    long evictFrame(int frame) {
        if (!restorable.remove(frame))
            return 0;
        long freed = bins.evict(frame, Constants.Bin.META_DATABIN);
        onEvicted.accept(frame);
        return freed;
    }

    boolean isDataBinCompleted(int klassID, long streamID, long binID) {
        return bins.isComplete(klassID, streamID, binID);
    }

    void put(int frame, JPIPSegment seg) {
        if (seg.data.length > 0 || seg.isFinal)
            bins.put(seg.klassID, frame, seg.binID, seg.offset, seg.data, seg.isFinal);
    }

    /** Replay a snapshot the disk cache kept from an earlier session. */
    public void put(int frame, JPIPStream stream) {
        for (JPIPStream.Databin databin : stream.databins)
            bins.put(databin.klassID(), frame, databin.binID(), 0, databin.data(), databin.complete());
        restorable.add(frame);
    }

    /**
     * A snapshot of one frame, for the disk cache.
     *
     * <p>Metadata bins are left out, as they were when Kakadu kept this: they describe the file
     * rather than a frame, and a live session is sent them again before anything is decoded.
     */
    JPIPStream scan(int frame) {
        JPIPStream stream = new JPIPStream();
        for (DataBinCache.BinId id : bins.contents(frame)) {
            if (id.klass() == Constants.Bin.META_DATABIN)
                continue;
            byte[] data = bins.bytes(id.klass(), id.codestream(), id.id());
            stream.databins.add(new JPIPStream.Databin(id.klass(), id.id(),
                    bins.isComplete(id.klass(), id.codestream(), id.id()), data == null ? new byte[0] : data));
        }
        return stream;
    }

}
