package org.helioviewer.jhv.app;

/** Linux on ARM64 has no native libraries yet: Platform must say so in words. */
public class PlatformLinuxArmCheck {
    public static void main(String[] args) {
        String os = System.getProperty("os.name"), arch = System.getProperty("os.arch");
        int fail = 0;
        try {
            System.setProperty("os.name", "Linux");
            System.setProperty("os.arch", "aarch64");
            try {
                Platform.init();
                System.out.println("  FAIL Linux aarch64 was accepted");
                fail++;
            } catch (IllegalStateException e) {
                boolean said = e.getMessage().contains("ARM64") && e.getMessage().contains("x86_64");
                System.out.println((said ? "  ok   " : "  FAIL ") + "Linux aarch64 is refused with an ARM64 message: " + e.getMessage());
                if (!said) fail++;
            }
            System.setProperty("os.arch", "amd64");
            try {
                Platform.init();
                boolean ok = "/jhv/linux-amd64/".equals(Platform.getResourceDir());
                System.out.println((ok ? "  ok   " : "  FAIL ") + "Linux amd64 still resolves to linux-amd64");
                if (!ok) fail++;
            } catch (IllegalStateException e) {
                System.out.println("  FAIL Linux amd64 refused: " + e.getMessage());
                fail++;
            }
        } finally {
            System.setProperty("os.name", os);
            System.setProperty("os.arch", arch);
        }
        System.exit(fail == 0 ? 0 : 1);
    }
}
