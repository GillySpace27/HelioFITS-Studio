package org.helioviewer.jhv.view.j2k.opj;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * The box structure a JPEG 2000 file is wrapped in, read in Java.
 *
 * <p>Kakadu read these on our behalf: the XML box that carries the FITS header of a Helioviewer
 * image, and the codestream boxes that a JPX movie holds one of per frame. Both are needed without
 * it, and they arrive two ways: as a file on disk, and over JPIP as a metadata bin that holds the
 * same boxes with a placeholder standing in for each codestream.
 *
 * <p>Boxes are length, type, content, where a length of 1 means a 64-bit length follows the type
 * and a length of 0 means the box runs to the end. Superboxes hold boxes; the two that matter here
 * are jp2h, which describes the image, and asoc, which JPX uses to tie metadata to a frame.
 */
public final class Jp2Boxes {

    private static final Pattern BARE_AMPERSAND = Pattern.compile("&(?!(?:amp|lt|gt|quot|apos|#[0-9]+|#x[0-9a-fA-F]+);)");

    /** One box: its four-character type, where its content starts, and how long that content is. */
    public record Box(String type, int contentAt, int contentLength) {}

    private static final int MIN_BOX = 8;

    private static int u32(byte[] d, int at) {
        return ((d[at] & 0xFF) << 24) | ((d[at + 1] & 0xFF) << 16) | ((d[at + 2] & 0xFF) << 8) | (d[at + 3] & 0xFF);
    }

    private static long u64(byte[] d, int at) {
        return ((long) u32(d, at) << 32) | (u32(d, at + 4) & 0xFFFFFFFFL);
    }

    /** The boxes directly inside a range, not descending into superboxes. */
    public static List<Box> walk(byte[] data, int from, int to) {
        List<Box> boxes = new ArrayList<>();
        int at = from;
        while (at + MIN_BOX <= to) {
            long length = u32(data, at) & 0xFFFFFFFFL;
            String type = new String(data, at + 4, 4, StandardCharsets.ISO_8859_1);
            int header = MIN_BOX;
            if (length == 1) {
                if (at + 16 > to)
                    break;
                length = u64(data, at + 8);
                header = 16;
            } else if (length == 0) {
                length = to - at; // the last box runs to the end
            }
            if (length < header || at + length > to)
                break; // a truncated box, which over JPIP simply means the rest has not arrived

            boxes.add(new Box(type, at + header, (int) (length - header)));
            at += (int) length;
        }
        return boxes;
    }

    public static List<Box> walk(byte[] data) {
        return walk(data, 0, data.length);
    }

    /**
     * The first XML box, which for a Helioviewer image is the FITS header of the frame.
     *
     * @return the XML text, or null when this holds none
     */
    @Nullable
    public static String xml(byte[] data) {
        return xml(data, 0, data.length, 0);
    }

    @Nullable
    private static String xml(byte[] data, int from, int to, int depth) {
        if (depth > 4) // superboxes nest, but not that far in anything these archives serve
            return null;
        for (Box box : walk(data, from, to)) {
            if ("xml ".equals(box.type()))
                return text(data, box.contentAt(), box.contentLength());
            if ("asoc".equals(box.type()) || "jp2h".equals(box.type())) {
                String nested = xml(data, box.contentAt(), box.contentAt() + box.contentLength(), depth + 1);
                if (nested != null)
                    return nested;
            }
        }
        return null;
    }

    /** Every XML box in document order, which for a JPX movie is one FITS header per frame. */
    public static List<String> xmls(byte[] data) {
        return xmls(data, null);
    }

    /**
     * Every XML box in document order, following placeholders into the metadata bins they name.
     *
     * <p>A JPIP server may send a box as a placeholder in metadata bin 0 and its body as a bin of
     * its own: the ROB server does this with the asoc box that holds each frame's FITS header. A
     * placeholder whose body has not fully arrived gives a null entry, so that the headers after it
     * keep their frame numbers.
     *
     * @param metadataBin the bytes of a metadata bin by its identifier, or null for a file
     */
    public static List<String> xmls(byte[] data, @Nullable LongFunction<byte[]> metadataBin) {
        List<String> out = new ArrayList<>();
        collectXml(data, 0, data.length, 0, metadataBin, out);
        return out;
    }

