package org.helioviewer.jhv.view;

import java.lang.reflect.Proxy;
import java.util.List;

import org.helioviewer.jhv.time.JHVTime;

/**
 * A movie of several pieces (a request past the server's 1000-frame cap) is a ManyView, and Use FITS reads
 * each frame's header through getXMLMetaData(int); ManyView once left the View default, so every frame
 * came back empty ("1291 frames ... without a usable header"). The pieces here are stand-ins.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.view.ManyViewXmlMetaDataCheck
 */
public final class ManyViewXmlMetaDataCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static View piece(long start, int frames, String tag) {
        return (View) Proxy.newProxyInstance(View.class.getClassLoader(), new Class<?>[]{View.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "getMaximumFrameNumber": return frames - 1;
                case "getFrameTime": return new JHVTime(start + 1000L * (Integer) a[0]);
                case "getXMLMetaData": return a == null ? View.EMPTY_METAXML : "<meta>" + tag + (Integer) a[0] + "</meta>";
                case "toString": return "piece " + tag;
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == a[0];
                default:
                    Class<?> r = m.getReturnType();
                    return r == boolean.class ? (Object) false : r == int.class ? (Object) 0 : r == long.class ? (Object) 0L : r == double.class ? (Object) 0.0 : null;
            }
        });
    }

    public static void main(String[] args) throws Exception {
        ManyView movie = new ManyView(List.of(piece(0, 2, "a"), piece(10_000, 3, "b")));
        expect("five frames", movie.getMaximumFrameNumber() == 4);
        expect("frame 0 is the first piece's frame 0", "<meta>a0</meta>".equals(movie.getXMLMetaData(0)));
        expect("frame 1 is the first piece's frame 1", "<meta>a1</meta>".equals(movie.getXMLMetaData(1)));
        expect("frame 2 is the second piece's frame 0", "<meta>b0</meta>".equals(movie.getXMLMetaData(2)));
        expect("frame 4 is the second piece's frame 2", "<meta>b2</meta>".equals(movie.getXMLMetaData(4)));
        expect("a frame past the end is empty", View.EMPTY_METAXML.equals(movie.getXMLMetaData(5)));
        System.exit(failures == 0 ? 0 : 1);
    }
}
