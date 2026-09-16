package org.helioviewer.jhv.opengl;

import java.nio.ByteBuffer;

import javax.annotation.Nullable;

import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.HdrGain;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageDisplaySettings;
import org.helioviewer.jhv.image.ImageDisplaySettings.DifferenceMode;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.image.lut.LUTLabels;
import org.helioviewer.jhv.metadata.DetectorMask;
import org.helioviewer.jhv.metadata.MetaData;

public class GLSLImage {

    /**
     * What an export pass wants from this layer instead of its on-screen colour. DISPLAY is the
     * value the colour table was indexed with (Levels, radial gain, sharpen and upsilon applied),
     * so colour = lut[V] reproduces the screen; DATA is the decoded value with every slider left
     * to the file's metadata, only the difference mode surviving because it changes what is being
     * shown rather than how.
     */
    public enum Capture {
        NONE, DISPLAY, DATA
    }

    // A process-wide flag rather than a parameter threaded through five render signatures. Only
    // the export sets it, on the GL thread, and resets it in a finally.
    public static Capture capture = Capture.NONE;

    private final ImageDisplaySettings settings;

    private GLStreamingTexture2D tex;
    private GLStreamingTexture2D diffTex;
    private GLStreamingTexture2D maskTex;
    private GLTexture lutTex;

    private LUT lastLut;
    private boolean lastInverted;
    private DetectorMask uploadedMask = DetectorMask.NONE;
    private ImageBuffer uploadedImageBuffer;
    private ImageBuffer uploadedDiffBuffer;

    public GLSLImage(ImageDisplaySettings _settings) {
        settings = _settings;
    }

    public void streamImages(ImageBuffer imageBuffer, @Nullable ImageBuffer differenceBuffer) {
        if (uploadedImageBuffer != imageBuffer) {
            tex.upload(imageBuffer);
            uploadedImageBuffer = imageBuffer;
        }
        if (differenceBuffer != null && uploadedDiffBuffer != differenceBuffer) {
            diffTex.upload(differenceBuffer);
            uploadedDiffBuffer = differenceBuffer;
        }
    }

    private final float[] color = new float[4];

    public void applyFilters(ImageBuffer imageBuffer, MetaData metaData, boolean rhefActive) {
        applyFilters(imageBuffer, metaData, rhefActive, false);
    }

    /**
     * @param legend bind this layer's display state for the colour-table legend rather than for
     *               the picture: no sharpen (its taps assume the image's pixel pitch), no dither
     *               (a legend is not banded and should not be noisy), no clipping flags (they
     *               would paint the bar's ends, which are the very values the bar is there to
     *               name). Everything else, Levels, response, HDR gain, mode and knee, is exactly
     *               the picture's, which is the point. The caller binds its own image and mask.
     */
    public void applyFilters(ImageBuffer imageBuffer, MetaData metaData, boolean rhefActive, boolean legend) {
        float userSectorCenter = 0;
        float userSectorHalfWidth = 0;
        if (settings.getSectorWidth() != 0) {
            userSectorCenter = (float) Math.toRadians(settings.getSectorCenter());
            userSectorHalfWidth = (float) Math.toRadians(settings.getSectorWidth() / 2);
        }
        double metadataHalfWidth = metadataSectorHalfWidth(metaData);
        float metadataSectorCenter = (float) metadataSectorCenter(metaData, metadataHalfWidth);

        boolean raw = capture != Capture.NONE, data = capture == Capture.DATA;
        // Opacity, blend and the channel toggles are compositing parameters: a layer written on
        // its own carries them in its metadata, not in its pixels.
        // The legend is drawn at full opacity whatever the layer's own: opacity and blend are how
        // the picture is composited over what lies beneath it, not part of what a value means.
        boolean plain = raw || legend;
        color[0] = plain ? 1 : (float) (settings.getOpacity() * settings.getRedScale()); // premultiplied alpha
        color[1] = plain ? 1 : (float) (settings.getOpacity() * settings.getGreenScale());
        color[2] = plain ? 1 : (float) (settings.getOpacity() * settings.getBlueScale());
        color[3] = plain ? 1 : (float) (settings.getOpacity() * settings.getBlend());

        GLSLImageShader.bindDisplay(color,
                1f / imageBuffer.width, 1f / imageBuffer.height,
                data || legend ? 0 : (float) (-2 * settings.getSharpen()), settings.getDifferenceMode().ordinal(),
                // RHEF output is already a normalized rank in [0, 1]; the raw-DN response
                // factor must NOT rescale it (that pushes the uniform upper half past 1 and
                // clamps it to white). The user's Levels (brightOffset/brightScale) still
                // apply as a black/white-point control on the equalized output.
                data ? 0 : (float) settings.getBrightOffset(),
                data ? 1 : (float) (settings.getBrightScale() * (rhefActive ? 1 : metaData.getResponseFactor())),
                (float) (rhefActive && !data ? settings.getUpsilonLow() : 1),
                (float) (rhefActive && !data ? settings.getUpsilonHigh() : 1),
                userSectorCenter, userSectorHalfWidth, metadataSectorCenter, (float) metadataHalfWidth,
                metaData.getCutOffX(), metaData.getCutOffY(), metaData.getCutOffValue(), metaData.getCalculateDepth() ? 1 : 0,
                // Both masks are stored in solar radii, with an infinite outer mask meaning "no
                // outer mask at all"; RangeSliderFilterPanel is what presents them as a fraction
                // of the layer's own outer radius. Min against the metadata radius so the mask
                // can only ever tighten what the file already declares.
                Math.max(metaData.getInnerRadius(), (float) settings.getInnerMask()),
                Math.min(Display.getShowCorona() ? metaData.getOuterRadius() : 1, (float) settings.getOuterMask()),
                (float) settings.getSlitLeft(), (float) settings.getSlitRight(),
                data ? 0 : (float) settings.getEnhanced(),
                LUTLabels.isCategorical(settings.getLUT()) ? 1 : 0,
                Display.skipDither() || legend ? 1 : 0,
                Display.showClipping && !raw && !legend ? 1 : 0,
                raw ? 1 : 0,
                HdrGain.current(raw), HdrGain.mode().ordinal(), HdrGain.knee(), HdrGain.inRange());

        applyLUT();
        if (legend)
            return; // the legend binds its own ramp on unit ZERO and a blank mask on unit THREE
        applyMask(metaData.getDetectorMask());
        maskTex.bind();
        tex.bind();
        if (settings.getDifferenceMode() != DifferenceMode.None)
            diffTex.bind();
    }

