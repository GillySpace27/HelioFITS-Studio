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

        if (failures != 0)
            throw new AssertionError(failures + " disk-scale failure(s)");
        System.out.println("DiskScaleCheck: PASS");
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

}
