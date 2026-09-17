package org.helioviewer.jhv.metadata;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;

import nom.tam.fits.Fits;
import nom.tam.fits.Header;

import org.helioviewer.jhv.io.FitsHeaderContainer;

/**
 * What rotation the application ends up drawing a given LASCO frame at.
 *
 * <p>Not a check: a utility, so it does not end in "Check" and run-checks.sh leaves it alone. It reads
 * the primary header of each URL given, runs it through the same FitsMetaData the layer builds, and
 * prints the header's own CROTA beside the angle the image is actually drawn at, which is what the eye
 * sees. Give it a frame from inside the pointing gap and one from outside it; the two angles agreeing
 * is the whole claim the borrowing makes.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.metadata.LascoRealFrameDump &lt;url&gt;...
 */
public final class LascoRealFrameDump {

    public static void main(String[] args) throws Exception {
        // Building any FitsMetaData initialises Sun, which asks SPICE for Earth's position; as LascoPointingCheck.
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-lasco-dump").toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        for (String arg : args) {
            try (InputStream in = new URL(arg).openStream(); Fits fits = new Fits(in)) {
                Header h = fits.readHDU().getHeader();
                FitsHeaderContainer raw = new FitsHeaderContainer(h);
                var m = new FitsMetaData(raw, URI.create(arg)).getWcsHeader().imageToPlane;
                double drawn = Math.toDegrees(Math.atan2(m.m10, m.m00));
                System.out.printf("%-14s %s %s  header CROTA1=%-10s CRPIX=(%s, %s)  drawn at %+8.3f deg%n",
                        arg.substring(arg.lastIndexOf('/') + 1),
                        h.getStringValue("DETECTOR"), h.getStringValue("DATE-OBS") + ' ' + h.getStringValue("TIME-OBS"),
                        h.getStringValue("CROTA1"), h.getStringValue("CRPIX1"), h.getStringValue("CRPIX2"), drawn);
            }
        }
    }
}
