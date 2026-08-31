package org.helioviewer.jhv.opengl;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.imageio.ImageIO;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.base.BufferUtils;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.opengl.angle.AngleRenderer;
import org.helioviewer.jhv.opengl.model.ModelMaterial;
import org.helioviewer.jhv.opengl.model.ModelMesh;
import org.helioviewer.jhv.opengl.model.ModelScene;
import org.helioviewer.jhv.time.TimeUtils;

public final class ModelRenderingTest {

    private static final int SIZE = 256;
    private static final float VIEW_WIDTH = 4;

    public static void main(String[] args) throws Exception {
        if (args.length > 1)
            throw new IllegalArgumentException("usage: ModelRenderingTest [output.png]");

        initApplication();
        AngleRenderer renderer = AngleRenderer.pbuffer(SIZE, SIZE);
        GLSLModel model = new GLSLModel(testScene());
        GLSLModel background = new GLSLModel(backgroundScene());
        try {
            GLRenderer.reshape(SIZE, SIZE);
            model.init();
            background.init();

            Viewport vp = Display.getViewport(0);
            MapView mv = GLRenderer.getMapView();
            // At the default test scale the point is entirely antialiased fringe, so enlarge it to exercise its depth-writing core.
            vp.zoom = 0.25;
            GL.glViewport(vp.x, vp.yGL, vp.width, vp.height);
            GL.glClearColor(0, 0, 0, 0);
            GL.glClear(GL.COLOR_BUFFER_BIT | GL.DEPTH_BUFFER_BIT);
            Transform.ortho(vp.aspect, VIEW_WIDTH, 0, 0, Quat.ZERO);
            model.render(mv, vp);
            GLException.checkErrors("ModelRenderingTest.model");

            ByteBuffer pixels = readPixels();
            checkBlack(pixels, -1.5f, 0.6f, "single-sided back face");
            checkChannel(pixels, 1.5f, 0.6f, 2, 240, 255, "double-sided back face");
            checkPremultiplied(pixels, -1.5f, -1, 0, 120, 136, "blended triangle");
            checkPremultiplied(pixels, 0, 0, 1, 10, 40, "blended line");
            checkChannel(pixels, 1.4f, -1, 2, 240, 255, "opaque point");

            if (args.length == 1)
                writeImage(pixels, Path.of(args[0]));

            background.render(mv, vp);
            GLException.checkErrors("ModelRenderingTest.depth");
            checkChannel(readPixels(), 1.4f, -1, 2, 240, 255, "point depth");
        } finally {
            background.dispose();
            model.dispose();
            renderer.destroy();
        }
        System.out.println("ModelRenderingTest passed");
    }

    private static void initApplication() throws Exception {
        System.setProperty("user.timezone", TimeZone.getDefault().getID());
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Locale.setDefault(Locale.US);
        Platform.init();
        Directories.createPersistentDirs();
        Log.init();
        Directories.createCacheDirs();
        AppInit.loadSpice();
    }

    private static ModelScene testScene() {
        List<ModelMaterial> materials = List.of(
                material(0, 1, 0, 1, ModelMaterial.AlphaMode.OPAQUE, false, false),
                material(0, 0, 1, 1, ModelMaterial.AlphaMode.OPAQUE, true, false),
                material(1, 0, 0, 0.5f, ModelMaterial.AlphaMode.BLEND, true, true),
                material(0, 1, 0, 0.5f, ModelMaterial.AlphaMode.BLEND, true, true),
                material(0, 0, 1, 0.5f, ModelMaterial.AlphaMode.OPAQUE, true, true));
        return new ModelScene("rendering-test", TimeUtils.START, List.of(
                triangle("culled", -1.8f, 0.3f, -1.2f, 1.1f, 0, 0, true, true),
                triangle("double-sided", 1.2f, 0.3f, 1.8f, 1.1f, 0, 1, true, true),
                triangle("blended", -1.8f, -1.4f, -1.2f, -0.4f, 0, 2, false, false),
                line(),
                point()), materials, List.of());
    }

    private static ModelScene backgroundScene() {
        ModelMaterial material = material(1, 1, 0, 1, ModelMaterial.AlphaMode.OPAQUE, true, true);
        ModelMesh mesh = triangle("background", 1.05f, -1.3f, 1.75f, -0.6f, 0, 0, false, false);
        return new ModelScene("background", TimeUtils.START, List.of(mesh), List.of(material), List.of());
    }

