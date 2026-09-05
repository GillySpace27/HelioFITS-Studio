# EDR Canvas Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Image layers render brighter than the interface white on the XDR panel, up to the display's current EDR headroom, with a flat user-controlled gain; nothing exported changes.

**Architecture:** The existing deep-colour path (IOSurface pbuffer rendered by ANGLE, presented by a native Metal step into the app's own CAMetalLayer) is extended one rung: the IOSurface becomes RGBA16F, the layer becomes RGBA16Float tagged extended linear sRGB with EDR requested, and the blit becomes a one-triangle render pass that applies the sRGB EOTF. One shader uniform multiplies image-layer colour by the gain; capture paths pass gain 1.

**Tech Stack:** Java 25 (FFM downcalls), LWJGL 3.4.1 EGL/GLES over ANGLE Metal, Objective-C + Metal (runtime-compiled MSL), ant.

## Global Constraints

- Branch `edr-canvas`, worktree `~/Documents/NWRA/PUNCH_Science/jhv-edr`. `demo-all` and `jhv-demo` are never touched.
- Settings keys: `display.edrCanvas` (default true), `display.hdrGain` (`auto` or a number in [1, 16], default `auto`). `display.deepColorCanvas` keeps its meaning.
- Fallback ladder: EDR → 10-bit deep → 8-bit, each step logged with what it got.
- Gain is applied to RGB only, never alpha, and is 1 whenever `GLImage.capture != Capture.NONE`.
- No em dashes anywhere. Jar is `HFStudio.jar` (`ant jar`). Run with `java --enable-native-access=ALL-UNNAMED -jar HFStudio.jar`.
- Probes under `extra/test` are compiled ad hoc: `javac -d extra/test-classes -cp "bin:$(find lib -name '*.jar' | tr '\n' ':')" extra/test/<Name>.java`, run with `java --enable-native-access=ALL-UNNAMED -cp "extra/test-classes:bin:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.<pkg>.<Name>`.

---

### Task 1: Native presenter: RGBA16F canvas, EDR layer, linearizing pass, headroom query

**Files:**
- Modify: `native/macos/jhv_metal_host.m` (deep-colour section, from `// --- Deep-colour presentation` to the end of `jhv_metal_host_present_deep`)
- Modify: `src/org/helioviewer/jhv/opengl/angle/MacAngleBridge.java` (PREPARE_DEEP, DEEP_CANVAS_CREATE, PRESENT_DEEP descriptors and wrappers; new EDR_HEADROOM)
- Test: `extra/test/native/metal_host_check.m`

**Interfaces:**
- Produces (C): `int jhv_metal_host_prepare_deep(void *layer, int edr)`, `void *jhv_deep_canvas_create(int w, int h, int edr)`, `int jhv_metal_host_present_deep(void *layer, void *surf, int w, int h, int edr)`, `double jhv_metal_host_edr_headroom(void *layer)`.
- Produces (Java): `MacAngleBridge.prepareDeepLayer(long layer, boolean edr)`, `deepCanvasCreate(int, int, boolean edr)`, `presentDeep(long, long, int, int, boolean edr)`, `edrHeadroom(long layer): double`.

- [ ] **Step 1: Write the failing native check**

`extra/test/native/metal_host_check.m`:

```objc
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
    if (!create || !release || !headroom) { printf("FAIL missing symbol\n"); return 1; }
    int fails = 0;
    IOSurfaceRef ten = create(64, 64, 0), edr = create(64, 64, 1);
    if (IOSurfaceGetPixelFormat(ten) != 'l10r' || IOSurfaceGetBytesPerElement(ten) != 4) { printf("FAIL 10-bit canvas format\n"); fails++; }
    if (IOSurfaceGetPixelFormat(edr) != 'RGhA' || IOSurfaceGetBytesPerElement(edr) != 8) { printf("FAIL EDR canvas format\n"); fails++; }
    release(ten); release(edr);
    double h = headroom(NULL);
    if (!(h >= 1.0)) { printf("FAIL headroom(NULL) = %f\n", h); fails++; }
    printf("%s (headroom with no layer = %.2f)\n", fails ? "FAIL" : "metal_host_check: PASS", h);
    return fails ? 1 : 0;
}
```

- [ ] **Step 2: Run it against the current dylib to see it fail**

Run: `xcrun clang -fobjc-arc -framework AppKit -framework IOSurface -o /tmp/metal_host_check extra/test/native/metal_host_check.m && /tmp/metal_host_check lib/natives-macos/libjhvmetalhost.dylib`
Expected: `FAIL missing symbol` (no `jhv_metal_host_edr_headroom` yet).

- [ ] **Step 3: Replace the deep-colour section of `jhv_metal_host.m`**

Delete from the line `// --- Deep-colour presentation ---` to the closing brace of `jhv_metal_host_present_deep`, and put this in its place. `jhv_metal_host_destroy` stays below it untouched.

```objc
// --- Deep-colour and EDR presentation --------------------------------------------------------
//
// The EGL window surface caps the canvas at 8 bits per channel because ANGLE's Metal backend
// only enumerates 8-bit configs. The route around it: the scene is rendered into an IOSurface
// wrapped as an EGL pbuffer (EGL_ANGLE_iosurface_client_buffer, whose format comes from the
// pbuffer attributes rather than the config), and these functions carry that IOSurface to the
// screen.
//
// Two modes, chosen by the caller:
//   edr = 0: 10-bit. BGR10A2 IOSurface, BGR10A2Unorm layer, colorspace nil, plain blit. The
//            compositor passes UNORM values through unchanged, so the image looks exactly as
//            the 8-bit path did with four times the levels.
//   edr = 1: RGBA16F IOSurface, RGBA16Float layer tagged extended linear sRGB with EDR content
//            requested, presented by a render pass that applies the sRGB EOTF (extended past
//            1.0). Measured 2026-09-04 (extra/test/native/edr_present_probe.m): tagging the layer
//            extended *sRGB* never engages EDR, extended *linear* sRGB does, so the conversion
//            to linear has to happen here. The canvas keeps the sRGB-encoded values every
//            shader and colour table assumes; only the last step changes.
//
// The vertical flip: GL's framebuffer origin is bottom-left, Metal's top-left. The 10-bit blit
// copies raw rows and flips the layer with a transform; the EDR pass samples row 0 at the
// bottom of the screen instead, so the layer transform is identity in that mode.

@interface JHVDeepPresenter : NSObject
@property(nonatomic, strong) id<MTLCommandQueue> queue;
@property(nonatomic, strong) id<MTLTexture> wrapped;      // MTLTexture view of the canvas IOSurface
@property(nonatomic, assign) IOSurfaceRef wrappedSurface; // cache key only, not retained here
@property(nonatomic, strong) id<MTLRenderPipelineState> edrPipeline;
@end

@implementation JHVDeepPresenter
@end

static char jhv_deep_presenter_key;

// Screen headroom, read on the main thread after each EDR present and served from here, so the
// render thread never touches AppKit. 1.0 until the first EDR frame has been on screen.
static double jhv_edr_headroom_cached = 1.0;

static NSScreen *jhv_screen_of_layer(CALayer *layer) {
    CALayer *root = layer;
    while (root.superlayer != nil)
        root = root.superlayer;
    id delegate = root.delegate;
    NSScreen *screen = [delegate isKindOfClass:NSView.class] ? ((NSView *)delegate).window.screen : nil;
    return screen != nil ? screen : NSScreen.mainScreen;
}

static NSString *const jhv_edr_shader_source = @
    "#include <metal_stdlib>\n"
    "using namespace metal;\n"
    "struct V { float4 pos [[position]]; float2 uv; };\n"
    "vertex V jhv_edr_vertex(uint vid [[vertex_id]]) {\n"
    "    float2 p[3] = { float2(-1, -1), float2(3, -1), float2(-1, 3) };\n"
    "    V o; o.pos = float4(p[vid], 0, 1);\n"
    // GL wrote row 0 as the bottom of the image; Metal's v = 0 is row 0. Mapping NDC y = -1
    // (screen bottom) to v = 0 therefore shows the image upright with no explicit flip.
    "    o.uv = (p[vid] + 1.0) * 0.5;\n"
    "    return o;\n"
    "}\n"
    "fragment float4 jhv_edr_fragment(V in [[stage_in]], texture2d<float> src [[texture(0)]]) {\n"
    "    constexpr sampler s(coord::normalized, filter::nearest, address::clamp_to_edge);\n"
    "    float4 c = src.sample(s, in.uv);\n"
    // sRGB EOTF, extended past 1.0 by applying it to the magnitude (how Apple's extended
    // spaces are defined). The canvas is premultiplied over an opaque black layer, so its RGB
    // is already the final colour and linearizing it directly is exact.
    "    float3 a = abs(c.rgb);\n"
    "    float3 lin = select(pow((a + 0.055) / 1.055, 2.4), a / 12.92, a <= 0.04045);\n"
    "    return float4(sign(c.rgb) * lin, c.a);\n"
    "}\n";

static id<MTLRenderPipelineState> jhv_edr_pipeline(id<MTLDevice> device) {
    NSError *error = nil;
    id<MTLLibrary> library = [device newLibraryWithSource:jhv_edr_shader_source options:nil error:&error];
    if (library == nil) {
        NSLog(@"jhv_metal_host: EDR shader failed to compile: %@", error);
        return nil;
    }
    MTLRenderPipelineDescriptor *desc = [MTLRenderPipelineDescriptor new];
    desc.vertexFunction = [library newFunctionWithName:@"jhv_edr_vertex"];
    desc.fragmentFunction = [library newFunctionWithName:@"jhv_edr_fragment"];
    desc.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
    id<MTLRenderPipelineState> pipeline = [device newRenderPipelineStateWithDescriptor:desc error:&error];
    if (pipeline == nil)
        NSLog(@"jhv_metal_host: EDR pipeline failed: %@", error);
    return pipeline;
}

// Switch the layer to the deep format for the mode. Returns 1 on success. Main-thread: the
// layer is in a live tree.
int jhv_metal_host_prepare_deep(void *layerPtr, int edr) {
    if (layerPtr == NULL)
        return 0;

    __block int ok = 0;
    jhv_run_on_main_sync(^{
        @autoreleasepool {
            CAMetalLayer *layer = (__bridge CAMetalLayer *)layerPtr;
            jhv_run_without_actions(^{
                if (edr) {
                    layer.pixelFormat = MTLPixelFormatRGBA16Float;
                    layer.wantsExtendedDynamicRangeContent = YES;
                    CGColorSpaceRef linear = CGColorSpaceCreateWithName(kCGColorSpaceExtendedLinearSRGB);
                    layer.colorspace = linear;
                    CGColorSpaceRelease(linear);
                    layer.transform = CATransform3DIdentity; // the pass samples upright
                } else {
                    layer.pixelFormat = MTLPixelFormatBGR10A2Unorm;
                    layer.wantsExtendedDynamicRangeContent = NO;
                    layer.colorspace = nil;
                    layer.transform = CATransform3DMakeScale(1, -1, 1);
                }
            });
            ok = 1;
        }
    });
    return ok;
}

// Undo prepare_deep before ANGLE takes the layer back as an 8-bit window surface (ANGLE resets
// the pixel format itself, but not the transform, the colorspace or the EDR request).
void jhv_metal_host_reset_deep(void *layerPtr) {
    if (layerPtr == NULL)
        return;

    jhv_run_on_main_sync(^{
        @autoreleasepool {
            CAMetalLayer *layer = (__bridge CAMetalLayer *)layerPtr;
            jhv_run_without_actions(^{
                layer.transform = CATransform3DIdentity;
                layer.wantsExtendedDynamicRangeContent = NO;
                layer.colorspace = nil;
            });
        }
    });
}

// The canvas IOSurface: 'l10r' (BGR10A2, 4 bytes) for 10-bit, 'RGhA' (RGBA16F, 8 bytes) for
// EDR. Returned retained; release with jhv_deep_canvas_release.
void *jhv_deep_canvas_create(int width, int height, int edr) {
    if (width <= 0 || height <= 0)
        return NULL;

    int bytesPerElement = edr ? 8 : 4;
    int64_t pixelFormat = edr ? 'RGhA' : 'l10r';
    size_t bpr = IOSurfaceAlignProperty(kIOSurfaceBytesPerRow, (size_t)width * bytesPerElement);
    size_t allocSize = IOSurfaceAlignProperty(kIOSurfaceAllocSize, bpr * height);
    int64_t values[] = {width, height, pixelFormat, bytesPerElement, (int64_t)bpr, (int64_t)allocSize};
    CFStringRef keys[] = {kIOSurfaceWidth, kIOSurfaceHeight, kIOSurfacePixelFormat,
                          kIOSurfaceBytesPerElement, kIOSurfaceBytesPerRow, kIOSurfaceAllocSize};
    CFMutableDictionaryRef props = CFDictionaryCreateMutable(NULL, 6,
            &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    for (size_t i = 0; i < 6; i++) {
        CFNumberRef number = CFNumberCreate(NULL, kCFNumberSInt64Type, &values[i]);
        CFDictionarySetValue(props, keys[i], number);
        CFRelease(number);
    }
    IOSurfaceRef surf = IOSurfaceCreate(props);
    CFRelease(props);
    return surf;
}

void jhv_deep_canvas_release(void *surfPtr) {
    if (surfPtr != NULL)
        CFRelease((IOSurfaceRef)surfPtr);
}

// The screen's current EDR headroom in units of SDR white, as of the last EDR present; 1.0
// before any (and always 1.0 when EDR is not engaged). Safe from any thread.
double jhv_metal_host_edr_headroom(void *layerPtr) {
    (void)layerPtr;
    return jhv_edr_headroom_cached;
}

// Carry the rendered IOSurface into the layer's next drawable and present it. Called on the
// render thread after the GL work has finished (glFinish), and returns only after the GPU work
// has completed, so the caller may immediately render the next frame into the same IOSurface.
// ponytail: fully synchronous single-buffer present; ping-pong IOSurfaces + MTLSharedEvent if
// the wait ever shows up in a profile.
int jhv_metal_host_present_deep(void *layerPtr, void *surfPtr, int width, int height, int edr) {
    if (layerPtr == NULL || surfPtr == NULL || width <= 0 || height <= 0)
        return 0;

    @autoreleasepool {
        CAMetalLayer *layer = (__bridge CAMetalLayer *)layerPtr;
        IOSurfaceRef surf = (IOSurfaceRef)surfPtr;
        JHVDeepPresenter *presenter = objc_getAssociatedObject(layer, &jhv_deep_presenter_key);
        if (presenter == nil) {
            presenter = [JHVDeepPresenter new];
            presenter.queue = [layer.device newCommandQueue];
            objc_setAssociatedObject(layer, &jhv_deep_presenter_key, presenter, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        }
        if (presenter.queue == nil)
            return 0;
        if (edr && presenter.edrPipeline == nil) {
            presenter.edrPipeline = jhv_edr_pipeline(layer.device);
            if (presenter.edrPipeline == nil)
                return 0;
        }

        MTLPixelFormat canvasFormat = edr ? MTLPixelFormatRGBA16Float : MTLPixelFormatBGR10A2Unorm;
        // Pointer AND size AND format: a fresh IOSurface can reuse a released one's address.
        if (presenter.wrapped == nil || presenter.wrappedSurface != surf || presenter.wrapped.pixelFormat != canvasFormat
                || presenter.wrapped.width != (NSUInteger)width || presenter.wrapped.height != (NSUInteger)height) {
            MTLTextureDescriptor *desc = [MTLTextureDescriptor
                    texture2DDescriptorWithPixelFormat:canvasFormat
                                                 width:width height:height mipmapped:NO];
            desc.usage = MTLTextureUsageShaderRead;
            // IOSurface-backed textures must be Shared on unified memory, Managed on discrete.
            desc.storageMode = layer.device.hasUnifiedMemory ? MTLStorageModeShared : MTLStorageModeManaged;
            presenter.wrapped = [layer.device newTextureWithDescriptor:desc iosurface:surf plane:0];
            presenter.wrappedSurface = surf;
            if (presenter.wrapped == nil)
                return 0;
        }

        id<CAMetalDrawable> drawable = [layer nextDrawable];
        if (drawable == nil)
            return 0;

        id<MTLTexture> dst = drawable.texture;
        NSUInteger w = MIN((NSUInteger)width, dst.width);
        NSUInteger h = MIN((NSUInteger)height, dst.height);
        id<MTLCommandBuffer> commands = [presenter.queue commandBuffer];
        if (edr) {
            MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
            pass.colorAttachments[0].texture = dst;
            pass.colorAttachments[0].loadAction = MTLLoadActionClear; // mid-resize border is black, not stale memory
            pass.colorAttachments[0].storeAction = MTLStoreActionStore;
            pass.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1);
            id<MTLRenderCommandEncoder> encoder = [commands renderCommandEncoderWithDescriptor:pass];
            [encoder setRenderPipelineState:presenter.edrPipeline];
            // Viewport covers exactly the canvas-sized region; the source is sampled 1:1 over it.
            [encoder setViewport:(MTLViewport){0, 0, (double)w, (double)h, 0, 1}];
            [encoder setFragmentTexture:presenter.wrapped atIndex:0];
            [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            [encoder endEncoding];
        } else {
            if (w != dst.width || h != dst.height) {
                // Mid-resize the canvas and the drawable disagree for a frame; clear the drawable so
                // the uncovered border is black rather than stale memory.
                MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
                pass.colorAttachments[0].texture = dst;
                pass.colorAttachments[0].loadAction = MTLLoadActionClear;
                pass.colorAttachments[0].storeAction = MTLStoreActionStore;
                pass.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1);
                [[commands renderCommandEncoderWithDescriptor:pass] endEncoding];
            }
            id<MTLBlitCommandEncoder> blit = [commands blitCommandEncoder];
            [blit copyFromTexture:presenter.wrapped
                      sourceSlice:0 sourceLevel:0
                     sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(w, h, 1)
                        toTexture:dst
                 destinationSlice:0 destinationLevel:0
                destinationOrigin:MTLOriginMake(0, 0, 0)];
            [blit endEncoding];
        }
        [commands presentDrawable:drawable];
        [commands commit];
        [commands waitUntilCompleted];

        if (edr) {
            jhv_run_on_main_async(^{
                @autoreleasepool {
                    NSScreen *screen = jhv_screen_of_layer(layer);
                    jhv_edr_headroom_cached = screen != nil ? screen.maximumExtendedDynamicRangeColorComponentValue : 1.0;
                }
            });
        }
        return 1;
    }
}
```

- [ ] **Step 4: Update the Java bridge**

In `MacAngleBridge.java` replace the three descriptors and add one:

```java
    private static final MethodHandle PREPARE_DEEP = downcall("jhv_metal_host_prepare_deep",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle DEEP_CANVAS_CREATE = downcall("jhv_deep_canvas_create",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle PRESENT_DEEP = downcall("jhv_metal_host_present_deep",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle EDR_HEADROOM = downcall("jhv_metal_host_edr_headroom",
            FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.ADDRESS));
```

and replace the three wrappers, adding a fourth:

```java
    // Switch the CAMetalLayer to the deep format: 10-bit unorm, or RGBA16Float tagged linear with
    // EDR requested.
    public static boolean prepareDeepLayer(long layer, boolean edr) {
        try {
            return (int) PREPARE_DEEP.invokeExact(MemorySegment.ofAddress(layer), edr ? 1 : 0) != 0;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to prepare deep-colour layer", t);
        }
    }

    // An RGB10_A2 (or, for EDR, RGBA16F) IOSurface for the canvas; 0 on failure. Release with deepCanvasRelease.
    public static long deepCanvasCreate(int width, int height, boolean edr) {
        try {
            return ((MemorySegment) DEEP_CANVAS_CREATE.invokeExact(width, height, edr ? 1 : 0)).address();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create deep-colour canvas IOSurface", t);
        }
    }

    // Carry the rendered IOSurface into the layer's drawable and present it. Call after glFinish.
    public static boolean presentDeep(long layer, long ioSurface, int width, int height, boolean edr) {
        try {
            return (int) PRESENT_DEEP.invokeExact(MemorySegment.ofAddress(layer),
                    MemorySegment.ofAddress(ioSurface), width, height, edr ? 1 : 0) != 0;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to present deep-colour canvas", t);
        }
    }

    // The screen's EDR headroom in SDR whites, as of the last EDR present (1 before any).
    public static double edrHeadroom(long layer) {
        try {
            return (double) EDR_HEADROOM.invokeExact(MemorySegment.ofAddress(layer));
        } catch (Throwable t) {
            throw new RuntimeException("Failed to read EDR headroom", t);
        }
    }
```

- [ ] **Step 5: Rebuild the dylib and run the check**

Run: `ant build-metal-host && xcrun clang -fobjc-arc -framework AppKit -framework IOSurface -o /tmp/metal_host_check extra/test/native/metal_host_check.m && /tmp/metal_host_check lib/natives-macos/libjhvmetalhost.dylib`
Expected: `metal_host_check: PASS (headroom with no layer = 1.00)`

- [ ] **Step 6: Commit**

```bash
git add native/macos/jhv_metal_host.m src/org/helioviewer/jhv/opengl/angle/MacAngleBridge.java extra/test/native/metal_host_check.m lib/natives-macos/libjhvmetalhost.dylib
git commit -m "Metal host: RGBA16F canvas, EDR layer, linearizing present pass, headroom query"
```

(The dylib is tracked in this repo; the 10-bit commit shipped it the same way.)

---

### Task 2: Renderer: the EDR rung of the ladder

**Files:**
- Modify: `src/org/helioviewer/jhv/opengl/angle/AngleRenderer.java` (constants near line 89, `create`, `deepColor`, constructor deep branch, `render`, `createDeepSurface`)
- Modify: `src/org/helioviewer/jhv/display/Display.java` (next to `public static boolean deepCanvas;`)

**Interfaces:**
- Consumes: `MacAngleBridge.prepareDeepLayer(long, boolean)`, `deepCanvasCreate(int, int, boolean)`, `presentDeep(long, long, int, int, boolean)`, `edrHeadroom(long)`.
- Produces: `Display.edrCanvas` (boolean, true when the EDR rung is live), `Display.edrHeadroom` (volatile double, refreshed each presented frame).

- [ ] **Step 1: Add the Display fields**

After `public static boolean deepCanvas;` in `Display.java`:

```java
    /**
     * True when the canvas is the EDR rung: RGBA16F IOSurface, RGBA16Float layer tagged linear.
     * Image layers may then be scaled past 1.0 by {@link HdrGain}.
     */
    public static boolean edrCanvas;

    /** The screen's EDR headroom in SDR whites, refreshed after each presented frame; 1 otherwise. */
    public static volatile double edrHeadroom = 1;
```

- [ ] **Step 2: Add the EDR constants and setting to AngleRenderer**

Below `private static final int GL_RGB10_A2 = 0x8059;` add:

```java
    private static final int GL_RGBA = 0x1908;
    private static final int GL_HALF_FLOAT = 0x140B;
```

Below the `deepColor` field add:

```java
    // EDR is the rung above deep colour: same IOSurface route, half-float canvas, layer tagged
    // linear with EDR requested, so image layers can exceed the interface white. Off leaves the
    // 10-bit canvas exactly as it was.
    private static boolean edrColor =
            !"false".equals(org.helioviewer.jhv.app.Settings.getProperty("display.edrCanvas"));
    private boolean edr; // this renderer's canvas is the EDR rung
```

- [ ] **Step 3: Extend the ladder in `create`**

Replace the body of `create` with:

```java
    private static AngleRenderer create(SurfaceKind kind, long nativeWindowHandle, int width, int height) {
        try {
            return new AngleRenderer(kind, nativeWindowHandle, width, height);
        } catch (RuntimeException e) {
            if (edrColor && deepColor) {
                Log.warn("EDR canvas failed, falling back to 10 bits per channel", e);
                edrColor = false;
                return create(kind, nativeWindowHandle, width, height);
            }
            if (!deepColor)
                throw e;
            // Deep colour is the only thing asked for here that a driver might refuse, so it is
            // the only thing worth giving up before failing outright.
            Log.warn("Deep-colour canvas failed, falling back to 8 bits per channel", e);
            deepColor = false;
            return new AngleRenderer(kind, nativeWindowHandle, width, height);
        }
    }
```

- [ ] **Step 4: Plan and log the EDR rung in the constructor**

Replace the block from `if (deepSurfacePlanned && MacAngleBridge.prepareDeepLayer(nativeWindowHandle)) {` through the matching `} else {` line's preceding `Log.info(...)` with:

```java
                boolean edrPlanned = deepSurfacePlanned && edrColor;
                if (deepSurfacePlanned && MacAngleBridge.prepareDeepLayer(nativeWindowHandle, edrPlanned)) {
                    deepLayer = nativeWindowHandle;
                    edr = edrPlanned;
                    newSurface = createDeepSurface(stack, Math.max(1, Display.getCanvasWidth()), Math.max(1, Display.getCanvasHeight()));
                    Display.deepCanvas = true;
                    Display.edrCanvas = edr;
                    if (edr)
                        Log.info("EDR canvas: RGBA16F IOSurface pbuffer, CAMetalLayer RGBA16Float tagged extended linear sRGB,"
                                + " EDR content requested; headroom is logged after the first frame");
                    else
                        Log.info("Deep-colour canvas: RGB10_A2 IOSurface pbuffer presented by Metal blit;"
                                + " CAMetalLayer pixelFormat=BGR10A2Unorm, colorspace unmanaged as before");
                } else {
```

and in the `else` branch, after `Display.deepCanvas = false;` add `Display.edrCanvas = false;`.

- [ ] **Step 5: Present with the mode, and track the headroom**

Replace the deep branch of `render` with:

```java
        if (deepLayer != 0L) {
            GLES20.glFinish(); // the Metal pass below reads the IOSurface; the GL writes must be done
            if (!MacAngleBridge.presentDeep(deepLayer, deepCanvas, deepWidth, deepHeight, edr))
                Log.warn("Deep-colour present failed for a frame");
            else if (edr) {
                double headroom = MacAngleBridge.edrHeadroom(deepLayer);
                if (headroom != Display.edrHeadroom) {
                    Display.edrHeadroom = headroom;
                    Log.info("EDR headroom now " + headroom + " SDR whites");
                }
            }
        } else if (swapBuffers && !EGL15.eglSwapBuffers(display, surface))
```

- [ ] **Step 6: Create the canvas in the mode's format**

In `createDeepSurface` replace the `deepCanvasCreate` call and the attrs:

```java
        long ioSurface = MacAngleBridge.deepCanvasCreate(width, height, edr);
        if (ioSurface == 0L)
            throw new RuntimeException("IOSurface creation failed for deep-colour canvas " + width + "x" + height);

        IntBuffer attrs = stack.ints(
                EGL15.EGL_WIDTH, width,
                EGL15.EGL_HEIGHT, height,
                EGL_IOSURFACE_PLANE_ANGLE, 0,
                EGL_TEXTURE_TARGET, EGL_TEXTURE_2D,
                EGL_TEXTURE_INTERNAL_FORMAT_ANGLE, edr ? GL_RGBA : GL_RGB10_A2,
                EGL_TEXTURE_FORMAT, EGL_TEXTURE_RGBA,
                EGL_TEXTURE_TYPE_ANGLE, edr ? GL_HALF_FLOAT : GL_UNSIGNED_INT_2_10_10_10_REV,
                EGL_IOSURFACE_USAGE_HINT_ANGLE, 3, // read | write
                EGL15.EGL_NONE);
```

- [ ] **Step 7: Compile and run once, check the log**

Run: `ant jar && (java --enable-native-access=ALL-UNNAMED -jar HFStudio.jar & sleep 25; pkill -f HFStudio.jar)`
Then: `grep -h -E 'EDR canvas|EDR headroom|Deep-colour' "$(ls -t ~/JHelioviewer-SWHV/Logs/*.log | head -1)"`
Expected: the `EDR canvas:` line, and an `EDR headroom now <value> SDR whites` line with value > 1 (10.85 measured at the brightness used on 2026-09-04). If the headroom line says 1.0, the layer is not engaging EDR; stop and check `prepare_deep` ran with `edr = 1` (the `EDR canvas:` line proves the Java side asked for it).

- [ ] **Step 8: Commit**

```bash
git add src/org/helioviewer/jhv/opengl/angle/AngleRenderer.java src/org/helioviewer/jhv/display/Display.java
git commit -m "Renderer: EDR rung above deep colour, with its own fallback and headroom log"
```

---

### Task 3: The gain: setting, shader uniform, capture exemption

**Files:**
- Create: `src/org/helioviewer/jhv/display/HdrGain.java`
- Modify: `resources/glsl/solarCommon.frag` (DisplayBlock and the final return)
- Modify: `src/org/helioviewer/jhv/opengl/GLSLSolarShader.java` (displayBuf capacity, bindDisplay)
- Modify: `src/org/helioviewer/jhv/opengl/GLImage.java` (the bindDisplay call in applyFilters)
- Test: `extra/test/HdrGainCheck.java`

**Interfaces:**
- Produces: `HdrGain.resolve(String setting, double headroom, boolean sdr): float`, `HdrGain.current(boolean capturing): float`, `HdrGain.setting(): String`, `HdrGain.setSetting(String)`, `HdrGain.canvasEnabled(): boolean`, `HdrGain.setCanvasEnabled(boolean)`.
- Consumes: `Display.edrCanvas`, `Display.edrHeadroom`.

- [ ] **Step 1: Write the failing check**

`extra/test/HdrGainCheck.java`:

```java
package org.helioviewer.jhv.display;

// Standalone self-check (no test framework in this repo; see extra/test/LUTLabelsCheck.java for
// the pattern). The gain is the one number that decides whether an image is brighter than the
// window, and whether an export is contaminated by it. Both directions are pinned here.
public final class HdrGainCheck {

    public static void main(String[] args) {
        // The capture exemption wins over everything: exports never see a gain.
        assertEq("capturing", 1f, HdrGain.resolve("auto", 10.85, true));
        assertEq("capturing, fixed", 1f, HdrGain.resolve("4", 10.85, true));
        // Auto tracks the display, and never dims below 1.
        assertEq("auto", 10.85f, HdrGain.resolve("auto", 10.85, false));
        assertEq("auto, no EDR", 1f, HdrGain.resolve("auto", 1.0, false));
        assertEq("auto, absurd", 1f, HdrGain.resolve("auto", 0.0, false));
        // Fixed stops are clamped to [1, 16] and fall back to auto when unparsable.
        assertEq("fixed", 4f, HdrGain.resolve("4", 10.85, false));
        assertEq("fixed, too low", 1f, HdrGain.resolve("0.5", 10.85, false));
        assertEq("fixed, too high", 16f, HdrGain.resolve("64", 10.85, false));
        assertEq("garbage", 10.85f, HdrGain.resolve("bright", 10.85, false));
        assertEq("null", 10.85f, HdrGain.resolve(null, 10.85, false));
        System.out.println("HdrGainCheck: PASS");
    }

    private static void assertEq(String what, float expected, float actual) {
        if (Math.abs(expected - actual) > 1e-6f)
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `javac -d extra/test-classes -cp "bin:$(find lib -name '*.jar' | tr '\n' ':')" extra/test/HdrGainCheck.java`
Expected: compile error, `cannot find symbol: class HdrGain`.

- [ ] **Step 3: Write HdrGain**

`src/org/helioviewer/jhv/display/HdrGain.java`:

```java
package org.helioviewer.jhv.display;

import org.helioviewer.jhv.app.Settings;

/**
 * How far into the display's EDR headroom image layers are pushed.
 *
 * <p>A flat gain on the colour after the colour table (decided 2026-09-04, docs/edr-canvas-brief.md):
 * "auto" means the screen's current headroom, so LUT white lands on peak brightness and the rest
 * scales with it; a number is a fixed multiple of SDR white. Overlays never see it, and neither
 * does any capture path: what is exported is exactly what the 10-bit canvas would have shown.
 */
public final class HdrGain {

    private static final String KEY_GAIN = "display.hdrGain";
    private static final String KEY_CANVAS = "display.edrCanvas";
    private static final float MAX = 16;

    private static String setting = defaultSetting();

    private static String defaultSetting() {
        String stored = Settings.getProperty(KEY_GAIN);
        return stored == null || stored.isBlank() ? "auto" : stored.trim();
    }

    /** The gain the shader should apply now: 1 while capturing or without an EDR canvas. */
    public static float current(boolean capturing) {
        return resolve(setting, Display.edrHeadroom, capturing || !Display.edrCanvas);
    }

    /** Pure: the gain for a setting, a headroom and whether the target is SDR (capture, no EDR). */
    static float resolve(String _setting, double headroom, boolean sdr) {
        if (sdr)
            return 1;
        float auto = (float) Math.max(1, headroom);
        if (_setting == null || "auto".equals(_setting))
            return auto;
        try {
            return (float) Math.clamp(Double.parseDouble(_setting), 1, MAX);
        } catch (NumberFormatException e) {
            return auto;
        }
    }

    public static String setting() {
        return setting;
    }

    public static void setSetting(String _setting) {
        setting = _setting == null || _setting.isBlank() ? "auto" : _setting.trim();
        Settings.setProperty(KEY_GAIN, setting);
    }

    /** Whether the EDR rung is asked for at the next canvas attach; the renderer reads the same key. */
    public static boolean canvasEnabled() {
        return !"false".equals(Settings.getProperty(KEY_CANVAS));
    }

    public static void setCanvasEnabled(boolean enabled) {
        Settings.setProperty(KEY_CANVAS, Boolean.toString(enabled));
    }

    private HdrGain() {}
}
```

- [ ] **Step 4: Compile the app and run the check**

Run: `ant compile && javac -d extra/test-classes -cp "bin:$(find lib -name '*.jar' | tr '\n' ':')" extra/test/HdrGainCheck.java && java -cp "extra/test-classes:bin:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.display.HdrGainCheck`
Expected: `HdrGainCheck: PASS`

- [ ] **Step 5: Add the uniform to the shader**

In `resources/glsl/solarCommon.frag`, after `float rawOutput;` inside `DisplayBlock` add:

```glsl
    // Multiplies an image layer's RGB after the colour table, on the EDR canvas: 1 means
    // interface white, the screen's headroom means its peak. Alpha is never scaled. Starts a new
    // std140 16-byte row (the previous rounding floats were all taken), so the Java buffer grows
    // by four floats.
    float hdrGain;
```

and replace the final line of the colour function

```glsl
    return texture(lut, vec2(value, 0.5)) * display.color;
```

with

```glsl
    vec4 colour = texture(lut, vec2(value, 0.5)) * display.color;
    return vec4(colour.rgb * display.hdrGain, colour.a);
```

- [ ] **Step 6: Mirror it on the Java side**

In `GLSLSolarShader.java` change the buffer:

```java
    private static final FloatBuffer displayBuf = BufferUtils.newFloatBuffer(4 + 4 + 4 + 4 + 2 + 2 + 2 + 1 + 1 + 4 /* indexed, skipDither, showClipping, rawOutput */ + 4 /* hdrGain + std140 rounding */);
```

add a trailing parameter to `bindDisplay`:

```java
                            float indexed, float skipDither, float showClipping, float rawOutput,
                            float hdrGain) {
```

and after the `rawOutput` put line add:

```java
        displayBuf.put(hdrGain).put(0).put(0).put(0); // hdrGain opens a new std140 row; the rest is rounding
```

In `GLImage.applyFilters`, the `bindDisplay(...)` call ends with `raw ? 1 : 0);`. Change that ending to:

```java
                raw ? 1 : 0,
                HdrGain.current(raw));
```

and add `import org.helioviewer.jhv.display.HdrGain;` with the other display imports.

- [ ] **Step 7: Validate the shader and rebuild**

Run: `python3 extra/test/validate_glsl_syntax.py && ant jar`
Expected: the validator reports no errors; the jar builds.

- [ ] **Step 8: Run and look**

Run: `java --enable-native-access=ALL-UNNAMED -jar HFStudio.jar`
Load any image layer. Expected: the image is visibly brighter than the menu bar and the timestamp; the Swing chrome is unchanged. Check the log for `EDR headroom now` > 1.

- [ ] **Step 9: Commit**

```bash
git add src/org/helioviewer/jhv/display/HdrGain.java resources/glsl/solarCommon.frag src/org/helioviewer/jhv/opengl/GLSLSolarShader.java src/org/helioviewer/jhv/opengl/GLImage.java extra/test/HdrGainCheck.java
git commit -m "HDR gain: one uniform after the colour table, auto tracks the screen, captures stay at 1"
```

---

### Task 4: View menu: HDR Canvas toggle and HDR Brightness stops

**Files:**
- Modify: `src/org/helioviewer/jhv/gui/component/MenuBar.java` (after the `dither` item, before `viewMenu.addSeparator();`)

**Interfaces:**
- Consumes: `HdrGain.canvasEnabled()`, `setCanvasEnabled(boolean)`, `setting()`, `setSetting(String)`.

- [ ] **Step 1: Add the items**

After `viewMenu.add(dither);` insert:

```java
        JCheckBoxMenuItem hdrCanvas = new JCheckBoxMenuItem("HDR Canvas", HdrGain.canvasEnabled());
        hdrCanvas.setToolTipText("Render image layers into the display's extended range, so the corona can be "
                + "brighter than the window. Needs an EDR display; takes effect the next time JHelioviewer starts.");
        hdrCanvas.addItemListener(e -> HdrGain.setCanvasEnabled(hdrCanvas.getState()));
        viewMenu.add(hdrCanvas);

        JMenu hdrBrightness = new JMenu("HDR Brightness");
        hdrBrightness.setToolTipText("How far image layers go into the display's headroom. Auto uses whatever the "
                + "display offers at its current brightness setting.");
        ButtonGroup gainGroup = new ButtonGroup();
        String[][] stops = {{"Auto (display maximum)", "auto"}, {"1x (no HDR)", "1"}, {"2x", "2"}, {"4x", "4"}, {"8x", "8"}};
        for (String[] stop : stops) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(stop[0], stop[1].equals(HdrGain.setting()));
            item.addActionListener(e -> {
                HdrGain.setSetting(stop[1]);
                DisplayController.display();
            });
            gainGroup.add(item);
            hdrBrightness.add(item);
        }
        viewMenu.add(hdrBrightness);
```

and add `import org.helioviewer.jhv.display.HdrGain;` with the display imports.

- [ ] **Step 2: Build, run, exercise**

Run: `ant jar && java --enable-native-access=ALL-UNNAMED -jar HFStudio.jar`
Expected: View menu shows "HDR Canvas" (checked) and "HDR Brightness" with Auto selected. Picking "1x (no HDR)" makes the image match the window's white again; picking "8x" makes it brighter; Auto returns it to the display maximum. `~/JHelioviewer-SWHV/Settings/user.properties` gains `display.hdrGain=8` after picking 8x.

- [ ] **Step 3: Commit**

```bash
git add src/org/helioviewer/jhv/gui/component/MenuBar.java
git commit -m "View menu: HDR Canvas toggle and HDR Brightness stops"
```

---

### Task 5: Verification and the brief's outcome

**Files:**
- Create: `extra/test/edr_sdr_fidelity.sh`
- Modify: `docs/edr-canvas-brief.md` (append `## Outcome`)

- [ ] **Step 1: SDR fidelity script**

`extra/test/edr_sdr_fidelity.sh`:

```bash
#!/bin/sh
# At gain 1 the EDR canvas must look exactly like the 10-bit canvas: same pixels within the two
# counts the 10-bit path was measured at. An untagged float layer measured 40/255 off; that is
# the failure this catches. Run with the app open, showing a layer, gain set to 1x, twice: once
# with display.edrCanvas=true and once =false (View > HDR Canvas, restart between), giving each
# screenshot a name:
#   sh extra/test/edr_sdr_fidelity.sh capture edr.png
#   sh extra/test/edr_sdr_fidelity.sh capture ten.png
#   sh extra/test/edr_sdr_fidelity.sh compare edr.png ten.png
set -e
case "$1" in
  capture)
    WID=$(osascript -e 'tell application "System Events" to get id of first window of (first process whose name contains "java" or name contains "HFStudio")' 2>/dev/null || true)
    [ -n "$WID" ] || { echo "no JHV window found"; exit 1; }
    screencapture -x -l "$WID" "$2" && echo "captured $2 (window $WID)";;
  compare)
    python3 - "$2" "$3" <<'PY'
import sys, numpy as np
from PIL import Image
a=np.asarray(Image.open(sys.argv[1]).convert("RGB")).astype(int); b=np.asarray(Image.open(sys.argv[2]).convert("RGB")).astype(int)
h=min(a.shape[0],b.shape[0]); w=min(a.shape[1],b.shape[1]); d=np.abs(a[:h,:w]-b[:h,:w])
print(f"mean |diff| = {d.mean():.3f} counts, p99 = {np.percentile(d,99):.1f}, max = {d.max()}")
sys.exit(0 if d.mean() <= 2.0 else 1)
PY
    ;;
  *) echo "usage: $0 capture <png> | compare <a.png> <b.png>"; exit 2;;
esac
```

- [ ] **Step 2: Run it both ways and record the numbers**

Follow the script's header. Expected: `mean |diff| <= 2.0 counts`. If the mean is tens of counts, the linearization curve or the colorspace tag is wrong; if the image is upside down, the vertex `uv` mapping is.

- [ ] **Step 3: Export fidelity**

With gain at Auto and a layer loaded, export one frame as "EXR series (layered, half float)". Then:

`oiiotool --stats <frame>.exr | grep -A3 'LASCO\|SUVI\|AIA' | head` and confirm every `.Y` and `.V` channel's max is <= 1.0 and the composite `R,G,B` max is <= 1.0. A value above 1.0 in any export channel means the gain leaked into a capture pass.

- [ ] **Step 4: Append the outcome to the brief**

Add to `docs/edr-canvas-brief.md`:

```markdown
## Outcome (fill in the measured values)

- Startup log: `EDR canvas: ...` and `EDR headroom now N SDR whites` (N measured: ...).
- SDR fidelity at gain 1: mean |diff| ... counts against the 10-bit canvas.
- Export: max of every data channel and the composite <= 1.0 with gain at Auto: ...
- Acceptance: ...
```

- [ ] **Step 5: Commit**

```bash
git add extra/test/edr_sdr_fidelity.sh docs/edr-canvas-brief.md
git commit -m "EDR canvas: fidelity checks and measured outcome"
```
