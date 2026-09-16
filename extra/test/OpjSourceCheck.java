package org.helioviewer.jhv.view.j2k;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.helioviewer.jhv.view.j2k.opj.DataBinCache;

/**
 * What the viewer needs to know about an image, answered without Kakadu.
 *
 * <p>Four questions, for a file on disk and for a JPIP session: how many frames, how large each is
 * at each resolution, what its FITS header says, and whether it carries its own colour table. Get
 * the resolution ladder wrong and the viewer asks for the wrong level and draws a frame at the
 * wrong scale; lose the header and it draws in the wrong place. Neither announces itself.
 *
 * <p>Run: java -cp "bin:extra/test-classes" org.helioviewer.jhv.view.j2k.OpjSourceCheck
 */
public final class OpjSourceCheck {

    private static final Path FILE = Path.of("extra", "test", "j2k", "lasco-c2-256.jp2");
    private static final Path BINS = Path.of("extra", "test", "j2k", "lasco-c2-jpip-bins.bin");

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static DataBinCache bins() throws IOException {
        DataBinCache cache = new DataBinCache();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(BINS))) {
            while (in.available() > 0) {
                int klass = in.readInt();
                long codestream = in.readLong();
                long id = in.readLong();
                long offset = in.readLong();
                byte[] data = new byte[in.readInt()];
                in.readFully(data);
                cache.put(klass, codestream, id, offset, data, in.readBoolean());
            }
        }
        return cache;
    }

    public static void main(String[] args) throws Exception {
        OpjSource file = OpjSource.ofFile(FILE.toString());
        expect("a file holds the frames its boxes do, " + file.frameCount(), file.frameCount() == 1);
        expect("and its codestream comes out", file.codestream(0) != null);

        ResolutionSet set = file.resolutionSet(0);
        expect("with a resolution ladder", set != null);
        if (set != null) {
            ResolutionSet.Level full = set.getLevel(0);
            expect("whose top is the frame itself, " + full.width() + "x" + full.height(),
                    full.width() == 256 && full.height() == 256);
            ResolutionSet.Level half = set.getLevel(1);
            expect("and whose next rung is half of it, " + half.width() + "x" + half.height(),
                    half.width() == 128 && half.height() == 128);
            expect("each rung saying how much it was shrunk by", Math.abs(half.factorX() - 2) < 1e-9);
        }
        expect("the fixture carries no palette, and none is invented", file.lut() == null);

        OpjSource jpip = OpjSource.ofCache(bins());
        expect("a JPIP session counts its frames before they arrive, " + jpip.frameCount(), jpip.frameCount() == 1);
        expect("and rebuilds a frame from what has arrived", jpip.hasFrame(0));

        String header = jpip.header(0);
        expect("the frame's FITS header is there", header != null && header.contains("<fits>"));
        expect("and says which instrument this is", header != null && header.contains("LASCO"));

        ResolutionSet remote = jpip.resolutionSet(0);
        expect("the remote frame has a ladder too", remote != null);
        if (remote != null) {
            ResolutionSet.Level full = remote.getLevel(0);
            expect("topping out at the frame's own size, " + full.width() + "x" + full.height(),
                    full.width() == 1024 && full.height() == 1024);
            // Eight decompositions, so nine rungs: the level the viewer picks for a small window.
            ResolutionSet.Level small = remote.getNextLevel(200, 200);
            expect("with a rung for a small window, " + small.width() + "x" + small.height(),
                    small.width() <= 256 && small.width() >= 64);
        }

        // A frame nobody has sent anything for must say so rather than invent an empty one.
        expect("a frame with nothing delivered has no codestream", !jpip.hasFrame(7));

        System.out.println(failures == 0 ? "OpjSourceCheck: PASS" : "OpjSourceCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private OpjSourceCheck() {}

}
