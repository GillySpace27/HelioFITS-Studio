package org.helioviewer.jhv.layers;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.view.View;

/**
 * The first dataset of a run is framed when it lands, without a double-click on the canvas.
 *
 * <p>A new layer is only enabled in activateView(), and the fit that setView runs counts enabled
 * layers only, so with nothing else on screen the fit saw a size of zero and left the default
 * field of view. The check lands a stub view on a fresh layer and asks the camera what it was
 * fitted to.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.FirstLoadFitCheck
 */
public final class FirstLoadFitCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static Object stub(Class<?> type, Object metaData) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            Class<?> r = m.getReturnType();
            if (r == MetaData.class)
                return metaData;
            if (r == Region.class)
                return new Region(-16, -16, 32, 32);
            if (r == URI.class)
                return URI.create("file:///stub.fits");
            if (r == boolean.class)
                return false;
            if (r == int.class)
                return 0;
            if (r == double.class)
                return 1.0;
            if (r == float.class)
                return 1f;
            if (r == long.class)
                return 0L;
            if (r == String.class)
                return "";
            if (m.isDefault())
                return java.lang.invoke.MethodHandles.privateLookupIn(type, java.lang.invoke.MethodHandles.lookup())
                        .unreflectSpecial(m, type).bindTo(p).invokeWithArguments(a == null ? new Object[0] : a);
            return null;
        });
    }

    // Camera keeps its field of view private; its session form is the one place it is readable.
    private static double fov() throws Exception {
        var m = org.helioviewer.jhv.display.Camera.class.getDeclaredMethod("toJson");
        m.setAccessible(true);
        return ((org.json.JSONObject) m.invoke(Display.getCamera())).getDouble("fov");
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-first-fit").toString());
        Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.io.Directories.SETTINGS.getFile().mkdirs();

        MetaData meta = (MetaData) stub(MetaData.class, null);
        View view = (View) stub(View.class, meta);

        double before = fov();
        ImageLayer layer;
        try {
            layer = ImageLayer.create(null);
        } catch (UnsatisfiedLinkError | ExceptionInInitializerError | NoClassDefFoundError e) {
            // A layer's metadata reaches SPICE, whose natives are not built for every platform.
            System.out.println("SKIP: SPICE natives are not available on this platform");
            return;
        }
        expect("a fresh layer starts disabled, as the bug needs", !layer.isEnabled());
        layer.setView(view);
        double after = fov();
        expect("the first dataset of a run changes the field of view from the default (" + before + " -> " + after + ")", after != before);
        expect("and the layer is enabled", layer.isEnabled());

        System.out.println(failures == 0 ? "FirstLoadFitCheck: PASS" : "FirstLoadFitCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private FirstLoadFitCheck() {}

}
