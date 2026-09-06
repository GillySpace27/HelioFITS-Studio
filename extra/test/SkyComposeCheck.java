package org.helioviewer.jhv.display;

/**
 * The composed observer sky: the radial modes' Box-Cox scale applied to the sky.
 *
 * <p>Two things have to hold or the picture lies about where the corona is. The pair of maps has
 * to be an inverse pair, because the image goes one way through the shader and the grid, the
 * cursor readout and every overlay go the other way through SkyMap: if they disagree the grid
 * drifts off the picture, which is the failure the sky shader's own header warns about. And the
 * rim has to be a fixed point, because that is what makes the composition a redistribution of the
 * field rather than a rescaling of it: composed and uncomposed agree at the edge and differ
 * inside, which is the warp showing up as a change of angular scale.
 *
 * <p>It also pins the Java against the GLSL. unwarpRadiusWith in solarCommon.frag and
 * MapScale.BoxCoxRadialScale.toMapY are the same function written twice, and this fails if they
 * stop being.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.display.SkyComposeCheck
 */
public final class SkyComposeCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /** solarCommon.frag's unwarpRadiusWith, ported. */
    private static double glslUnwarp(double normalizedRadius, double outerRadius, double limb, double lambda) {
        if (outerRadius <= 1 || normalizedRadius <= limb)
            return normalizedRadius / limb;
        double u = (normalizedRadius - limb) / (1 - limb);
        return lambda == 0 ? Math.pow(outerRadius, u)
                : Math.pow(1 + u * (Math.pow(outerRadius, lambda) - 1), 1 / lambda);
    }

    public static void main(String[] args) {
        double distance = 215; // solar radii, about 1 au
        double[] lambdas = {1, 0.5, 0, -0.5};
        double[] outers = {100, 245};
        SurfaceModel[] surfaces = SurfaceModel.values();

        for (double lambda : lambdas) {
            Display.setWarpLambda(lambda);
            for (double outer : outers) {
                MapScale warp = MapScale.boxCoxRadial(outer); // reads the lambda just set
                for (SurfaceModel surface : surfaces) {
                    // The field ends where the surface stops reaching, which for a Thomson sphere
                    // against a field wider than the observer's distance is the observer: 215 of
                    // the 245 solar radii PUNCH loads. The celestial sphere reaches twice as far
                    // and covers the whole of it, which is one reason it is the right surface here.
                    double rMax = Math.min(outer, surface.reach(distance) * 0.999);
                    double eMax = surface.elongation(rMax, distance);
                    String tag = String.format("lambda %.1f, R %.0f, %s", lambda, outer, surface);

                    // The Java and the GLSL are the same function.
                    double worst = 0;
                    for (double u = 0.02; u <= 1.0001; u += 0.02)
                        worst = Math.max(worst, Math.abs(warp.toMapY(u) - glslUnwarp(u, outer, warp.warpLimb(), lambda)) / outer);
                    expect(tag + ": the shader's unwarp and MapScale agree (worst " + String.format("%.2e", worst) + " of R)", worst < 1e-12);

                    // An inverse pair, so the overlays land on the picture.
                    double worstTrip = 0;
                    for (double f = 0.02; f <= 0.999; f += 0.02) {
                        double eDome = f * eMax;
                        double back = SkyMap.warpElongation(SkyMap.unwarpElongation(eDome, distance, surface, warp), distance, surface, warp);
                        worstTrip = Math.max(worstTrip, Math.abs(back - eDome));
                    }
                    expect(tag + ": dome to data and back is the identity (worst " + String.format("%.2e", worstTrip) + " rad)", worstTrip < 1e-9);

                    // The rim does not move.
                    expect(tag + ": the field edge is a fixed point",
                            Math.abs(SkyMap.unwarpElongation(eMax, distance, surface, warp) - eMax) < 1e-9);

                    // Monotone, and past the rim there is nothing.
                    double last = -1;
                    boolean rises = true;
                    for (double f = 0.02; f <= 0.999; f += 0.02) {
                        double e = SkyMap.unwarpElongation(f * eMax, distance, surface, warp);
                        rises &= e > last;
                        last = e;
                    }
                    expect(tag + ": strictly increasing across the field", rises);
                    expect(tag + ": past the rim is off the map",
                            Double.isNaN(SkyMap.unwarpElongation(eMax * 1.01, distance, surface, warp)));
                }
            }
        }

        // What the composition is for. At lambda 0 the page is logarithmic in radius, so the
        // inner corona takes most of it: the dome pixel half way out shows material from far
        // CLOSER in than its own angle, which is the warp spending angular scale on the inner
        // corona. That is the whole reason to compose rather than to look at the plain sky.
        SurfaceModel surface = SurfaceModel.ThomsonSphere;
        double rMax = Math.min(245, surface.reach(distance) * 0.999);
        double eMax = surface.elongation(rMax, distance);

        Display.setWarpLambda(0);
        MapScale warp = MapScale.boxCoxRadial(245);
        double composed = SkyMap.unwarpElongation(0.5 * eMax, distance, surface, warp);
        expect(String.format("at lambda 0 the half-way dome pixel shows %.1f degrees where the plain sky shows %.1f",
                        Math.toDegrees(composed), Math.toDegrees(0.5 * eMax)),
                composed < 0.5 * eMax * 0.5);

        // At the linear end it is linear in RADIUS, which is not the same as linear in angle.
        Display.setWarpLambda(1);
        MapScale linear = MapScale.boxCoxRadial(245);
        double half = SkyMap.unwarpElongation(0.5 * eMax, distance, surface, linear);
        double rHalf = surface.heliocentricRadius(half, distance);
        expect(String.format("at lambda 1 the half-way dome pixel is half way out in radius (%.1f of %.1f)", rHalf, rMax),
                Math.abs(rHalf / rMax - 0.5) < 0.02);
        expect("and that is not the same as half way in angle",
                Math.abs(half - 0.5 * eMax) > 0.02);

        if (failures > 0)
            throw new AssertionError(failures + " composed sky failure(s)");
        System.out.println("SkyComposeCheck: PASS");
    }

}
