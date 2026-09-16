package org.helioviewer.jhv.view.j2k.opj;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Decoding JPEG 2000 with OpenJPEG, called through the foreign function interface.
 *
 * <p>This exists to replace Kakadu, which is proprietary and whose licence does not reach a fork
 * (see the licence inventory in {@code archive/reviews/THIRD-PARTY.md}). OpenJPEG is BSD-2 and its
 * only dependency is the system C library, so it can be bundled and redistributed.
 *
 * <p>Decoding takes the whole codestream as bytes rather than a file path, because the JPIP client
 * will hand over a codestream it rebuilt from cached data bins, which never exists as a file. A
 * downloaded .jp2 or .jpx is just the same call with the file's bytes.
 *
 * <p>ponytail: one greyscale component, which is every Helioviewer browse image and every frame
 * this fork decodes today. Colour needs the component loop and the colour transform flag; add it
 * when something actually asks for it.
 */
public final class OpenJpeg {

    /** OPJ_CODEC_FORMAT, from openjpeg.h. */
    private static final int CODEC_J2K = 0;  // a raw codestream, which is what JPIP reconstructs
    private static final int CODEC_JP2 = 2;  // the boxed file format
    private static final int PARAMETERS_BYTES = 8252; // sizeof(opj_dparameters_t), measured on the ABI we build against

    // opj_image_t and opj_image_comp_t field offsets, measured with offsetof rather than assumed.
    private static final int IMAGE_NUMCOMPS = 16;
    private static final int IMAGE_COMPS = 24;
    private static final int COMP_W = 8;
    private static final int COMP_H = 12;
    private static final int COMP_PREC = 24;
    private static final int COMP_SGND = 32;
    private static final int COMP_DATA = 48;
    private static final int COMP_BYTES = 64;

    /** One decoded greyscale image. Samples are row-major, {@code width * height} of them. */
    public record Decoded(int width, int height, int precision, boolean signed, int[] samples) {}

    private record Api(MethodHandle createDecompress, MethodHandle defaultParameters, MethodHandle setupDecoder,
                       MethodHandle setThreads, MethodHandle streamCreate, MethodHandle setReadFunction,
                       MethodHandle setSkipFunction, MethodHandle setSeekFunction, MethodHandle setUserData,
                       MethodHandle setUserDataLength, MethodHandle readHeader, MethodHandle setResolutionFactor,
                       MethodHandle decode, MethodHandle endDecompress, MethodHandle imageDestroy,
                       MethodHandle streamDestroy, MethodHandle destroyCodec, MethodHandle setErrorHandler,
                       MethodHandle setStrictMode, MethodHandle setDecodeArea, MethodHandle version) {}

    private static final class Holder {
        private static final Arena ARENA = Arena.ofShared();
        private static final Api API = link(ARENA);
    }

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;

