package org.helioviewer.jhv.opengl;

import java.io.IOException;

import org.helioviewer.jhv.io.FileUtils;

abstract class GLSLShader {

    // Vertex-stage counterpart of the fragment commons, carrying the world-space warp shared by
    // the overlay shaders (line, point, shape). The fragment side needs no constant here: a
    // program lists its own fragment sources and they are concatenated in order, so the common
    // is simply the first entry (see GLSLImageShader).
    private static final String COMMON_VERTEX = "/glsl/warpCommon.vert";
    // A vertex shader that calls this needs the prelude that defines it, so the prelude is keyed
    // on the source rather than declared per program. Declaring it per program is what this
    // replaces, and the failure mode there is unusually bad: a new overlay shader that starts
    // using the warp without its owner remembering to ask for the prelude still compiles in the
    // validator (which splices unconditionally) and fails only at link time on a real GL context,
    // so it reaches a user rather than the build.
    private static final String WARP_MARKER = "warpWorld";

    protected static void setupUniformBlock(int programID, UniformBlockLayout block) {
        int blockIndex = GL.glGetUniformBlockIndex(programID, block.glslName);
        if (blockIndex < 0)
            throw new GLException("Required uniform block not found: " + block.glslName);
        int blockSize = GL.glGetActiveUniformBlocki(programID, blockIndex, GL.UNIFORM_BLOCK_DATA_SIZE);
        // A program may use only a prefix of a buffer shared with another program, as the solar sphere does.
        if (blockSize > block.byteSize())
            throw new GLException("Uniform block " + block.glslName + " requires " + blockSize + " bytes, buffer has " + block.byteSize());
        GL.glUniformBlockBinding(programID, blockIndex, block.binding);
    }

    protected static int requiredUniform(int programID, String name) {
        int location = GL.glGetUniformLocation(programID, name);
        if (location < 0)
            throw new GLException("Required uniform not found: " + name);
        return location;
    }

    /**
     * A uniform that only some programs sharing a common source declare.
     *
     * <p>Distinct from {@link #requiredUniform}, which throws: the shared fragment common is used
     * by programs with genuinely different geometry, so the sky's aim and the warp surface's model
     * exist on one program each. glUniform on -1 is a documented no-op, which is what lets the
     * binding calls stay unconditional at the call site.
     */
    protected static int optionalUniform(int programID, String name) {
        return GL.glGetUniformLocation(programID, name);
    }

    private int progID;

    private final String vertex;
    private final String[] fragments;

    GLSLShader(String _vertex, String... _fragments) {
        vertex = _vertex;
        fragments = _fragments;
    }

    protected final void _init() {
        int vertexID = 0;
        int fragmentID = 0;
        try {
            vertexID = compileShader(GL.VERTEX_SHADER, "vertex shader " + vertex, vertexSource());

            StringBuilder fragmentText = new StringBuilder();
            for (String fragment : fragments)
                fragmentText.append(readSource(fragment));
            fragmentID = compileShader(GL.FRAGMENT_SHADER, "fragment shader " + String.join(", ", fragments), fragmentText.toString());

            progID = initializeProgram(vertexID, fragmentID);
            use();
            initUniforms(progID);
        } catch (RuntimeException | Error e) {
            _dispose();
            throw e;
        } finally {
            if (vertexID != 0)
                GL.glDeleteShader(vertexID);
            if (fragmentID != 0)
                GL.glDeleteShader(fragmentID);
        }
    }

    private String vertexSource() {
        String text = readSource(vertex);
        if (!text.contains(WARP_MARKER))
            return text;
        // The #version line has to stay first, so splice the common in after it rather than in
        // front of it. This is why the vertex commons cannot simply be concatenated the way the
        // fragment ones are, where the common carries the #version itself.
        String common = readSource(COMMON_VERTEX);
        int nl = text.indexOf('\n');
        return nl < 0 ? common + text : text.substring(0, nl + 1) + common + text.substring(nl + 1);
    }

    private static String readSource(String resource) {
        try {
            return FileUtils.readResourceString(resource);
        } catch (IOException e) {
            throw new GLException("Cannot read shader resource " + resource, e);
        }
    }

    protected final void _dispose() {
        if (progID != 0) {
            GL.glUseProgram(0);
            GL.glDeleteProgram(progID);
            progID = 0;
        }
    }

    final void use() {
        GL.glUseProgram(progID);
    }

    protected abstract void initUniforms(int id);

    protected static void setTextureUnit(int id, String texname, GLTexture.Unit unit) {
        GL.glUniform1i(requiredUniform(id, texname), unit.ordinal());
    }

    private static int compileShader(int shaderType, String description, String text) {
        int id = GL.glCreateShader(shaderType);
        try {
            GL.glShaderSource(id, text);
            GL.glCompileShader(id);

            int compileStatus = GL.glGetShaderi(id, GL.COMPILE_STATUS);
            if (compileStatus != 1) {
                int infoLogLength = GL.glGetShaderi(id, GL.INFO_LOG_LENGTH);
                String log = infoLogLength > 0 ? GL.glGetShaderInfoLog(id, infoLogLength) : "unknown reason";
                throw new GLException("Cannot compile " + description + ": " + log);
            }
            return id;
        } catch (RuntimeException | Error e) {
            GL.glDeleteShader(id);
            throw e;
        }
    }

    private int initializeProgram(int vertexID, int fragmentID) {
        int id = GL.glCreateProgram();
        try {
            GL.glAttachShader(id, vertexID);
            GL.glAttachShader(id, fragmentID);
            GL.glLinkProgram(id);

            int linkStatus = GL.glGetProgrami(id, GL.LINK_STATUS);
            if (linkStatus != 1) {
                int infoLogLength = GL.glGetProgrami(id, GL.INFO_LOG_LENGTH);
                String log = infoLogLength > 0 ? GL.glGetProgramInfoLog(id, infoLogLength) : "unknown reason";
                throw new GLException("Cannot link shader program " + vertex + " + " + String.join(" + ", fragments) + ": " + log);
            }

            GL.glDetachShader(id, vertexID);
            GL.glDetachShader(id, fragmentID);
            return id;
        } catch (RuntimeException | Error e) {
            GL.glDeleteProgram(id);
            throw e;
        }
    }

}