    private static void collectXml(byte[] data, int from, int to, int depth, @Nullable LongFunction<byte[]> metadataBin, List<String> out) {
        if (depth > 4)
            return;
        for (Box box : walk(data, from, to)) {
            if ("xml ".equals(box.type()))
                out.add(text(data, box.contentAt(), box.contentLength()));
            else if ("asoc".equals(box.type()) || "jp2h".equals(box.type()))
                collectXml(data, box.contentAt(), box.contentAt() + box.contentLength(), depth + 1, metadataBin, out);
            else if ("phld".equals(box.type()) && metadataBin != null)
                followPlaceholder(data, box, depth, metadataBin, out);
        }
    }

    // A phld box: Flags (4), OrigID (8), then the original box's header (8, or 16 with an XLBox).
    // Flag 1 says the original box's body is the metadata bin OrigID.
    private static void followPlaceholder(byte[] data, Box box, int depth, LongFunction<byte[]> metadataBin, List<String> out) {
        int at = box.contentAt();
        if (box.contentLength() < 20 || (u32(data, at) & 1) == 0)
            return;
        long origId = u64(data, at + 4);
        long length = u32(data, at + 12) & 0xFFFFFFFFL;
        String type = new String(data, at + 16, 4, StandardCharsets.ISO_8859_1);
        boolean xml = "xml ".equals(type);
        if (origId == 0 || !(xml || "asoc".equals(type) || "jp2h".equals(type)))
            return;
        long header = 8;
        if (length == 1) {
            if (box.contentLength() < 28)
                return;
            length = u64(data, at + 20);
            header = 16;
        }

        byte[] body = metadataBin.apply(origId);
        long need = length == 0 ? 0 : length - header; // a length of 0 runs to the end of the bin
        if (body == null || need < 0 || body.length < need || body.length == 0) {
            out.add(null); // not here yet; keep the frame numbers of the headers after it
            return;
        }
        int end = need == 0 ? body.length : (int) need;
        if (xml)
            out.add(text(body, 0, end));
        else
            collectXml(body, 0, end, depth + 1, metadataBin, out);
    }

    /**
     * The text of an XML box.
     *
     * <p>These archives write the document with a trailing null, C fashion, and an XML parser
     * rejects anything after the root element closes. Stopping at the null is what Kakadu did for
     * us, and without it every Helioviewer frame arrives with no metadata at all.
     *
     * <p>Some headers also carry a bare ampersand (in HISTORY or COMMENT text), which an XML parser
     * rejects just as firmly. Escape those and leave real entities alone, as upstream JHelioviewer
     * does (its commit 658173e8); the Kakadu reader this replaced escaped every ampersand.
     */
    private static String text(byte[] data, int at, int length) {
        int end = at + length;
        for (int i = at; i < end; i++)
            if (data[i] == 0) {
                end = i;
                break;
            }
        return BARE_AMPERSAND.matcher(new String(data, at, end - at, StandardCharsets.UTF_8).trim()).replaceAll("&amp;");
    }

    /**
     * Codestream placeholders (flag 4), one per codestream JPIP has not sent inline, which is how a
     * movie's frames are counted. Placeholders for metadata boxes are not frames.
     */
    public static int placeholders(byte[] data) {
        int count = 0;
        for (Box box : walk(data))
            if ("phld".equals(box.type()) && box.contentLength() >= 4 && (u32(data, box.contentAt()) & 4) != 0)
                count++;
        return count;
    }

    /**
     * The codestreams in a file, in the order they appear, which for a JPX movie is frame order.
     *
     * <p>Over JPIP these are placeholders rather than content, so this returns nothing and the
     * codestream is rebuilt from data bins instead.
     */
    public static List<byte[]> codestreams(byte[] data) {
        List<byte[]> out = new ArrayList<>();
        for (Box box : walk(data))
            if ("jp2c".equals(box.type())) {
                byte[] codestream = new byte[box.contentLength()];
                System.arraycopy(data, box.contentAt(), codestream, 0, codestream.length);
                out.add(codestream);
            }
        return out;
    }

    /** Whether this looks like a boxed JPEG 2000 file rather than a bare codestream. */
    public static boolean isBoxed(byte[] data) {
        return data.length >= 12 && "jP  ".equals(new String(data, 4, 4, StandardCharsets.ISO_8859_1));
    }

    private Jp2Boxes() {}

}
