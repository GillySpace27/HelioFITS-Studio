#import <AppKit/AppKit.h>
#import <dispatch/dispatch.h>
#import <jawt_md.h>
#import <IOSurface/IOSurfaceRef.h>
#import <Metal/Metal.h>
#import <objc/runtime.h>
#import <QuartzCore/CATransaction.h>
#import <QuartzCore/CAMetalLayer.h>

@interface JHVMetalHostBox : NSObject
@property(nonatomic, strong) CALayer *windowLayer;
@property(nonatomic, strong) CAMetalLayer *metalLayer;
@end

@implementation JHVMetalHostBox
@end

static void jhv_run_without_actions(void (^block)(void)) {
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    block();
    [CATransaction commit];
}

static void jhv_set_metal_layer_frame(CAMetalLayer *metalLayer, CGRect frame) {
    jhv_run_without_actions(^{
        if (!CGRectEqualToRect(metalLayer.frame, frame))
            metalLayer.frame = frame;

        CGSize drawableSize = CGSizeMake(frame.size.width * metalLayer.contentsScale, frame.size.height * metalLayer.contentsScale);
        if (!CGSizeEqualToSize(metalLayer.drawableSize, drawableSize))
            metalLayer.drawableSize = drawableSize;
    });
}

static CAMetalLayer *jhv_create_metal_layer(id<MTLDevice> device, CGFloat contentsScale, CGRect frame) {
    CAMetalLayer *metalLayer = [CAMetalLayer layer];
    metalLayer.device = device;
    metalLayer.pixelFormat = MTLPixelFormatBGRA8Unorm;
    metalLayer.framebufferOnly = NO;
    metalLayer.opaque = YES;
    metalLayer.contentsScale = contentsScale;
    // Without this the layer defaults to kCAGravityResize: when the layer is resized, Core Animation
    // stretches the *previous* frame to the new bounds until fresh content is drawn, so a programmatic
    // layout change (collapsing a panel) briefly shows a distorted frame even though the drawable and
    // the viewport both end up correct.
    //
    // Without this the layer defaults to kCAGravityResize, which stretches the previous frame to the
    // new bounds until fresh content is drawn -- a visibly distorted frame on any programmatic resize.
    //
    // No anchor makes a stale frame correct: the right placement depends on which edge moved. Centre
    // is the deliberate choice, because it keeps the sidebar collapse -- the frequent one -- clean,
    // the Sun being drawn about the canvas centre. It leaves a brief vertical shift when the timelines
    // panel is collapsed, which is a once-a-session action. The only fix without this trade is an
    // atomic native resize-and-render, and every route to that dispatches synchronously to the main
    // thread, which deadlocks against AppKit.
    metalLayer.contentsGravity = kCAGravityCenter;
    jhv_set_metal_layer_frame(metalLayer, frame);
    return metalLayer;
}

static void jhv_run_on_main_sync(void (^block)(void)) {
    if ([NSThread isMainThread]) {
        block();
        return;
    }

    // A plain dispatch_sync(main) from the EDT deadlocks against AWT: LWCToolkit.invokeAndWait
    // pumps the main thread in its private "AWTRunLoopMode", which does NOT drain the main dispatch
    // queue — so if the AppKit thread is inside invokeAndWait while we hold the sync, neither side
    // advances. (Reliably hit when a second GUI process attaches its Metal layer.) Schedule the
    // block on the main run loop in both the default and the AWT modes so it runs even during
    // invokeAndWait, and wait on a semaphore. CFRunLoopPerformBlock runs the block once, in the
    // first of the given modes to become active.
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    CFRunLoopRef mainLoop = CFRunLoopGetMain();
    CFStringRef modes[] = {kCFRunLoopDefaultMode, CFSTR("AWTRunLoopMode")};
    CFArrayRef modeArray = CFArrayCreate(NULL, (const void **) modes, 2, &kCFTypeArrayCallBacks);
    CFRunLoopPerformBlock(mainLoop, modeArray, ^{
        block();
        dispatch_semaphore_signal(done);
    });
    CFRelease(modeArray);
    CFRunLoopWakeUp(mainLoop);
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
}

static void jhv_run_on_main_async(void (^block)(void)) {
    if ([NSThread isMainThread]) {
        block();
        return;
    }

    dispatch_async(dispatch_get_main_queue(), block);
}

static id<JAWT_SurfaceLayers> jhv_surface_layers(void *surfaceLayersPtr) {
    if (surfaceLayersPtr == NULL)
        return nil;

    id surfaceLayers = (__bridge id)surfaceLayersPtr;
    if (![surfaceLayers conformsToProtocol:@protocol(JAWT_SurfaceLayers)])
        return nil;

    return (id<JAWT_SurfaceLayers>)surfaceLayers;
}

static CGFloat jhv_layer_y(CALayer *windowLayer, double y, double height) {
    if (windowLayer == nil)
        return 0.0;
    return windowLayer.geometryFlipped ? y : (windowLayer.bounds.size.height - y - height);
}

static CGFloat jhv_window_scale(CALayer *windowLayer) {
    CGFloat windowScale = windowLayer.contentsScale;
    // NSScreen.mainScreen is the screen holding the KEY window, not the screen this layer is
    // on. With a Retina laptop driving a 1x external display (or the reverse) that is the wrong
    // backing scale, and the drawable comes out at half or double size. Ask the layer's own
    // window first and only fall back to a global guess when there is no window to ask.
    if (windowScale <= 0.0) {
        NSWindow *window = [(NSView *)windowLayer.delegate isKindOfClass:NSView.class]
                ? ((NSView *)windowLayer.delegate).window
                : nil;
        if (window != nil)
            windowScale = window.backingScaleFactor;
    }
    if (windowScale <= 0.0)
        windowScale = NSScreen.mainScreen.backingScaleFactor;
    if (windowScale <= 0.0)
        windowScale = 1.0;
    return windowScale;
}

