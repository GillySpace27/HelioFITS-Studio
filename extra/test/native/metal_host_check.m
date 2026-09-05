// Loads the built dylib and checks the EDR additions without a window: the canvas format for
// each mode, and that the headroom query answers. Build and run:
//   xcrun clang -fobjc-arc -framework AppKit -framework IOSurface -o /tmp/metal_host_check \
//       extra/test/native/metal_host_check.m && /tmp/metal_host_check lib/natives-macos/libjhvmetalhost.dylib
#import <AppKit/AppKit.h>
#import <IOSurface/IOSurfaceRef.h>
#import <dlfcn.h>
int main(int argc, const char *argv[]) {
    void *lib = dlopen(argc > 1 ? argv[1] : "lib/natives-macos/libjhvmetalhost.dylib", RTLD_NOW);
    if (!lib) { printf("FAIL dlopen: %s\n", dlerror()); return 1; }
    void *(*create)(int, int, int) = dlsym(lib, "jhv_deep_canvas_create");
    void (*release)(void *) = dlsym(lib, "jhv_deep_canvas_release");
    double (*headroom)(void *) = dlsym(lib, "jhv_metal_host_edr_headroom");
    double (*potential)(void *) = dlsym(lib, "jhv_metal_host_edr_potential");
    if (!create || !release || !headroom || !potential) { printf("FAIL missing symbol\n"); return 1; }
    int fails = 0;
    IOSurfaceRef ten = create(64, 64, 0), edr = create(64, 64, 1);
    if (IOSurfaceGetPixelFormat(ten) != 'l10r' || IOSurfaceGetBytesPerElement(ten) != 4) { printf("FAIL 10-bit canvas format\n"); fails++; }
    if (IOSurfaceGetPixelFormat(edr) != 'RGhA' || IOSurfaceGetBytesPerElement(edr) != 8) { printf("FAIL EDR canvas format\n"); fails++; }
    release(ten); release(edr);
    double h = headroom(NULL);
    if (!(h >= 1.0)) { printf("FAIL headroom(NULL) = %f\n", h); fails++; }
    if (!(potential(NULL) >= 1.0)) { printf("FAIL potential(NULL)\n"); fails++; }
    printf("%s (headroom with no layer = %.2f)\n", fails ? "FAIL" : "metal_host_check: PASS", h);
    return fails ? 1 : 0;
}
