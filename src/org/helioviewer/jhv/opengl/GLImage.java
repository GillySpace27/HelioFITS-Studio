package org.helioviewer.jhv.opengl;

import java.nio.ByteBuffer;
import java.util.Set;

import org.helioviewer.jhv.base.BufferUtils;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.Interpolation;
import org.helioviewer.jhv.display.HdrGain;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.lut.LUT;
import org.helioviewer.jhv.image.lut.LUTLabels;
import org.helioviewer.jhv.metadata.DetectorMask;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.wcs.ImageBounds;
import org.helioviewer.jhv.view.View;

import org.json.JSONObject;

public class GLImage {

    public enum DifferenceMode {
        None, Running, Base
    }

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

    // ponytail: a process-wide flag rather than a parameter threaded through five render
    // signatures. Only the export sets it, on the GL thread, and resets it in a finally.
    public static Capture capture = Capture.NONE;

    public static final int MIN_DCROTA = -15;
    public static final int MAX_DCROTA = 15;
    public static final int MIN_DCRVAL = -180;
    public static final int MAX_DCRVAL = 180;
    public static final int MAX_INNER = 5;

    private GLTexture tex;
    private GLTexture lutTex;
    private GLTexture diffTex;
    private GLTexture maskTex;

    private float red = 1;
    private float green = 1;
    private float blue = 1;

    private double deltaCROTA = 0;
    private int deltaCRVAL1 = 0;
    private int deltaCRVAL2 = 0;

    // Radial mask as fractions of the layer's inscribed radius: the band [innerMask, outerMask]
    // is shown. innerMask = 0 and outerMask = 1 mask nothing; them meeting masks everything.
    private double innerMask = 0;
    private double outerMask = 1;
    private double slitLeft = 0;
    private double slitRight = 1;
    // private double sector0 = -Math.PI;
    // private double sector1 = Math.PI;
    private double brightOffset = 0;
    private double brightScale = 1;
    private double opacity = 1;
    private double blend = .5;
    private double sharpen = 0;
    private double enhanced = 0;
    // RHEF two-sided midtone control (Upsilon), AIA 171 defaults (Gilly & DeForest 2024, §3.2,
    // https://arxiv.org/html/2511.02798v1). The curve below the median (shadows, upsilonLow) and
    // above it (highlights, upsilonHigh) are shaped independently, so the two handles are
    // asymmetric by design. sunkit-image's rhef exposes the same split light/dark upsilon:
    // https://docs.sunpy.org/projects/sunkit-image/en/stable/api/sunkit_image.radial.rhef.html
    private double upsilonLow = .6;
    private double upsilonHigh = .4;
    private DifferenceMode diffMode = DifferenceMode.None;

    private LUT lut = LUT.gray();
    private LUT lastLut;

    private boolean invertLUT = false;
    private boolean showColorbar = false;
    private boolean colorbarChosen = false; // user toggled it, or a saved session specified it
    private boolean lastInverted = false;
    private boolean lutChanged = true;
    private DetectorMask uploadedMask = DetectorMask.NONE;
    private View.ImageData uploadedImageData;

    public void streamImage(View.ImageData imageData, View.ImageData prevImageData, View.ImageData baseImageData) {
        // The filter is a texture parameter set at upload, so a changed interpolation setting has
        // to force one: without this, switching it did nothing until the frame changed.
        int wanted = Interpolation.glFilter(LUTLabels.isCategorical(lut));
        if (uploadedImageData != imageData || uploadedFilter != wanted) {
            uploadedFilter = wanted;
            tex.bind();
            // NEAREST so a categorical LUT never samples a blended half-index between two category
            // IDs; gated on the LUT in use, not the FITS product, so a continuous LUT over the same
            // data gets the ordinary smooth LINEAR treatment. Mirrors applyFilters()'s dither gate.
            tex.copyImageBuffer(imageData.imageBuffer(), wanted);
            uploadedImageData = imageData;
        }

        View.ImageData prevFrame = diffMode == DifferenceMode.Base ? baseImageData : prevImageData;
        if (diffMode != DifferenceMode.None && prevFrame != null) {
            diffTex.bind();
            diffTex.copyImageBuffer(prevFrame.imageBuffer(), GL.LINEAR);
        }
    }

    private int uploadedFilter = -1; // the filter the current texture was uploaded with

    public void collectImageBuffers(Set<ImageBuffer> retained) {
        if (uploadedImageData != null)
            retained.add(uploadedImageData.imageBuffer());
    }

    private final float[] color = new float[4];

    public void applyFilters(boolean rhefActive) {
        applyFilters(rhefActive, false);
    }

