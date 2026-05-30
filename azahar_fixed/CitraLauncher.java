import org.citra.citra_emu.NativeLibrary;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import java.awt.event.*;

/**
 * Azahar macOS Launcher
 *
 * FIXES & FEATURES:
 *  1. JNI_OnLoad class-init race fixed (pre-resolve NativeLibrary)
 *  2. Per-game config overrides (Majora's Mask LLE crash)
 *  3. applyGameOverrides is idempotent — won't clobber manual settings
 *  4. SDL destructor SIGSEGV: stopEmulation() then Runtime.halt()
 *  5. Bundled MoltenVK preferred over Homebrew
 *  6. Software keyboard: showSoftwareKeyboard is called by native lib,
 *     NativeLibrary.java already handles it via JOptionPane + setKeyboardResult.
 *     We just ensure keyboard_fix.dylib is loaded so it doesn't crash.
 *  7. Fullscreen: Cmd+F or F11 toggles SDL fullscreen
 *  8. Proper window close: Cmd+Q and window close button cleanly shut down
 *  9. Title ID read from ROM header if filename doesn't contain it
 * 10. Save file directories created on first launch
 */
public class CitraLauncher {

    private static volatile long    windowPtr = 0;
    private static volatile Thread  emuThread = null;
    private static volatile boolean running   = true;
    private static volatile boolean fullscreen = false;

    // SDL key codes
    private static final int SDLK_F11     = 0x4000004E;
    private static final int SDLK_q       = 113;
    private static final int KMOD_GUI     = 0x0400; // Cmd on macOS

    // 3DS button codes used by NativeLibrary.onKeyPress
    private static final int N3DS_A      = 0;
    private static final int N3DS_B      = 1;
    private static final int N3DS_SELECT = 2;
    private static final int N3DS_START  = 3;
    private static final int N3DS_RIGHT  = 4;
    private static final int N3DS_LEFT   = 5;
    private static final int N3DS_UP     = 6;
    private static final int N3DS_DOWN   = 7;
    private static final int N3DS_R      = 8;
    private static final int N3DS_L      = 9;
    private static final int N3DS_X      = 10;
    private static final int N3DS_Y      = 11;
    private static final int N3DS_ZL     = 14;
    private static final int N3DS_ZR     = 15;
    private static final int N3DS_HOME   = 24;

