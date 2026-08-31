package org.helioviewer.jhv.metadata;

import javax.annotation.Nonnull;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.wcs.WcsHeader;

public interface MetaData {

    @Nonnull
    String getDisplayName();

    @Nonnull
    Region getPhysicalRegion();

    double getUnitPerPixelY();

    double getUnitPerArcsec();

    /**
     * Arcseconds per pixel as the header gave it, or 0 when this metadata has no plate scale.
     *
     * <p>Separate from {@link #getUnitPerPixelY} over {@link #getUnitPerArcsec}, which looks like
     * the same quantity and is not always: for a surface map that ratio is a longitude step
     * divided by radians-per-arcsec, and for a pixel-based product both sides are placeholders.
     * Both cases produce a plausible number that means nothing, so the ones that have no plate
     * scale say 0 rather than leaving a caller to divide and believe the result.
     */
    default double getArcsecPerPixel() {
        return 0;
    }

    float getResponseFactor();

    @Nonnull
    WcsHeader getWcsHeader();

    float getSector0();

    float getSector1();

    float getInnerRadius();

    float getOuterRadius();

    float getCutOffValue();

    float getCutOffX();

    float getCutOffY();

    @Nonnull
    Position getViewpoint();

    @Nonnull
    Region roiToRegion(int roiX, int roiY, int roiWidth, int roiHeight, double factorX, double factorY);

    @Nonnull
    Vec2 getSunShift();

    boolean getCalculateDepth();

    @Nonnull
    DetectorMask getDetectorMask();

    boolean isIndexedSurfaceMap();

}