    /**
     * @param legend bind this layer's display state for the colour-table legend rather than for
     *               the picture: no sharpen (its taps assume the image's pixel pitch), no dither
     *               (a legend is not banded and should not be noisy), no clipping flags (they
     *               would paint the bar's ends, which are the very values the bar is there to
     *               name). Everything else, Levels, response, HDR gain, mode and knee, is exactly
     *               the picture's, which is the point. The caller binds its own image and mask.
     */
    public void applyFilters(boolean rhefActive, boolean legend) {
        MetaData metaData = uploadedImageData.metaData();
        // Radial mask scale: the layer's corner (outermost) radius, so the mask handle at 1.0
        // sits at the far corner of a square-cornered FOV (e.g. PUNCH). Referencing the inscribed
        // nearest-edge radius instead made the first tick below 1.0 jump straight past all the
        // corner data — a discontinuity between "full image" and "cropped to the nearest edge".
        double maskRef = ImageBounds.radial(metaData);
        // shader.bindSector(gl, -Math.max(Math.abs(metaData.getSector0()), Math.abs(sector0)), Math.max(metaData.getSector1(), sector1));
        boolean raw = capture != Capture.NONE, data = capture == Capture.DATA;
        // Opacity, blend and the channel toggles are compositing parameters: a layer written on
        // its own carries them in its metadata, not in its pixels.
        // The legend is drawn at full opacity whatever the layer's own: opacity and blend are how
        // the picture is composited over what lies beneath it, not part of what a value means.
        boolean plain = raw || legend;
        color[0] = plain ? 1 : (float) (opacity * red); // https://amindforeverprogramming.blogspot.com/2013/07/why-alpha-premultiplied-colour-blending.html
        color[1] = plain ? 1 : (float) (opacity * green);
        color[2] = plain ? 1 : (float) (opacity * blue);
        color[3] = plain ? 1 : (float) (opacity * blend);
        GLSLSolarShader.bindDisplay(color,
                1f / uploadedImageData.imageBuffer().width, 1f / uploadedImageData.imageBuffer().height, data || legend ? 0 : (float) (-2 * sharpen), diffMode.ordinal(),
                metaData.getSector0(), metaData.getSector1(), data ? 0 : (float) enhanced,
                metaData.getCutOffX(), metaData.getCutOffY(), metaData.getCutOffValue(), metaData.getCalculateDepth() ? 1 : 0,
                // RHEF output is already a normalized rank in [0, 1]; the raw-DN response
                // factor must NOT rescale it (that pushes the uniform upper half past 1 and
                // clamps it to white). The user's Levels (brightOffset/brightScale) still
                // apply as a black/white-point control on the equalized output.
                data ? 0 : (float) brightOffset, data ? 1 : (float) (brightScale * (rhefActive ? 1 : metaData.getResponseFactor())),
                Math.max(metaData.getInnerRadius(), (float) (innerMask * maskRef)),
                Display.getShowCorona() ? (outerMask < 1 ? (float) (outerMask * maskRef) : metaData.getOuterRadius()) : 1,
                (float) slitLeft, (float) slitRight,
                (float) (rhefActive && !data ? upsilonLow : 1), (float) (rhefActive && !data ? upsilonHigh : 1),
                LUTLabels.isCategorical(lut) ? 1 : 0,
                Display.skipDither() || legend ? 1 : 0,
                Display.showClipping && !raw && !legend ? 1 : 0,
                raw ? 1 : 0,
                HdrGain.current(raw), HdrGain.mode().ordinal(), HdrGain.knee());

        applyLUT();
        if (legend)
            return; // the legend binds its own ramp on unit ZERO and a blank mask on unit THREE
        applyMask(metaData.getDetectorMask());
        maskTex.bind();
        tex.bind();
        if (diffMode != DifferenceMode.None)
            diffTex.bind();
    }

    private void applyLUT() {
        lutTex.bind();

        LUT currlut = diffMode == DifferenceMode.None ? lut : LUT.gray();
        if (lutChanged || lastLut != currlut || invertLUT != lastInverted) {
            ByteBuffer lutBuffer = invertLUT ? currlut.rgbaInv() : currlut.rgba();
            lastLut = currlut;
            lastInverted = invertLUT;

            // LINEAR for a continuous ramp, NEAREST for a categorical one.
            //
            // The table is 256 entries of 8-bit RGBA, so NEAREST caps the whole renderer at 256
            // colours no matter how much precision the data carried in (FITS arrives as 16-bit
            // half-float and survives the shader's arithmetic intact) -- which is why a dither
            // has to be added before the lookup to break up the banding. Interpolating between
            // entries lifts that ceiling: the ramp becomes continuous and the dither is only
            // needed for an 8-bit destination.
            //
            // Categorical tables must keep NEAREST. Their pixel value SELECTS an entry rather
            // than positioning on a ramp, so a blend of two entries is a colour that means
            // nothing -- a mix of two channel polarities, say. Same reason the shader already
            // refuses to dither them. Keyed on the table actually being uploaded, which in
            // difference mode is grey rather than the layer's own.
            // Half-entry note: sampling at `value` rather than at the texel centre shifts the
            // ramp by 1/512 under LINEAR. Left alone deliberately -- it is invisible on a ramp,
            // and correcting it would change which entry a categorical lookup lands on.
            int filter = Interpolation.glFilter(LUTLabels.isCategorical(currlut));
            GLTexture.copyByteImage(lutBuffer.remaining() / 4, 1, filter, lutBuffer);
        }
        lutChanged = false;
    }

