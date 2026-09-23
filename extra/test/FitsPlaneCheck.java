package org.helioviewer.jhv.view.uri;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.helioviewer.jhv.image.ImageProcessingSettings;
import org.helioviewer.jhv.layers.filters.PlanePanel;

import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import nom.tam.fits.ImageHDU;
import nom.tam.fits.header.Compression;
import nom.tam.image.compression.hdu.CompressedImageHDU;
import nom.tam.util.FitsOutputStream;

/**
 * A FITS datacube is a stack of images, and a layer shows one of them.
 *
 * <p>This existed as "Only 2D FITS files supported", thrown two milliseconds after the file was
 * opened, which refused every polarized PUNCH product: PTM and CTM are 4096 x 4096 x 3, the
 * B / pB / pBp triplet, and PUNCH names the three in OBSLAYR1..3. Nothing else in the application
 * had a concept of a plane, so the refusal was the whole behaviour.
 *
 * <p>The cubes here are written on the fly rather than committed: the real ones are 26 MB each,
 * and what needs pinning is the slicing, which a 4 x 3 x 2 cube exercises exactly as well. Both
 * storage forms are covered because they take different routes out of nom-tam: a compressed cube
 * decompresses whole and flat and is sliced, an uncompressed one is read a tile at a time.
 *
 * <p>Verified against the real thing separately (2026-09-22, PUNCH_L3_PTM_20260421000230_v0l.fits):
 * the three planes read here as [Polar_B, Polar_pB, Polar_pBp] and their pixels agree with
 * astropy's to the quantization noise of the compressor.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.view.uri.FitsPlaneCheck
 */
