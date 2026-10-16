package org.helioviewer.jhv.image;

import org.helioviewer.jhv.image.lut.LUT;

import org.json.JSONObject;

/**
 * A streamed movie activates its layer twice, on the first frame and again when the last frame
 * lands, and both times hands over the same default colour table. The repeat must not count as a
 * new default: it used to put the instrument's table back over one the user picked while the movie
 * was still arriving, and over a session's restored table, whose one-time pass the first frame had
 * already spent. A different default (another product) is still taken.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.image.DefaultLutRepeatCheck
 */
public final class DefaultLutRepeatCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        // Three distinct registered tables; which ones does not matter.
        String[] names = LUT.names();
        if (names.length < 3) {
            System.out.println("SKIP: fewer than three colour tables registered");
            return;
        }
        LUT gray = LUT.get(names[0]);
        LUT red = LUT.get(names[1]);
        LUT blue = LUT.get(names[2]);

        // A fresh layer: the first frame's default is taken.
        ImageDisplaySettings fresh = new ImageDisplaySettings();
        fresh.setDefaultLUT(red, false);
        expect("the first view's default is taken, got " + fresh.getLUT().name(), fresh.getLUT().name().equals(red.name()));

        // The user picks a table while the movie arrives; the last frame brings the same default again.
        fresh.setLUT(gray, false);
        fresh.setDefaultLUT(red, false);
        expect("the same default on completion keeps the table picked meanwhile, got " + fresh.getLUT().name(),
                fresh.getLUT().name().equals(gray.name()));

        // Another product brings another default, which is taken.
        fresh.setDefaultLUT(blue, false);
        expect("a different default is still taken, got " + fresh.getLUT().name(), fresh.getLUT().name().equals(blue.name()));

        // A restored session's table survives both activations of its streamed movie.
        ImageDisplaySettings restored = new ImageDisplaySettings();
        restored.fromJson(new JSONObject().put("lut", blue.name()));
        restored.setDefaultLUT(red, false); // first frame
        restored.setDefaultLUT(red, false); // last frame
        expect("a session's table survives the first and the last frame, got " + restored.getLUT().name(),
                restored.getLUT().name().equals(blue.name()));

        // A view that names no table (no colour rule matches its FITS header) brings null: read as grey,
        // and a repeat of it is a repeat like any other. It used to throw on every such load.
        ImageDisplaySettings plain = new ImageDisplaySettings();
        plain.setLUT(red, false);
        try {
            plain.setDefaultLUT(null, false);
            expect("a null default is read as grey, got " + plain.getLUT().name(), plain.getLUT().name().equals(LUT.gray().name()));
            plain.setLUT(blue, false);
            plain.setDefaultLUT(null, false);
            expect("a repeated null default keeps the table picked meanwhile, got " + plain.getLUT().name(),
                    plain.getLUT().name().equals(blue.name()));
        } catch (NullPointerException e) {
            expect("a null default does not throw (" + e.getMessage() + ")", false);
        }

        System.out.println(failures == 0 ? "DefaultLutRepeatCheck: ok" : "DefaultLutRepeatCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