    public void init() {
        tex = new GLTexture(GL.TEXTURE_2D, GLTexture.Unit.ZERO);
        lutTex = new GLTexture(GL.TEXTURE_2D, GLTexture.Unit.ONE);
        diffTex = new GLTexture(GL.TEXTURE_2D, GLTexture.Unit.TWO);
        maskTex = new GLTexture(GL.TEXTURE_2D, GLTexture.Unit.THREE);
        // Texture objects were recreated, so their corresponding upload bookkeeping must start fresh.
        uploadedImageData = null;
        lutChanged = true;
        uploadedMask = DetectorMask.NONE;

        // Keep diffImage and mask samplers backed by a complete texture from startup to avoid macOS driver warnings.
        diffTex.bind();
        ByteBuffer emptyDiffTexture = BufferUtils.newByteBuffer(4).put(new byte[]{0, 0, 0, (byte) 0xFF}).flip();
        GLTexture.copyByteImage(1, 1, GL.LINEAR, emptyDiffTexture);

        maskTex.bind();
        maskTex.copyImageBuffer(uploadedMask.getImageBuffer(), GL.NEAREST);
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
    }

    private void applyMask(DetectorMask detectorMask) {
        if (uploadedMask == detectorMask)
            return;
        maskTex.bind();
        maskTex.copyImageBuffer(detectorMask.getImageBuffer(), GL.NEAREST);
        uploadedMask = detectorMask;
    }

    public void setDeltaCROTA(double delta) {
        deltaCROTA = Math.clamp(delta, MIN_DCROTA, MAX_DCROTA);
    }

    public void setDeltaCRVAL1(int delta) {
        deltaCRVAL1 = Math.clamp(delta, MIN_DCRVAL, MAX_DCRVAL);
    }

    public void setDeltaCRVAL2(int delta) {
        deltaCRVAL2 = Math.clamp(delta, MIN_DCRVAL, MAX_DCRVAL);
    }

    public void setInnerMask(double mask) {
        innerMask = Math.clamp(mask, 0, 1);
    }

    public void setOuterMask(double mask) {
        outerMask = Math.clamp(mask, 0, 1);
    }

    public double getOuterMask() {
        return outerMask;
    }

    public void setSlit(double left, double right) {
        slitLeft = Math.clamp(left, 0, 1);
        slitRight = Math.clamp(right, slitLeft, 1);
    }

    /*
        public void setSector(double left, double right) {
            sector0 = Math.toRadians(Math.clamp(left, -180, 0));
            sector1 = Math.toRadians(Math.clamp(right, 0, 180));
        }
    */
    public void setBrightness(double offset, double scale) {
        brightOffset = Math.clamp(offset, -1, 2);
        brightScale = Math.clamp(scale, 0, 2 - brightOffset);
    }

    public double getDeltaCROTA() {
        return deltaCROTA;
    }

    public int getDeltaCRVAL1() {
        return deltaCRVAL1;
    }

    public int getDeltaCRVAL2() {
        return deltaCRVAL2;
    }

    public double getInnerMask() {
        return innerMask;
    }

    public double getSlitLeft() {
        return slitLeft;
    }

    /*
        public double getSector0() {
            return Math.toDegrees(sector0);
        }

        public double getSector1() {
            return Math.toDegrees(sector1);
        }
    */
    public double getSlitRight() {
        return slitRight;
    }

    public double getBrightOffset() {
        return brightOffset;
    }

    public double getBrightScale() {
        return brightScale;
    }

    public void setColor(float _red, float _green, float _blue) {
        red = _red;
        green = _green;
        blue = _blue;
    }

    public void setOpacity(double _opacity) {
        opacity = Math.clamp(_opacity, 0, 1);
    }

    public void setBlend(double _blend) {
        blend = Math.clamp(_blend, 0, 1);
    }

    public void setSharpen(double _sharpen) {
        sharpen = Math.clamp(_sharpen, -1, 1);
    }

