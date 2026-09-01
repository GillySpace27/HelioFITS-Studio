package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.math.Mat2;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.wcs.WcsHeader;

public class GLSLSolarShader extends GLSLShader {

    public static final GLSLSolarShader sphere = new GLSLSolarShader("/glsl/solar.vert", "/glsl/solarSphere.frag", false);
    public static final GLSLSolarShader ortho = new GLSLSolarShader("/glsl/solar.vert", "/glsl/solarOrtho.frag", true);
    public static final GLSLSolarShader hpc = new GLSLSolarShader("/glsl/solar.vert", "/glsl/solarHpc.frag", true);
    public static final GLSLSolarShader lati = new GLSLSolarShader("/glsl/solar.vert", "/glsl/solarLati.frag", true);
    public static final GLSLSolarShader radialWarp = new GLSLSolarShader("/glsl/solar.vert", "/glsl/solarRadialWarp.frag", true);
    public static final GLSLSolarShader rectWarp = new GLSLSolarShader("/glsl/solar.vert", "/glsl/solarRectWarp.frag", true);

    private final boolean hasCommon;

    private int pv0Ref;
    private int pv1Ref;

    private GLSLSolarShader(String vertex, String fragment, boolean _hasCommon) {
        super(vertex, fragment);
        hasCommon = _hasCommon;
    }

    private static final int IMAGE_FLOATS = 48;
    private static final GLUniformBuffer imageBuffer = new GLUniformBuffer(IMAGE_FLOATS, UBO.IMAGE, GL.STREAM_DRAW);

    private static final int SCREEN_FLOATS = 24;
    private static final GLUniformBuffer screenBuffer = new GLUniformBuffer(SCREEN_FLOATS, UBO.SOLAR_SCREEN, GL.STREAM_DRAW);

    private static final int DISPLAY_FLOATS = 28;
    private static final GLUniformBuffer displayBuffer = new GLUniformBuffer(DISPLAY_FLOATS, UBO.DISPLAY, GL.STREAM_DRAW);

    public static void init() {
        try {
            imageBuffer.init();
            screenBuffer.init();
            displayBuffer.init();
            sphere._init(sphere.hasCommon);
            ortho._init(ortho.hasCommon);
            hpc._init(hpc.hasCommon);
            lati._init(lati.hasCommon);
            radialWarp._init(radialWarp.hasCommon);
            rectWarp._init(rectWarp.hasCommon);
        } catch (RuntimeException | Error e) {
            dispose();
            throw e;
        }
    }

    private static void setupCommonBlocks(int programID) {
        imageBuffer.bindBlock(programID, "ImageBlock");
        screenBuffer.bindBlock(programID, "ScreenBlock");
        displayBuffer.bindBlock(programID, "DisplayBlock");
    }

    @Override
    protected void initUniforms(int id) {
        if (hasCommon) {
            pv0Ref = requiredUniform(id, "pv0");
            pv1Ref = requiredUniform(id, "pv1");
            setupCommonBlocks(id);
            setTextureUnit(id, "image", GLTexture.Unit.ZERO);
            setTextureUnit(id, "lut", GLTexture.Unit.ONE);
            setTextureUnit(id, "diffImage", GLTexture.Unit.TWO);
            setTextureUnit(id, "mask", GLTexture.Unit.THREE);
        } else
            screenBuffer.bindBlock(id, "ScreenBlock");
    }

    public static void dispose() {
        sphere._dispose();
        ortho._dispose();
        hpc._dispose();
        lati._dispose();
        radialWarp._dispose();
        rectWarp._dispose();
        imageBuffer.dispose();
        screenBuffer.dispose();
        displayBuffer.dispose();
    }

    public static void bindImages(
            Region r0, Mat2 planeToImage0, float[] crval0, WcsHeader wcs0,
            float observerDistance0, float deltaT0, Quat cameraDiff0, Quat sourceView0,
            Region r1, Mat2 planeToImage1, float[] crval1, WcsHeader wcs1,
            float observerDistance1, float deltaT1, Quat cameraDiff1, Quat sourceView1) {
        FloatBuffer values = imageBuffer.begin();
        putImage(values, r0, planeToImage0, crval0, wcs0, observerDistance0, deltaT0, cameraDiff0, sourceView0);
        putImage(values, r1, planeToImage1, crval1, wcs1, observerDistance1, deltaT1, cameraDiff1, sourceView1);

        imageBuffer.uploadIfChanged();
    }

    private static void putImage(FloatBuffer values, Region r, Mat2 planeToImage, float[] crval, WcsHeader wcs,
                                 float observerDistance, float deltaT, Quat cameraDiff, Quat sourceView) {
        values.put(r.glslArray);
        planeToImage.setFloatBuffer(values);
        values.put(crval).put((float) wcs.unitsPerRad).put(wcs.projection.ordinal());
        values.put((float) wcs.zpnUpperEta).put(observerDistance).put(deltaT).put(0);
        cameraDiff.setFloatBuffer(values);
        sourceView.setFloatBuffer(values);
    }

    public static void bindScreen(MapView mv, Viewport vp) {
        MapScale scale = mv.scale(vp);
        FloatBuffer values = screenBuffer.begin();
        FloatBuffer inverse = Transform.getInverse();
        values.put(inverse);
        inverse.flip();
        values.put((float) scale.toMapX(0)).put((float) scale.toMapX(1));
        values.put((float) scale.toMapY(0)).put((float) scale.toMapY(1));
        values.put((float) mv.latiLongitudeOrigin()).put((float) mv.latiLatitudeOrigin());
        values.put((float) (1 / vp.aspect));
        values.put((float) scale.warpLambda());
        screenBuffer.upload();
    }

    static void bindDisplay(float[] color,
                            float shWidth, float shHeight, float shWeight, int isDiff,
                            float bOffset, float bScale,
                            float upsilonLow, float upsilonHigh,
                            float userSectorCenter, float userSectorHalfWidth, float metadataSectorCenter, float metadataSectorHalfWidth,
                            float cutOffX, float cutOffY, float cutOffVal, int calculateDepth,
                            float innerRadius, float outerRadius,
                            float slitLeft, float slitRight,
                            float enhanced) {
        FloatBuffer values = displayBuffer.begin();
        values.put(color);
        values.put(shWidth).put(shHeight).put(shWeight).put(isDiff);
        values.put(bOffset).put(bScale).put(upsilonLow).put(upsilonHigh);
        values.put(userSectorCenter).put(userSectorHalfWidth).put(metadataSectorCenter).put(metadataSectorHalfWidth);
        values.put(cutOffX).put(cutOffY).put(cutOffVal).put(calculateDepth);
        values.put(innerRadius).put(outerRadius).put(slitLeft).put(slitRight);
        values.put(enhanced).put(0).put(0).put(0);
        displayBuffer.uploadIfChanged();
    }

    public void bindPV(float[] pv0, float[] pv1) {
        GL.glUniform1fv(pv0Ref, pv0);
        GL.glUniform1fv(pv1Ref, pv1);
    }
}
