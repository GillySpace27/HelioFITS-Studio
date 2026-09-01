package org.helioviewer.jhv.io;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.annotation.Nullable;

/**
 * A FITS header read without its image, so a frame can be judged before it is worth downloading.
 *
 * <p>Asked for over HTTP Range: closing an ordinary response early does not avoid the transfer,
 * and a probe that did that cost two thirds of the download it was meant to replace. Gzipped
 * files are decompressed as far as the prefix goes, which is all the header needs and always ends
 * in a truncation the caller should not hear about.
 */
final class FitsHeaders {

    /** Room for around 180 cards, which every archive here fits inside. */
    private static final int HEADER_BYTES = 14400;
    /** Compressed, so the prefix has to be bigger to be sure of covering the same cards. */
    private static final int GZIP_PREFIX_BYTES = 262144;

    /** Keyword to value, or null when nothing could be read. Values keep no quotes or comments. */
    @Nullable
    static Map<String, String> read(URI uri, boolean gzipped) {
        try (NetClient nc = NetClient.prefix(uri, gzipped ? GZIP_PREFIX_BYTES : HEADER_BYTES)) {
            if (!nc.isSuccessful())
                return null;
            byte[] raw = nc.getSource().readByteArray(Math.min(
                    gzipped ? GZIP_PREFIX_BYTES : HEADER_BYTES, Math.max(1, nc.getContentLength())));
            return parse(gzipped || isGzip(raw) ? gunzipPrefix(raw) : raw);
        } catch (Exception e) {
            return null; // an unreadable probe is not evidence about the frame
        }
    }

    private static boolean isGzip(byte[] raw) {
        return raw.length > 1 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B;
    }

    /** Decompress what the prefix allows; the stream ending mid-stride is expected, not an error. */
    private static byte[] gunzipPrefix(byte[] raw) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(raw), 8192)) {
            return in.readNBytes(HEADER_BYTES);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static Map<String, String> parse(byte[] head) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i + 80 <= head.length; i += 80) {
            String card = new String(head, i, 80, StandardCharsets.ISO_8859_1);
            if (card.startsWith("END "))
                break;
            int eq = card.indexOf('=');
            if (eq < 0 || eq > 9)
                continue; // a comment or a continuation, not a keyword
            String value = card.substring(eq + 1);
            int slash = value.indexOf('/');
            if (slash >= 0)
                value = value.substring(0, slash);
            out.put(card.substring(0, eq).trim(), value.trim().replace("'", "").trim());
        }
        return out;
    }

    /** The value as a number, or NaN when absent or unparseable. */
    static double number(Map<String, String> header, String key) {
        String v = header.get(key);
        if (v == null)
            return Double.NaN;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private FitsHeaders() {
    }

}