const char *jhv_metal_device_info(void) {
    static char info[256];

    @autoreleasepool {
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (device == nil) {
            snprintf(info, sizeof(info), "available=false reason=no default Metal device");
            return info;
        }

        const char *name = device.name.UTF8String;
        snprintf(info, sizeof(info),
                 "MTLGPUFamilyMac2=%s name=\"%s\"",
                 [device supportsFamily:MTLGPUFamilyMac2] ? "true" : "false",
                 name != NULL ? name : "");
        return info;
    }
}

void *jhv_metal_host_create(void *surfaceLayersPtr, double x, double y, double width, double height) {
    __block void *result = NULL;
    jhv_run_on_main_sync(^{
        @autoreleasepool {
            id<JAWT_SurfaceLayers> surfaceLayers = jhv_surface_layers(surfaceLayersPtr);
            if (surfaceLayers == nil)
                return;

            CALayer *windowLayer = surfaceLayers.windowLayer;
            if (windowLayer == nil)
                return;

            id<MTLDevice> device = MTLCreateSystemDefaultDevice();
            if (device == nil)
                return;

            JHVMetalHostBox *box = [JHVMetalHostBox new];
            CGFloat layerY = jhv_layer_y(windowLayer, y, height);
            CGFloat windowScale = jhv_window_scale(windowLayer);
            CGRect frame = CGRectMake(x, layerY, width, height);
            box.windowLayer = windowLayer;
            box.metalLayer = jhv_create_metal_layer(device, windowScale, frame);
            [windowLayer addSublayer:box.metalLayer];
            result = (__bridge_retained void *)box;
        }
    });
    return result;
}

static void jhv_apply_frame(JHVMetalHostBox *retainedBox, double x, double y, double width, double height) {
    @try {
        CGFloat layerY = jhv_layer_y(retainedBox.windowLayer, y, height);
        CGFloat windowScale = jhv_window_scale(retainedBox.windowLayer);
        if (retainedBox.metalLayer.contentsScale != windowScale)
            retainedBox.metalLayer.contentsScale = windowScale;
        CGRect frame = CGRectMake(x, layerY, width, height);
        jhv_set_metal_layer_frame(retainedBox.metalLayer, frame);
    } @finally {
        CFRelease((__bridge CFTypeRef)retainedBox);
    }
}

void jhv_metal_host_set_frame(void *boxPtr, double x, double y, double width, double height) {
    if (boxPtr == NULL)
        return;

    JHVMetalHostBox *box = (__bridge JHVMetalHostBox *)boxPtr;
    CFRetain((__bridge CFTypeRef)box);
    jhv_run_on_main_async(^{
        @autoreleasepool { jhv_apply_frame(box, x, y, width, height); }
    });
}

// Synchronous variant: the CAMetalLayer frame AND drawableSize are updated before returning, so a
// render issued immediately afterwards draws at the new resolution (no oblate frame, no flash).
// Used for programmatic resizes (collapsing the sidebar), not the frequent window-drag path.
void jhv_metal_host_set_frame_sync(void *boxPtr, double x, double y, double width, double height) {
    if (boxPtr == NULL)
        return;

    JHVMetalHostBox *box = (__bridge JHVMetalHostBox *)boxPtr;
    CFRetain((__bridge CFTypeRef)box);
    jhv_run_on_main_sync(^{
        @autoreleasepool { jhv_apply_frame(box, x, y, width, height); }
    });
}

void *jhv_metal_host_get_layer(void *boxPtr) {
    if (boxPtr == NULL)
        return NULL;

    JHVMetalHostBox *box = (__bridge JHVMetalHostBox *)boxPtr;
    return (__bridge void *)box.metalLayer;
}

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
// What the screen could offer if EDR content were present (16 on the XDR panel, 1 on an SDR
// one). Read when the layer is prepared, so the first frame already knows whether to bootstrap.
// Measured 2026-09-04: the compositor engages EDR only once content exceeds roughly 1.1 to
// 1.25, so a canvas that never goes past white never sees a headroom above 1.
static double jhv_edr_potential_cached = 1.0;

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
                    NSScreen *screen = jhv_screen_of_layer(layer);
                    jhv_edr_potential_cached = screen != nil ? screen.maximumPotentialExtendedDynamicRangeColorComponentValue : 1.0;
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

// The screen's potential EDR headroom (what it can offer once EDR content is on it); 1.0 on a
// display without EDR. Safe from any thread.
double jhv_metal_host_edr_potential(void *layerPtr) {
    (void)layerPtr;
    return jhv_edr_potential_cached;
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
                    jhv_edr_potential_cached = screen != nil ? screen.maximumPotentialExtendedDynamicRangeColorComponentValue : 1.0;
                }
            });
        }
        return 1;
    }
}

void jhv_metal_host_destroy(void *boxPtr) {
    if (boxPtr == NULL)
        return;

    jhv_run_on_main_sync(^{
        @autoreleasepool {
            JHVMetalHostBox *box = (__bridge_transfer JHVMetalHostBox *)boxPtr;
            [box.metalLayer removeFromSuperlayer];
        }
    });
}
