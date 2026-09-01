package org.helioviewer.jhv.opengl;

import java.nio.ByteBuffer;

import org.helioviewer.jhv.app.Log;

import org.lwjgl.system.MemoryUtil;

final class GLFrameCapture {
    private static final int[] DEPTH_FORMATS = {GL.DEPTH_COMPONENT32, GL.DEPTH_COMPONENT24, GL.DEPTH_COMPONENT16};
    private static final int EXPORT_SAMPLES = 4;

    private final int width;
    private final int height;
    private final int samples;

    /**
     * Whether the colour attachment is RGBA16F rather than RGB8.
     *
     * <p>The shaders compute in float and lose precision only where the result is written, so an
     * 8-bit attachment is exactly where a 16-bit source and a smoothly interpolated colour table
     * collapse back to 256 levels. RGBA16F keeps them. It is colour-renderable only with
     * EXT_color_buffer_half_float (or _float), which is why the caller builds this optimistically
     * and falls back to 8 bits rather than failing a recording outright.
     */
    private final boolean highBitDepth;
    private final int readType;
    private final int bytesPerPixel;

    private final int resolveFramebuffer;
    private final int resolveTexture;
    private final int drawFramebuffer;
    private final int drawColorRenderbuffer;
    private final int drawDepthRenderbuffer;
    private final ByteBuffer readback;
    private final byte[] readbackRow;
    private final byte[] outputRow;

    GLFrameCapture(int captureW, int captureH, boolean wantHighBitDepth) {
        int frameWidth = Math.max(1, captureW);
        int frameHeight = Math.max(1, captureH);
        int frameSamples = Math.clamp(EXPORT_SAMPLES, 0, GL.glGetInteger(GL.MAX_SAMPLES));
        int colorInternalFormat = wantHighBitDepth ? GL.RGBA16F : GL.RGB8;
        int colorPixelFormat = wantHighBitDepth ? GL.RGBA : GL.RGB;
        int resolveFbo = 0;
        int resolveTex = 0;
        int drawFbo = 0;
        int drawColorRbo = 0;
        int drawDepthRbo = 0;
        int chosenDepthFormat;
        int chosenReadType = GL.UNSIGNED_BYTE;
        int pixelBytes = 3;
        ByteBuffer buffer = null;

        try {
            resolveFbo = GL.glGenFramebuffer();
            GL.glBindFramebuffer(GL.FRAMEBUFFER, resolveFbo);

            resolveTex = GL.glGenTexture();
            GL.glBindTexture(GL.TEXTURE_2D, resolveTex);
            GL.glTexParameteri(GL.TEXTURE_2D, GL.TEXTURE_MAG_FILTER, GL.LINEAR);
            GL.glTexParameteri(GL.TEXTURE_2D, GL.TEXTURE_MIN_FILTER, GL.LINEAR);
            GL.glTexParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_S, GL.CLAMP_TO_EDGE);
            GL.glTexParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_T, GL.CLAMP_TO_EDGE);
            GL.glTexImage2D(GL.TEXTURE_2D, 0, colorInternalFormat, frameWidth, frameHeight, 0, colorPixelFormat,
                    wantHighBitDepth ? GL.HALF_FLOAT : GL.UNSIGNED_BYTE, (ByteBuffer) null);
            GL.glFramebufferTexture2D(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, GL.TEXTURE_2D, resolveTex, 0);
            checkFramebufferComplete("resolve"); // fails here if RGBA16F is not colour-renderable

            if (frameSamples > 0) {
                drawFbo = GL.glGenFramebuffer();
                GL.glBindFramebuffer(GL.FRAMEBUFFER, drawFbo);

                drawColorRbo = GL.glGenRenderbuffer();
                GL.glBindRenderbuffer(GL.RENDERBUFFER, drawColorRbo);
                GL.glRenderbufferStorageMultisample(GL.RENDERBUFFER, frameSamples, colorInternalFormat, frameWidth, frameHeight);
                GL.glFramebufferRenderbuffer(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, GL.RENDERBUFFER, drawColorRbo);

                drawDepthRbo = GL.glGenRenderbuffer();
                chosenDepthFormat = attachDepthRenderbuffer(frameWidth, frameHeight, frameSamples, drawDepthRbo);

                GL.glBindFramebuffer(GL.FRAMEBUFFER, resolveFbo);
                checkFramebufferComplete("resolve");
            } else {
                drawFbo = resolveFbo;

                drawDepthRbo = GL.glGenRenderbuffer();
                chosenDepthFormat = attachDepthRenderbuffer(frameWidth, frameHeight, 0, drawDepthRbo);
            }

