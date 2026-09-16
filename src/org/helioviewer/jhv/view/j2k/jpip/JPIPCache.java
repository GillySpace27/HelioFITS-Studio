package org.helioviewer.jhv.view.j2k.jpip;

import javax.annotation.Nullable;

import org.helioviewer.jhv.view.j2k.opj.DataBinCache;

import kdu_jni.KduException;
import kdu_jni.Kdu_cache;
import kdu_jni.Kdu_global;

public class JPIPCache extends Kdu_cache {

    /**
     * The same bins, kept a second time in Java, while the replacement for Kakadu is proved.
     *
     * <p>Off unless -Djhv.opj.verify is set, because it doubles what a JPIP session holds. With
     * it on, every frame the application decodes is also rebuilt and decoded by the new path and
     * the two are compared, which is a far wider test than any fixture: whatever the archives
     * actually serve, in whatever order a real session asks for it.
     */
    public static final boolean VERIFY = System.getProperty("jhv.opj.verify") != null;

    @Nullable
    private final DataBinCache shadow = VERIFY ? new DataBinCache() : null;

    @Nullable
    public DataBinCache shadow() {
        return shadow;
    }

    boolean isDataBinCompleted(int klassID, long streamID, long binID) throws KduException {
        boolean[] complete = new boolean[1];
        Get_databin_length(klassID, streamID, binID, complete);
        return complete[0];
    }

    JPIPStream scan(int frame) throws KduException {
        int flags = Kdu_global.KDU_CACHE_SCAN_START | Kdu_global.KDU_CACHE_SCAN_FIX_CODESTREAM;
        int[] klassID = new int[1];
        long[] codestreamID = {frame};
        long[] binID = new long[1];
        int[] binLen = new int[1];
        boolean[] complete = new boolean[1];

        JPIPStream stream = new JPIPStream();
        while (Scan_databins(flags, klassID, codestreamID, binID, binLen, complete, null, 0)) {
            flags &= ~Kdu_global.KDU_CACHE_SCAN_START;
            if (klassID[0] == Constants.KDU.META_DATABIN)
                continue;

            byte[] data = new byte[binLen[0]];
            if (!Scan_databins(flags | Kdu_global.KDU_CACHE_SCAN_NO_ADVANCE, klassID, codestreamID, binID, binLen, complete, data, binLen[0]))
                break;

            stream.databins.add(new JPIPStream.Databin(klassID[0], binID[0], complete[0], data));
        }
        return stream;
    }

    void put(int frame, JPIPSegment seg) throws KduException {
        Add_to_databin(seg.klassID, frame, seg.binID, seg.data, seg.offset, seg.length, seg.isFinal, true, false);
        if (shadow != null && seg.data != null)
            shadow.put(seg.klassID, frame, seg.binID, seg.offset, seg.data, seg.isFinal);
    }

    public void put(int frame, JPIPStream stream) throws KduException {
        for (JPIPStream.Databin databin : stream.databins)
            Add_to_databin(databin.klassID(), frame, databin.binID(), databin.data(), 0,
                    databin.data().length, databin.complete(), true, false);
    }

}
