package org.helioviewer.jhv.view.j2k.jpip;

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

    /** The bins themselves, for the source and for the codestream rebuild. */
    public DataBinCache bins() {
        return bins;
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
    }

    /**
     * A snapshot of one frame, for the disk cache.
     *
     * <p>Metadata bins are left out, as they were when Kakadu kept this: they describe the file
     * rather than a frame, and a live session is sent them again before anything is decoded.
     */
    JPIPStream scan(int frame) {
        JPIPStream stream = new JPIPStream();
        for (DataBinCache.BinId id : bins.contents()) {
            if (id.codestream() != frame || id.klass() == Constants.Bin.META_DATABIN)
                continue;
            byte[] data = bins.bytes(id.klass(), id.codestream(), id.id());
            stream.databins.add(new JPIPStream.Databin(id.klass(), id.id(),
                    bins.isComplete(id.klass(), id.codestream(), id.id()), data == null ? new byte[0] : data));
        }
        return stream;
    }

}
