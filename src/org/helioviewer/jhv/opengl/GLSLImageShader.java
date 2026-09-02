package org.helioviewer.jhv.opengl;

import java.nio.FloatBuffer;

import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.math.Mat2;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.wcs.WcsHeader;

public final class GLSLImageShader extends GLSLShader {

    private static final String VERTEX = "/glsl/screen.vert";
    private static final String COMMON_FRAGMENT = "/glsl/imageCommon.frag";

    private static final GLSLImageShader ortho = new GLSLImageShader("/glsl/imageOrtho.frag");
    private static final GLSLImageShader hpc = new GLSLImageShader("/glsl/imageHpc.frag");
    private static final GLSLImageShader lati = new GLSLImageShader("/glsl/imageLati.frag");
    private static final GLSLImageShader radialWarp = new GLSLImageShader("/glsl/imageRadialWarp.frag");
    private static final GLSLImageShader rectWarp = new GLSLImageShader("/glsl/imageRectWarp.frag");
    private static final GLSLImageShader[] imagePrograms = {ortho, hpc, lati, radialWarp, rectWarp};

    private int pv0Ref;
    private int pv1Ref;

    private GLSLImageShader(String fragment) {
        super(VERTEX, COMMON_FRAGMENT, fragment);
    }

    private static final UniformBufferObject imageBuffer = new UniformBufferObject(UniformBlockLayout.IMAGE, GL.STREAM_DRAW);
    private static final UniformBufferObject displayBuffer = new UniformBufferObject(UniformBlockLayout.DISPLAY, GL.STREAM_DRAW);

    static void init() {
        try {
            imageBuffer.init();
            displayBuffer.init();
            imageBuffer.bind();
            displayBuffer.bind();
            for (GLSLImageShader program : imagePrograms)
                program._init();
        } catch (RuntimeException | Error e) {
            dispose();
            throw e;
        }
    }

    private static void setupImageBlocks(int programID) {
        setupUniformBlock(programID, UniformBlockLayout.IMAGE);
        setupUniformBlock(programID, UniformBlockLayout.SCREEN);
        setupUniformBlock(programID, UniformBlockLayout.DISPLAY);
    }

    @Override
    protected void initUniforms(int id) {
        pv0Ref = requiredUniform(id, "pv0");
        pv1Ref = requiredUniform(id, "pv1");
        setupImageBlocks(id);
        setTextureUnit(id, "image", GLTexture.Unit.ZERO);
        setTextureUnit(id, "lut", GLTexture.Unit.ONE);
        setTextureUnit(id, "diffImage", GLTexture.Unit.TWO);
        setTextureUnit(id, "mask", GLTexture.Unit.THREE);
    }

    static void dispose() {
        for (GLSLImageShader program : imagePrograms)
            program._dispose();
        imageBuffer.dispose();
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

    static void useImage(MapMode mode, float[] pv0, float[] pv1) {
        GLSLImageShader shader = switch (mode) {
            case Orthographic -> ortho;
            case HPC -> hpc;
            case Latitudinal -> lati;
            case RadialWarp -> radialWarp;
            case RectWarp -> rectWarp;
        };
        shader.use();
        shader.bindPV(pv0, pv1);
    }

    private void bindPV(float[] pv0, float[] pv1) {
        GL.glUniform1fv(pv0Ref, pv0);
        GL.glUniform1fv(pv1Ref, pv1);
    }

}
