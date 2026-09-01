package org.helioviewer.jhv.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.FilenameUtils;
import org.apache.tika.Tika;

public class DataUri {

    private static final Tika tika = new Tika();

    /**
     * @param name the name the resource has at its source, not the cached file's
     *
     * <p>The distinction is the whole point. Tika reports a gzipped FITS as gzip, so the extension
     * is what rescues it, and the cached copy of a download is named by hash with no extension at
     * all: testing the cached path meant every remote {@code .fits.gz} was Unknown while the same
     * file opened locally was fine. That is every SUVI frame, which NOAA serves gzipped.
     *
     * <p>The content check behind it covers a server that does not say gz in the name.
     */
    private static Format detect(File file, String name) throws IOException {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".fits.gz") || lower.endsWith(".fts.gz"))
            return Format.Image.FITS;

        Format format = getFormat(tika.detect(file));
        return format == Format.Unknown.UNKNOWN && isGzippedFits(file) ? Format.Image.FITS : format;
    }

    /** Every FITS begins with the SIMPLE keyword, so one decompressed read settles it. */
    private static boolean isGzippedFits(File file) {
        try (InputStream in = new GZIPInputStream(new FileInputStream(file), 512)) {
            return "SIMPLE".equals(new String(in.readNBytes(6), StandardCharsets.US_ASCII));
        } catch (IOException e) {
            return false; // not gzip, or truncated: either way not a FITS we can read
        }
    }

    private static final Map<String, Format> map = Map.of(
            "application/x-jpp-stream", Format.Image.JPIP,
            "image/jp2", Format.Image.JP2,
            "image/jpx", Format.Image.JPX,
            "application/fits", Format.Image.FITS,
            "image/png", Format.Image.PNG,
            "image/jpeg", Format.Image.JPEG,
            "application/zip", Format.Image.ZIP,
            "application/x-netcdf", Format.Timeline.CDF,
            "text/csv", Format.Timeline.CSV
    );

    private static Format getFormat(String spec) {
        Format f = map.get(spec);
        return f == null ? Format.Unknown.UNKNOWN : f;
    }

    public interface Format {
        enum Unknown implements Format {UNKNOWN}

        enum Image implements Format {JPIP, JP2, JPX, FITS, PNG, JPEG, ZIP}

        enum Timeline implements Format {CDF, CSV}
    }

    private final URI sourceUri;
    private final URI uri;
    private final Format format;
    private final File file;
    private final String baseName;

    DataUri(URI originalUri, URI cachedUri, File _file) throws IOException {
        sourceUri = originalUri;
        uri = cachedUri;
        file = _file;
        baseName = FilenameUtils.getName(originalUri.toString());
        format = file == null ? Format.Image.JPIP : detect(file, baseName); // JPIP not backed by file
    }

    public URI sourceUri() {
        return sourceUri;
    }

    public URI uri() {
        return uri;
    }

    public Format format() {
        return format;
    }

    public File file() {
        return file;
    }

    public String baseName() {
        return baseName;
    }

    @Override
    public String toString() {
        return uri.toString();
    }

}
