package org.helioviewer.jhv.io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * Format detection has to survive the cache, which is where it broke.
 *
 * <p>A downloaded file is stored under a hash with no extension, so a rule written against the
 * file's own path never fires for a download even though it works perfectly on the same file
 * opened locally. Every SUVI frame is a gzipped FITS served over HTTP, and every one of them was
 * "Unknown image type" while the local copy loaded fine. The name at the source and the name in
 * the cache are different questions, and only the first one carries the extension.
 *
 * <p>Run: java -cp bin:extra/test-classes:lib/* org.helioviewer.jhv.io.DataUriFormatCheck
 */
public final class DataUriFormatCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        File cached = gzipped(fitsHeader());          // hash-named, as the cache stores it
        File plain = uncompressed(fitsHeader());
        File notFits = gzipped("this is not a FITS".getBytes(StandardCharsets.US_ASCII));

        check("gzipped FITS named .fits.gz at source",
                format("https://example.org/OR_SUVI-L1b-Fe195.fits.gz", cached), DataUri.Format.Image.FITS);
        check("gzipped FITS named .fts.gz at source",
                format("https://example.org/frame.fts.gz", cached), DataUri.Format.Image.FITS);
        // The source name says nothing, so only looking inside can answer.
        check("gzipped FITS with no telltale name",
                format("https://example.org/download?id=7", cached), DataUri.Format.Image.FITS);
        check("plain FITS", format("https://example.org/frame.fts", plain), DataUri.Format.Image.FITS);
        check("gzip that is not FITS", format("https://example.org/notes.gz", notFits) == DataUri.Format.Image.FITS, false);

        System.out.println(failures == 0 ? "DataUriFormatCheck: PASS" : "DataUriFormatCheck: FAIL");
        if (failures != 0)
            System.exit(1);
    }

    /** The cached file is deliberately hash-named: that is what the real cache does. */
    private static DataUri.Format format(String sourceUrl, File cachedFile) throws Exception {
        return new DataUri(new URI(sourceUrl), cachedFile.toURI(), cachedFile).format();
    }

    /** A minimal primary header: SIMPLE, BITPIX, NAXIS, END, padded to one 2880-byte block. */
    private static byte[] fitsHeader() {
        StringBuilder sb = new StringBuilder();
        for (String card : new String[]{
                "SIMPLE  =                    T",
                "BITPIX  =                   16",
                "NAXIS   =                    0",
                "END"})
            sb.append(String.format("%-80s", card));
        while (sb.length() < 2880)
            sb.append(' ');
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static File gzipped(byte[] content) throws Exception {
        File f = File.createTempFile("a1b2c3d4e5f6", ""); // no extension, like the cache
        f.deleteOnExit();
        try (OutputStream out = new GZIPOutputStream(new FileOutputStream(f))) {
            out.write(content);
        }
        return f;
    }

    private static File uncompressed(byte[] content) throws Exception {
        File f = File.createTempFile("f6e5d4c3b2a1", "");
        f.deleteOnExit();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(content);
        }
        return f;
    }

    private static void check(String what, Object got, Object want) {
        boolean ok = got.equals(want);
        if (!ok)
            failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + ": got " + got + ", want " + want);
    }

}