    private void applyLUT() {
        lutTex.bind();
        LUT currlut = settings.getDifferenceMode() == DifferenceMode.None ? settings.getLUT() : LUT.gray();
        boolean inverted = settings.getInvertLUT();
        if (lastLut != currlut || inverted != lastInverted) {
            ByteBuffer lutBuffer = inverted ? currlut.rgbaInv() : currlut.rgba();
            lastLut = currlut;
            lastInverted = inverted;

            // LINEAR for a continuous ramp, NEAREST for a categorical one.
            //
            // The table is 256 entries of 8-bit RGBA, so NEAREST caps the whole renderer at 256
            // colours no matter how much precision the data carried in (FITS arrives as 16-bit
            // half-float and survives the shader's arithmetic intact), which is why a dither has
            // to be added before the lookup to break up the banding. Interpolating between
            // entries lifts that ceiling: the ramp becomes continuous and the dither is only
            // needed for an 8-bit destination.
            //
            // Categorical tables must keep NEAREST. Their pixel value SELECTS an entry rather
            // than positioning on a ramp, so a blend of two entries is a colour that means
            // nothing, a mix of two channel polarities, say. Same reason the shader already
            // refuses to dither them. Keyed on the table actually being uploaded, which in
            // difference mode is grey rather than the layer's own.
            // Half-entry note: sampling at `value` rather than at the texel centre shifts the
            // ramp by 1/512 under LINEAR. Left alone deliberately: it is invisible on a ramp,
            // and correcting it would change which entry a categorical lookup lands on.
            int filter = LUTLabels.isCategorical(currlut) ? GL.NEAREST : GL.LINEAR;
            lutTex.upload2D(GLTexture.Format.RGBA8, lutBuffer.remaining() / 4, 1, filter, lutBuffer);
        }
    }

    public void init() {
        if (tex != null)
            return;
        try {
            // Image pixels are never interpolated: what is drawn are the samples the data has. A
            // smoothing filter invents values between them, and faint small-scale coronal
            // structure is exactly what an invented value imitates. It also keeps a categorical
            // LUT from ever sampling a blended half-index between two category IDs.
            tex = new GLStreamingTexture2D(GLTexture.Unit.ZERO, GL.NEAREST);
            lutTex = new GLTexture(GL.TEXTURE_2D, GLTexture.Unit.ONE);
            diffTex = new GLStreamingTexture2D(GLTexture.Unit.TWO, GL.NEAREST);
            maskTex = new GLStreamingTexture2D(GLTexture.Unit.THREE, GL.NEAREST);
            maskTex.upload(uploadedMask.getImageBuffer());
        } catch (RuntimeException | Error e) {
            dispose();
            throw e;
        }
    }

    public void dispose() {
        if (tex != null)
            tex.delete();
        if (lutTex != null)
            lutTex.delete();
        if (diffTex != null)
            diffTex.delete();
        if (maskTex != null)
            maskTex.delete();
        tex = null;
        lutTex = null;
        diffTex = null;
        maskTex = null;
        uploadedImageBuffer = null;
        uploadedDiffBuffer = null;
        uploadedMask = DetectorMask.NONE;
        lastLut = null;
    }

    private void applyMask(DetectorMask detectorMask) {
        if (uploadedMask == detectorMask)
            return;
        maskTex.upload(detectorMask.getImageBuffer());
        uploadedMask = detectorMask;
    }

    private static double metadataSectorCenter(MetaData metaData, double halfWidth) {
        if (halfWidth == 0)
            return 0;
        double center = metaData.getSector1() + halfWidth;
        return (center + 3 * Math.PI) % (2 * Math.PI) - Math.PI;
    }

    private static double metadataSectorHalfWidth(MetaData metaData) {
        float start = metaData.getSector0();
        float end = metaData.getSector1();
        return start == end ? 0 : Math.PI + (start - end) / 2;
    }

}