    private static SymbolLookup lookup(Arena arena) {
        // Where the library is, in the order it is worth looking: an explicit override, the copy
        // the application unpacks beside its other natives, then whatever the system offers.
        String override = System.getProperty("jhv.openjpeg");
        List<String> candidates = override != null ? List.of(override)
                : List.of("/opt/homebrew/opt/openjpeg/lib/libopenjp2.dylib", "/usr/local/lib/libopenjp2.dylib");
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isReadable(path))
                return SymbolLookup.libraryLookup(path, arena);
        }
        try {
            return SymbolLookup.libraryLookup(System.mapLibraryName("openjp2"), arena);
        } catch (IllegalArgumentException e) {
            // On Windows the bundled decoder needs Microsoft's C runtime, which a machine may not
            // have; say so rather than leaving "library not found" to be puzzled over.
            String hint = System.getProperty("os.name", "").startsWith("Windows")
                    ? ". On Windows this usually means the Microsoft Visual C++ runtime is missing"
                    : "";
            throw new IllegalStateException("No JPEG 2000 decoder: OpenJPEG could not be loaded" + hint, e);
        }
    }

    private static Api link(Arena arena) {
        Linker linker = Linker.nativeLinker();
        SymbolLookup opj = lookup(arena);
        var A = ValueLayout.ADDRESS;

        return new Api(
                bind(linker, opj, "opj_create_decompress", FunctionDescriptor.of(A, I32)),
                bind(linker, opj, "opj_set_default_decoder_parameters", FunctionDescriptor.ofVoid(A)),
                bind(linker, opj, "opj_setup_decoder", FunctionDescriptor.of(I32, A, A)),
                bind(linker, opj, "opj_codec_set_threads", FunctionDescriptor.of(I32, A, I32)),
                bind(linker, opj, "opj_stream_create", FunctionDescriptor.of(A, I64, I32)),
                bind(linker, opj, "opj_stream_set_read_function", FunctionDescriptor.ofVoid(A, A)),
                bind(linker, opj, "opj_stream_set_skip_function", FunctionDescriptor.ofVoid(A, A)),
                bind(linker, opj, "opj_stream_set_seek_function", FunctionDescriptor.ofVoid(A, A)),
                bind(linker, opj, "opj_stream_set_user_data", FunctionDescriptor.ofVoid(A, A, A)),
                bind(linker, opj, "opj_stream_set_user_data_length", FunctionDescriptor.ofVoid(A, I64)),
                bind(linker, opj, "opj_read_header", FunctionDescriptor.of(I32, A, A, A)),
                bind(linker, opj, "opj_set_decoded_resolution_factor", FunctionDescriptor.of(I32, A, I32)),
                bind(linker, opj, "opj_decode", FunctionDescriptor.of(I32, A, A, A)),
                bind(linker, opj, "opj_end_decompress", FunctionDescriptor.of(I32, A, A)),
                bind(linker, opj, "opj_image_destroy", FunctionDescriptor.ofVoid(A)),
                bind(linker, opj, "opj_stream_destroy", FunctionDescriptor.ofVoid(A)),
                bind(linker, opj, "opj_destroy_codec", FunctionDescriptor.ofVoid(A)),
                bind(linker, opj, "opj_set_error_handler", FunctionDescriptor.of(I32, A, A, A)),
                bind(linker, opj, "opj_decoder_set_strict_mode", FunctionDescriptor.of(I32, A, I32)),
                bind(linker, opj, "opj_set_decode_area", FunctionDescriptor.of(I32, A, A, I32, I32, I32, I32)),
                bind(linker, opj, "opj_version", FunctionDescriptor.of(A)));
    }

    @SuppressWarnings("restricted")
    private static MethodHandle bind(Linker linker, SymbolLookup opj, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = opj.find(name).orElseThrow(() -> new IllegalStateException("OpenJPEG is missing " + name));
        return linker.downcallHandle(symbol, descriptor);
    }

    /** The linked library's version string, and the first thing to print when a binding misbehaves. */
    public static String version() {
        try {
            MemorySegment s = (MemorySegment) Holder.API.version().invokeExact();
            return s.reinterpret(Long.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            throw new IllegalStateException("OpenJPEG version call failed", t);
        }
    }

    /**
     * Decode one image.
     *
     * @param data      a whole .jp2/.jpx file when {@code boxed}, otherwise a raw codestream
     * @param boxed     true for the JP2 file format, false for a bare codestream
     * @param reduce    resolution levels to skip: 0 full size, 1 half, 2 quarter, and so on
     * @param threads   decoding threads, or 0 to leave OpenJPEG's default alone
     */
    public static Decoded decode(byte[] data, boolean boxed, int reduce, int threads) {
        return decode(data, boxed, reduce, threads, 0, 0, 0, 0);
    }

    /**
     * Decode part of an image.
     *
     * <p>The rectangle is in the image's own full-size coordinates whatever the reduction, which
     * is how JPEG 2000 addresses a region: ask for the same rectangle at a coarser level and the
     * same part of the picture comes back smaller. An empty rectangle means the whole image.
     */
    public static Decoded decode(byte[] data, boolean boxed, int reduce, int threads,
                                 int x0, int y0, int x1, int y1) {
        Api api = Holder.API;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocate(data.length);
            MemorySegment.copy(data, 0, bytes, ValueLayout.JAVA_BYTE, 0, data.length);

            MemorySegment codec = (MemorySegment) api.createDecompress().invokeExact(boxed ? CODEC_JP2 : CODEC_J2K);
            if (codec.equals(MemorySegment.NULL))
                throw new IllegalStateException("OpenJPEG would not create a decoder");
            MemorySegment stream = memoryStream(api, arena, bytes, data.length);

            MemorySegment imagePtr = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment image = MemorySegment.NULL;
            // Without this, a refusal arrives as a bare false and the reason goes to nobody.
            StringBuilder complaints = new StringBuilder();
            Message onError = (text, user) -> complaints.append(text.reinterpret(Long.MAX_VALUE).getString(0).strip()).append("; ");
            int handled = (int) api.setErrorHandler().invokeExact(codec,
                    upcall(Linker.nativeLinker(), arena, onError, Message.class, "message",
                            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
                    MemorySegment.NULL);
            try {
                MemorySegment parameters = arena.allocate(PARAMETERS_BYTES);
                api.defaultParameters().invokeExact(parameters);
                if (0 == (int) api.setupDecoder().invokeExact(codec, parameters))
                    throw new IllegalStateException("OpenJPEG rejected the decoder parameters");
                // A codestream rebuilt from a partly delivered frame ends early on purpose, which
                // strict mode treats as corruption. Decoding what arrived is the whole point of JPIP.
                int lenient = (int) api.setStrictMode().invokeExact(codec, 0);
                if (lenient == 0)
                    complaints.append("this build cannot decode truncated codestreams; ");
                // invokeExact is exact about the return type too, so these ints are read even when ignored.
                if (threads > 0) {
                    int threaded = (int) api.setThreads().invokeExact(codec, threads);
                    if (threaded == 0)
                        threads = 0; // a build without thread support says no, and decodes single threaded
                }

                if (0 == (int) api.readHeader().invokeExact(stream, codec, imagePtr))
                    throw new IllegalStateException("OpenJPEG could not read the header: " + complaints);
                image = imagePtr.get(ValueLayout.ADDRESS, 0);
                if (reduce > 0 && 0 == (int) api.setResolutionFactor().invokeExact(codec, reduce))
                    throw new IllegalStateException("OpenJPEG refused resolution factor " + reduce);
                if (x1 > x0 && y1 > y0 && 0 == (int) api.setDecodeArea().invokeExact(codec, image, x0, y0, x1, y1))
                    throw new IllegalStateException("OpenJPEG refused the area " + x0 + "," + y0 + " to " + x1 + "," + y1
                            + ": " + complaints);

                if (0 == (int) api.decode().invokeExact(codec, stream, image))
                    throw new IllegalStateException("OpenJPEG could not decode the image: " + complaints);
                int ended = (int) api.endDecompress().invokeExact(codec, stream);
                if (ended == 0)
                    throw new IllegalStateException("OpenJPEG could not finish the codestream");

                return read(image);
            } finally {
                if (!image.equals(MemorySegment.NULL))
                    api.imageDestroy().invokeExact(image);
                api.streamDestroy().invokeExact(stream);
                api.destroyCodec().invokeExact(codec);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("OpenJPEG decode failed", t);
        }
    }

    /**
     * A stream over bytes already in memory. OpenJPEG pulls through three callbacks, so the
     * position lives here rather than in the C side's user data, which stays null.
     */
    private static MemorySegment memoryStream(Api api, Arena arena, MemorySegment bytes, long length) throws Throwable {
        MemorySegment stream = (MemorySegment) api.streamCreate().invokeExact(1L << 20, 1);
        if (stream.equals(MemorySegment.NULL))
            throw new IllegalStateException("OpenJPEG would not create a stream");

        long[] position = {0};
        Linker linker = Linker.nativeLinker();

        Reader reader = (buffer, want, user) -> {
            long left = length - position[0];
            if (left <= 0)
                return -1; // (OPJ_SIZE_T)-1 is OpenJPEG's end of stream
            long n = Math.min(want, left);
            MemorySegment.copy(bytes, position[0], buffer.reinterpret(n), 0, n);
            position[0] += n;
            return n;
        };
        Skipper skipper = (want, user) -> {
            long n = Math.min(want, length - position[0]);
            position[0] += n;
            return n;
        };
        Seeker seeker = (to, user) -> {
            if (to < 0 || to > length)
                return 0;
            position[0] = to;
            return 1;
        };

        api.setReadFunction().invokeExact(stream, upcall(linker, arena, reader, Reader.class, "read",
                FunctionDescriptor.of(I64, ValueLayout.ADDRESS, I64, ValueLayout.ADDRESS)));
        api.setSkipFunction().invokeExact(stream, upcall(linker, arena, skipper, Skipper.class, "skip",
                FunctionDescriptor.of(I64, I64, ValueLayout.ADDRESS)));
        api.setSeekFunction().invokeExact(stream, upcall(linker, arena, seeker, Seeker.class, "seek",
                FunctionDescriptor.of(I32, I64, ValueLayout.ADDRESS)));
        api.setUserData().invokeExact(stream, MemorySegment.NULL, MemorySegment.NULL);
        api.setUserDataLength().invokeExact(stream, length);
        return stream;
    }

    @FunctionalInterface
    private interface Message {
        void message(MemorySegment text, MemorySegment user);
    }

    @FunctionalInterface
    private interface Reader {
        long read(MemorySegment buffer, long want, MemorySegment user);
    }

    @FunctionalInterface
    private interface Skipper {
        long skip(long want, MemorySegment user);
    }

    @FunctionalInterface
    private interface Seeker {
        int seek(long to, MemorySegment user);
    }

    @SuppressWarnings("restricted")
    private static MemorySegment upcall(Linker linker, Arena arena, Object target, Class<?> type, String method,
                                        FunctionDescriptor descriptor) throws Throwable {
        MethodHandle handle = java.lang.invoke.MethodHandles.lookup()
                .findVirtual(type, method, descriptor.toMethodType())
                .bindTo(target);
        return linker.upcallStub(handle, descriptor, arena);
    }

    private static Decoded read(MemorySegment image) {
        MemorySegment header = image.reinterpret(IMAGE_COMPS + 8);
        int numComps = header.get(I32, IMAGE_NUMCOMPS);
        if (numComps != 1)
            throw new UnsupportedOperationException("OpenJpeg decodes one greyscale component, not " + numComps);

        MemorySegment comp = header.get(ValueLayout.ADDRESS, IMAGE_COMPS).reinterpret(COMP_BYTES);
        int width = comp.get(I32, COMP_W);
        int height = comp.get(I32, COMP_H);
        int precision = comp.get(I32, COMP_PREC);
        boolean signed = comp.get(I32, COMP_SGND) != 0;

        MemorySegment data = comp.get(ValueLayout.ADDRESS, COMP_DATA);
        if (data.equals(MemorySegment.NULL))
            throw new IllegalStateException("OpenJPEG returned an image with no samples");

        int count = Math.multiplyExact(width, height);
        int[] samples = data.reinterpret((long) count * Integer.BYTES).toArray(I32);
        return new Decoded(width, height, precision, signed, samples);
    }

    private OpenJpeg() {}

}
