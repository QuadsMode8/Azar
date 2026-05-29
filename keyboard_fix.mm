#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#include <objc/runtime.h>

// ============================================================
// keyboard_fix.mm  —  corrected version
//
// PROBLEM WITH ORIGINAL:
//   The original fix swizzled [NSAlert runModal], but the crash
//   happens earlier, at [NSAlert init] which calls [NSWindow init].
//   macOS throws NSInternalInconsistencyException at NSWindow
//   *creation* on a non-main thread, never reaching runModal.
//
// THIS FIX:
//   1. Swizzle [NSAlert init] to always run on the main thread.
//      This covers the NSWindow allocation crash.
//   2. Also swizzle [NSAlert runModal] as a belt-and-suspenders
//      guard in case the alert is created on main but run elsewhere.
//   3. Uses dispatch_sync so the emulator thread blocks correctly
//      until the user has finished typing their name.
// ============================================================

// ---------- helpers -----------------------------------------

static IMP original_alertInit    = nil;
static IMP original_runModal     = nil;

// Swizzled [NSAlert init] — runs entire init on main thread
static id swizzled_alertInit(id self, SEL _cmd) {
    if ([NSThread isMainThread]) {
        return ((id(*)(id,SEL))original_alertInit)(self, _cmd);
    }
    __block id result = nil;
    dispatch_sync(dispatch_get_main_queue(), ^{
        result = ((id(*)(id,SEL))original_alertInit)(self, _cmd);
    });
    return result;
}

// Swizzled [NSAlert runModal] — belt-and-suspenders guard
static NSInteger swizzled_runModal(id self, SEL _cmd) {
    if ([NSThread isMainThread]) {
        return ((NSInteger(*)(id,SEL))original_runModal)(self, _cmd);
    }
    __block NSInteger result = NSModalResponseCancel;
    dispatch_sync(dispatch_get_main_queue(), ^{
        result = ((NSInteger(*)(id,SEL))original_runModal)(self, _cmd);
    });
    return result;
}

// ---------- install at dylib load time ----------------------

__attribute__((constructor))
static void install_keyboard_fix(void) {
    // Swizzle init
    {
        Method m = class_getInstanceMethod([NSAlert class], @selector(init));
        if (m) {
            original_alertInit = method_getImplementation(m);
            method_setImplementation(m, (IMP)swizzled_alertInit);
        }
    }
    // Swizzle runModal
    {
        Method m = class_getInstanceMethod([NSAlert class], @selector(runModal));
        if (m) {
            original_runModal = method_getImplementation(m);
            method_setImplementation(m, (IMP)swizzled_runModal);
        }
    }
    NSLog(@"[keyboard_fix] NSAlert thread-safety swizzle installed (init + runModal)");
}
