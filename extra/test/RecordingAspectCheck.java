package org.helioviewer.jhv.app.state;

/**
 * Aspect and resolution are now two controls, and the six fixed presets must still work.
 *
 * <p>Output size used to be a single enum of width/height pairs. It is now an aspect plus a long
 * side, with the short side derived, so that "2:1 at 8K" is one decision and an inconsistent
 * width/height pair cannot be expressed at all.
 *
 * <p>The compatibility half matters more than the arithmetic. SAMP messages and scripts name the
 * old preset values, so RecordingSize has to keep resolving, and it has to resolve to EXACTLY
 * the pixel dimensions it used to produce. A preset that silently came back one pixel different
 * would change the framing of an existing recording script without any error.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.app.state.RecordingAspectCheck
 */
public final class RecordingAspectCheck {

    private static int failures;

    public static void main(String[] args) {
        // Every legacy preset must still produce its original pixel dimensions.
        checkPreset(ViewState.RecordingSize.H1024, 1024, 1024);
        checkPreset(ViewState.RecordingSize.H1080, 1920, 1080);
        checkPreset(ViewState.RecordingSize.H2048, 2048, 2048);
        checkPreset(ViewState.RecordingSize.H2160, 3840, 2160);
        checkPreset(ViewState.RecordingSize.H4096, 4096, 4096);

        // The aspects produce their exact ratios, with the long side honoured as given.
        checkAspect(ViewState.RecordingAspect.EQUIRECT, 8192, 8192, 4096);
        checkAspect(ViewState.RecordingAspect.EQUIRECT, 4096, 4096, 2048);
        checkAspect(ViewState.RecordingAspect.WIDE, 3840, 3840, 2160);
        checkAspect(ViewState.RecordingAspect.SQUARE, 2048, 2048, 2048);
        checkAspect(ViewState.RecordingAspect.CLASSIC, 1600, 1600, 1200);

        // 2:1 is the one that motivated the feature, so assert the ratio itself rather than
        // trusting the pair above: a dome master that is not exactly 2:1 is unusable.
        for (int longSide : new int[]{1024, 2048, 4096, 8192, 16384}) {
            ViewState.Size out = ViewState.RecordingAspect.EQUIRECT.sizeFor(longSide);
            if (out.width() != 2 * out.height()) {
                System.out.printf("FAIL: equirectangular at %d is %d x %d, which is not 2:1%n",
                                  longSide, out.width(), out.height());
                failures++;
            }
        }

        // ORIGINAL is not a fixed aspect and must not pretend to derive a short side.
        expect(!ViewState.RecordingAspect.ORIGINAL.isFixed(), "ORIGINAL is not a fixed aspect");
        expect(ViewState.RecordingAspect.ORIGINAL.shortSide(1920) == 0, "ORIGINAL derives no short side");
        for (ViewState.RecordingAspect a : ViewState.RecordingAspect.values())
            if (a != ViewState.RecordingAspect.ORIGINAL)
                expect(a.isFixed(), a.name() + " is a fixed aspect");

        // The remaining assertions touch ViewState's mutable state, whose static init reaches
        // DisplayController and from there SPICE natives that a headless run does not have. The
        // enum arithmetic above is the part that carries the compatibility guarantee and does
        // not need any of it, so the rest reports SKIP rather than passing silently.
        try {
            ViewState.setRecordingLongSide(1);
            expect(ViewState.getRecordingLongSide() >= ViewState.MIN_LONG_SIDE,
                   "an absurdly small long side is clamped up");
            ViewState.setRecordingLongSide(1 << 20);
            expect(ViewState.getRecordingLongSide() < (1 << 20), "an oversized long side is clamped down");

            ViewState.setRecordingAspect(ViewState.RecordingAspect.EQUIRECT);
            ViewState.setRecordingLongSide(8192);
            ViewState.RecordingData data = ViewState.recordingData();
            same(data.aspect(), ViewState.RecordingAspect.EQUIRECT, "aspect survives into RecordingData");
            equal(data.longSide(), 8192, "long side survives into RecordingData");
            equal(data.size().width(), 8192, "exported width");
            equal(data.size().height(), 4096, "exported height");
            expect(data.size().internal(), "a fixed aspect reports an explicit size");
            ViewState.setRecordingAspect(ViewState.RecordingAspect.ORIGINAL);
        } catch (Throwable t) {
            System.out.println("SKIP: live recording state needs DisplayController (" +
                               t.getClass().getSimpleName() + "); enum arithmetic still checked");
        }

        System.out.println(failures == 0 ? "RecordingAspectCheck: PASS" : "RecordingAspectCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void checkPreset(ViewState.RecordingSize preset, int width, int height) {
        ViewState.Size viaAspect = preset.aspect().sizeFor(preset.longSide());
        equal(viaAspect.width(), width, preset.name() + " width via aspect");
        equal(viaAspect.height(), height, preset.name() + " height via aspect");
        ViewState.Size legacy = preset.getSize();
        equal(viaAspect.width(), legacy.width(), preset.name() + " matches its own legacy width");
        equal(viaAspect.height(), legacy.height(), preset.name() + " matches its own legacy height");
    }

    private static void checkAspect(ViewState.RecordingAspect aspect, int longSide, int width, int height) {
        ViewState.Size out = aspect.sizeFor(longSide);
        equal(out.width(), width, aspect.name() + " at " + longSide + " width");
        equal(out.height(), height, aspect.name() + " at " + longSide + " height");
    }

    private static void equal(int got, int want, String what) {
        if (got != want) {
            System.out.println("FAIL: " + what + " -- got " + got + ", want " + want);
            failures++;
        }
    }

    private static void same(Object got, Object want, String what) {
        if (got != want) {
            System.out.println("FAIL: " + what + " -- got " + got + ", want " + want);
            failures++;
        }
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private RecordingAspectCheck() {}
}
