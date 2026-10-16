package org.helioviewer.jhv.app.state;

import org.json.JSONObject;

/** PortableScene: the sender's home is not in the file, and local files outside the cache are listed. */
public final class PortableSceneCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        String home = "/Users/abc";
        JSONObject scene = new JSONObject("""
                {"layers":[{"uri":"file:///Users/abc/data/x.fits"},
                           {"uri":"file:///Users/abc/HFStudio/FileCache/y.fits"},
                           {"uri":"https://example.org/z.fits"}],
                 "note":"/Users/abc/notes and /Users/abcd/other"}""");
        PortableScene.Result r = PortableScene.of(scene, home, home + "/HFStudio/FileCache");
        String out = r.scene().toString();
        expect("the home folder is gone", !out.contains("/Users/abc/") && !out.contains("\"/Users/abc\""));
        expect("another account's folder with the same prefix is left alone", out.contains("/Users/abcd/other"));
        expect("a local file outside the cache is listed", r.localFiles().size() == 1 && r.localFiles().get(0).contains("data/x.fits"));
        expect("a cached file and a web URL are not", !r.localFiles().toString().contains("y.fits") && !r.localFiles().toString().contains("example.org"));
        expect("the input is not changed", scene.toString().contains("/Users/abc/data"));
        if (failures > 0) {
            System.out.println("PortableSceneCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("PortableSceneCheck: all passed");
    }
}