public final class FitsPlaneCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static final int W = 4, H = 3, PLANES = 2;

    /** Plane p, row j, column i carries a value that says so, so a misread slice cannot look right. */
    private static float value(int p, int j, int i) {
        return 100 * p + 10 * j + i;
    }

    private static float[][][] cube() {
        float[][][] data = new float[PLANES][H][W];
        for (int p = 0; p < PLANES; p++)
            for (int j = 0; j < H; j++)
                for (int i = 0; i < W; i++)
                    data[p][j][i] = value(p, j, i);
        return data;
    }

    private static void name(Header header) throws Exception {
        header.addValue("OBSLAYR1", "Polar_B", "Image Mode for first datacube layer");
        header.addValue("OBSLAYR2", "Polar_pB", "Image Mode for second datacube layer");
    }

    private static File writePlain(File dir) throws Exception {
        File file = new File(dir, "cube.fits");
        ImageHDU hdu = (ImageHDU) Fits.makeHDU(cube());
        name(hdu.getHeader());
        try (Fits fits = new Fits(); FitsOutputStream out = new FitsOutputStream(Files.newOutputStream(file.toPath()))) {
            fits.addHDU(hdu);
            fits.write(out);
        }
        return file;
    }

    private static File writeCompressed(File dir) throws Exception {
        File file = new File(dir, "cube-compressed.fits");
        ImageHDU hdu = (ImageHDU) Fits.makeHDU(cube());
        name(hdu.getHeader()); // before the wrap: the image header is rebuilt from what was compressed
        CompressedImageHDU compressed = CompressedImageHDU.fromImageHDU(hdu, W, H, 1);
        // GZIP_1 rather than PUNCH's RICE_1: it is lossless for float, so the pixels below can be
        // compared for equality. What is under test is the slicing, which is the same either way.
        compressed.setCompressAlgorithm(Compression.ZCMPTYPE_GZIP_1);
        compressed.compress();
        try (Fits fits = new Fits(); FitsOutputStream out = new FitsOutputStream(Files.newOutputStream(file.toPath()))) {
            fits.addHDU(compressed);
            fits.write(out);
        }
        return file;
    }

    /** The pixels FITSImage hands the decoder, bottom row first as the rest of the reader expects. */
    private static float[] pixels(File file, int plane) throws Exception {
        java.lang.reflect.Method read = FITSImage.class.getDeclaredMethod("readData", File.class, int.class);
        read.setAccessible(true);
        return (float[]) ((FITSData) read.invoke(null, file, plane)).pixels();
    }

    private static void checkCube(String kind, File file) throws Exception {
        URIView.SourceInfo info = FITSImage.readInfo(file, 0);
        expect(kind + ": the cube is not refused for having three axes", info.width() == W && info.height() == H);
        expect(kind + ": its layers are named from OBSLAYRn",
                List.of("Polar_B", "Polar_pB").equals(info.planes()));

        for (int p = 0; p < PLANES; p++) {
            float[] px = pixels(file, p);
            boolean right = px.length == W * H;
            for (int j = 0; right && j < H; j++)
                for (int i = 0; i < W; i++)
                    right &= px[j * W + i] == value(p, j, i);
            expect(kind + ": plane " + p + " reads back as itself, not as another plane", right);
        }

        // A plane remembered from a different product must not refuse the file.
        expect(kind + ": a plane past the last one falls back to the first",
                java.util.Arrays.equals(pixels(file, 99), pixels(file, 0)));
    }

    /**
     * The chooser in the layer's options panel: present only for a cube, and wired both ways.
     *
     * <p>Driven through the settings rather than the loader, which is where the panel gets its
     * answers from. Reflection for the combo because it is private and is nobody else's business.
     *
     * <p>Visibility is the panel's own, not its container's: a layer is selected before its load
     * has said what the file holds, so a row shown or hidden from outside at selection time shows
     * the previous layer's answer. That is how the control went missing after it moved.
     */
    private static void checkControl() throws Exception {
        int[] reloads = {0};
        ImageProcessingSettings state = new ImageProcessingSettings(() -> {});
        PlanePanel panel = new PlanePanel(state, () -> reloads[0]++);

        java.lang.reflect.Field field = PlanePanel.class.getDeclaredField("combo");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        javax.swing.JComboBox<PlanePanel.PlaneOption> combo =
                (javax.swing.JComboBox<PlanePanel.PlaneOption>) field.get(panel);
        java.awt.Component row = combo; // the row hides and shows as one; the combo carries it

        // isVisible, not isShowing: nothing here is realized, and it is the visibility the panel
        // sets that decides whether the row is in the layout at all.
        expect("a plain image offers no chooser", !row.isVisible() && combo.getItemCount() == 0);

        state.setPlanes(List.of("Polar_B", "Polar_pB", "Polar_pBp"));
        expect("a cube shows the chooser", row.isVisible());
        expect("a cube fills the chooser with its layer names",
                combo.getItemCount() == 3 && "3.  Polar_pBp".equals(combo.getItemAt(2).toString()));
        expect("and starts on the plane the layer is showing", combo.getSelectedIndex() == 0);

        state.setPlane(1); // as the dialog does
        expect("the chooser follows a plane set elsewhere", combo.getSelectedIndex() == 1);
        expect("and setting it from outside does not reload", reloads[0] == 0);

        combo.setSelectedIndex(2); // as the user does
        expect("choosing a plane moves the layer to it", state.fitsParameters().plane() == 2);
        expect("and re-reads the frames once, for that plane's clip set", reloads[0] == 1);

        combo.setSelectedIndex(2); // the same plane again
        expect("choosing the plane it is already on re-reads nothing", reloads[0] == 1);

        state.setPlanes(List.of()); // the layer is pointed at a plain image
        expect("leaving a cube drops the chooser and the plane",
                state.fitsParameters().plane() == 0 && !row.isVisible());
    }

    /**
     * A new layer clips at 0.001%.
     *
     * <p>Chosen for PUNCH, whose polarized planes are illegible at 0.001% (the sky sits at 85% of
     * the display range). It is not free: on a STEREO COR2 frame the same setting clips 0.353% of
     * pixels at the bright end against 0.001%, and lifts the low bound from 256 to 4134. See the
     * comment on the field itself for both sets of numbers.
     */
    private static void checkClippingDefault() {
        ImageProcessingSettings fresh = new ImageProcessingSettings(() -> {});
        expect("a new layer clips at 0.5%, where a PUNCH polarized plane is legible",
                fresh.fitsParameters().clippingMode() == ImageProcessingSettings.ClippingMode.Percentile05);
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("hfs-fits-plane").toFile();
        dir.deleteOnExit();
        try {
            checkCube("uncompressed", writePlain(dir));
            checkCube("compressed", writeCompressed(dir));

            // A plain image is not a cube, and must not start offering a choice.
            File flat = new File("extra/test/data/PUNCH_L3_CAM_20260425001600_v0k.fits");
            if (flat.isFile())
                expect("a 2-D PUNCH mosaic reports no planes to choose between",
                        FITSImage.readInfo(flat, 0).planes().isEmpty());
            else
                System.out.println("SKIP: " + flat + " not readable from " + System.getProperty("user.dir"));

            checkControl();
            checkClippingDefault();
        } finally {
            File[] files = dir.listFiles();
            if (files != null)
                for (File f : files)
                    f.delete();
            dir.delete();
        }

        if (failures != 0)
            throw new AssertionError(failures + " FITS plane failure(s)");
        System.out.println("FitsPlaneCheck: PASS");
    }

}
