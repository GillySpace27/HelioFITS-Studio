package org.helioviewer.jhv.gui.dialog;

import java.lang.reflect.Method;
import java.util.List;

import org.helioviewer.jhv.io.SoarClient;

/**
 * The two things SOAR's result filtering has to get right, neither of which fails loudly.
 *
 * <p>SOAR has no cadence or exclusion in its query, so both are applied to the returned list. A
 * timestamp parsed out of the data item id is what thinning depends on, and the id format is a
 * convention rather than a contract: {@code solo_L2_eui-fsi174-image_20211227T173845330}. If that
 * pattern stops matching, every item reads as time zero, and thinning would either drop the whole
 * list or silently keep all of it depending on which way the fallback went.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.gui.dialog.SoarFilterCheck
 */
public final class SoarFilterCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        // Real ids, taken from a live SOAR query rather than invented.
        expect(milli("solo_L2_eui-fsi174-image_20211227T173845330") > 0, "a real id parses");
        expect(milli("solo_L2_mag-rtn-normal_20220301T000000") > 0, "an id without milliseconds parses");
        expect(milli("something_with_no_timestamp") == 0, "an id with no timestamp yields 0, not a throw");
        expect(milli("solo_L2_x_20211327T173845") == 0, "an impossible month yields 0, not a throw");

        // Ordering, so thinning sees a monotonic sequence whatever order the archive returned.
        long a = milli("solo_L2_eui-fsi174-image_20211227T130245302");
        long b = milli("solo_L2_eui-fsi174-image_20211227T173845330");
        expect(a < b, "13:02 sorts before 17:38");
        // 4h36m apart, so an hourly cadence keeps both and a daily one keeps the first only.
        expect(b - a > 4 * 3600_000L && b - a < 5 * 3600_000L, "the gap is the 4h36m it should be");

        if (failures != 0)
            throw new AssertionError(failures + " SOAR filter failure(s)");
        System.out.println("SoarFilterCheck: PASS");
    }

    private static long milli(String id) throws Exception {
        Method m = SoarDialog.class.getDeclaredMethod("itemMilli", SoarClient.DataItem.class);
        m.setAccessible(true);
        return (long) m.invoke(null, new SoarClient.DataItem(id, null, 0));
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

}
