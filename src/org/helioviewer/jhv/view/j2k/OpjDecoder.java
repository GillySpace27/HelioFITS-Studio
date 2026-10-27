package org.helioviewer.jhv.view.j2k;

import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.thread.DecodePool;
import org.helioviewer.jhv.view.j2k.opj.OpenJpeg;

/**
 * Decoding one requested piece of one frame, with OpenJPEG in place of Kakadu.
 *
 * <p>The viewer asks for a rectangle of a frame at a resolution level: the part of the picture on
 * screen, no finer than the screen can show. JPEG 2000 addresses a region in the image's own
 * full-size coordinates whatever the level, so the rectangle is scaled up by the level before it
 * is handed over, and what comes back is that same part of the picture, smaller.
 *
 * <p>ponytail: the fractional scale Kakadu's compositor applied on top of the level is dropped
 * here, so a frame can arrive with more pixels than the screen strictly needs and the renderer
 * scales it the rest of the way. The alternative is resampling every frame in Java to save a
 * texture upload; measure before adding it.
 */
record OpjDecoder(OpjSource src, J2KParams.Decode params, int numComps, ImageFilter.Type filterType,
                  MetaData metaData, double factorX, double factorY) implements Callable<DecodedImage> {

    @Override
    public DecodedImage call() {
        byte[] codestream = src.codestream(params.frame);
        if (codestream == null)
            throw new CancellationException("Frame " + params.frame + " has not arrived yet");
        if (numComps >= 3)
            throw new UnsupportedOperationException("colour images are not decoded here yet, " + numComps + " components");

        int[] area = area(params.subImage, params.level);
        J2KParams.SubImage sub = params.subImage;
        // The samples are converted where OpenJPEG left them: no int[] copy of the plane, and no
        // per-pixel ByteBuffer.put (60% of the Java CPU in a 45 s profile, with a 4 B/pixel allocation each decode).
        int threads = DecodePool.begin(); // this decode's share of native threads, given how many run at once
        try {
            return OpenJpeg.decode(codestream, false, params.level, threads, area[0], area[1], area[2], area[3], plane -> {
                Region imageRegion = metaData.roiToRegion(sub.x(), sub.y(), plane.width(), plane.height(), factorX, factorY);
                ImageFilter filter = ImageFilter.of(filterType, imageRegion, metaData);
                ImageBuffer.WriteBuffer outBuffer = ImageBuffer.createWriteBuffer(plane.width(), plane.height(), ImageBuffer.Format.Gray8, filter);
                plane.gray8(outBuffer.byteBuffer());
                return DecodedImage.measured(outBuffer.finish(), imageRegion);
            });
        } finally {
            DecodePool.end();
        }
    }

    /**
     * The requested rectangle in the image's own full-size coordinates.
     *
     * <p>The viewer asks in the coordinates of the level it wants; JPEG 2000 answers in the
     * coordinates of the image. Getting this wrong decodes the wrong part of the picture at every
     * level but the top one, where the two happen to agree.
     */
    static int[] area(J2KParams.SubImage sub, int level) {
        int scale = 1 << level;
        int x0 = sub.x() * scale, y0 = sub.y() * scale;
        return new int[]{x0, y0, x0 + sub.w() * scale, y0 + sub.h() * scale};
    }

}
