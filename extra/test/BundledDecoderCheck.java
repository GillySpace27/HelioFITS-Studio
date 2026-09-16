package org.helioviewer.jhv.view.j2k.opj;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Every platform's build carries a JPEG 2000 decoder, under the name that platform will look for.
 *
 * <p>Since Kakadu went, JPEG 2000 is decoded by OpenJPEG, which the application unpacks from the
 * natives jar for the platform it is running on. Drop that file from one jar and nothing fails
 * until somebody on that platform opens a Helioviewer image, which is the sort of hole a release
 * carries to a user rather than to a build log.
 *
 * <p>The names are what {@code System.mapLibraryName} produces on each platform, so they are not
 * interchangeable: a Linux build looking for libopenjp2.so does not find libopenjp2.so.7.
 *
 * <p>Run: java -cp "bin:extra/test-classes" org.helioviewer.jhv.view.j2k.opj.BundledDecoderCheck
 */
public final class BundledDecoderCheck {

    private record Bundle(String jar, String entry, String magic, String what) {}

    private static final Bundle[] BUNDLES = {
            new Bundle("lib/jhv/jhv-natives-linux.jar", "jhv/linux-amd64/libopenjp2.so", "7f454c46", "an ELF shared object"),
            new Bundle("lib/jhv/jhv-natives-macos-arm64.jar", "jhv/macos-arm64/libopenjp2.dylib", "cffaedfe", "a Mach-O library"),
            new Bundle("lib/jhv/jhv-natives-macos.jar", "jhv/macos-amd64/libopenjp2.dylib", "cffaedfe", "a Mach-O library"),
            new Bundle("lib/jhv/jhv-natives-windows.jar", "jhv/windows-amd64/openjp2.dll", "4d5a", "a Windows DLL"),
    };

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws IOException {
        for (Bundle bundle : BUNDLES) {
            Path jar = Path.of(bundle.jar());
            if (!Files.isReadable(jar)) {
                expect(bundle.jar() + " is where it should be", false);
                continue;
            }
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry(bundle.entry());
                if (entry == null) {
                    expect(bundle.entry() + " is in " + bundle.jar(), false);
                    continue;
                }
                byte[] head = new byte[4];
                int read;
                try (InputStream in = zip.getInputStream(entry)) {
                    read = in.readNBytes(head, 0, 4);
                }
                String magic = hex(head, Math.min(read, bundle.magic().length() / 2));
                expect(bundle.entry() + " is " + bundle.what() + " of " + entry.getSize() + " bytes",
                        bundle.magic().equals(magic) && entry.getSize() > 100_000);
            }
        }

        // The decoder the running platform will actually load, when this is run on one we bundle for.
        String expected = System.mapLibraryName("openjp2");
        boolean known = false;
        for (Bundle bundle : BUNDLES)
            known |= bundle.entry().endsWith('/' + expected);
        expect("this platform's name for the library, " + expected + ", is one of the bundled ones", known);

        System.out.println(failures == 0 ? "BundledDecoderCheck: PASS" : "BundledDecoderCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String hex(byte[] bytes, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++)
            out.append(String.format("%02x", bytes[i]));
        return out.toString();
    }

    private BundledDecoderCheck() {}

}
