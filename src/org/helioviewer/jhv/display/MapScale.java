package org.helioviewer.jhv.display;

public interface MapScale {

    double toMapX(double unitX);

    double toMapY(double unitY);

    double toUnitX(double mapX);

    double toUnitY(double mapY);

    default double warpLambda() {
        return 1;
    }

    // Fraction of the radial extent occupied by the linear disk; only the Box-Cox scale
    // (and the warp shaders, via ScreenBlock) use a non-trivial value.
    default double warpLimb() {
        return 0;
    }

    /**
     * The radial extent this scale normalizes over, in solar radii.
     *
     * <p>Everything that warps must agree on this number: the imagery mesh takes it from the
     * scale, and so must the overlays, or the grid and the point clouds sit at a different
     * radius from the picture they annotate. It used to be passed to GLSLWarp separately, which
     * is exactly how the two came apart when the edge control stopped feeding the warp.
     * Reading it off the scale makes them impossible to desync.
     */
    default double warpOuterRadius() {
        return 1;
    }

    MapScale ortho = new LinearMapScale(0, 0, 0, 0);
    MapScale lati = new LinearMapScale(-180, 180, -90, 90);

    static MapScale hpc(double halfWidth, double halfHeight) {
        return new LinearMapScale(-halfWidth, halfWidth, -halfHeight, halfHeight);
    }

    /**
     * The observer-sky page, in the WCS native radial coordinate in degrees.
     *
     * <p>Linear like the HPC scale, and for the same reason: the projection's own radial law has
     * already been applied by the time a coordinate reaches here, so what is left is a plain
     * window onto the projection plane. For the azimuthal equidistant default that plane is
     * calibrated in degrees of arc, so halfHeight IS the field radius the user asked for.
     */
    static MapScale sky(double halfWidth, double halfHeight) {
        return new LinearMapScale(-halfWidth, halfWidth, -halfHeight, halfHeight);
    }

    static MapScale boxCoxRadial(double radialSize) {
        return new BoxCoxRadialScale(Math.max(radialSize, 1));
    }

    final class LinearMapScale implements MapScale {

        private final double xStart;
        private final double yStart;

        private final double xRange;
        private final double yRange;
        private final double invXRange;
        private final double invYRange;

        LinearMapScale(double _xStart, double _xStop, double _yStart, double _yStop) {
            xStart = _xStart;
            double effectiveXStop = _xStart == _xStop ? Math.nextUp(_xStart) : _xStop;

            yStart = _yStart;
            double effectiveYStop = _yStart == _yStop ? Math.nextUp(_yStart) : _yStop;

            xRange = effectiveXStop - xStart;
            yRange = effectiveYStop - yStart;
            invXRange = 1.0 / xRange;
            invYRange = 1.0 / yRange;
        }

        @Override
        public double toMapX(double unitX) {
            return xStart + unitX * xRange;
        }

        @Override
        public double toMapY(double unitY) {
            return yStart + unitY * yRange;
        }

        @Override
        public double toUnitX(double mapX) {
            return (mapX - xStart) * invXRange;
        }

        @Override
        public double toUnitY(double mapY) {
            return (mapY - yStart) * invYRange;
        }

    }

    // Box-Cox radial scale outside radius 1, anchored so the limb has the
    // same normalized position as the linear scale for every lambda.
    final class BoxCoxRadialScale implements MapScale {

        private final double radialSize;
        private final double limb;

        BoxCoxRadialScale(double _radialSize) {
            radialSize = _radialSize;
            // The limb's screen position. This restores the original origin-anchored warp: the
            // full radial axis is normalized by warp(R), so the limb sits at
            // warp(1)/warp(R) = 1/(1 + boxcox(R, lambda)) and the disk's share GROWS as lambda
            // compresses the corona (~18% at lambda=0 with a 100 Rsun FOV, ~50% at -1, 1/R at
            // +1 = linear). The corona formula is unchanged; only this anchor differs from
            // upstream's fixed 1/R, which rendered the disk invisible under a wide FOV.
            double lambda = Display.getWarpLambda();
            double bc = _radialSize <= 1 ? 0
                    : (lambda == 0 ? Math.log(_radialSize) : (Math.pow(_radialSize, lambda) - 1) / lambda);
            // Scaled rather than replaced, so lambda stops silently deciding the photosphere's
            // share while the anchor it is scaling still follows the warp. A scale of 1 returns
            // the nominal value untouched, which is what makes the control reversible.
            //
            // Still floored at 1/R: below the true limb the disk would be drawn smaller than the
            // Sun actually subtends at this field, which is a misstatement rather than a taste.
            double auto = Math.max(1 / _radialSize, 1 / (1 + bc));
            // The floor is where the limb actually is at this field; the ceiling leaves some
            // corona to look at. They can cross: with a field barely wider than the Sun (1.1 is
            // fullWarpFieldRadius's own floor, so this is the no-layers-loaded case) the true limb
            // is 0.909, above the 0.9 ceiling, and Math.clamp throws on min > max rather than
            // picking one. The true size wins there, because it is a fact and the ceiling is a
            // preference.
            double trueLimb = 1 / _radialSize;
            limb = Math.clamp(auto * Display.getDiskScale(), trueLimb, Math.max(0.9, trueLimb));
        }

        @Override
        public double warpLimb() {
            return limb;
        }

        @Override
        public double warpOuterRadius() {
            return radialSize;
        }

        @Override
        public double toMapX(double unitX) {
            return 360 * unitX;
        }

        @Override
        public double toUnitX(double mapX) {
            return mapX / 360;
        }

        @Override
        public double warpLambda() {
            return Display.getWarpLambda();
        }

        @Override
        public double toMapY(double unitY) {
            if (radialSize <= 1 || unitY <= limb)
                return unitY / limb;

            double u = (unitY - limb) / (1 - limb);
            double lambda = warpLambda();
            return lambda == 0
                    ? Math.pow(radialSize, u)
                    : Math.pow(1 + u * (Math.pow(radialSize, lambda) - 1), 1 / lambda);
        }

        @Override
        public double toUnitY(double mapY) {
            if (radialSize <= 1 || mapY <= 1)
                return mapY * limb;

            double lambda = warpLambda();
            double u = lambda == 0
                    ? Math.log(mapY) / Math.log(radialSize)
                    : (Math.pow(mapY, lambda) - 1) / (Math.pow(radialSize, lambda) - 1);
            return limb + u * (1 - limb);
        }

    }

}
