package org.helioviewer.jhv.gui;

import org.helioviewer.jhv.opengl.PresentationOutput;
import org.helioviewer.jhv.opengl.PreviewClock;

/**
 * The presenter window's live preview: how often it reads the projector's frame back, that it
 * never loses the last frame of a burst, and how the frame is sized and fitted.
 *
 * <p>The readback itself needs a GL context and a projector; what can go wrong without one is the
 * arithmetic, and two of its failures are silent on screen: a throttle that lets every frame
 * through (a GPU stall per frame, felt as a stutter on the projector, with nothing to point at)
 * and one that drops the last frame of a drag (the presenter's copy then shows a view the room
 * no longer sees).
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.gui.PresenterPreviewCheck
 */
public final class PresenterPreviewCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        long interval = 1000 / PresentationOutput.PREVIEW_HZ;
        expect("the preview refreshes a few times a second, not at the frame rate (" + PresentationOutput.PREVIEW_HZ + " Hz)",
                PresentationOutput.PREVIEW_HZ >= 1 && PresentationOutput.PREVIEW_HZ <= 10);

        PreviewClock clock = new PreviewClock(interval);
        expect("the first frame is always read", clock.take(0));
        expect("nothing is owed after a read", clock.owedIn(0) == -1);
        expect("a frame inside the interval is skipped", !clock.take(interval / 2));
        expect("and leaves one owed, due when the interval ends", clock.owedIn(interval / 2) == interval - interval / 2);
        expect("an owed frame past its time is due now, not in the past", clock.owedIn(interval * 3) == 0);
        expect("the frame at the end of the interval is read", clock.take(interval));
        expect("and pays the debt", clock.owedIn(interval) == -1);

        // A ten-second drag at 60 frames a second.
        PreviewClock drag = new PreviewClock(interval);
        int taken = 0;
        long t = 0;
        for (int i = 0; i < 600; i++) {
            t = i * 1000L / 60;
            if (drag.take(t))
                taken++;
        }
        long cap = 10 * PresentationOutput.PREVIEW_HZ + 1;
        expect("a 60 fps drag is read back at most " + cap + " times in ten seconds: " + taken, taken <= cap);
        expect("and not starved either (at least " + (cap - 3) + ")", taken >= cap - 3);
        expect("when the drag stops between reads, a frame is owed, so the preview ends where the projector does",
                drag.owedIn(t) >= 0);

        int[] wide = PreviewClock.fit(3840, 2160, PresentationOutput.PREVIEW_LONG_SIDE);
        expect("a 3840x2160 frame previews at 480x270: " + wide[0] + "x" + wide[1], wide[0] == 480 && wide[1] == 270);
        int[] square = PreviewClock.fit(2048, 2048, 480);
        expect("a square frame stays square", square[0] == 480 && square[1] == 480);
        int[] small = PreviewClock.fit(200, 100, 480);
        expect("a frame smaller than the preview is never enlarged", small[0] == 200 && small[1] == 100);
        int[] sliver = PreviewClock.fit(1, 100000, 480);
        expect("and never shrinks to nothing", sliver[0] >= 1 && sliver[1] == 480);

        int[] box = PresenterPreview.fitInto(480, 270, 360, 240);
        expect("the preview fills the window's width: " + box[2] + "x" + box[3], box[2] == 360);
        expect("keeps the frame's shape", Math.abs(box[2] / (double) box[3] - 480 / 270.) < 0.01);
        expect("and is centred", box[0] == 0 && box[1] == (240 - box[3]) / 2);
        int[] tall = PresenterPreview.fitInto(480, 480, 360, 200);
        expect("a square frame in a wide box is height-bound and centred across",
                tall[3] == 200 && tall[2] == 200 && tall[0] == 80);

        if (failures > 0) {
            System.out.println("PresenterPreviewCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("PresenterPreviewCheck: all passed");
    }

}
