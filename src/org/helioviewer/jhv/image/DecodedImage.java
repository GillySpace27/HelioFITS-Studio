package org.helioviewer.jhv.image;

import org.helioviewer.jhv.metadata.Region;

public record DecodedImage(ImageBuffer imageBuffer, Region region) {

    /** For decode threads: counts the frame's levels here so the EDT, which shows it, only reads the cached number. */
    public static DecodedImage measured(ImageBuffer imageBuffer, Region region) {
        imageBuffer.measuredLevels();
        return new DecodedImage(imageBuffer, region);
    }

}
