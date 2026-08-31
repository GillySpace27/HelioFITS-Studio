// Probe helper for IOSurfacePbufferProbe.java: create/read IOSurfaces so the Java EGL probe
// can verify that ANGLE renders into them. Not part of the app. Build with:
//   xcrun clang -O2 -fobjc-arc -dynamiclib -framework IOSurface -framework CoreFoundation \
//       -o /tmp/libjhvprobe.dylib extra/test/native/probe_iosurface.m
// See docs/deep-colour-canvas-brief.md for the investigation this served.
#import <IOSurface/IOSurfaceObjC.h>
#import <IOSurface/IOSurfaceRef.h>
#import <CoreFoundation/CoreFoundation.h>

void *jhv_probe_iosurface_create(int width, int height, unsigned int pixelFormat, int bytesPerElement) {
    size_t bpr = IOSurfaceAlignProperty(kIOSurfaceBytesPerRow, (size_t)width * bytesPerElement);
    size_t alloc = IOSurfaceAlignProperty(kIOSurfaceAllocSize, bpr * height);
    CFMutableDictionaryRef props = CFDictionaryCreateMutable(NULL, 6,
            &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    int64_t w = width, h = height, pf = pixelFormat, bpe = bytesPerElement, bprV = bpr, allocV = alloc;
    CFNumberRef nw = CFNumberCreate(NULL, kCFNumberSInt64Type, &w);
    CFNumberRef nh = CFNumberCreate(NULL, kCFNumberSInt64Type, &h);
    CFNumberRef npf = CFNumberCreate(NULL, kCFNumberSInt64Type, &pf);
    CFNumberRef nbpe = CFNumberCreate(NULL, kCFNumberSInt64Type, &bpe);
    CFNumberRef nbpr = CFNumberCreate(NULL, kCFNumberSInt64Type, &bprV);
    CFNumberRef nalloc = CFNumberCreate(NULL, kCFNumberSInt64Type, &allocV);
    CFDictionarySetValue(props, kIOSurfaceWidth, nw);
    CFDictionarySetValue(props, kIOSurfaceHeight, nh);
    CFDictionarySetValue(props, kIOSurfacePixelFormat, npf);
    CFDictionarySetValue(props, kIOSurfaceBytesPerElement, nbpe);
    CFDictionarySetValue(props, kIOSurfaceBytesPerRow, nbpr);
    CFDictionarySetValue(props, kIOSurfaceAllocSize, nalloc);
    IOSurfaceRef surf = IOSurfaceCreate(props);
    CFRelease(nw); CFRelease(nh); CFRelease(npf); CFRelease(nbpe); CFRelease(nbpr); CFRelease(nalloc);
    CFRelease(props);
    return surf;
}

void jhv_probe_iosurface_release(void *surf) {
    if (surf != NULL)
        CFRelease((IOSurfaceRef)surf);
}

// Copy `count` raw bytes starting at pixel (x, y) into out. Returns 0 on success.
int jhv_probe_iosurface_read(void *surfPtr, int x, int y, int bytesPerElement, unsigned char *out, int count) {
    IOSurfaceRef surf = (IOSurfaceRef)surfPtr;
    if (surf == NULL)
        return -1;
    if (IOSurfaceLock(surf, kIOSurfaceLockReadOnly, NULL) != kIOReturnSuccess)
        return -2;
    const unsigned char *base = IOSurfaceGetBaseAddress(surf);
    size_t bpr = IOSurfaceGetBytesPerRow(surf);
    const unsigned char *px = base + (size_t)y * bpr + (size_t)x * bytesPerElement;
    for (int i = 0; i < count; i++)
        out[i] = px[i];
    IOSurfaceUnlock(surf, kIOSurfaceLockReadOnly, NULL);
    return 0;
}
