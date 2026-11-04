package org.helioviewer.jhv.opengl;

// The HFS_DEBUG_WARP diagnostic switch (GLSLShader.debugWarpDefine): only stages 1 to 6 add a
// define, and anything else, including unset, leaves the shaders exactly as shipped.
public class DebugWarpDefineCheck {

    private static int failures;

    private static void expect(String what, String value, String want) {
        String got = GLSLShader.debugWarpDefine(value);
        if (want.equals(got))
            System.out.println("  ok   " + what);
        else {
            System.out.println("  FAIL " + what + ": got [" + got + "] want [" + want + "]");
            failures++;
        }
    }

    public static void main(String[] args) {
        expect("unset adds nothing", null, "");
        expect("empty adds nothing", "", "");
        expect("out of range adds nothing", "7", "");
        expect("non-numeric adds nothing", "yes", "");
        expect("stage 1", "1", "#define HFS_DEBUG_WARP 1\n");
        expect("stage 6 with whitespace", " 6 ", "#define HFS_DEBUG_WARP 6\n");
        if (failures > 0)
            System.exit(1);
    }

}
