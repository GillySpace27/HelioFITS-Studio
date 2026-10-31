package org.helioviewer.jhv.movie;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.json.JSONObject;

/**
 * A contact sheet of four PNGs is 2x2, the right size, labelled in the corner, and carries the
 * hfstudio block naming its sources. A caption stub sits beside it.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.movie.ContactSheetCheck
 */
public final class ContactSheetCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        // user.home is set before Settings is touched; the check must never reach Gilly's settings
        Path home = Files.createTempDirectory("contactsheet");
        System.setProperty("user.home", home.toString());
        System.setProperty("java.awt.headless", "true");

        Color[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};
        List<Path> pngs = new ArrayList<>();
        List<String> labels = List.of("lam 1", "lam 0", "lam -1", "unrolled");
        for (int i = 0; i < 4; i++) {
            BufferedImage im = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB); // wide, so it letterboxes
            Graphics2D g = im.createGraphics();
            g.setColor(colors[i]);
            g.fillRect(0, 0, 200, 100);
            g.dispose();
            Path p = home.resolve("v" + i + ".png");
            ImageIO.write(im, "png", p.toFile());
            pngs.add(p);
        }
        Path out = home.resolve("sheet.png");
        JSONObject stubSession = Provenance.session(new JSONObject(), home.toString(), java.time.Instant.now()); // the live one needs the window
        int tile = 128, gut = 8;
        ContactSheet.grid(pngs, labels, tile, gut, Color.BLACK, out, List.of(List.of("AIA 193"), List.of(), List.of(), List.of()), true, stubSession);

        BufferedImage sheet = ImageIO.read(out.toFile());
        int[] wh = ContactSheet.size(4, tile, gut);
        expect("2x2 columns", ContactSheet.columns(4) == 2 && ContactSheet.columns(5) == 3);
        expect("size " + wh[0] + "x" + wh[1], sheet.getWidth() == 2 * tile + 3 * gut && sheet.getHeight() == wh[1] && wh[0] == wh[1]);

        Rectangle c3 = ContactSheet.cell(3, 4, tile, gut);
        expect("tile 3 (bottom right) holds yellow", (sheet.getRGB(c3.x + 64, c3.y + 64) & 0xFFFFFF) == 0xFFFF00);
        expect("gutter is background", (sheet.getRGB(gut / 2, gut / 2) & 0xFFFFFF) == 0);
        // the image is 200x100 scaled to 128x64, centred: the top letterbox strip of the cell is background
        expect("letterbox strip is background", (sheet.getRGB(c3.x + 64, c3.y + 4) & 0xFFFFFF) == 0);

        // label: darkened box in the top-left of the scaled image, so the red tile's pixel there is no longer pure red
        Rectangle c0 = ContactSheet.cell(0, 4, tile, gut);
        Rectangle img0 = new Rectangle(c0.x, c0.y + 32, 128, 64);
        int inLabel = sheet.getRGB(img0.x + 3, img0.y + 3) & 0xFFFFFF;
        expect("label darkens the top-left corner of tile 0", inLabel != 0xFF0000);
        expect("label leaves the opposite corner alone", (sheet.getRGB(img0.x + 126, img0.y + 62) & 0xFFFFFF) == 0xFF0000);

        JSONObject prov = Provenance.readPngChunk(out);
        expect("sheet carries the provenance block", prov != null && prov.has("contactSheet") && prov.has("scene"));
        expect("provenance lists the source PNG names",
                prov != null && prov.getJSONObject("contactSheet").getJSONArray("sources").toList().equals(List.of("v0.png", "v1.png", "v2.png", "v3.png")));

        List<String> stub = Files.readAllLines(home.resolve("sheet.caption.txt"));
        expect("caption stub has one line per panel", stub.size() == 4);
        expect("caption stub line carries label and layers", stub.get(0).equals("lam 1: AIA 193") && stub.get(1).equals("lam 0"));

        Path unlabelled = home.resolve("plain.png");
        ContactSheet.grid(pngs, labels, tile, gut, Color.BLACK, unlabelled, null, false, stubSession);
        BufferedImage plain = ImageIO.read(unlabelled.toFile());
        expect("labels off leaves the corner untouched", (plain.getRGB(img0.x + 3, img0.y + 3) & 0xFFFFFF) == 0xFF0000);

        System.out.println(failures == 0 ? "ContactSheetCheck: all ok" : "ContactSheetCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
