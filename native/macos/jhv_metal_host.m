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

// --- Deep-colour presentation ---------------------------------------------------------------
//
// The EGL window surface caps the canvas at 8 bits per channel because ANGLE's Metal backend
// only enumerates 8-bit configs. The route around it: the scene is rendered into a 10-bit
// IOSurface wrapped as an EGL pbuffer (EGL_ANGLE_iosurface_client_buffer, whose format comes
// from the pbuffer attributes rather than the config), and these functions carry that IOSurface
// to the screen: the layer is switched to a 10-bit pixel format and each frame is blitted from
// the IOSurface into the layer's drawable.
//
// Why BGR10A2Unorm and not RGBA16Float: the layer's colorspace is nil, as it has always been
// for the 8-bit path, and for an untagged UNORM layer the compositor passes pixel values
// through unchanged, so the image looks exactly as before with four times the levels. An
// untagged FLOAT layer is different: the compositor treats its values as linear and re-encodes
// them for the display (measured on this machine: greys and primaries brightened along an sRGB
// encode curve, saturated colours handled by some further gamut/EDR logic). Half-float
// presentation therefore needs real colour management first, and 10 bits matches what the
// panel's pipe carries anyway.

@interface JHVDeepPresenter : NSObject
@property(nonatomic, strong) id<MTLCommandQueue> queue;
@property(nonatomic, strong) id<MTLTexture> wrapped;      // MTLTexture view of the canvas IOSurface
@property(nonatomic, assign) IOSurfaceRef wrappedSurface; // cache key only, not retained here
@end

@implementation JHVDeepPresenter
@end

static char jhv_deep_presenter_key;

// Switch the layer to 10 bits per channel so the compositor receives more than 8.
// Returns 1 on success. Main-thread: the layer is in a live tree.
//
// The vertical flip: GL's framebuffer origin is bottom-left, Metal's top-left. ANGLE's window
// surface reconciles the two during its own present; the deep path blits raw rows, so the layer
// is flipped at composite time instead. Free (the compositor applies it), and it must be undone
// if the deep path is abandoned, because ANGLE's window surface would then flip twice.
int jhv_metal_host_prepare_deep(void *layerPtr) {
    if (layerPtr == NULL)
        return 0;

    __block int ok = 0;
    jhv_run_on_main_sync(^{
        @autoreleasepool {
            CAMetalLayer *layer = (__bridge CAMetalLayer *)layerPtr;
            jhv_run_without_actions(^{
                layer.pixelFormat = MTLPixelFormatBGR10A2Unorm;
                layer.transform = CATransform3DMakeScale(1, -1, 1);
            });
            ok = 1;
        }
    });
    return ok;
}

// Undo prepare_deep's layer flip before ANGLE takes the layer back as an 8-bit window surface
// (ANGLE resets the pixel format itself, but not the transform).
void jhv_metal_host_reset_deep(void *layerPtr) {
    if (layerPtr == NULL)
        return;

    jhv_run_on_main_sync(^{
        @autoreleasepool {
            CAMetalLayer *layer = (__bridge CAMetalLayer *)layerPtr;
            jhv_run_without_actions(^{
                layer.transform = CATransform3DIdentity;
            });
        }
    });
}

// A 10-bit BGR10A2 IOSurface ('l10r') for the canvas. Returned retained; release with
// jhv_deep_canvas_release.
void *jhv_deep_canvas_create(int width, int height) {
    if (width <= 0 || height <= 0)
        return NULL;

    int bytesPerElement = 4; // packed 2-10-10-10
    size_t bpr = IOSurfaceAlignProperty(kIOSurfaceBytesPerRow, (size_t)width * bytesPerElement);
    size_t allocSize = IOSurfaceAlignProperty(kIOSurfaceAllocSize, bpr * height);
    int64_t values[] = {width, height, 'l10r', bytesPerElement, (int64_t)bpr, (int64_t)allocSize};
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

// Copy the rendered IOSurface into the layer's next drawable and present it. Called on the
// render thread after the GL work has finished (glFinish), and returns only after the blit has
// completed, so the caller may immediately render the next frame into the same IOSurface.
// ponytail: fully synchronous single-buffer present; ping-pong IOSurfaces + MTLSharedEvent if
// the wait ever shows up in a profile.
int jhv_metal_host_present_deep(void *layerPtr, void *surfPtr, int width, int height) {
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

        // Pointer AND size: a fresh IOSurface can reuse a released one's address.
        if (presenter.wrapped == nil || presenter.wrappedSurface != surf
                || presenter.wrapped.width != (NSUInteger)width || presenter.wrapped.height != (NSUInteger)height) {
            MTLTextureDescriptor *desc = [MTLTextureDescriptor
                    texture2DDescriptorWithPixelFormat:MTLPixelFormatBGR10A2Unorm
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
        [commands presentDrawable:drawable];
        [commands commit];
        [commands waitUntilCompleted];
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
