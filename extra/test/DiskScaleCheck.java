package org.helioviewer.jhv.display;

/**
 * The disk's share of the radial axis, and the guarantee that pinning it is reversible.
 *
 * <p>Automatically the limb sits at {@code max(1/R, 1/(1 + boxcox(R, lambda)))}, which makes the
 * photosphere's share a side effect of the warp exponent: on a 245 solar-radii field it is a
 * fraction of a percent at lambda = 1 and half the picture at lambda = -1. Neither is a choice,
 * and the low corona gets the remainder either way.
 *
 * <p>What has to hold: an explicit share is honoured, zero restores the automatic anchor exactly
 * (that is the undo), and no setting can put the drawn limb inside the real one.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.display.DiskScaleCheck
 */
public final class DiskScaleCheck {

    private static final double EPS = 1e-9;
    private static int failures;

    public static void main(String[] args) {
        double R = 245;

        // The coupling this exists to break, recorded so the numbers are not folklore.
        Display.applyDiskScale(Display.DISK_SCALE_NOMINAL);
        Display.setWarpLambda(1);
        double atOne = MapScale.boxCoxRadial(R).warpLimb();
        Display.setWarpLambda(-1);
        double atMinusOne = MapScale.boxCoxRadial(R).warpLimb();
        expect(atOne < 0.01, "at lambda = 1 the disk is under 1% of the axis, got " + atOne);
        expect(atMinusOne > 0.4, "at lambda = -1 it takes over 40%, got " + atMinusOne);

        // Scaled, the share is a fixed multiple of the anchor rather than a fixed fraction of the
        // screen: "twice the nominal disk" keeps its meaning as lambda moves, which a pinned 8%
        // would not -- 8% is a different thing at every warp.
        Display.applyDiskScale(2);
        Display.setWarpLambda(1);
        expect(Math.abs(MapScale.boxCoxRadial(R).warpLimb() - 2 * atOne) < EPS, "2x doubles the lambda = 1 anchor");
        Display.setWarpLambda(0);
        Display.applyDiskScale(Display.DISK_SCALE_NOMINAL);
        double nominalAtZero = MapScale.boxCoxRadial(R).warpLimb();
        Display.applyDiskScale(0.5);
        expect(Math.abs(MapScale.boxCoxRadial(R).warpLimb() - 0.5 * nominalAtZero) < EPS, "0.5x halves it");

        // Reversibility: 1.0 must reproduce the nominal values bit for bit, or "undo" is a lie.
        Display.applyDiskScale(Display.DISK_SCALE_NOMINAL);
        Display.setWarpLambda(1);
        expect(MapScale.boxCoxRadial(R).warpLimb() == atOne, "1.0 restores the lambda = 1 anchor exactly");
        Display.setWarpLambda(-1);
        expect(MapScale.boxCoxRadial(R).warpLimb() == atMinusOne, "1.0 restores the lambda = -1 anchor exactly");

        // The floor is physical, not cosmetic: below 1/R the disk would be drawn smaller than the
        // Sun actually subtends at this field, which is not a matter of taste.
        Display.applyDiskScale(Display.DISK_SCALE_MIN);
        for (double field : new double[]{2, 10, 245}) {
            double limb = MapScale.boxCoxRadial(field).warpLimb();
            expect(limb >= 1 / field - EPS,
                    "the drawn limb never goes inside the real one at field " + field + ": " + limb);
        }

        // And the setter clamps rather than trusting its caller.
        Display.applyDiskScale(10);
        expect(Display.getDiskScale() <= Display.DISK_SCALE_MAX, "an absurd scale is clamped");
        Display.applyDiskScale(-5);
        expect(Display.getDiskScale() >= Display.DISK_SCALE_MIN, "a negative scale clamps to the floor");

        Display.applyDiskScale(Display.DISK_SCALE_NOMINAL);
        Display.setWarpLambda(0);

        // A field barely wider than the Sun: the true limb (1/1.1 = 0.909) sits ABOVE the 0.9
        // ceiling, so a naive clamp throws on min > max. That is fullWarpFieldRadius's own floor,
        // which means it is the state with no layers loaded, which means it is the state the app
        // starts in. It threw on startup exactly once; this is why.
        for (double tinyField : new double[]{1.0, 1.1, 1.11, 1.2}) {
            for (double scale : new double[]{Display.DISK_SCALE_MIN, 0.5, 1, Display.DISK_SCALE_MAX}) {
                Display.applyDiskScale(scale);
                double limb;
                try {
                    limb = MapScale.boxCoxRadial(tinyField).warpLimb();
                } catch (RuntimeException e) {
                    failures++;
                    System.out.println("FAIL: field " + tinyField + " scale " + scale + " threw " + e);
                    continue;
                }
                expect(limb > 0 && limb <= 1, "field " + tinyField + " scale " + scale + " gave limb " + limb);
                expect(limb >= 1 / tinyField - EPS,
                        "the true limb still wins at field " + tinyField + ": " + limb);
            }
        }
        Display.applyDiskScale(Display.DISK_SCALE_NOMINAL);

        onlyTheWarpScalesListen();
        theTrackerSolvesAgainstTheDrawnMap();

        if (failures != 0)
            throw new AssertionError(failures + " disk-scale failure(s)");
        System.out.println("DiskScaleCheck: PASS");
    }

