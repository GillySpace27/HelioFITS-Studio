package org.helioviewer.jhv.view.j2k;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.helioviewer.jhv.view.j2k.opj.DataBinCache;

/**
 * A JPIP movie whose FITS headers sit in their own metadata bins reads with one header per frame.
 *
 * <p>The ROB server (jpip.swhv.oma.be) sends an SDO movie this way, as captured on 2026-10-24:
 * metadata bin 0 holds, per frame, a codestream placeholder (flags 4) and a second placeholder
 * (flags 1) whose original box is an asoc of LBox about 5 kB, and that asoc's body (an nlst box and
 * the xml box) is metadata bin n. Studio read only bin 0, so it counted two frames per frame and
 * found no header for any of them, and the layer failed with a null ResolutionSet. The bins here
 * are synthetic, built with that layout.
 *
 * <p>Run: java -cp "bin:extra/test-classes" org.helioviewer.jhv.view.j2k.JpipPlaceholderHeadersCheck
 */
public final class JpipPlaceholderHeadersCheck {

    private static final int METADATA_BIN = 4;
    private static final int FRAMES = 3;

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static byte[] box(String type, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int length = 8 + content.length;
        out.write(length >>> 24);
        out.write(length >>> 16);
        out.write(length >>> 8);
        out.write(length);
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static byte[] be(long value, int bytes) {
        byte[] out = new byte[bytes];
        for (int i = bytes - 1; i >= 0; i--, value >>>= 8)
            out[i] = (byte) value;
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts)
            out.writeBytes(p);
        return out.toByteArray();
    }

    // A placeholder for a box whose body is metadata bin origId, with the original box header.
    private static byte[] phld(int flags, long origId, long origLength, String origType) {
        return box("phld", concat(be(flags, 4), be(origId, 8), be(origLength, 4), origType.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private static String fits(int frame) {
        return "<meta>\n<fits>\n<DATE-OBS>2026-10-23T15:3" + frame + ":00</DATE-OBS>\n</fits>\n</meta>";
    }

    public static void main(String[] args) {
        DataBinCache cache = new DataBinCache();
        ByteArrayOutputStream bin0 = new ByteArrayOutputStream();
        bin0.writeBytes(box("jP  ", be(0x0D0A870A, 4)));
        bin0.writeBytes(box("ftyp", "jpx \0\0\0\0jpx jp2 ".getBytes(StandardCharsets.ISO_8859_1)));
        for (int i = 0; i < FRAMES; i++) {
            byte[] body = concat(box("nlst", concat(be(0x01000000L | i, 4), be(0x02000001, 4))),
                    box("xml ", (fits(i) + "\0").getBytes(StandardCharsets.UTF_8)));
            bin0.writeBytes(phld(4, 0, 0, "ftbl"));
            bin0.writeBytes(phld(1, i + 1, 8 + body.length, "asoc"));
            cache.put(METADATA_BIN, 0, i + 1, 0, body, true);
        }
        cache.put(METADATA_BIN, 0, 0, 0, bin0.toByteArray(), true);

        OpjSource source = OpjSource.ofCache(cache);
        expect("the movie has " + FRAMES + " frames, not one per placeholder: " + source.frameCount(), source.frameCount() == FRAMES);
        for (int i = 0; i < FRAMES; i++) {
            String header = source.header(i);
            expect("frame " + i + " has its own FITS header from metadata bin " + (i + 1),
                    header != null && header.contains("2026-10-23T15:3" + i + ":00") && header.endsWith("</meta>"));
        }

        // A header bin that has only partly arrived is not read as a header yet, and is once it has.
        DataBinCache partial = new DataBinCache();
        byte[] body = concat(box("nlst", be(0x01000000L, 4)), box("xml ", (fits(0) + "\0").getBytes(StandardCharsets.UTF_8)));
        partial.put(METADATA_BIN, 0, 0, 0, concat(box("jP  ", be(0x0D0A870A, 4)), phld(4, 0, 0, "ftbl"),
                phld(1, 1, 8 + body.length, "asoc")), true);
        partial.put(METADATA_BIN, 0, 1, 0, java.util.Arrays.copyOf(body, body.length / 2), false);
        OpjSource growing = OpjSource.ofCache(partial);
        expect("a half-arrived header bin gives no header", growing.header(0) == null);
        partial.put(METADATA_BIN, 0, 1, body.length / 2, java.util.Arrays.copyOfRange(body, body.length / 2, body.length), true);
        expect("and the header appears once the rest of the bin arrives", growing.header(0) != null);

        System.out.println(failures == 0 ? "JpipPlaceholderHeadersCheck: PASS" : "JpipPlaceholderHeadersCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private JpipPlaceholderHeadersCheck() {}

}
