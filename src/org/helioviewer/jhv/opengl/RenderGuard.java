package org.helioviewer.jhv.opengl;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.helioviewer.jhv.app.Log;

/**
 * Contains a failure in one piece of the scene so it cannot take the whole frame with it.
 *
 * <p>Written after a null vertex buffer in the recording-area outline, a dashed rectangle,
 * turned the entire viewer black. The exception escaped through {@code GLRenderer.display()},
 * the render loop died, and the only symptom was an empty canvas. "One overlay is missing" is a
 * far better failure than "the viewer does not work", and it is diagnosable in seconds instead
 * of by reading a stack trace.
 *
 * <p>Two things this deliberately does <em>not</em> do. It does not swallow quietly: every
 * distinct failure is logged, so a silently absent layer is still traceable. And it does not
 * report on every frame, which at sixty frames a second would bury the log in seconds; the first
 * failure of each kind is logged and the rest are counted.
 */
public final class RenderGuard {

    private static final Set<String> reported = ConcurrentHashMap.newKeySet();

    /**
     * Run a piece of rendering, absorbing any failure.
     *
     * @param what a stable label for this piece; used to log each distinct failure once
     */
    public static void run(String what, Runnable body) {
        // The matrix stacks are only two and three deep, so a throw between a push and its pop
        // would exhaust them within a frame or two and break rendering everywhere -- turning a
        // contained failure back into a global one. Unwind to the depth we came in at.
        int proj = Transform.projDepth();
        int view = Transform.viewDepth();
        try {
            body.run();
        } catch (Throwable t) {
            Transform.unwindTo(proj, view);
            if (reported.add(what)) {
                Log.error("Skipping " + what + ": it failed to render and has been left out of the scene");
                // errorStack formats the trace into the message. Log.error(msg, throwable) hands
                // the throwable to the logger, and this formatter drops it -- which would leave a
                // report naming the casualty with nothing to diagnose it from.
                Log.errorStack(t);
            }
        }
    }

    /**
     * Forget what has already been reported, so a fresh attempt logs again. Called when the
     * scene is rebuilt (a new session, a projection change), since the same failure after a
     * change of circumstances is news rather than repetition.
     */
    public static void reset() {
        reported.clear();
    }

    private RenderGuard() {}

}