    /**
     * The disk control acts through the Box-Cox limb anchor and nowhere else.
     *
     * <p>This is what justifies greying the Disk slider wherever the warp slider is greyed: the two
     * are not merely usually used together, they share one mechanism, so the disk control has
     * literally nothing to act on in a projection whose scale is linear. Pinned here rather than
     * left to the toolbar, because the toolbar cannot be run headless and the claim is about the
     * geometry rather than about the widget.
     */
    private static void onlyTheWarpScalesListen() {
        double R = 245;
        MapScale[] deaf = {MapScale.ortho, MapScale.lati, MapScale.hpc(2, 2), MapScale.sky(60, 60)};
        double[][] before = sample(deaf);
        Display.applyDiskScale(Display.DISK_SCALE_MIN);
        double[][] atMin = sample(deaf);
        Display.applyDiskScale(Display.DISK_SCALE_MAX);
        double[][] atMax = sample(deaf);
        for (int i = 0; i < deaf.length; i++)
            for (int j = 0; j < before[i].length; j++)
                expect(before[i][j] == atMin[i][j] && before[i][j] == atMax[i][j],
                        "scale " + i + " must not move with the disk control");

        // And the warp scale must, or the control would be greyed everywhere.
        Display.setWarpLambda(0);
        Display.applyDiskScale(Display.DISK_SCALE_MIN);
        double small = MapScale.boxCoxRadial(R).warpLimb();
        Display.applyDiskScale(Display.DISK_SCALE_MAX);
        expect(MapScale.boxCoxRadial(R).warpLimb() > small,
                "the warp scale must still answer to the disk control");
        Display.applyDiskScale(Display.DISK_SCALE_NOMINAL);
    }

    /**
     * What the tracker solves for is where the feature is actually drawn.
     *
     * <p>CMETracker pins a feature at a fixed fraction of the radial axis by solving the radial
     * map for either lambda or the crop. It used to solve a private copy of that map, and the copy
     * had been written before the disk control existed: it anchored the limb at the bare
     * max(1/R, 1/(1 + boxcox)) and never multiplied by the disk scale, which ships at 0.5. So the
     * solve was against a map nobody was drawing, and a tracked feature sat up to 0.08 of the axis
     * away from the ring that claimed to hold it. A CME front cannot show that, having no true
     * position to be wrong about. A comet, which has one, did.
     *
     * <p>Stated as a round trip rather than as "no copy exists", so it holds however the solve is
     * implemented: solve for the knob, then ask the real scale where the feature landed.
     *
     * <p>The real scale is the one Display.warpScale draws: since the Crop became a cut of a warp
     * normalized over the full field rather than a warp over the crop, that is boxCoxRadialCrop
     * over the loaded field, so each half sets the field it means (headless, the layer stack that
     * would supply it cannot load). And it runs in Helioradial: the Crop fix taught the tracker
     * that Orthographic has no warp and crops linearly, and the headless default is Orthographic.
     */
    private static void theTrackerSolvesAgainstTheDrawnMap() {
        java.util.function.DoubleSupplier field = Display.fieldRadius;
        MapMode mode = Display.mode;
        Display.mode = MapMode.Helioradial; // the tracker solves the Box-Cox map only where it is drawn
        double target = CMETracker.screenFraction();
        for (double scale : new double[]{0.5, Display.DISK_SCALE_NOMINAL, 2}) {
            Display.applyDiskScale(scale);
            for (double lambda : new double[]{-0.5, 0, 0.5}) {
                for (double r : new double[]{5, 10}) {
                    // Crop mode: solve the outer radius, holding lambda. Both solves saturate at
                    // their bounds for some of these combinations -- a warp that compresses the
                    // outer corona can put a 5 R_sun feature beyond 0.6 of every field there is --
                    // and saturating is correct behaviour, so only an interior answer is a claim
                    // about the map, and only an interior answer is checked.
                    double maxOut = 100;
                    Display.fieldRadius = () -> maxOut; // a field as wide as the widest crop
                    double out = CMETracker.solveOuter(r, lambda, maxOut);
                    if (out < maxOut - 1e-6) {
                        double landedCrop = MapScale.boxCoxRadialCrop(maxOut, out, lambda).toUnitY(r);
                        expect(Math.abs(landedCrop - target) < 1e-6,
                                "crop solve at disk " + scale + " lambda " + lambda + " r " + r
                                        + " lands at " + landedCrop + ", not " + target);
                    } else {
                        expect(MapScale.boxCoxRadialCrop(maxOut, maxOut, lambda).toUnitY(r) >= target,
                                "crop solve gave up at disk " + scale + " lambda " + lambda + " r " + r
                                        + " where the widest field would have reached the target");
                    }

                    // Warp mode: solve lambda, holding a 30 R_sun field.
                    Display.fieldRadius = () -> 30; // no crop: the field is the view
                    double lam = CMETracker.solve(r, 30);
                    if (Math.abs(lam) < 1 - 1e-6) {
                        double landedWarp = MapScale.boxCoxRadialCrop(30, 30, lam).toUnitY(r);
                        expect(Math.abs(landedWarp - target) < 1e-6,
                                "warp solve at disk " + scale + " r " + r
                                        + " lands at " + landedWarp + ", not " + target);
                    }
                }
            }
        }
        Display.applyDiskScale(Display.DISK_SCALE_NOMINAL);
        Display.setWarpLambda(0);
        Display.fieldRadius = field;
        Display.mode = mode;
    }

    private static double[][] sample(MapScale[] scales) {
        double[][] out = new double[scales.length][6];
        for (int i = 0; i < scales.length; i++) {
            out[i][0] = scales[i].toMapX(0.25);
            out[i][1] = scales[i].toMapY(0.25);
            out[i][2] = scales[i].toMapX(0.9);
            out[i][3] = scales[i].toMapY(0.9);
            out[i][4] = scales[i].warpLimb();
            out[i][5] = scales[i].warpOuterRadius();
        }
        return out;
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

}
