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
     * @param sourceUri the URI the resource has at its source, not the cached file's path
     *
     * <p>The distinction is the whole point. Tika reports a gzipped FITS as gzip, so the name is
     * what rescues it, and the cached copy of a download is named by hash with no extension at
     * all: testing the cached path meant every remote {@code .fits.gz} was Unknown while the same
     * file opened locally was fine. That is every SUVI frame, which NOAA serves gzipped.
     *
     * <p>The content check behind it covers a server that does not say gz in the name.
     */
    private static Format detect(URI sourceUri, File file) throws IOException {
        String sourcePath = sourceUri.getPath();
        if (sourcePath != null) {
            sourcePath = sourcePath.toLowerCase(Locale.US);
            boolean gzip = sourcePath.endsWith(".gz");
            if (gzip)
                sourcePath = sourcePath.substring(0, sourcePath.length() - 3);
            if (gzip && (sourcePath.endsWith(".fits") || sourcePath.endsWith(".fts")))
                return Format.FITS;
            if (sourcePath.endsWith(".gltf") || sourcePath.endsWith(".glb"))
                return Format.GLTF;
        }

        Format format = getFormat(tika.detect(file));
        return format == Format.UNKNOWN && isGzippedFits(file) ? Format.FITS : format;
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
            "application/x-jpp-stream", Format.JPIP,
            "image/jp2", Format.JP2,
            "image/jpx", Format.JPX,
            "application/fits", Format.FITS,
            "image/png", Format.PNG,
            "image/jpeg", Format.JPEG,
            "application/zip", Format.ZIP,
            "application/x-netcdf", Format.CDF,
            "text/csv", Format.CSV
    );

    private static Format getFormat(String spec) {
        Format f = map.get(spec);
        return f == null ? Format.UNKNOWN : f;
    }

    public enum Format {UNKNOWN, JPIP, JP2, JPX, FITS, PNG, JPEG, ZIP, GLTF, CDF, CSV}

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
        format = file == null ? Format.JPIP : detect(originalUri, file); // JPIP not backed by file
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
