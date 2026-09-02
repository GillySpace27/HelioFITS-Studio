package org.helioviewer.jhv.opengl;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.io.FileUtils;

abstract class GLSLShader {
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

    private int progID;
    private int vertexID;
    private int fragmentID;

    private final String vertex;
    private final String[] fragments;

    GLSLShader(String _vertex, String... _fragments) {
        vertex = _vertex;
        fragments = _fragments;
    }

    protected final void _init() {
        try {
            vertexID = attachShader(GL.VERTEX_SHADER, FileUtils.readResourceString(vertex));

            StringBuilder fragmentText = new StringBuilder();
            for (String fragment : fragments)
                fragmentText.append(FileUtils.readResourceString(fragment));
            fragmentID = attachShader(GL.FRAGMENT_SHADER, fragmentText.toString());

            progID = initializeProgram();
            use();
            initUniforms(progID);
        } catch (Exception e) {
            _dispose();
            throw new GLException("Cannot load shader", e);
        }
    }

    protected final void _dispose() {
        if (progID != 0) {
            GL.glUseProgram(0);
        }
        if (vertexID != 0) {
            GL.glDeleteShader(vertexID);
            vertexID = 0;
        }
        if (fragmentID != 0) {
            GL.glDeleteShader(fragmentID);
            fragmentID = 0;
        }
        if (progID != 0) {
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

    private static int attachShader(int shaderType, String text) {
        int id = GL.glCreateShader(shaderType);
        try {
            GL.glShaderSource(id, text);
            GL.glCompileShader(id);

            int compileStatus = GL.glGetShaderi(id, GL.COMPILE_STATUS);
            if (compileStatus != 1) {
                Log.error("Shader compile status: " + compileStatus);
                int infoLogLength = GL.glGetShaderi(id, GL.INFO_LOG_LENGTH);
                if (infoLogLength > 0) {
                    String log = GL.glGetShaderInfoLog(id, infoLogLength);
                    Log.error(log);
                    throw new GLException("Cannot compile shader: " + log);
                } else
                    throw new GLException("Cannot compile shader: unknown reason");
            }
            return id;
        } catch (Exception e) {
            GL.glDeleteShader(id);
            throw e;
        }
    }

    private int initializeProgram() {
        int id = GL.glCreateProgram();
        try {
            GL.glAttachShader(id, vertexID);
            GL.glAttachShader(id, fragmentID);
            GL.glLinkProgram(id);

            int linkStatus = GL.glGetProgrami(id, GL.LINK_STATUS);
            if (linkStatus != 1) {
                Log.error("Shader link status: " + linkStatus);
                int infoLogLength = GL.glGetProgrami(id, GL.INFO_LOG_LENGTH);
                if (infoLogLength > 0) {
                    String log = GL.glGetProgramInfoLog(id, infoLogLength);
                    Log.error(log);
                    throw new GLException("Cannot link shader: " + log);
                } else
                    throw new GLException("Cannot link shader: unknown reason");
            }

            GL.glDetachShader(id, vertexID);
            GL.glDeleteShader(vertexID);
            vertexID = 0;
            GL.glDetachShader(id, fragmentID);
            GL.glDeleteShader(fragmentID);
            fragmentID = 0;
            return id;
        } catch (Exception e) {
            GL.glDeleteProgram(id);
            throw e;
        }
    }

}
