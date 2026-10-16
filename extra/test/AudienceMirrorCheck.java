package org.helioviewer.jhv.opengl;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * The projector mirror in presentation mode: how a frame is sized, how its pixels are turned
 * round, and how frames pass from the GL thread to the painter.
 *
 * <p>The readback needs a GL context and a projector; what can go wrong without one is silent on
 * screen and worth pinning. A swapped red and blue or an unflipped row is invisible on a grey test
 * frame and obvious on the Sun in front of a room. A handoff that lets the GL thread write into
 * the image being painted tears; one that drops the frame skipped while the last was painting
 * leaves the projector showing a view the presenter has already left.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.opengl.AudienceMirrorCheck
 */
public final class AudienceMirrorCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        int[] same = PresentationOutput.mirrorSize(1920, 1080, 1920, 1080);
        expect("a 1920x1080 frame on a 1920x1080 projector is read back whole: " + same[0] + "x" + same[1],
                same[0] == 1920 && same[1] == 1080);
        int[] big = PresentationOutput.mirrorSize(3840, 2160, 1920, 1200);
        expect("a 4K frame is cut to the projector's width, keeping its shape: " + big[0] + "x" + big[1],
                big[0] == 1920 && big[1] == 1080);
        int[] tall = PresentationOutput.mirrorSize(2048, 2048, 1920, 1080);
        expect("a square frame is cut to the projector's height: " + tall[0] + "x" + tall[1],
                tall[0] == 1080 && tall[1] == 1080);
        int[] small = PresentationOutput.mirrorSize(1280, 720, 1920, 1080);
        expect("a frame smaller than the projector is never enlarged", small[0] == 1280 && small[1] == 720);
        int[] none = PresentationOutput.mirrorSize(1920, 1080, 0, 0);
        expect("a window not laid out yet still gets at least one pixel", none[0] >= 1 && none[1] >= 1);

        // Two rows, bottom first as GL reads them: red, green / blue, white-ish.
        ByteBuffer rgba = ByteBuffer.allocateDirect(2 * 2 * 4);
        rgba.put(new byte[]{(byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255}); // GL row 0 (bottom)
        rgba.put(new byte[]{0, 0, (byte) 255, (byte) 255, 1, 2, 3, (byte) 255});          // GL row 1 (top)
        int[] out = new int[4];
        PresentationOutput.rgbaToRgb(rgba, 2, 2, out);
        expect("the top image row is GL's last row: blue first", out[0] == 0x0000FF);
        expect("and its channels stay in order", out[1] == 0x010203);
        expect("the bottom image row is GL's first: red", out[2] == 0xFF0000);
        expect("then green", out[3] == 0x00FF00);
        rgba.position(rgba.limit());
        PresentationOutput.rgbaToRgb(rgba, 2, 2, out);
        expect("the buffer's position does not matter", out[2] == 0xFF0000);

        FrameHandoff handoff = new FrameHandoff();
        expect("nothing to paint before the first frame", handoff.shown() == null);
        BufferedImage first = handoff.claim(4, 3);
        expect("the first frame may be read", first != null && first.getWidth() == 4 && first.getHeight() == 3);
        handoff.publish(first);
        expect("and is what the painter paints", handoff.shown() == first);
        expect("while it waits to be painted, the next readback is skipped", handoff.claim(4, 3) == null);
        expect("and the painter is told a frame was skipped, so it asks for one more", handoff.painted());
        expect("a debt is paid once", !handoff.painted());
        BufferedImage second = handoff.claim(4, 3);
        expect("after painting, the next frame goes into the other image, never the one on screen",
                second != null && second != first);
        handoff.publish(second);
        expect("painted with nothing skipped, no extra frame is asked for", !handoff.painted());
        BufferedImage third = handoff.claim(4, 3);
        expect("and the two images alternate without allocating", third == first);
        BufferedImage resized = handoff.claim(8, 6);
        expect("a new projector size gets a new image of that size",
                resized != null && resized.getWidth() == 8 && resized != handoff.shown());

        // The mirror is an 8-bit SDR copy: a frame pushed into the laptop's EDR headroom reached the
        // projector clipped at white (Gilly, 2026-10-06). So no HDR gain while mirroring.
        // Display's class initialisation reaches SPICE; same bootstrap as AnimateMenuCheck.
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-mirror").toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();
        org.helioviewer.jhv.display.Display.edrCanvas = true;
        org.helioviewer.jhv.display.Display.edrHeadroom = 4;
        float before = org.helioviewer.jhv.display.HdrGain.current(false);
        PresentationOutput.OUTPUT.setActive(true);
        PresentationOutput.OUTPUT.setSink(new PresentationOutput.Sink() {
            public boolean wants() { return false; }
            public int[] pixels() { return new int[]{1, 1}; }
            public FrameHandoff frames() { return new FrameHandoff(); }
            public void framePublished() {}
        });
        expect("presenting to a projector is mirroring", PresentationOutput.OUTPUT.mirroring());
        float during = org.helioviewer.jhv.display.HdrGain.current(false);
        PresentationOutput.OUTPUT.setSink(null);
        float after = org.helioviewer.jhv.display.HdrGain.current(false);
        PresentationOutput.OUTPUT.setActive(false);
        expect("with EDR headroom 4 the gain is above 1 normally: " + before, before > 1);
        expect("but 1 while mirroring: " + during, during == 1);
        expect("and back when the mirror goes: " + after, after == before);

        if (failures > 0) {
            System.out.println("AudienceMirrorCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("AudienceMirrorCheck: all passed");
    }

    private AudienceMirrorCheck() {}

}
