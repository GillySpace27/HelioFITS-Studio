package org.helioviewer.jhv.view.j2k.opj;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Data bins from a real JPIP server rebuild into a codestream a decoder accepts.
 *
 * <p>The fixture is what Helioviewer's server actually sent for one LASCO C2 frame asked for at
 * 128 pixels: the main header, an empty tile header, and the first six precinct bins. That is a
 * partly delivered frame, which is the normal state of a frame in a movie, and the case where a
 * rebuild is easiest to get wrong: write the packets in the wrong order, or forget that the
 * tile-part length has to be filled in, and the decoder reports success on a wrong picture.
 *
 * <p>Fidelity was measured separately against the same frame's .jp2 file: with every bin delivered
 * the rebuilt codestream decoded pixel for pixel identically to the file. That needs the network,
 * so it cannot live here; what lives here is the structure, which is what silently breaks.
 *
 * <p>Run: java -cp "bin:extra/test-classes" org.helioviewer.jhv.view.j2k.opj.JpipRebuildCheck
 */
public final class JpipRebuildCheck {

    private static final Path FIXTURE = Path.of("extra", "test", "j2k", "lasco-c2-jpip-bins.bin");

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static DataBinCache load() throws IOException {
        DataBinCache cache = new DataBinCache();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(FIXTURE))) {
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

    private static int u16(byte[] d, int at) {
        return ((d[at] & 0xFF) << 8) | (d[at + 1] & 0xFF);
    }

    public static void main(String[] args) throws Exception {
        DataBinCache cache = load();
        expect("the fixture holds a main header", cache.isComplete(Codestream.MAIN_HEADER_BIN, 0, 0));
        expect("and six precinct bins", cache.ids(Codestream.PRECINCT_BIN, 0).size() == 6);

        Codestream.Header header = Codestream.parseHeader(cache.bytes(Codestream.MAIN_HEADER_BIN, 0, 0));
        expect("the header reads as the frame it is: " + header.width() + "x" + header.height(),
                header.width() == 1024 && header.height() == 1024);
        expect("one tile, one component", header.tilesAcross() * header.tilesDown() == 1 && header.components() == 1);
        expect("RPCL, eight layers, eight decompositions",
                header.progression() == 2 && header.layers() == 8 && header.decompositions() == 8);

        byte[] stream = Codestream.build(cache, 0);
        expect("the bins rebuild into a codestream", stream != null);
        if (stream == null) {
            System.out.println("JpipRebuildCheck: 1 FAILURE(S)");
            System.exit(1);
        }

        expect("which starts at the start of a codestream", u16(stream, 0) == 0xFF4F && u16(stream, 2) == 0xFF51);
        expect("and ends where a codestream ends", u16(stream, stream.length - 2) == 0xFFD9);

        int sot = header.width() == 0 ? -1 : indexOfMarker(stream, 0xFF90);
        expect("a tile-part starts after the main header", sot > 0);
        int psot = ((stream[sot + 6] & 0xFF) << 24) | ((stream[sot + 7] & 0xFF) << 16)
                | ((stream[sot + 8] & 0xFF) << 8) | (stream[sot + 9] & 0xFF);
        // The server cannot know this length: it is the one number the rebuild has to supply.
        expect("and its length covers the tile-part but not the end marker, " + psot,
                psot == stream.length - sot - 2);

        byte[] again = Codestream.build(cache, 0);
        expect("rebuilding twice gives the same bytes", Arrays.equals(stream, again));

        String version;
        try {
            version = OpenJpeg.version();
        } catch (Throwable t) {
            System.out.println("  skip  OpenJPEG is not installed here, so the decode is not exercised");
            System.out.println(failures == 0 ? "JpipRebuildCheck: PASS" : "JpipRebuildCheck: " + failures + " FAILURE(S)");
            System.exit(failures == 0 ? 0 : 1);
            return;
        }

        OpenJpeg.Decoded image = OpenJpeg.decode(stream, false, 0, 2);
        expect("OpenJPEG " + version + " decodes it at the frame's own size",
                image.width() == 1024 && image.height() == 1024);

        long sum = 0;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int sample : image.samples()) {
            sum += sample;
            min = Math.min(min, sample);
            max = Math.max(max, sample);
        }
        // A frame delivered in part is blurred, not blank: a rebuild that mis-ordered the packets
        // decodes to noise or to a flat field, and both show up here.
        expect("into a picture rather than a flat field, range " + min + ".." + max, max - min > 32);
        expect("with a sane mean, " + sum / image.samples().length, sum / image.samples().length > 0);

        System.out.println(failures == 0 ? "JpipRebuildCheck: PASS" : "JpipRebuildCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static int indexOfMarker(byte[] stream, int marker) {
        for (int i = 0; i + 1 < stream.length; i++)
            if (u16(stream, i) == marker)
                return i;
        return -1;
    }

    private JpipRebuildCheck() {}

}
