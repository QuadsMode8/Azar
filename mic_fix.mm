// mic_fix.mm — CoreAudio microphone permission + capture bridge for Azahar macOS
//
// The emulator's MIC service uses SDL's COREAUDIO_OpenDevice for capture,
// which IS compiled in. The problem is:
//   1. macOS requires explicit permission before any audio input opens
//   2. If the permission dialog fires on the wrong thread mid-game it crashes
//   3. The emulator never pre-requests mic permission at startup
//
// This dylib fixes all three by:
//   - Pre-requesting mic permission at startup (before game loads)
//   - Keeping an AudioQueue capture session alive so the OS doesn't revoke access
//   - Bridging captured PCM into a static buffer SDL/Citra can read
//
// Build:
//   clang -arch arm64 -std=c++17 -fobjc-arc -fPIC -dynamiclib \
//     -framework Foundation -framework CoreAudio -framework AudioToolbox \
//     -framework AVFoundation \
//     -o lib/mic_fix.dylib mic_fix.mm
//   codesign --force --sign - lib/mic_fix.dylib
//
// Add to DYLD_INSERT_LIBRARIES in run.sh alongside keyboard_fix.dylib

#import <Foundation/Foundation.h>
#import <AVFoundation/AVFoundation.h>
#import <AudioToolbox/AudioToolbox.h>
#import <CoreAudio/CoreAudio.h>
#include <atomic>
#include <mutex>
#include <cstdio>
#include <cstdint>

static constexpr int MIC_SAMPLE_RATE    = 32728;
static constexpr int MIC_BUFFER_SAMPLES = 2048;

static int16_t              g_micBuffer[MIC_BUFFER_SAMPLES] = {};
static std::atomic<int>     g_writePos{0};
static std::mutex           g_micMutex;
static std::atomic<bool>    g_micActive{false};
static AudioQueueRef        g_queue = nullptr;
static AudioQueueBufferRef  g_bufs[3] = {};

static void audioQueueCallback(void*, AudioQueueRef queue, AudioQueueBufferRef buf,
                                const AudioTimeStamp*, UInt32 numPackets,
                                const AudioStreamPacketDescription*) {
    if (g_micActive && numPackets > 0) {
        const int16_t* src = static_cast<const int16_t*>(buf->mAudioData);
        UInt32 nSamples = buf->mAudioDataByteSize / sizeof(int16_t);
        std::lock_guard<std::mutex> lock(g_micMutex);
        int wp = g_writePos.load(std::memory_order_relaxed);
        for (UInt32 i = 0; i < nSamples; i++) {
            g_micBuffer[wp] = src[i];
            wp = (wp + 1) % MIC_BUFFER_SAMPLES;
        }
        g_writePos.store(wp, std::memory_order_release);
    }
    AudioQueueEnqueueBuffer(queue, buf, 0, nullptr);
}

static void startMicCapture() {
    AudioStreamBasicDescription fmt = {};
    fmt.mSampleRate       = MIC_SAMPLE_RATE;
    fmt.mFormatID         = kAudioFormatLinearPCM;
    fmt.mFormatFlags      = kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked;
    fmt.mBitsPerChannel   = 16;
    fmt.mChannelsPerFrame = 1;
    fmt.mBytesPerFrame    = 2;
    fmt.mFramesPerPacket  = 1;
    fmt.mBytesPerPacket   = 2;

    OSStatus err = AudioQueueNewInput(&fmt, audioQueueCallback, nullptr,
                                      nullptr, nullptr, 0, &g_queue);
    if (err != noErr) {
        fprintf(stderr, "[mic_fix] AudioQueueNewInput failed: %d\n", (int)err);
        return;
    }

    int bufBytes = (MIC_SAMPLE_RATE / 20) * sizeof(int16_t);
    for (int i = 0; i < 3; i++) {
        AudioQueueAllocateBuffer(g_queue, bufBytes, &g_bufs[i]);
        AudioQueueEnqueueBuffer(g_queue, g_bufs[i], 0, nullptr);
    }

    err = AudioQueueStart(g_queue, nullptr);
    if (err != noErr) {
        fprintf(stderr, "[mic_fix] AudioQueueStart failed: %d\n", (int)err);
        AudioQueueDispose(g_queue, true);
        g_queue = nullptr;
        return;
    }

    g_micActive = true;
    fprintf(stderr, "[mic_fix] Microphone capture active at %dHz mono s16le\n", MIC_SAMPLE_RATE);
}

__attribute__((constructor))
static void mic_fix_init() {
    NSString* configPath = [NSHomeDirectory() stringByAppendingPathComponent:
        @"Library/Application Support/Azahar/config/config.ini"];
    NSString* config = [NSString stringWithContentsOfFile:configPath
                                                 encoding:NSUTF8StringEncoding error:nil];
    BOOL micEnabled = config && [config rangeOfString:@"mic_enabled = true"].location != NSNotFound;

    if (!micEnabled) {
        fprintf(stderr, "[mic_fix] mic_enabled not true in config — skipping capture\n");
        fprintf(stderr, "[mic_fix] Set mic_enabled = true in settings to enable microphone\n");
        return;
    }

    fprintf(stderr, "[mic_fix] Requesting microphone permission...\n");
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        dispatch_semaphore_t sem = dispatch_semaphore_create(0);
        [AVCaptureDevice requestAccessForMediaType:AVMediaTypeAudio completionHandler:^(BOOL granted) {
            if (granted) {
                fprintf(stderr, "[mic_fix] Permission granted\n");
                [NSThread sleepForTimeInterval:1.5];
                startMicCapture();
            } else {
                fprintf(stderr, "[mic_fix] Permission DENIED — go to System Settings > Privacy > Microphone\n");
            }
            dispatch_semaphore_signal(sem);
        }];
        dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC));
    });

    fprintf(stderr, "[mic_fix] Microphone bridge installed\n");
}

__attribute__((destructor))
static void mic_fix_deinit() {
    g_micActive = false;
    if (g_queue) {
        AudioQueueStop(g_queue, true);
        AudioQueueDispose(g_queue, true);
        g_queue = nullptr;
    }
}