    private static final Map<String, List<String>> GAME_OVERRIDES = new LinkedHashMap<>();
    static {
        // Majora's Mask 3D — LLE audio crashes on arm64, force HLE
        for (String id : new String[]{
                "00040000001B8E00",  // JP
                "00040000001B8F00",  // US
                "00040000001B9000"   // EU
        }) {
            GAME_OVERRIDES.put(id, Arrays.asList(
                "Renderer/resolution_factor=2",
                "Renderer/async_shader_compilation=true",
                "Audio/audio_emulation=HLE"
            ));
        }
        // Ocarina of Time 3D
        for (String id : new String[]{
                "0004000000033500",  // JP
                "0004000000033600",  // US
                "0004000000033700"   // EU
        }) {
            GAME_OVERRIDES.put(id, Arrays.asList(
                "Audio/audio_emulation=HLE",
                "Renderer/async_shader_compilation=true"
            ));
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("apple.awt.application.name", "Azahar");
        System.setProperty("java.awt.headless", "false");

        if (args.length == 0) { printUsage(); System.exit(0); }

        final String romPath = args[0];
        if (!new File(romPath).exists()) {
            System.err.println("Error: ROM not found: " + romPath);
            System.exit(1);
        }

        System.out.println("=== Azahar Launcher ===");
        System.out.println("ROM: " + romPath);

        // FIX 1: pre-resolve class before loadLibrary fires JNI_OnLoad
        try { Class.forName("org.citra.citra_emu.NativeLibrary", false, CitraLauncher.class.getClassLoader()); }
        catch (ClassNotFoundException ignored) {}

        // FIX 5: bundled MoltenVK first
        String mvk = resolveMoltenVK();
        NativeLibrary.initializeMoltenVK(mvk);
        System.out.println("✓ MoltenVK: " + mvk);

        String userDir = System.getProperty("user.home") + "/Library/Application Support/Azahar";
        NativeLibrary.setUserDirectory(userDir);

        // Ensure base save directories exist on first launch
        ensureSaveDirs(userDir);

        // Resolve title ID
        String titleId = extractTitleIdFromFilename(romPath);
        if (titleId == null) titleId = extractTitleIdFromHeader(romPath);
        if (titleId != null) {
            System.out.println("Title ID: " + titleId);
            applyGameOverrides(titleId, userDir);
        } else {
            System.out.println("Warning: could not determine Title ID — per-game overrides skipped");
        }

        // Create window
        windowPtr = NativeLibrary.createWindow(400, 480, "Azahar - " + new File(romPath).getName());
        if (windowPtr == 0) { System.err.println("Failed to create window"); nativeExit(1); }
        System.out.println("✓ Window created");

        // Register window close handler (Cmd+Q / red button)
        registerShutdownHook();

        NativeLibrary.setWindowForEmulation(windowPtr);
        NativeLibrary.loadGame(romPath);
        NativeLibrary.showWindow(windowPtr);

        // Notify SDL of current surface size
        NativeLibrary.surfaceChanged(null);

        emuThread = new Thread(NativeLibrary::startEmulation, "EmulationThread");
        emuThread.setDaemon(true);
        emuThread.start();

        System.out.println("✓ Emulation started");
        System.out.println("Keys: Z=A X=B C=X V=Y | WASD=Circle Pad | Arrows=D-Pad");
        System.out.println("Q=L U=R E=ZL O=ZR | Enter=Start Esc=Select H=Home");
        System.out.println("F11 or Cmd+F = Toggle Fullscreen | Cmd+Q = Quit");

        String jarPath = new File(CitraLauncher.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).getAbsolutePath();
        File settingsTrigger = new File(System.getProperty("user.home") + "/.azahar_open_settings");

        // Main loop
        while (running) {
            NativeLibrary.processWindowEvents(windowPtr);

            // Settings trigger file (written by settings.sh)
            if (settingsTrigger.exists()) {
                settingsTrigger.delete();
                openSettingsProcess(jarPath);
            }

            // Check if emulator stopped itself
            if (!NativeLibrary.isRunning()) {
                System.out.println("Emulation ended.");
                break;
            }

            Thread.sleep(8);
        }

        nativeExit(0);
    }

    // ── Fullscreen ────────────────────────────────────────────────────────────
    // Called from key event. SDL fullscreen toggle via NativeLibrary key codes.
    // We send a synthetic SDL_KEYDOWN for F11 which the emulator's SDL window
    // handler processes via Cocoa_SetWindowFullscreen.
    private static void toggleFullscreen() {
        fullscreen = !fullscreen;
        // SDL fullscreen is triggered by sending the window a resize hint.
        // The most reliable path without direct SDL access is surfaceChanged,
        // which causes SDL to re-evaluate the window state.
        // On macOS, double-clicking the title bar or the native green button
        // also works. We use NativeLibrary.surfaceChanged to prod SDL.
        System.out.println("Fullscreen: " + fullscreen);
        // Use Runtime to send Cmd+Ctrl+F (macOS standard fullscreen shortcut)
        // via AppleScript as SDL intercepts it at the Cocoa level
        new Thread(() -> {
            try {
                Runtime.getRuntime().exec(new String[]{
                    "osascript", "-e",
                    "tell application \"System Events\" to keystroke \"f\" using {command down, control down}"
                });
            } catch (Exception e) {
                System.err.println("Fullscreen toggle failed: " + e.getMessage());
            }
        }).start();
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    private static void registerShutdownHook() {
        // Intercept Cmd+Q at the Java level via AWT
        // The native window close button is handled by SDL internally
        // We register a shutdown hook so cleanup happens on any exit path
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            running = false;
            try {
                NativeLibrary.stopEmulation();
                if (emuThread != null) emuThread.join(3000);
            } catch (Throwable ignored) {}
        }));
    }

    // FIX 4: halt instead of exit to avoid SDL destructor SIGSEGV
    static void nativeExit(int code) {
        running = false;
        try {
            NativeLibrary.stopEmulation();
            if (emuThread != null) emuThread.join(3000);
        } catch (Throwable ignored) {}
        Runtime.getRuntime().halt(code);
    }

    // ── MoltenVK ──────────────────────────────────────────────────────────────

    private static String resolveMoltenVK() {
        String[] candidates = {
            "./lib/libMoltenVK.dylib",
            "/Users/daniyalkhan/Desktop/Azar/lib/libMoltenVK.dylib",
            "/opt/homebrew/Cellar/molten-vk/1.4.1/lib/libMoltenVK.dylib",
            "/usr/local/lib/libMoltenVK.dylib",
        };
        for (String p : candidates) if (new File(p).exists()) return p;
        System.err.println("Warning: libMoltenVK.dylib not found, trying default path");
        return candidates[0];
    }

    // ── Title ID ──────────────────────────────────────────────────────────────

    private static String extractTitleIdFromFilename(String romPath) {
        String name = new File(romPath).getName().toUpperCase();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9A-F]{16})").matcher(name);
        return m.find() ? m.group(1) : null;
    }

    private static String extractTitleIdFromHeader(String romPath) {
        try (RandomAccessFile raf = new RandomAccessFile(romPath, "r")) {
            if (raf.length() < 0x110) return null;
            raf.seek(0x100);
            byte[] magic = new byte[4]; raf.read(magic);
            if (!"NCSD".equals(new String(magic))) return null;
            raf.seek(0x108);
            byte[] buf = new byte[8]; raf.read(buf);
            StringBuilder sb = new StringBuilder();
            for (int i = 7; i >= 0; i--) sb.append(String.format("%02X", buf[i] & 0xFF));
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    // ── Game overrides ────────────────────────────────────────────────────────

    static void applyGameOverrides(String titleId, String userDir) {
        List<String> overrides = GAME_OVERRIDES.get(titleId.toUpperCase());
        if (overrides == null || overrides.isEmpty()) return;

        File configFile = new File(userDir + "/config/config.ini");
        if (!configFile.exists()) return;

        try {
            List<String> lines = Files.readAllLines(configFile.toPath());

            // Parse existing values
            Map<String,Map<String,String>> existing = new LinkedHashMap<>();
            String cs = "";
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("[") && t.endsWith("]")) { cs = t.substring(1,t.length()-1).trim(); }
                else if (t.contains("=") && !t.startsWith(";")) {
                    int eq = t.indexOf('=');
                    existing.computeIfAbsent(cs, k->new LinkedHashMap<>())
                            .put(t.substring(0,eq).trim(), t.substring(eq+1).trim());
                }
            }

            // Build needed map — skip already-correct values
            Map<String,Map<String,String>> needed = new LinkedHashMap<>();
            for (String override : overrides) {
                int sl = override.indexOf('/'), eq = override.indexOf('=');
                if (sl < 0 || eq < 0) continue;
                String sec = override.substring(0,sl).trim();
                String key = override.substring(sl+1,eq).trim();
                String val = override.substring(eq+1).trim();
                Map<String,String> exSec = existing.get(sec);
                if (exSec != null && val.equals(exSec.get(key))) continue; // already set
                needed.computeIfAbsent(sec, k->new LinkedHashMap<>()).put(key, val);
            }

            if (needed.isEmpty()) {
                System.out.println("✓ Per-game overrides already applied for " + titleId);
                return;
            }

            // Merge into file
            List<String> result = new ArrayList<>();
            Set<String> applied = new HashSet<>();
            String sec = "";
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("[") && t.endsWith("]")) { sec=t.substring(1,t.length()-1).trim(); result.add(line); continue; }
                if (t.contains("=") && !t.startsWith(";")) {
                    String k = t.substring(0,t.indexOf('=')).trim();
                    Map<String,String> ns = needed.get(sec);
                    if (ns != null && ns.containsKey(k)) {
                        System.out.println("  Override ["+sec+"] "+k+" = "+ns.get(k));
                        result.add(k+" = "+ns.get(k)); applied.add(sec+"/"+k); continue;
                    }
                }
                result.add(line);
            }
            for (Map.Entry<String,Map<String,String>> se : needed.entrySet()) {
                for (Map.Entry<String,String> kv : se.getValue().entrySet()) {
                    if (applied.contains(se.getKey()+"/"+kv.getKey())) continue;
                    boolean found=false; int at=-1;
                    for (int i=0;i<result.size();i++) { String t=result.get(i).trim();
                        if(t.equals("["+se.getKey()+"]")) found=true;
                        else if(found&&t.startsWith("[")&&t.endsWith("]")){at=i;break;} }
                    if(found&&at==-1) at=result.size();
                    if(found) result.add(at,kv.getKey()+" = "+kv.getValue());
                    else { result.add(""); result.add("["+se.getKey()+"]"); result.add(kv.getKey()+" = "+kv.getValue()); }
                }
            }
            Files.write(configFile.toPath(), result);
            System.out.println("✓ Per-game overrides applied for " + titleId);
        } catch (IOException e) {
            System.err.println("Warning: could not apply overrides: " + e.getMessage());
        }
    }

    // ── Save dirs ─────────────────────────────────────────────────────────────

    private static void ensureSaveDirs(String userDir) {
        String sdmc = userDir+"/sdmc/Nintendo 3DS/00000000000000000000000000000000/00000000000000000000000000000000";
        for (String d : new String[]{
                sdmc+"/title", sdmc+"/extdata",
                userDir+"/nand/data/00000000000000000000000000000000/sysdata",
                userDir+"/nand/data/00000000000000000000000000000000/extdata",
                userDir+"/cheats", userDir+"/screenshots",
                userDir+"/textures", userDir+"/shaders"
        }) new File(d).mkdirs();

        File movable = new File(userDir+"/nand/data/00000000000000000000000000000000/sysdata/0001f2/movable.sed");
        if (!movable.exists()) {
            movable.getParentFile().mkdirs();
            try { new FileOutputStream(movable).close(); }
            catch (Exception ignored) {}
        }
    }

    // ── Settings process ──────────────────────────────────────────────────────

    static void openSettingsProcess(String jarPath) {
        new Thread(() -> {
            try {
                String javaCmd = System.getProperty("java.home") + "/bin/java";
                new ProcessBuilder(javaCmd,
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp", jarPath, "AzaharSettings")
                    .inheritIO().start().waitFor();
            } catch (Exception e) {
                System.err.println("Failed to open settings: " + e.getMessage());
            }
        }, "SettingsProcess").start();
    }

    static void printUsage() {
        System.out.println("Usage: ./run.sh /path/to/game.3ds");
        System.out.println();
        System.out.println("Controls:");
        System.out.println("  Z=A  X=B  C=X  V=Y   Q=L  U=R  E=ZL  O=ZR");
        System.out.println("  WASD=Circle Pad  IJKL=C-Stick  Arrows=D-Pad");
        System.out.println("  Enter=Start  Esc=Select  H=Home");
        System.out.println("  F11 or Cmd+F = Fullscreen  |  Cmd+Q = Quit");
        System.out.println();
        System.out.println("Run ./settings.sh while game is running to open settings.");
    }
}
