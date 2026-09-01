package org.helioviewer.jhv.opengl.model;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.helioviewer.jhv.base.BufferUtils;
import org.helioviewer.jhv.io.DataUri;
import org.helioviewer.jhv.io.NetFileCache;

import org.lwjgl.assimp.AIFile;
import org.lwjgl.assimp.AIFileCloseProc;
import org.lwjgl.assimp.AIFileFlushProc;
import org.lwjgl.assimp.AIFileIO;
import org.lwjgl.assimp.AIFileOpenProc;
import org.lwjgl.assimp.AIFileReadProc;
import org.lwjgl.assimp.AIFileSeek;
import org.lwjgl.assimp.AIFileTellProc;
import org.lwjgl.assimp.AIFileWriteProc;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.Assimp;
import org.lwjgl.system.MemoryUtil;

final class AssimpFileIO implements AutoCloseable {

    private final DataUri source;
    private final String mainName;
    private final AIFileOpenProc openProc = AIFileOpenProc.create(this::openFile);
    private final AIFileCloseProc closeProc = AIFileCloseProc.create(this::closeFile);
    private final AIFileReadProc readProc = AIFileReadProc.create(this::read);
    private final AIFileWriteProc writeProc = AIFileWriteProc.create((file, buffer, size, count) -> 0);
    private final AIFileTellProc tellProc = AIFileTellProc.create(this::tell);
    private final AIFileTellProc sizeProc = AIFileTellProc.create(this::size);
    private final AIFileSeek seekProc = AIFileSeek.create(this::seek);
    private final AIFileFlushProc flushProc = AIFileFlushProc.create(file -> {});
    private final AIFileIO fileIO = AIFileIO.calloc().set(openProc, closeProc, 0);
    private final Map<Long, OpenFile> openFiles = new HashMap<>();
    private final Map<URI, ByteBuffer> resources = new HashMap<>();

    private IOException failure;

    AssimpFileIO(DataUri _source) {
        source = _source;
        mainName = fileName(source.sourceUri());
    }

    AIScene importScene(int flags) throws IOException {
        AIScene scene = Assimp.aiImportFileEx(mainName, flags, fileIO);
        if (failure != null) {
            if (scene != null)
                Assimp.aiReleaseImport(scene);
            throw failure;
        }
        if (scene == null)
            throw new IOException("Assimp could not load " + source.sourceUri() + ": " + Assimp.aiGetErrorString());
        return scene;
    }

    private long openFile(long ignored, long fileName, long mode) {
        try {
            String openMode = MemoryUtil.memUTF8(mode);
            if (!openMode.startsWith("r"))
                throw new IOException("Assimp requested unsupported file mode " + openMode);

            URI uri = resolve(MemoryUtil.memUTF8(fileName));
            ByteBuffer data = resources.get(uri);
            if (data == null) {
                boolean mainResource = uri.equals(source.sourceUri());
                DataUri dataUri = mainResource ? source : NetFileCache.get(uri);
                data = readResource(dataUri, mainResource);
                resources.put(uri, data);
            }

            AIFile file = AIFile.calloc().set(readProc, writeProc, tellProc, sizeProc, seekProc, flushProc, 0);
            openFiles.put(file.address(), new OpenFile(file, data.duplicate()));
            return file.address();
        } catch (IOException | RuntimeException e) {
            fail(e);
            return MemoryUtil.NULL;
        }
    }

    private void closeFile(long ignored, long file) {
        OpenFile openFile = openFiles.remove(file);
        if (openFile != null)
            openFile.file().free();
    }

    private long read(long file, long destination, long size, long count) {
        OpenFile openFile = openFiles.get(file);
        if (openFile == null || size <= 0 || count <= 0)
            return 0;

        ByteBuffer data = openFile.data();
        long actualCount = Math.min(count, data.remaining() / size);
        int byteCount = (int) (actualCount * size); // bounded by data.remaining()
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), destination, byteCount);
        data.position(data.position() + byteCount);
        return actualCount;
    }

    private long tell(long file) {
        OpenFile openFile = openFiles.get(file);
        return openFile == null ? 0 : openFile.data().position();
    }

    private long size(long file) {
        OpenFile openFile = openFiles.get(file);
        return openFile == null ? 0 : openFile.data().limit();
    }

    private int seek(long file, long offset, int origin) {
        OpenFile openFile = openFiles.get(file);
        if (openFile == null)
            return Assimp.aiReturn_FAILURE;

        long base = switch (origin) {
            case Assimp.aiOrigin_SET -> 0;
            case Assimp.aiOrigin_CUR -> openFile.data().position();
            case Assimp.aiOrigin_END -> openFile.data().limit();
            default -> -1;
        };
        long position;
        try {
            position = Math.addExact(base, offset);
        } catch (ArithmeticException e) {
            return Assimp.aiReturn_FAILURE;
        }
        if (base < 0 || position < 0 || position > openFile.data().limit())
            return Assimp.aiReturn_FAILURE;

        openFile.data().position((int) position);
        return Assimp.aiReturn_SUCCESS;
    }

    private URI resolve(String requestedName) throws IOException {
        String portableName = requestedName.replace('\\', '/');
        if (portableName.equals(mainName) || portableName.equals("./" + mainName))
            return source.sourceUri();
        try {
            return source.sourceUri().resolve(portableName);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid model resource URI: " + requestedName, e);
        }
    }

    private static ByteBuffer readResource(DataUri data, boolean mainResource) throws IOException {
        File file = data.file();
        if ((mainResource || hasGzipSuffix(data.sourceUri())) && isGzip(file)) {
            byte[] bytes;
            try (InputStream input = new GZIPInputStream(Files.newInputStream(file.toPath()))) {
                bytes = input.readAllBytes();
            }
            return BufferUtils.newByteBuffer(bytes.length).put(bytes).flip();
        }

        long size = Files.size(file.toPath());
        ByteBuffer result = BufferUtils.newByteBuffer(Math.toIntExact(size));
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            while (result.hasRemaining()) {
                if (channel.read(result) < 0)
                    throw new IOException("Unexpected end of file: " + file);
            }
        }
        return result.flip();
    }

    private static boolean isGzip(File file) throws IOException {
        try (InputStream input = Files.newInputStream(file.toPath())) {
            return input.read() == 0x1f && input.read() == 0x8b;
        }
    }

    private static boolean hasGzipSuffix(URI uri) {
        String path = uri.getPath();
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".gz");
    }

    private static String fileName(URI uri) {
        String path = uri.getPath();
        int slash = path == null ? -1 : path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        if (name == null || name.isEmpty())
            name = "model.gltf";
        if (name.toLowerCase(Locale.ROOT).endsWith(".gz"))
            name = name.substring(0, name.length() - 3);
        return name;
    }

    private void fail(Exception exception) {
        if (failure == null)
            failure = exception instanceof IOException io ? io : new IOException(exception);
    }

    @Override
    public void close() {
        for (OpenFile openFile : openFiles.values())
            openFile.file().free();
        openFiles.clear();
        fileIO.free();
        flushProc.free();
        seekProc.free();
        sizeProc.free();
        tellProc.free();
        writeProc.free();
        readProc.free();
        closeProc.free();
        openProc.free();
    }

    private record OpenFile(AIFile file, ByteBuffer data) {}

}
