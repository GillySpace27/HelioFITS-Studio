package org.helioviewer.jhv.opengl;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import org.helioviewer.jhv.image.ImageBuffer;

final class GLStreamingTexture2D {

    private final GLTexture texture;
    private final int filter;
    private final GLBO pbo;

    private int previousWidth = -1;
    private int previousHeight = -1;
    private GLTexture.Format previousFormat;

    GLStreamingTexture2D(GLTexture.Unit _unit, int _filter) {
        texture = new GLTexture(GL.TEXTURE_2D, _unit);
        filter = _filter;
        pbo = new GLBO(GL.PIXEL_UNPACK_BUFFER, GL.STREAM_DRAW);
    }

    void bind() {
        texture.bind();
    }

    void delete() {
        texture.delete();
        pbo.delete();
    }

    void upload(ImageBuffer image) {
        int width = image.width;
        int height = image.height;
        if (GLTexture.invalid2DSize(width, height))
            return;

        GLTexture.Format format = textureFormat(image.format);
        if (width != previousWidth || height != previousHeight || format != previousFormat) {
            texture.upload2D(format, width, height, filter, null);
            previousWidth = width;
            previousHeight = height;
            previousFormat = format;
        } else {
            texture.bind();
        }

        GL.glPixelStorei(GL.UNPACK_ALIGNMENT, format.alignment);
        GL.glPixelStorei(GL.UNPACK_ROW_LENGTH, width);
        switch (image.format) {
            case Gray8, RGBA32 -> pbo.setBufferData((ByteBuffer) image.buffer);
            case Gray16F -> pbo.setBufferData((ShortBuffer) image.buffer);
        }
        GL.glTexSubImage2D(GL.TEXTURE_2D, 0, 0, 0, width, height, format.inputFormat, format.inputType, 0L);
        GL.glBindBuffer(GL.PIXEL_UNPACK_BUFFER, 0);
    }

    private static GLTexture.Format textureFormat(ImageBuffer.Format format) {
        return switch (format) {
            case Gray8 -> GLTexture.Format.R8;
            case Gray16F -> GLTexture.Format.R16F;
            case RGBA32 -> GLTexture.Format.RGBA8;
        };
    }
}