    public void setLUT(LUT newLUT, boolean invert) {
        if (lut == newLUT && invertLUT == invert) {
            return;
        }
        if (newLUT == null)
            newLUT = LUT.gray();

        lut = newLUT;
        invertLUT = invert;
        lutChanged = true;
    }

    public void setEnhanced(double _enhanced) {
        enhanced = Math.clamp(_enhanced, 0, 3);
    }

    public void setUpsilon(double low, double high) {
        upsilonLow = Math.clamp(low, 0.05, 1);
        upsilonHigh = Math.clamp(high, 0.05, 1);
    }

    public double getUpsilonLow() {
        return upsilonLow;
    }

    public double getUpsilonHigh() {
        return upsilonHigh;
    }

    public void setDifferenceMode(DifferenceMode mode) {
        diffMode = mode;
    }

    public DifferenceMode getDifferenceMode() {
        return diffMode;
    }

    public double getSharpen() {
        return sharpen;
    }

    public double getEnhanced() {
        return enhanced;
    }

    public double getOpacity() {
        return opacity;
    }

    public double getBlend() {
        return blend;
    }

    public boolean getRed() {
        return red != 0;
    }

    public boolean getGreen() {
        return green != 0;
    }

    public boolean getBlue() {
        return blue != 0;
    }

    public boolean getInvertLUT() {
        return invertLUT;
    }

    public boolean getShowColorbar() {
        return showColorbar;
    }

    public void setShowColorbar(boolean show) {
        showColorbar = show;
        colorbarChosen = true;
    }

    /**
     * Default the legend on for a layer type that is unreadable without one (an indexed
     * categorical map), without overriding a choice the user or a saved session already made.
     */
    public void setShowColorbarDefault(boolean show) {
        if (!colorbarChosen)
            showColorbar = show;
    }

    // The colour table currently driving this layer, for the legend. Inversion is a display
    // toggle, so the legend must mirror it -- callers pair this with getInvertLUT().
    public LUT getLUT() {
        return lut;
    }

    public void fromJson(JSONObject jo) {
        setSharpen(jo.optDouble("sharpen", sharpen));
        setOpacity(jo.optDouble("opacity", opacity));
        setBlend(jo.optDouble("blend", blend));
        setSlit(jo.optDouble("slitLeft", slitLeft), jo.optDouble("slitRight", slitRight));
        // setSector(jo.optDouble("sector0", sector0), jo.optDouble("sector1", sector1));
        setInnerMask(jo.optDouble("innerMask", innerMask));
        setOuterMask(jo.optDouble("outerMask", outerMask));
        setBrightness(jo.optDouble("brightOffset", brightOffset), jo.optDouble("brightScale", brightScale));
        setEnhanced(jo.optDouble("enhanced", enhanced));
        setUpsilon(jo.optDouble("upsilonLow", upsilonLow), jo.optDouble("upsilonHigh", upsilonHigh));
        String strDiffMode = jo.optString("differenceMode", diffMode.toString());
        try {
            diffMode = DifferenceMode.valueOf(strDiffMode);
        } catch (Exception ignore) {}
        JSONObject colorObject = jo.optJSONObject("color");
        if (colorObject != null) {
            red = colorObject.optBoolean("red", getRed()) ? 1 : 0;
            green = colorObject.optBoolean("green", getGreen()) ? 1 : 0;
            blue = colorObject.optBoolean("blue", getBlue()) ? 1 : 0;
        }
        invertLUT = jo.optBoolean("invert", invertLUT);
        if (jo.has("colorbar")) {
            showColorbar = jo.optBoolean("colorbar", showColorbar);
            colorbarChosen = true;
        }
    }

    public JSONObject toJson() {
        JSONObject jo = new JSONObject();
        jo.put("sharpen", sharpen);
        jo.put("opacity", opacity);
        jo.put("blend", blend);
        jo.put("slitLeft", slitLeft);
        jo.put("slitRight", slitRight);
        // jo.put("sector0", getSector0());
        // jo.put("sector1", getSector1());
        jo.put("innerMask", innerMask);
        jo.put("outerMask", outerMask);
        jo.put("brightOffset", brightOffset);
        jo.put("brightScale", brightScale);
        jo.put("enhanced", enhanced);
        jo.put("upsilonLow", upsilonLow);
        jo.put("upsilonHigh", upsilonHigh);
        jo.put("differenceMode", diffMode);

        JSONObject colorObject = new JSONObject();
        colorObject.put("red", getRed());
        colorObject.put("green", getGreen());
        colorObject.put("blue", getBlue());
        jo.put("color", colorObject);
        jo.put("invert", invertLUT);
        jo.put("colorbar", showColorbar);

        return jo;
    }

}
