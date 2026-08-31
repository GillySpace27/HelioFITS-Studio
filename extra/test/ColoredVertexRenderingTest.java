package org.helioviewer.jhv.opengl;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
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
import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.opengl.angle.AngleRenderer;

public final class ColoredVertexRenderingTest {

    private static final int SIZE = 256;
    private static final float VIEW_WIDTH = 4;

    public static void main(String[] args) throws Exception {
        if (args.length > 1)
            throw new IllegalArgumentException("usage: ColoredVertexRenderingTest [output.png]");

        initApplication();
        AngleRenderer renderer = AngleRenderer.pbuffer(SIZE, SIZE);
        GLSLLine line = new GLSLLine(true);
        GLSLShape shape = new GLSLShape(true);
        try {
            GLRenderer.reshape(SIZE, SIZE);
            line.init();
            shape.init();

            Viewport vp = Display.getViewport(0);
            GL.glViewport(vp.x, vp.yGL, vp.width, vp.height);
            GL.glClearColor(0, 0, 0, 0);
            GL.glClear(GL.COLOR_BUFFER_BIT | GL.DEPTH_BUFFER_BIT);
            Transform.ortho(vp.aspect, VIEW_WIDTH, 0, 0, Quat.ZERO);

            drawTriangles(shape);
            drawPoints(shape);
            drawLines(line, vp);
            GLException.checkErrors("ColoredVertexRenderingTest.render");

            ByteBuffer pixels = readPixels();
            checkColor(pixels, -1.4f, 1.05f, Colors.Red.bytes(), "first triangle");
            checkColor(pixels, 1.4f, 1.05f, Colors.Green.bytes(), "triangle after replacement upload");
            checkColor(pixels, -1.4f, -1.35f, Colors.Cyan.bytes(), "cyan point");
            checkColor(pixels, 1.4f, -1.35f, Colors.Magenta.bytes(), "magenta point");
            checkColor(pixels, -0.9f, -0.15f, Colors.Yellow.bytes(), "joined polyline");
            checkColor(pixels, 0.45f, 0, Colors.Blue.bytes(), "first disconnected segment");
            checkColor(pixels, 1.35f, 0, Colors.Blue.bytes(), "second disconnected segment");
            checkBlack(pixels, 0.9f, -0.25f, "gap between disconnected segments");

            if (args.length == 1)
                writeImage(pixels, Path.of(args[0]));
        } finally {
            shape.dispose();
            line.dispose();
            renderer.destroy();
        }
        System.out.println("ColoredVertexRenderingTest passed");
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

    private static void drawTriangles(GLSLShape shape) {
        BufVertex vertices = new BufVertex();
        vertices.putVertex(-1.75f, 0.7f, 0, 1, Colors.Red.bytes());
        vertices.putVertex(-1.05f, 0.7f, 0, 1, Colors.Red.bytes());
        vertices.putVertex(-1.4f, 1.4f, 0, 1, Colors.Red.bytes());
        shape.uploadAndClear(vertices);
        shape.renderShape(GL.TRIANGLES);

        vertices.putVertex(1.05f, 0.7f, 0, 1, Colors.Green.bytes());
        vertices.putVertex(1.75f, 0.7f, 0, 1, Colors.Green.bytes());
        vertices.putVertex(1.4f, 1.4f, 0, 1, Colors.Green.bytes());
        shape.uploadAndClear(vertices);
        shape.renderShape(GL.TRIANGLES);
    }

    private static void drawPoints(GLSLShape shape) {
        BufVertex vertices = new BufVertex(2);
        vertices.putVertex(-1.4f, -1.35f, 0, 16, Colors.Cyan.bytes());
        vertices.putVertex(1.4f, -1.35f, 0, 16, Colors.Magenta.bytes());
        shape.uploadAndClear(vertices);
        shape.renderPoints(1);
    }

    private static void drawLines(GLSLLine line, Viewport vp) {
        BufVertex vertices = BufVertex.join(List.of(
                polyline(Colors.Yellow.bytes(), -1.65f, 0.2f, -0.9f, -0.15f, -0.2f, 0.2f),
                polyline(Colors.Blue.bytes(), 0.2f, 0.25f, 0.7f, -0.25f),
                polyline(Colors.Blue.bytes(), 1.1f, -0.25f, 1.6f, 0.25f)));
        line.upload(new DirectBufVertex(vertices));
        line.renderLine(vp, 0.025);
    }

    private static BufVertex polyline(byte[] color, float... coordinates) {
        BufVertex vertices = new BufVertex(coordinates.length / 2 + 2);
        int last = coordinates.length - 2;
        vertices.putVertex(coordinates[0], coordinates[1], 0, 1, Colors.Null);
        vertices.repeatVertex(color);
        for (int i = 2; i < coordinates.length; i += 2)
            vertices.putVertex(coordinates[i], coordinates[i + 1], 0, 1, color);
        vertices.putVertex(coordinates[last], coordinates[last + 1], 0, 1, Colors.Null);
        return vertices;
    }

    private static ByteBuffer readPixels() {
        ByteBuffer pixels = BufferUtils.newByteBuffer(4 * SIZE * SIZE);
        GL.glReadPixels(0, 0, SIZE, SIZE, GL.RGBA, GL.UNSIGNED_BYTE, pixels);
        return pixels;
    }

    private static void checkColor(ByteBuffer pixels, float x, float y, byte[] expected, String label) {
        for (int channel = 0; channel < 3; channel++) {
            int value = maxChannel(pixels, x, y, channel);
            if (expected[channel] == 0)
                check(value <= 20, label + " has unexpected channel " + channel + " value " + value);
            else
                check(value >= 180, label + " channel " + channel + " is " + value);
        }
    }

    private static void checkBlack(ByteBuffer pixels, float x, float y, String label) {
        for (int channel = 0; channel < 3; channel++)
            check(maxChannel(pixels, x, y, channel) == 0, label + " is not empty");
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

    private ColoredVertexRenderingTest() {}
}
