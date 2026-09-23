package org.helioviewer.jhv.display;

/**
 * The Crop cuts the warped picture; it does not re-warp it.
 *
 * <p>The Crop used to be passed to the warp as if it were the whole field. That renormalizes the
 * mapping: the limb anchor is recomputed from the crop, so tightening it moved structure around
 * inside a rim that never moved, and it read as a second Zoom. With the warp off (lambda = 1)
 * the anchor is 1/R either way and the two coincide, which is why it only looked right there.
 *
 * <p>What is pinned: for every lambda, a point's position in the cropped view is its position in
 * the full-field view divided by the crop's, so the picture is the same picture scaled and cut;
 * the crop radius lands exactly on the rim; and at lambda = 1 nothing changed from before.
 */
final class CropIsACutCheck {

    public static void main(String[] args) {
        double full = 227, crop = 60;
        for (double lambda : new double[] {-0.8, -0.3, 0, 0.4, 1}) {
            MapScale field = MapScale.boxCoxRadial(full, lambda);
            MapScale cut = MapScale.boxCoxRadialCrop(full, crop, lambda);
            double edge = field.toUnitY(crop);
            for (double r : new double[] {0.5, 1, 1.5, 3, 10, 30, 59}) {
                double expected = field.toUnitY(r) / edge;
                near("lambda " + lambda + " r " + r, cut.toUnitY(r), expected);
                near("round trip lambda " + lambda + " r " + r, cut.toMapY(cut.toUnitY(r)), r);
            }
            near("rim at lambda " + lambda, cut.toUnitY(crop), 1);
        }

        // With the warp off the old renormalizing scale was already a pure cut; it must not move.
        MapScale before = MapScale.boxCoxRadial(crop, 1);
        MapScale now = MapScale.boxCoxRadialCrop(full, crop, 1);
        for (double r : new double[] {0.5, 2, 20, 55})
            near("lambda 1 unchanged at r " + r, now.toUnitY(r), before.toUnitY(r));

        // And under a warp it must differ, or the fix did nothing.
        if (Math.abs(MapScale.boxCoxRadialCrop(full, crop, 0).toUnitY(10) - MapScale.boxCoxRadial(crop, 0).toUnitY(10)) < 1e-3)
            throw new AssertionError("under a warp the cut still matches the renormalized scale");

        // No crop is the full field, untouched.
        near("auto", MapScale.boxCoxRadialCrop(full, 0, 0).toUnitY(50), MapScale.boxCoxRadial(full, 0).toUnitY(50));

        System.out.println("ok CropIsACutCheck");
    }

    private static void near(String what, double actual, double expected) {
        if (Math.abs(actual - expected) > 1e-9 * Math.max(1, Math.abs(expected)))
            throw new AssertionError(what + ": " + actual + ", expected " + expected);
    }

}
