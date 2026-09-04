// Does a CAMetalLayer with wantsExtendedDynamicRange engage EDR on this screen, and does a
// value above 1.0 in an extended-sRGB-tagged RGBA16Float layer get presented? Shows four bars
// (0.5, 1.0, 2.0, 4.0) for a few seconds and prints the screen's EDR headroom before and after.
#import <AppKit/AppKit.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
int main(int argc, const char *argv[]) {
    @autoreleasepool {
        NSApplication *app = [NSApplication sharedApplication];
        [app setActivationPolicy:NSApplicationActivationPolicyRegular];
        NSScreen *scr = NSScreen.mainScreen;
        printf("before: potential=%.1f current=%.1f\n",
               scr.maximumPotentialExtendedDynamicRangeColorComponentValue,
               scr.maximumExtendedDynamicRangeColorComponentValue);
        NSRect r = NSMakeRect(200, 200, 800, 200);
        NSWindow *win = [[NSWindow alloc] initWithContentRect:r styleMask:NSWindowStyleMaskTitled backing:NSBackingStoreBuffered defer:NO];
        win.title = @"EDR probe: 0.5 | 1.0 | 2.0 | 4.0";
        NSView *v = win.contentView; v.wantsLayer = YES;
        id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
        CAMetalLayer *L = [CAMetalLayer layer];
        L.device = dev; L.pixelFormat = MTLPixelFormatRGBA16Float; L.framebufferOnly = NO;
        L.wantsExtendedDynamicRangeContent = YES;
        const char *space = argc > 1 ? argv[1] : "extendedSRGB";
        CFStringRef name = strcmp(space, "linear") == 0 ? kCGColorSpaceExtendedLinearSRGB : kCGColorSpaceExtendedSRGB;
        L.colorspace = CGColorSpaceCreateWithName(name);
        L.frame = v.bounds; L.contentsScale = win.backingScaleFactor;
        L.drawableSize = CGSizeMake(r.size.width * L.contentsScale, r.size.height * L.contentsScale);
        [v.layer addSublayer:L];
        [win makeKeyAndOrderFront:nil]; [app activateIgnoringOtherApps:YES];
        id<MTLCommandQueue> q = [dev newCommandQueue];
        float levels[4] = {0.5f, 1.0f, 2.0f, 4.0f};
        for (int frame = 0; frame < 3; frame++) {
            id<CAMetalDrawable> d = [L nextDrawable];
            id<MTLCommandBuffer> cb = [q commandBuffer];
            NSUInteger W = d.texture.width, H = d.texture.height;
            // CPU fill: half floats, RGBA
            NSUInteger bpr = W * 8; uint16_t *buf = malloc(bpr * H);
            for (NSUInteger y = 0; y < H; y++) for (NSUInteger x = 0; x < W; x++) {
                float lv = levels[(x * 4) / W]; __fp16 h = (__fp16)lv; uint16_t bits; memcpy(&bits, &h, 2);
                uint16_t *px = buf + (y * W + x) * 4; px[0] = px[1] = px[2] = bits; __fp16 one = 1.0f; memcpy(&px[3], &one, 2);
            }
            [cb commit]; [cb waitUntilCompleted];
            [d.texture replaceRegion:MTLRegionMake2D(0, 0, W, H) mipmapLevel:0 withBytes:buf bytesPerRow:bpr];
            free(buf);
            id<MTLCommandBuffer> cb2 = [q commandBuffer]; [cb2 presentDrawable:d]; [cb2 commit]; [cb2 waitUntilCompleted];
            [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:1.0]];
            printf("frame %d: current EDR max=%.2f (%s)\n", frame, scr.maximumExtendedDynamicRangeColorComponentValue, space);
        }
        [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:4.0]];
        printf("after: current=%.2f\n", scr.maximumExtendedDynamicRangeColorComponentValue);
    }
    return 0;
}
