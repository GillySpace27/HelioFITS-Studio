package org.helioviewer.jhv.view.j2k;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.view.j2k.jpip.JPIPCache;

/**
 * One JPEG 2000 image, as a file or as a JPIP session, without Kakadu.
 *
 * <p>The lifecycle is unchanged, because the rest of the viewer depends on it: a source is opened,
 * used under a guard that keeps it alive while a decode is running, and destroyed once when the
 * layer goes. What has changed is what is underneath. There are no native objects to open, close
 * or destroy any more; {@link OpjSource} reads the boxes and the codestream headers, and a frame's
 * codestream is either in the file or rebuilt from the data bins that have arrived.
 *
 * <p>Closing is therefore cheap rather than a release of native memory, and reopening is free. The
 * guard stays because a decode still must not run against a source a layer has abandoned.
 */
abstract class J2KSource {

    private final boolean isJP2;
    private boolean isClosed = true;
    private boolean closing;
    private int users;
    private int maxFrame;
    private boolean resolutionStateInitialized;

    @Nullable
    private OpjSource opj;

    private J2KSource(boolean _isJP2) {
        isJP2 = _isJP2;
    }

    // Source lifecycle

    final void open() throws IOException {
        if (!isClosed)
            return;
        isClosed = false;
        try {
            opj = createSource();
            initResolutionStateOnce();
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    // Temporary close: a JP2 source reopens for the next decode, which now costs a file read.
    final void close() {
        isClosed = true;
    }

    // Terminal cleanup: wait for active users, as a decode may still be reading this.
    void destroy() {
        boolean interrupted = false;
        synchronized (this) {
            closing = true;
            while (users > 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
        close();
        opj = null;
    }

    // Access guards

    synchronized Use use() {
        if (closing)
            throw new CancellationException("J2KSource access cancelled after close");
        users++;
        return new Use(this);
    }

    private synchronized void endUse() {
        users--;
        if (users == 0)
            notifyAll();
    }

    record Use(J2KSource source) implements AutoCloseable {
        @Override
        public void close() {
            source.endUse();
        }
    }

    // Source information

    int maxFrame() {
        return maxFrame;
    }

    /** The image itself, for the decoder. Null only before the first open. */
    @Nullable
    OpjSource source() {
        return opj;
    }

    @Nullable
    ResolutionSet readResolutionSet(int frame) throws IOException {
        OpjSource source = current();
        ResolutionSet set = source.resolutionSet(frame);
        if (set == null && frame == 0)
            throw new IOException("The image's first frame has not arrived");
        return set;
    }

    @Nullable
    LUT getLUT() throws IOException {
        return current().lut();
    }

    void extractMetaData(String[] xmlMetaData) throws IOException {
        OpjSource source = current();
        for (int i = 0; i < xmlMetaData.length; i++)
            xmlMetaData[i] = source.header(i);
    }

    boolean isJP2() {
        return isJP2;
    }

    private OpjSource current() throws IOException {
        OpjSource source = opj;
        if (source == null)
            throw new IOException("The source is not open");
        return source;
    }

    // Progressive completion state

    abstract int getPartialUntil();

    abstract ResolutionSet resolutionSet(int frame);

    abstract boolean isComplete(int level);

    @Nullable
    abstract AtomicBoolean getFrameStatus(int frame, int level);

    // Initialization internals

    private void initResolutionStateOnce() throws IOException {
        if (resolutionStateInitialized)
            return;
        maxFrame = current().frameCount() - 1;
        doInitResolutionState();
        resolutionStateInitialized = true;
    }

    abstract OpjSource createSource() throws IOException;

    abstract void doInitResolutionState() throws IOException;

    private static final AtomicBoolean full = new AtomicBoolean(true);

    static class Local extends J2KSource {

        private final String path;
        private ResolutionSet[] resolutionSet;

        Local(String _path, boolean isJP2) {
            super(isJP2);
            path = _path;
        }

        @Override
        OpjSource createSource() throws IOException {
            return OpjSource.ofFile(path);
        }

        @Override
        void doInitResolutionState() throws IOException {
            resolutionSet = new ResolutionSet[maxFrame() + 1];
            for (int i = 0; i <= maxFrame(); ++i) {
                resolutionSet[i] = readResolutionSet(i);
                if (resolutionSet[i] != null)
                    resolutionSet[i].setComplete(0);
            }
        }

        @Override
        int getPartialUntil() {
            return maxFrame();
        }

        @Override
        ResolutionSet resolutionSet(int frame) {
            return resolutionSet[frame];
        }

        @Override
        boolean isComplete(int level) {
            return true;
        }

        @Nullable
        @Override
        AtomicBoolean getFrameStatus(int frame, int level) {
            return full;
        }

    }

    static class Remote extends J2KSource {

        private final JPIPCache cache = new JPIPCache();
        private ResolutionSet[] resolutionSet;
        private int partialUntil;
        private boolean fullyComplete;

        Remote() {
            super(false);
        }

        JPIPCache cache() {
            return cache;
        }

        @Override
        OpjSource createSource() {
            // Rebuilt from whatever the session holds now: the frame count and the headers come
            // out of the metadata bin, which arrives before anything is decoded.
            return OpjSource.ofCache(cache.bins());
        }

        @Override
        void doInitResolutionState() throws IOException {
            resolutionSet = new ResolutionSet[maxFrame() + 1];
            resolutionSet[0] = readResolutionSet(0);
        }

        @Override
        int getPartialUntil() {
            int i;
            for (i = partialUntil; i <= maxFrame(); i++) {
                if (resolutionSet[i] == null)
                    break;
            }
            partialUntil = Math.max(0, i - 1);
            return partialUntil;
        }

        @Override
        ResolutionSet resolutionSet(int frame) {
            if (resolutionSet[frame] == null) {
                Log.error("resolutionSet[" + frame + "] is null"); // never happened?
                return resolutionSet[0];
            }
            return resolutionSet[frame];
        }

        @Override
        boolean isComplete(int level) {
            if (fullyComplete)
                return true;

            for (int i = 0; i <= maxFrame(); i++) {
                if (resolutionSet[i] == null)
                    return false;
                AtomicBoolean status = resolutionSet[i].getComplete(level);
                if (status == null || !status.get())
                    return false;
            }
            if (level == 0)
                fullyComplete = true;
            return true;
        }

        @Nullable
        @Override
        AtomicBoolean getFrameStatus(int frame, int level) {
            if (fullyComplete)
                return full;
            if (resolutionSet[frame] == null)
                return null;
            return resolutionSet[frame].getComplete(level);
        }

        @SuppressWarnings("try")
        void setFramePartial(int frame) throws IOException {
            if (resolutionSet[frame] == null) {
                try (Use ignored = use()) {
                    resolutionSet[frame] = readResolutionSet(frame);
                }
            }
        }

        void setFrameComplete(int frame, int level) throws IOException {
            setFramePartial(frame);
            if (fullyComplete)
                return;

            if (resolutionSet[frame] != null)
                resolutionSet[frame].setComplete(level);
        }

    }

}
