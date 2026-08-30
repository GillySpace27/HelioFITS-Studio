package org.helioviewer.jhv.opengl;

import org.helioviewer.jhv.image.ImageBuffer;

final class GLStreamingTexture2D {

    private final GLTexture texture;
    private GLBO pbo;

    private int previousWidth = -1;
    private int previousHeight = -1;
    private int previousFilter = -1;
    private GLTexture.Format previousFormat;

    GLStreamingTexture2D(GLTexture.Unit unit) {
        texture = new GLTexture(GL.TEXTURE_2D, unit);
    }

    void bind() {
        texture.bind();
    }

    void delete() {
        texture.delete();
        if (pbo != null) {
            pbo.delete();
            pbo = null;
        }
    }

    void upload(ImageBuffer image, int filter) {
        int width = image.width;
        int height = image.height;
        if (!GLTexture.valid2DSize(width, height))
            return;

        GLTexture.Format format = textureFormat(image.format);
        if (width != previousWidth || height != previousHeight || filter != previousFilter || format != previousFormat) {
            texture.upload2D(format, width, height, filter, null);
            previousWidth = width;
            previousHeight = height;
            previousFilter = filter;
            previousFormat = format;
        } else {
            texture.bind();
        }

        GL.glPixelStorei(GL.UNPACK_ALIGNMENT, format.alignment);
        GL.glPixelStorei(GL.UNPACK_ROW_LENGTH, width);
        if (pbo == null)
            pbo = new GLBO(GL.PIXEL_UNPACK_BUFFER, GL.STREAM_DRAW);
        pbo.setBufferData(image.byteSize(), image.buffer);
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
