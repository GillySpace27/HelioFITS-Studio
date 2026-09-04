// Two CAMetalLayers side by side showing the same sRGB-encoded image: left is BGR10A2Unorm,
// untagged (the 10-bit path); right is RGBA16Float tagged extended linear sRGB with EDR
// requested, filled with the sRGB EOTF applied on the CPU (what the EDR pass does). One
// screenshot then holds both, so the comparison is free of window, camera and timing races.
// argv: <rgba file> <w> <h> [edr]  -- "edr" adds a 2.0 patch on the right so the screen engages.
// Measured 2026-09-04 on the M4 Max XDR panel with a 205x214 crop of an AIA 171 render:
// mean |left-right| on lit pixels 0.48 counts, p99 2, max 19, median ratio 1.0000, EDR engaged
// or not. The layers come out at half size (frames are in points, sizes here in pixels), so
// compare columns 0..w/2 against w/2..w of the bottom h rows of the capture.
#import <AppKit/AppKit.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
static float eotf(float v){ return v <= 0.04045f ? v/12.92f : powf((v+0.055f)/1.055f, 2.4f); }
int main(int argc, const char *argv[]) {
    @autoreleasepool {
        NSApplication *app = [NSApplication sharedApplication];
        [app setActivationPolicy:NSApplicationActivationPolicyRegular];
        int W = atoi(argv[2]), H = atoi(argv[3]); int edr = argc > 4;
        NSData *src = [NSData dataWithContentsOfFile:[NSString stringWithUTF8String:argv[1]]];
        const uint8_t *px = src.bytes;
        NSRect r = NSMakeRect(300, 300, W, H/2);   // points; layers are 2x scale
        NSWindow *win = [[NSWindow alloc] initWithContentRect:r styleMask:NSWindowStyleMaskTitled backing:NSBackingStoreBuffered defer:NO];
        win.title = @"fidelity probe"; NSView *v = win.contentView; v.wantsLayer = YES;
        id<MTLDevice> dev = MTLCreateSystemDefaultDevice(); id<MTLCommandQueue> q = [dev newCommandQueue];
        CGFloat scale = win.backingScaleFactor;
        // left: untagged 10-bit
        CAMetalLayer *L = [CAMetalLayer layer]; L.device = dev; L.pixelFormat = MTLPixelFormatBGR10A2Unorm; L.framebufferOnly = NO; L.opaque = YES;
        L.frame = CGRectMake(0, 0, W/2/scale, H/scale); L.contentsScale = scale; L.drawableSize = CGSizeMake(W/2, H);
        // right: linear-tagged half float, EDR requested
        CAMetalLayer *R = [CAMetalLayer layer]; R.device = dev; R.pixelFormat = MTLPixelFormatRGBA16Float; R.framebufferOnly = NO; R.opaque = YES;
        R.wantsExtendedDynamicRangeContent = YES; R.colorspace = CGColorSpaceCreateWithName(kCGColorSpaceExtendedLinearSRGB);
        R.frame = CGRectMake(W/2/scale, 0, W/2/scale, H/scale); R.contentsScale = scale; R.drawableSize = CGSizeMake(W/2, H);
        [v.layer addSublayer:L]; [v.layer addSublayer:R];
        [win makeKeyAndOrderFront:nil]; [app activateIgnoringOtherApps:YES];
        int w2 = W/2;
        for (int frame = 0; frame < 2; frame++) {
            // left: pack 10-bit
            id<CAMetalDrawable> dl = [L nextDrawable]; uint32_t *lb = malloc(w2*H*4);
            for (int y = 0; y < H; y++) for (int x = 0; x < w2; x++) {
                const uint8_t *p = px + (y*W + x)*4;   // left half of the source
                uint32_t r10 = p[0]*1023/255, g10 = p[1]*1023/255, b10 = p[2]*1023/255;
                lb[y*w2+x] = (3u<<30) | (r10<<20) | (g10<<10) | b10;   // BGR10A2: A in top bits, then R? no: layout is B in low bits
            }
            [dl.texture replaceRegion:MTLRegionMake2D(0,0,w2,H) mipmapLevel:0 withBytes:lb bytesPerRow:w2*4]; free(lb);
            // right: half float linear
            id<CAMetalDrawable> dr = [R nextDrawable]; uint16_t *rb = malloc(w2*H*8);
            for (int y = 0; y < H; y++) for (int x = 0; x < w2; x++) {
                const uint8_t *p = px + (y*W + x)*4;   // SAME left half of the source, shown on the right
                float c[4] = { eotf(p[0]/255.f), eotf(p[1]/255.f), eotf(p[2]/255.f), 1.f };
                if (edr && x < 24 && y < 24) c[0] = c[1] = c[2] = 2.0f;   // a small patch above white engages EDR
                for (int i = 0; i < 4; i++) { __fp16 h = (__fp16)c[i]; memcpy(&rb[(y*w2+x)*4+i], &h, 2); }
            }
            [dr.texture replaceRegion:MTLRegionMake2D(0,0,w2,H) mipmapLevel:0 withBytes:rb bytesPerRow:w2*8]; free(rb);
            id<MTLCommandBuffer> cb = [q commandBuffer]; [cb presentDrawable:dl]; [cb presentDrawable:dr]; [cb commit]; [cb waitUntilCompleted];
            [NSRunLoop.mainRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:1.5]];
        }
        printf("headroom now %.2f\n", NSScreen.mainScreen.maximumExtendedDynamicRangeColorComponentValue);
        NSInteger wid = win.windowNumber; char cmd[256];
        snprintf(cmd, sizeof cmd, "screencapture -x -o -l %ld /tmp/fidelity_probe_%s.png", (long)wid, edr ? "edr" : "sdr"); system(cmd);
        printf("captured /tmp/fidelity_probe_%s.png\n", edr ? "edr" : "sdr");
    }
    return 0;
}