            if (wantHighBitDepth) {
                // readPixels guarantees only RGBA/FLOAT for a float attachment; HALF_FLOAT is
                // commonly offered as the implementation pair and halves the transfer, so take
                // it when the implementation names it and fall back to FLOAT otherwise.
                GL.glBindFramebuffer(GL.FRAMEBUFFER, resolveFbo);
                chosenReadType = GL.glGetInteger(GL.IMPLEMENTATION_COLOR_READ_TYPE) == GL.HALF_FLOAT
                        ? GL.HALF_FLOAT : GL.FLOAT;
                pixelBytes = 6; // rgb48le
            }

            int readBytes = chosenReadType == GL.UNSIGNED_BYTE ? 4 : chosenReadType == GL.HALF_FLOAT ? 8 : 16;
            buffer = MemoryUtil.memAlloc(frameWidth * frameHeight * readBytes);
            readbackRow = new byte[frameWidth * readBytes];
            outputRow = new byte[frameWidth * pixelBytes];
        } catch (RuntimeException e) {
            if (drawDepthRbo != 0)
                GL.glDeleteRenderbuffer(drawDepthRbo);
            if (drawColorRbo != 0)
                GL.glDeleteRenderbuffer(drawColorRbo);
            if (drawFbo != resolveFbo)
                GL.glDeleteFramebuffer(drawFbo);
            if (resolveTex != 0)
                GL.glDeleteTexture(resolveTex);
            if (resolveFbo != 0)
                GL.glDeleteFramebuffer(resolveFbo);
            if (buffer != null)
                MemoryUtil.memFree(buffer);
            throw e;
        } finally {
            GL.glBindRenderbuffer(GL.RENDERBUFFER, 0);
            GL.glBindTexture(GL.TEXTURE_2D, 0);
            GL.glBindFramebuffer(GL.FRAMEBUFFER, 0);
        }

        resolveFramebuffer = resolveFbo;
        resolveTexture = resolveTex;
        drawFramebuffer = drawFbo;
        drawColorRenderbuffer = drawColorRbo;
        drawDepthRenderbuffer = drawDepthRbo;
        width = frameWidth;
        height = frameHeight;
        samples = frameSamples;
        highBitDepth = wantHighBitDepth;
        readType = chosenReadType;
        bytesPerPixel = pixelBytes;
        readback = buffer;
        int depthFormat = chosenDepthFormat;
        Log.info("GLFrameCapture config: size=" + width + "x" + height
                + " samples=" + samples
                + " depth=" + depthBits(depthFormat)
                + " color=" + (highBitDepth ? "RGBA16F -> rgb48le" : "RGB8 -> rgb24"));
    }

    int bytesPerPixel() {
        return bytesPerPixel;
    }

    void bindForRender() {
        GL.glBindFramebuffer(GL.FRAMEBUFFER, drawFramebuffer);
    }

    private void resolveAndRead() {
        if (samples > 0) {
            GL.glBindFramebuffer(GL.READ_FRAMEBUFFER, drawFramebuffer);
            GL.glBindFramebuffer(GL.DRAW_FRAMEBUFFER, resolveFramebuffer);
            GL.glBlitFramebuffer(0, 0, width, height,
                    0, 0, width, height,
                    GL.COLOR_BUFFER_BIT, GL.NEAREST);
        }

        GL.glBindFramebuffer(GL.READ_FRAMEBUFFER, resolveFramebuffer);
        GL.glPixelStorei(GL.PACK_ALIGNMENT, 1);
        readback.clear();
        GL.glReadPixels(0, 0, width, height, GL.RGBA, readType, readback);
        readback.limit(readback.capacity());
    }

    /**
     * RGBA as floats, top row first (GL reads bottom-up, image files go top-down), and exactly
     * what the target holds: no clamp, no quantization. On an RGBA16F target that is the half
     * value itself, so writing it back out as half is lossless.
     */
    float[] readFloats() {
        resolveAndRead();
        int comp = readType == GL.UNSIGNED_BYTE ? 1 : readType == GL.HALF_FLOAT ? 2 : 4;
        float[] out = new float[width * height * 4];
        for (int y = 0; y < height; y++) {
            readback.get(readbackRow);
            int dst = (height - 1 - y) * width * 4;
            for (int i = 0; i < width * 4; i++) {
                int off = i * comp;
                out[dst + i] = switch (comp) {
                    case 1 -> (readbackRow[off] & 0xFF) / 255f;
                    case 2 -> Float.float16ToFloat((short) ((readbackRow[off] & 0xFF) | (readbackRow[off + 1] << 8)));
                    default -> Float.intBitsToFloat((readbackRow[off] & 0xFF) | ((readbackRow[off + 1] & 0xFF) << 8)
                            | ((readbackRow[off + 2] & 0xFF) << 16) | (readbackRow[off + 3] << 24));
                };
            }
        }
        GL.glBindFramebuffer(GL.FRAMEBUFFER, 0);
        return out;
    }

    void readPixels(ByteBuffer buffer) {
        resolveAndRead();

        buffer.clear();
        for (int y = 0; y < height; y++) {
            readback.get(readbackRow);
            if (highBitDepth)
                packRow16(readbackRow);
            else
                packRow8(readbackRow);
            buffer.put(outputRow);
        }
        buffer.flip();
        GL.glBindFramebuffer(GL.FRAMEBUFFER, 0);
    }

    // RGBA8 -> rgb24, dropping alpha.
    private void packRow8(byte[] row) {
        int src = 0, dst = 0;
        for (int x = 0; x < width; x++) {
            outputRow[dst++] = row[src++];
            outputRow[dst++] = row[src++];
            outputRow[dst++] = row[src++];
            src++;
        }
    }

    // RGBA float -> rgb48le. The scene is display-referred and already in [0, 1], so out-of-range
    // values are clamped rather than tone-mapped: they were clipped on screen too.
    private void packRow16(byte[] row) {
        int componentBytes = readType == GL.HALF_FLOAT ? 2 : 4;
        int dst = 0;
        for (int x = 0; x < width; x++) {
            int base = x * 4 * componentBytes;
            for (int ch = 0; ch < 3; ch++) {
                int off = base + ch * componentBytes;
                float v = componentBytes == 2
                        ? Float.float16ToFloat((short) ((row[off] & 0xFF) | (row[off + 1] << 8)))
                        : Float.intBitsToFloat((row[off] & 0xFF) | ((row[off + 1] & 0xFF) << 8)
                                | ((row[off + 2] & 0xFF) << 16) | (row[off + 3] << 24));
                int q = (int) (Math.clamp(v, 0f, 1f) * 65535 + 0.5f);
                outputRow[dst++] = (byte) q;         // little endian, to match rgb48le
                outputRow[dst++] = (byte) (q >>> 8);
            }
        }
    }

    void dispose() {
        if (drawDepthRenderbuffer != 0)
            GL.glDeleteRenderbuffer(drawDepthRenderbuffer);
        if (drawColorRenderbuffer != 0)
            GL.glDeleteRenderbuffer(drawColorRenderbuffer);
        if (drawFramebuffer != resolveFramebuffer)
            GL.glDeleteFramebuffer(drawFramebuffer);
        if (resolveTexture != 0)
            GL.glDeleteTexture(resolveTexture);
        if (resolveFramebuffer != 0)
            GL.glDeleteFramebuffer(resolveFramebuffer);
        if (readback != null)
            MemoryUtil.memFree(readback);
    }

    private static void checkFramebufferComplete(String label) {
        int status = GL.glCheckFramebufferStatus(GL.FRAMEBUFFER);
        if (status != GL.FRAMEBUFFER_COMPLETE)
            throw new GLException("GLFrameCapture " + label + " framebuffer incomplete: 0x" + Integer.toHexString(status));
    }

    private static int attachDepthRenderbuffer(int width, int height, int samples, int renderbuffer) {
        GL.glBindRenderbuffer(GL.RENDERBUFFER, renderbuffer);
        for (int depthFormat : DEPTH_FORMATS) {
            if (samples > 0)
                GL.glRenderbufferStorageMultisample(GL.RENDERBUFFER, samples, depthFormat, width, height);
            else
                GL.glRenderbufferStorage(GL.RENDERBUFFER, depthFormat, width, height);
            GL.glFramebufferRenderbuffer(GL.FRAMEBUFFER, GL.DEPTH_ATTACHMENT, GL.RENDERBUFFER, renderbuffer);
            if (GL.glCheckFramebufferStatus(GL.FRAMEBUFFER) == GL.FRAMEBUFFER_COMPLETE)
                return depthFormat;
        }

        checkFramebufferComplete("draw");
        return 0;
    }

    private static int depthBits(int depthFormat) {
        return switch (depthFormat) {
            case GL.DEPTH_COMPONENT32 -> 32;
            case GL.DEPTH_COMPONENT24 -> 24;
            default -> 16;
        };
    }

}
