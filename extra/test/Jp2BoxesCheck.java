package org.helioviewer.jhv.view.j2k.opj;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The box reader finds what Kakadu used to find: the FITS header and the codestreams.
 *
 * <p>Two fixtures, because the same boxes reach us two ways. A .jp2 on disk carries its codestream
 * in a jp2c box. Over JPIP the boxes arrive as a metadata bin instead, with a placeholder where
 * the codestream would be, and that is where a Helioviewer frame's FITS header lives: everything
 * the viewer knows about pointing, exposure and wavelength comes out of that XML, so losing it
 * silently would leave images that draw in the wrong place rather than images that fail to draw.
 *
 * <p>Run: java -cp "bin:extra/test-classes" org.helioviewer.jhv.view.j2k.opj.Jp2BoxesCheck
 */
public final class Jp2BoxesCheck {

    private static final Path FILE = Path.of("extra", "test", "j2k", "lasco-c2-256.jp2");
    private static final Path BINS = Path.of("extra", "test", "j2k", "lasco-c2-jpip-bins.bin");

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static byte[] metadataBin() throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(BINS))) {
            while (in.available() > 0) {
                int klass = in.readInt();
                in.readLong(); // codestream
                in.readLong(); // identifier
                in.readLong(); // offset
                byte[] data = new byte[in.readInt()];
                in.readFully(data);
                in.readBoolean();
                if (klass == 4) // the metadata bin, as this fork's JPIP parser numbers classes
                    return data;
            }
        }
        return new byte[0];
    }

    public static void main(String[] args) throws Exception {
        byte[] file = Files.readAllBytes(FILE);
        expect("a .jp2 reads as boxed", Jp2Boxes.isBoxed(file));

        List<Jp2Boxes.Box> boxes = Jp2Boxes.walk(file);
        List<String> types = boxes.stream().map(Jp2Boxes.Box::type).toList();
        expect("with the boxes a file starts with, " + types, types.contains("jP  ") && types.contains("ftyp")
                && types.contains("jp2h") && types.contains("jp2c"));

        List<byte[]> codestreams = Jp2Boxes.codestreams(file);
        expect("one codestream comes out", codestreams.size() == 1);
        byte[] codestream = codestreams.getFirst();
        expect("which starts at the start of a codestream",
                ((codestream[0] & 0xFF) << 8 | (codestream[1] & 0xFF)) == 0xFF4F);

        Codestream.Header header = Codestream.parseHeader(codestream);
        expect("and describes the fixture, " + (header == null ? "null" : header.width() + "x" + header.height()),
                header != null && header.width() == 256 && header.height() == 256);

        // The JPIP side: the same boxes, with the codestream replaced by a placeholder.
        byte[] meta = metadataBin();
        expect("the captured metadata bin holds boxes", !Jp2Boxes.walk(meta).isEmpty());
        expect("and no codestream, because JPIP sends those as data bins", Jp2Boxes.codestreams(meta).isEmpty());

        String xml = Jp2Boxes.xml(meta);
        expect("the frame's FITS header comes out of the XML box", xml != null && xml.contains("<fits>"));
        // These archives write the document with a trailing null, and an XML parser refuses
        // anything after the root element closes: every frame arrived without metadata until this.
        expect("and ends where the document does, with nothing after it",
                xml != null && xml.stripTrailing().endsWith("</meta>") && xml.indexOf(0) < 0);
        expect("and says what the frame is: " + summarise(xml),
                xml != null && xml.contains("<NAXIS1>1024</NAXIS1>") && xml.contains("LASCO"));

        // A truncated box must stop the walk rather than read past the end of what arrived.
        byte[] half = new byte[meta.length / 2];
        System.arraycopy(meta, 0, half, 0, half.length);
        expect("half a metadata bin still walks without reading past its end", !Jp2Boxes.walk(half).isEmpty());

        System.out.println(failures == 0 ? "Jp2BoxesCheck: PASS" : "Jp2BoxesCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String summarise(String xml) {
        if (xml == null)
            return "no xml";
        int at = xml.indexOf("<DETECTOR>");
        return at < 0 ? xml.length() + " characters" : xml.substring(at, Math.min(at + 30, xml.length())).replace('\n', ' ');
    }

    private Jp2BoxesCheck() {}

}
