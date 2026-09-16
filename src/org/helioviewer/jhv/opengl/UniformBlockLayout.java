package org.helioviewer.jhv.opengl;

enum UniformBlockLayout {
    // Float counts must match the std140 block declared in the shader, rounded up to a multiple
    // of 4 floats (16 bytes). SCREEN carries the warp limb and DISPLAY the categorical, clipping
    // and HDR members beyond what upstream declares; both are sized for the padded block.
    IMAGE("ImageBlock", 0, 48),
    SCREEN("ScreenBlock", 1, 28),
    DISPLAY("DisplayBlock", 2, 36),
    LINE("LineBlock", 3, 24),
    MESH_MATERIAL("MaterialBlock", 4, 8),
    MESH_FRAME("FrameBlock", 5, 20),
    // The world-space warp the overlay vertex shaders read; see warpCommon.vert and GLSLWarp.
    WARP("WarpBlock", 6, 4);

    final String glslName;
    final int binding;
    final int floatCount;

    UniformBlockLayout(String _glslName, int _binding, int _floatCount) {
        glslName = _glslName;
        binding = _binding;
        floatCount = _floatCount;
    }

    int byteSize() {
        return floatCount * Float.BYTES;
    }
}
