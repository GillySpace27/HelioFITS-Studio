package org.helioviewer.jhv.opengl;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

/**
 * Hands frames from the GL thread to the audience window's painter, one in flight at a time.
 *
 * <p>Two images alternate: the GL thread writes into the one that is not on screen, so a frame is
 * never overwritten while it is being painted, and nothing is allocated per frame. While the last
 * frame is still waiting to be painted the GL thread skips its readback and a frame is owed; the
 * painter pays it by asking for one more frame once it has painted, so the projector always ends
 * on the frame the presenter's window ends on, even when a drag stops between two readbacks.
 *
 * <p>Pure apart from the images, so the check can drive both threads' sides in order.
 */
public final class FrameHandoff {

    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean owed = new AtomicBoolean();
    @Nullable private BufferedImage a; // GL thread only
    @Nullable private BufferedImage b; // GL thread only
    @Nullable private volatile BufferedImage shown;

    /**
     * GL thread: the image to read the next frame into, or null while the last one is unpainted,
     * which leaves a frame owed.
     */
    @Nullable
    public BufferedImage claim(int width, int height) {
        // Owed first, then the check: a painter that clears pending between the two sees the debt.
        owed.set(true);
        if (pending.get())
            return null;
        owed.set(false);
        boolean useA = shown == null || shown != a; // whichever is not on screen
        BufferedImage image = useA ? a : b;
        if (image == null || image.getWidth() != width || image.getHeight() != height) {
            image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            if (useA)
                a = image;
            else
                b = image;
        }
        return image;
    }

    /** GL thread: the claimed image now holds a frame. */
    public void publish(BufferedImage image) {
        shown = image;
        pending.set(true);
    }

    /** Painter: the frame to paint, or null before the first one. */
    @Nullable
    public BufferedImage shown() {
        return shown;
    }

    /**
     * Painter, after painting: the GL thread may read the next frame.
     *
     * @return whether a frame was skipped meanwhile, in which case the caller asks for a redraw
     */
    public boolean painted() {
        pending.set(false);
        return owed.getAndSet(false);
    }

    /** Forget the frame on screen and any debt, on leaving presentation mode. */
    public void clear() {
        shown = null;
        pending.set(false);
        owed.set(false);
    }

}
