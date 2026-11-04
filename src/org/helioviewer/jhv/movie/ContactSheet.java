package org.helioviewer.jhv.movie;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;

import org.helioviewer.jhv.app.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

/** Tiles a set of PNGs into one near-square grid, labelled, with the hfstudio provenance block. */
public final class ContactSheet {

    public static final String TILE_KEY = "contactsheet.tile";
    public static final String GUTTER_KEY = "contactsheet.gutter";
    public static final String BACKGROUND_KEY = "contactsheet.background";
    public static final String LABELS_KEY = "contactsheet.labels";
    public static final int DEFAULT_TILE = 512, DEFAULT_GUTTER = 8;

    private ContactSheet() {
    }

    public static int tilePx() {
        return intSetting(TILE_KEY, DEFAULT_TILE);
    }

    public static int gutterPx() {
        return intSetting(GUTTER_KEY, DEFAULT_GUTTER);
    }

    public static Color background() {
        String s = Settings.getProperty(BACKGROUND_KEY);
        try {
            return s == null ? Color.BLACK : Color.decode(s);
        } catch (NumberFormatException e) {
            return Color.BLACK;
        }
    }

    /** On for the sheet, unlike single PNGs (those default off, owned by their own setting). */
    public static boolean labels() {
        return !"false".equals(Settings.getProperty(LABELS_KEY));
    }

    private static int intSetting(String key, int def) {
        try {
            String s = Settings.getProperty(key);
            int v = s == null ? def : Integer.parseInt(s.trim());
            return v > 0 ? v : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Columns for n panels: the smallest c with c*c >= n, so 4 gives 2x2, 5 to 9 give 3 wide. */
    public static int columns(int n) {
        return (int) Math.ceil(Math.sqrt(n));
    }

    /** Sheet size for n tiles; tiles are square cells of tilePx, images letterboxed inside. */
    public static int[] size(int n, int tilePx, int gutterPx) {
        int cols = columns(n), rows = (n + cols - 1) / cols;
        return new int[]{cols * tilePx + (cols + 1) * gutterPx, rows * tilePx + (rows + 1) * gutterPx};
    }

    /** The cell of panel i, for labels and for checks. */
    public static Rectangle cell(int i, int n, int tilePx, int gutterPx) {
        int cols = columns(n);
        return new Rectangle(gutterPx + (i % cols) * (tilePx + gutterPx), gutterPx + (i / cols) * (tilePx + gutterPx), tilePx, tilePx);
    }

    /** Uses the settings for tile, gutter, background and label visibility. */
    public static void grid(List<Path> pngs, List<String> labels, Path out) throws IOException {
        grid(pngs, labels, tilePx(), gutterPx(), background(), out);
    }

    public static void grid(List<Path> pngs, List<String> labels, int tilePx, int gutterPx, Color background, Path out) throws IOException {
        grid(pngs, labels, tilePx, gutterPx, background, out, null, labels(), null);
    }

    /**
     * @param layers per-panel layer names for the caption stub (may be null or shorter than pngs)
     * @param session the provenance block to embed; null takes the live one (needs the running app)
     * @param drawLabels whether to draw the labels onto the tiles; the caption stub is written either way
     */
    public static void grid(List<Path> pngs, List<String> labels, int tilePx, int gutterPx, Color background, Path out,
                            @Nullable List<List<String>> layers, boolean drawLabels, @Nullable JSONObject session) throws IOException {
        int n = pngs.size();
        if (n == 0)
            throw new IOException("Contact sheet needs at least one PNG");
        int[] wh = size(n, tilePx, gutterPx);
        BufferedImage sheet = new BufferedImage(wh[0], wh[1], BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        try {
            g.setColor(background);
            g.fillRect(0, 0, wh[0], wh[1]);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            for (int i = 0; i < n; i++) {
                BufferedImage img = ImageIO.read(pngs.get(i).toFile());
                if (img == null)
                    throw new IOException("Not a readable PNG: " + pngs.get(i));
                Rectangle c = cell(i, n, tilePx, gutterPx);
                double s = (double) tilePx / Math.max(img.getWidth(), img.getHeight()); // long side to tilePx
                int w = Math.max(1, (int) Math.round(img.getWidth() * s)), h = Math.max(1, (int) Math.round(img.getHeight() * s));
                Rectangle at = new Rectangle(c.x + (tilePx - w) / 2, c.y + (tilePx - h) / 2, w, h);
                g.drawImage(img, at.x, at.y, at.width, at.height, null);
                if (drawLabels && i < labels.size())
                    PanelLabel.draw(g, labels.get(i), at);
            }
        } finally {
            g.dispose();
        }
        if (out.getParent() != null)
            Files.createDirectories(out.getParent());
        ImageIO.write(sheet, "png", out.toFile());
        List<String> names = new ArrayList<>();
        for (Path p : pngs)
            names.add(p.getFileName().toString());
        JSONObject block = (session != null ? session : Provenance.session()).put("contactSheet", new JSONObject()
                .put("sources", new JSONArray(names)).put("tilePx", tilePx).put("gutterPx", gutterPx)
                .put("columns", columns(n)));
        Provenance.writePngChunk(out, block);
        writeCaptions(out, labels, layers, n);
    }

    private static void writeCaptions(Path sheet, List<String> labels, @Nullable List<List<String>> layers, int n) throws IOException {
        String name = sheet.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path stub = sheet.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".caption.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i < labels.size() ? labels.get(i) : "panel " + (i + 1));
            if (layers != null && i < layers.size() && !layers.get(i).isEmpty())
                sb.append(": ").append(String.join(", ", layers.get(i)));
            sb.append('\n');
        }
        Files.writeString(stub, sb.toString(), StandardCharsets.UTF_8);
    }
}
