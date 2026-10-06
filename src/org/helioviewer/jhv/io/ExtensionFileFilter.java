package org.helioviewer.jhv.io;

import java.io.File;
import java.io.FilenameFilter;

public class ExtensionFileFilter {

    /**
     * Image files File > Open and a drop both accept. The reader decides the format from the
     * content (DataUri.detect), so .fit and tile-compressed .fz open like .fits; only the name
     * filter kept them out.
     */
    private static final String[] IMAGE_EXTENSIONS = {"jpg", "jpeg", "png", "fts", "fits", "fit", "fz",
            "fits.gz", "fts.gz", "fit.gz", "jp2", "jpx", "zip"};
    public static final FilenameFilter Image = new Filter(IMAGE_EXTENSIONS);

    /** Whether a file name has one of the image extensions, ignoring case. */
    public static boolean isImage(String name) {
        return ((Filter) Image).matches(name);
    }

    public static final FilenameFilter Model = new Filter(new String[]{"gltf", "glb", "gltf.gz", "glb.gz"});
    public static final FilenameFilter Timeline = new Filter(new String[]{"json", "cdf"});
    public static final FilenameFilter JHV = new Filter(new String[]{"jhv"});

    private record Filter(String[] extensions) implements FilenameFilter {
        @Override
        public boolean accept(File dir, String name) {
            File file = new File(dir, name);
            if (file.isDirectory())
                return true;

            return matches(file.getName());
        }

        boolean matches(String name) {
            String testName = name.toLowerCase(java.util.Locale.ROOT);
            for (String ext : extensions) {
                if (testName.endsWith("." + ext))
                    return true;
            }
            return false;
        }

    }

}
