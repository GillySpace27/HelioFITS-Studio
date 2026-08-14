package org.helioviewer.jhv.metadata;

import java.util.Optional;

import javax.annotation.Nullable;

import org.helioviewer.jhv.math.Mat2;
import org.helioviewer.jhv.wcs.WcsHeader;

final class WcsInterpreter {

    private static final double ARCSEC_PER_RAD = 180. * 3600. / Math.PI;

    record Result(
            WcsHeader.Projection projection,
            float[] pv2,
            double internalCrvalX,
            double internalCrvalY,
            Mat2 imageToPlane,
            double unitPerPixelX,
            double unitPerPixelY,
            double arcsecPerPixelX,
            double arcsecPerPixelY) {}

    private record PixelAxes(
            double arcsecX,
            double arcsecY,
            double arcsecPerPixelX,
            double arcsecPerPixelY,
            Mat2 pc) {}

    private record LinearTransform(
            Mat2 imageToPlane,
            double unitPerPixelX,
            double unitPerPixelY) {}

    private record WcsInput(
            double cdelt1,
            double cdelt2,
            double crval1,
            double crval2,
            double pv2_1,
            MatrixKeywords pc,
            MatrixKeywords cd) {}

    private record MatrixKeywords(boolean present, Mat2 matrix) {}

    static Result read(MetaDataContainer m) {
        return read(m, null);
    }

    static Result read(MetaDataContainer m, @Nullable WcsHeader.Projection forcedProjection) {
        String ctype1 = m.getString("CTYPE1").orElse("");
        String ctype2 = m.getString("CTYPE2").orElse("");
        WcsHeader.Projection projection = forcedProjection != null ? forcedProjection : WcsHeader.Projection.fromCtype(ctype1, ctype2);
        boolean isSurfaceMap = projection.isSurfaceMap();

        WcsInput wcs = readWcsInput(m);
        PixelAxes axes = computePixelAxes(wcs, m, isSurfaceMap);
        float[] pv2 = readPv2(m, wcs, projection);
        double crvalX;
        double crvalY;
        LinearTransform transform;

        if (isSurfaceMap) {
            boolean isCea = projection == WcsHeader.Projection.CEA;
            transform = computeSurfaceTransform(m, wcs, axes, isCea);
            double longitude = wcs.crval1 * axes.arcsecX / ARCSEC_PER_RAD;
            double latitude = wcs.crval2 * axes.arcsecY / ARCSEC_PER_RAD;
            crvalX = longitude;
            crvalY = isCea ? ceaLatitudeCoordinate(latitude, wcs.pv2_1) : latitude;
        } else {
            transform = computeObserverTransform(m, wcs, axes);
            crvalX = wcs.crval1 * axes.arcsecX;
            crvalY = wcs.crval2 * axes.arcsecY;
        }

        return new Result(
                projection,
                pv2,
                crvalX,
                crvalY,
                transform.imageToPlane,
                isSurfaceMap ? transform.unitPerPixelX : 0,
                isSurfaceMap ? transform.unitPerPixelY : 0,
                isSurfaceMap ? axes.arcsecPerPixelX : transform.unitPerPixelX,
                isSurfaceMap ? axes.arcsecPerPixelY : transform.unitPerPixelY);
    }

    private static WcsInput readWcsInput(MetaDataContainer m) {
        MatrixKeywords pc = readMatrix(m, "PC", 1.);
        MatrixKeywords cd = readMatrix(m, "CD", 0.);
        // FITS WCS/wcslib: PC takes precedence if both are present. Otherwise,
        // any CD card selects a zero-defaulted CD matrix; CDELT and CROTA are ignored.
        boolean usesCd = cd.present && !pc.present;
        return new WcsInput(
                usesCd ? 1. : m.getRequiredDouble("CDELT1"),
                usesCd ? 1. : m.getRequiredDouble("CDELT2"),
                m.getDouble("CRVAL1").orElse(0.),
                m.getDouble("CRVAL2").orElse(0.),
                m.getDouble("PV2_1").orElse(1.),
                pc,
                cd);
    }

    private static MatrixKeywords readMatrix(MetaDataContainer m, String prefix, double diagonalDefault) {
        Optional<Double> m11 = m.getDouble(prefix + "1_1");
        Optional<Double> m12 = m.getDouble(prefix + "1_2");
        Optional<Double> m21 = m.getDouble(prefix + "2_1");
        Optional<Double> m22 = m.getDouble(prefix + "2_2");
        boolean present = m11.isPresent() || m12.isPresent() || m21.isPresent() || m22.isPresent();
        return new MatrixKeywords(present, new Mat2(
                m11.orElse(diagonalDefault), m12.orElse(0.),
                m21.orElse(0.), m22.orElse(diagonalDefault)));
    }

    private static PixelAxes computePixelAxes(WcsInput wcs, MetaDataContainer m, boolean isSurfaceMap) {
        double arcsecX = readAngularAxisScaleArcsec(m, "CUNIT1", isSurfaceMap);
        double arcsecY = readAngularAxisScaleArcsec(m, "CUNIT2", isSurfaceMap);
        return new PixelAxes(
                arcsecX, arcsecY, wcs.cdelt1 * arcsecX, wcs.cdelt2 * arcsecY, wcs.pc.matrix);
    }