    private static ModelMaterial material(float red, float green, float blue, float alpha, ModelMaterial.AlphaMode alphaMode,
                                          boolean doubleSided, boolean unlit) {
        return new ModelMaterial(red, green, blue, alpha, ModelMaterial.NO_TEXTURE, alphaMode, 0.5f, doubleSided, unlit);
    }

    private static ModelMesh triangle(String name, float left, float bottom, float right, float top, float z, int materialIndex,
                                      boolean reverseWinding, boolean withNormals) {
        FloatBuffer positions = floats(left, bottom, z, right, bottom, z, (left + right) / 2, top, z);
        float normalZ = reverseWinding ? -1 : 1;
        FloatBuffer normals = withNormals ? floats(0, 0, normalZ, 0, 0, normalZ, 0, 0, normalZ) : null;
        IntBuffer indices = reverseWinding ? ints(0, 2, 1) : ints(0, 1, 2);
        return new ModelMesh(name, ModelMesh.Primitive.TRIANGLES, positions, normals, white(3), null, indices, ints(), materialIndex);
    }

    private static ModelMesh line() {
        return new ModelMesh("line", ModelMesh.Primitive.LINES, floats(-0.5f, 0, 0, 0.5f, 0, 0), null, white(2), null,
                ints(0, 1), ints(0, 2), 3);
    }

    private static ModelMesh point() {
        return new ModelMesh("point", ModelMesh.Primitive.POINTS, floats(1.4f, -1, 1), null, white(1), null, ints(0), ints(), 4);
    }

    private static FloatBuffer floats(float... values) {
        return BufferUtils.newFloatBuffer(values.length).put(values).flip();
    }

    private static IntBuffer ints(int... values) {
        return BufferUtils.newIntBuffer(values.length).put(values).flip();
    }

    private static ByteBuffer white(int vertices) {
        ByteBuffer colors = BufferUtils.newByteBuffer(4 * vertices);
        for (int i = 0; i < vertices; i++)
            colors.putInt(-1);
        return colors.flip();
    }

    private static ByteBuffer readPixels() {
        ByteBuffer pixels = BufferUtils.newByteBuffer(4 * SIZE * SIZE);
        GL.glReadPixels(0, 0, SIZE, SIZE, GL.RGBA, GL.UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static void checkBlack(ByteBuffer pixels, float x, float y, String label) {
        for (int channel = 0; channel < 3; channel++)
            check(maxChannel(pixels, x, y, channel) == 0, label + " is visible");
    }

    private static void checkChannel(ByteBuffer pixels, float x, float y, int channel, int minimum, int maximum, String label) {
        int value = maxChannel(pixels, x, y, channel);
        check(value >= minimum && value <= maximum, label + " channel is " + value);
    }

    private static void checkPremultiplied(ByteBuffer pixels, float x, float y, int channel, int minimum, int maximum, String label) {
        int color = maxChannel(pixels, x, y, channel);
        int alpha = maxChannel(pixels, x, y, 3);
        check(color >= minimum && color <= maximum, label + " channel is " + color);
        check(Math.abs(color - alpha) <= 1, label + " is not premultiplied: color=" + color + ", alpha=" + alpha);
    }

    private static int maxChannel(ByteBuffer pixels, float x, float y, int channel) {
        int centerX = Math.round(SIZE * (0.5f + x / VIEW_WIDTH));
        int centerY = Math.round(SIZE * (0.5f + y / VIEW_WIDTH));
        int maximum = 0;
        for (int py = centerY - 2; py <= centerY + 2; py++) {
            for (int px = centerX - 2; px <= centerX + 2; px++)
                maximum = Math.max(maximum, pixels.get(4 * (py * SIZE + px) + channel) & 0xff);
        }
        return maximum;
    }

    private static void writeImage(ByteBuffer pixels, Path output) throws Exception {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int offset = 4 * (y * SIZE + x);
                int rgb = (pixels.get(offset) & 0xff) << 16 | (pixels.get(offset + 1) & 0xff) << 8 | pixels.get(offset + 2) & 0xff;
                image.setRGB(x, SIZE - 1 - y, rgb);
            }
        }
        Path absolute = output.toAbsolutePath().normalize();
        if (absolute.getParent() != null)
            Files.createDirectories(absolute.getParent());
        check(ImageIO.write(image, "png", absolute.toFile()), "PNG writer unavailable");
        System.out.println("Rendered image: " + absolute);
    }

    private static void check(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    private ModelRenderingTest() {}

}
