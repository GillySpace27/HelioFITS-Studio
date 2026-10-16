package org.helioviewer.jhv.gui.dialog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SOAR and ASPIICS dialogs name the product variants an archive returns under one dataset.
 *
 * <p>The SOAR ids follow the pattern the existing SoarDialog comment records
 * ({@code solo_L2_eui-fsi174-image_20211227T173845330}); the ASPIICS names follow the
 * {@code aspiics_<product>_l3_<YYYYMMDDThhmmss>_...} pattern in AspiicsDialog. Both are synthetic,
 * not copied from either archive.
 *
 * <p>Run: java -cp "bin:extra/test-classes" org.helioviewer.jhv.gui.dialog.ProductVariantsCheck
 */
public final class ProductVariantsCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        // SOAR: the descriptor sits between the level and the time.
        expect("standard FSI descriptor is read from the id",
                "eui-fsi304-image".equals(ProductVariants.soarDescriptor("solo_L2_eui-fsi304-image_20230101T000015123_V01")));
        expect("short descriptor is not confused with the standard one",
                "eui-fsi304-image-short".equals(ProductVariants.soarDescriptor("solo_L2_eui-fsi304-image-short_20230101T000015123_V01")));
        expect("an id with no descriptor gives an empty one", "".equals(ProductVariants.soarDescriptor("garbage")));

        expect("eui-fsi174-image is the standard variant", ProductVariants.soarIsStandard("eui-fsi174-image"));
        expect("eui-fsi304-image is the standard variant", ProductVariants.soarIsStandard("eui-fsi304-image"));
        expect("occulter is not standard", !ProductVariants.soarIsStandard("eui-fsi304-image-occulter"));
        expect("short is not standard", !ProductVariants.soarIsStandard("eui-fsi174-image-short"));
        expect("other instruments have no standard variant", !ProductVariants.soarIsStandard("phi-hrt-blos"));

        expect("short exposure gets a plain label",
                ProductVariants.soarLabel("eui-fsi304-image-short").startsWith("Short exposure"));
        expect("occulter gets a plain label",
                ProductVariants.soarLabel("eui-fsi174-image-occulter").startsWith("Disc occulted"));
        expect("standard gets a plain label", ProductVariants.soarLabel("eui-fsi304-image").startsWith("Standard"));
        expect("every FSI label keeps the dataset id",
                ProductVariants.soarLabel("eui-fsi304-image-short").contains("eui-fsi304-image-short"));
        expect("an unknown descriptor is shown as its id", "phi-hrt-blos".equals(ProductVariants.soarLabel("phi-hrt-blos")));
        expect("FSI variants carry a tooltip", ProductVariants.soarTip("eui-fsi304-image-occulter") != null);
        expect("unknown descriptors carry none", ProductVariants.soarTip("phi-hrt-blos") == null);

        // ASPIICS: the kind is what is left once product and time are taken out.
        String a = ProductVariants.aspiicsKind("aspiics_pb_l3_20250523T091503_v03.fits");
        String b = ProductVariants.aspiicsKind("aspiics_pb_l3_20250523T093003_v03.fits");
        String c = ProductVariants.aspiicsKind("aspiics_pb_l3_20250523T091503_merged_v03.fits");
        expect("two frames of one kind share a kind", a.equals(b));
        expect("a different suffix is a different kind", !a.equals(c));
        expect("the kind keeps the distinguishing word", c.contains("merged"));
        expect("a directory in the data location is ignored",
                a.equals(ProductVariants.aspiicsKind("2025/05/23/aspiics_pb_l3_20250523T094503_v03.fits")));
        expect("a second time stamp does not split one kind per file",
                ProductVariants.aspiicsKind("aspiics_pb_l3_20250523T091503_20250601T120000_v03.fits")
                        .equals(ProductVariants.aspiicsKind("aspiics_pb_l3_20250523T093003_20250601T130000_v03.fits")));
        expect("a compressed file has the same kind as a plain one",
                a.equals(ProductVariants.aspiicsKind("aspiics_pb_l3_20250523T091503_v03.fits.gz")));
        expect("a name with nothing after the time has an empty kind",
                "".equals(ProductVariants.aspiicsKind("aspiics_pb_l3_20250523T091503.fits")));

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("few", 3);
        counts.put("many", 40);
        counts.put("some", 12);
        expect("the kind with the most frames is preferred", "many".equals(ProductVariants.preferredKind(counts)));
        Map<String, Integer> tie = new LinkedHashMap<>();
        tie.put("b", 5);
        tie.put("a", 5);
        expect("a tie goes to the first name in order", "a".equals(ProductVariants.preferredKind(tie)));
        expect("no kinds, no preference", ProductVariants.preferredKind(Map.of()) == null);

        Map<String, Integer> kinds = ProductVariants.countKinds(List.of(
                "aspiics_pb_l3_20250523T091503_v03.fits",
                "aspiics_pb_l3_20250523T093003_v03.fits",
                "aspiics_pb_l3_20250523T091503_merged_v03.fits"));
        expect("countKinds counts each kind", kinds.size() == 2 && kinds.get(a) == 2 && kinds.get(c) == 1);

        for (String code : List.of("bt", "pb", "pa", "fe", "he"))
            expect("ASPIICS product " + code + " has a tooltip", ProductVariants.aspiicsProductTip(code) != null);
        expect("an unknown ASPIICS product has none", ProductVariants.aspiicsProductTip("zz") == null);

        if (failures > 0) {
            System.out.println("ProductVariantsCheck: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("ProductVariantsCheck: PASS");
    }
}