    private static double readAngularAxisScaleArcsec(MetaDataContainer m, String cunitKey, boolean defaultDegrees) {
        return m.getString(cunitKey)
                .map(WcsInterpreter::arcsecPerUnit)
                .orElse(defaultDegrees ? 3600. : 1.);
    }

    private static double arcsecPerUnit(String unit) {
        return switch (unit.strip().toLowerCase()) {
            // "degree"/"degrees" are not FITS-standard but occur in the wild (IDL-written
            // synoptic maps); without them they fall to default and scale the image by 1/3600.
            case "deg", "degree", "degrees" -> 3600.;
            case "arcmin" -> 60.;
            case "arcsec" -> 1.;
            case "mas" -> .001;
            case "rad" -> 180. * 3600. / Math.PI;
            default -> 1.;
        };
    }

    private static float[] readPv2(MetaDataContainer m, WcsInput wcs, WcsHeader.Projection projection) {
        float[] pv2 = new float[6];
        for (int i = 0; i < pv2.length; i++)
            pv2[i] = m.getDouble("PV2_" + i).map(Double::floatValue).orElse(0f);
        if (projection == WcsHeader.Projection.CEA) // Thompson (2006): CEA defaults PV2_1 to 1 when omitted.
            pv2[1] = (float) wcs.pv2_1;
        return pv2;
    }

    private static LinearTransform computeSurfaceTransform(MetaDataContainer m, WcsInput wcs, PixelAxes axes, boolean isCea) {
        // Surface-map X is angular longitude. Y is angular latitude for CAR and equal-area Y for CEA.
        // Historical normalized CEA maps omit CUNIT2 and store the second plane coordinate
        // directly as sin(latitude) / lambda. An explicit CUNIT2 selects the FITS angular
        // convention used by wcslib/Astropy, which JHV converts to the same internal coordinate.
        // This deliberately overrides the FITS default of degrees when CUNIT2 is absent.
        boolean normalizedCeaY = isCea && m.getString("CUNIT2").isEmpty();
        double axis1Scale = axes.arcsecX / ARCSEC_PER_RAD;
        double axis2Scale = normalizedCeaY ? 1 : axes.arcsecY / ARCSEC_PER_RAD;

        if (!wcs.pc.present && wcs.cd.present) {
            Mat2 cd = wcs.cd.matrix;
            return normalize(
                    axis1Scale * cd.m00,
                    axis1Scale * cd.m01,
                    axis2Scale * cd.m10,
                    axis2Scale * cd.m11);
        }

        double cdelt1Surface = wcs.cdelt1 * axis1Scale;
        double cdelt2Surface = wcs.cdelt2 * axis2Scale;
        return normalize(
                cdelt1Surface * axes.pc.m00,
                cdelt1Surface * axes.pc.m01,
                cdelt2Surface * axes.pc.m10,
                cdelt2Surface * axes.pc.m11);
    }

    private static LinearTransform computeObserverTransform(MetaDataContainer m, WcsInput wcs, PixelAxes axes) {
        if (wcs.pc.present) {
            return normalize(
                    axes.arcsecPerPixelX * axes.pc.m00,
                    axes.arcsecPerPixelX * axes.pc.m01,
                    axes.arcsecPerPixelY * axes.pc.m10,
                    axes.arcsecPerPixelY * axes.pc.m11);
        }

        if (wcs.cd.present) {
            Mat2 cd = wcs.cd.matrix;
            return normalize(
                    axes.arcsecX * cd.m00,
                    axes.arcsecX * cd.m01,
                    axes.arcsecY * cd.m10,
                    axes.arcsecY * cd.m11);
        }

        double crota = m.getDouble("CROTA").or(() -> m.getDouble("CROTA1")).or(() -> m.getDouble("CROTA2"))
                .map(Math::toRadians).orElse(0.);
        double c = Math.cos(crota);
        double s = Math.sin(crota);
        return normalize(
                axes.arcsecPerPixelX * c,
                -axes.arcsecPerPixelY * s,
                axes.arcsecPerPixelX * s,
                axes.arcsecPerPixelY * c);
    }

    private static LinearTransform normalize(double m00, double m01, double m10, double m11) {
        double unitPerPixelX = Math.hypot(m00, m10);
        double unitPerPixelY = Math.hypot(m01, m11);
        double divisorX = unitPerPixelX == 0 ? 1 : unitPerPixelX;
        double divisorY = unitPerPixelY == 0 ? 1 : unitPerPixelY;
        return new LinearTransform(
                new Mat2(m00 / divisorX, m01 / divisorY, m10 / divisorX, m11 / divisorY),
                unitPerPixelX,
                unitPerPixelY);
    }

    private static double ceaLatitudeCoordinate(double latitude, double pv2_1) {
        // JHV stores the CEA second axis as the equal-area latitude coordinate y = sin(lat) / lambda.
        double lambda = Math.max(pv2_1, 1e-12);
        return Math.sin(latitude) / lambda;
    }

    private WcsInterpreter() {}
}
