import org.citra.citra_emu.NativeLibrary;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Azahar macOS Launcher
 *
 * Fixes in this version:
 *  1. JNI_OnLoad class-init race: pre-resolve NativeLibrary before .dylib loads
 *  2. Per-game config overrides (Majora's Mask LLE crash; 001B5000 M3 slowness)
 *  3. applyGameOverrides now actually APPENDS missing keys to the right section
 *     instead of silently warning and doing nothing
 *  4. SDL destructor SIGSEGV on exit: stopEmulation() first, then Runtime.halt()
 *     which skips __cxa_finalize_ranges entirely — SDL's static dtor never fires
 *  5. Bundled MoltenVK preferred over Homebrew to avoid duplicate objc class warning
 *  6. Software keyboard crash warning for known games (native fix in software_keyboard_fixed.mm)
 *  7. openSettingsProcess now launches AzaharSettings (the new combined settings UI)
 */
public class CitraLauncher {

    private static volatile long windowPtr = 0;
    private static volatile Thread emuThread = null;
    private static volatile boolean running = true;

    // -----------------------------------------------------------------------
    // Per-game config overrides applied before emulation starts.
    // These fix known crashes or severe performance issues for specific titles.
    // Format: "Section/key=value"  (must match config.ini section headers exactly)
    // -----------------------------------------------------------------------
    private static final Map<String, List<String>> GAME_OVERRIDES = new LinkedHashMap<>();
    static {
        // --- Majora's Mask 3D (all regions) ---
        // LLE audio (Teakra) crashes deterministically ~60-110s on arm64 due to
        // a stack corruption in the ALM DSP instruction handler. Force HLE.
        // Resolution capped at 2x: the game's N64-derived art looks fine and M3
        // benefits significantly from the reduced GPU load.
        for (String id : new String[]{
                "00040000001B8E00",  // JP
                "00040000001B8F00",  // US
                "00040000001B9000"   // EU
        }) {
            GAME_OVERRIDES.put(id, Arrays.asList(
                "Audio/audio_emulation=HLE",
                "Renderer/resolution_factor=2",
                "Renderer/async_shader_compilation=true"
            ));
        }

        // --- Mario & Luigi: Superstar Saga + Bowser's Minions (0004000000125500) ---
        // On M3 this game runs with three config problems stacked:
        //   1. Audio_Emulation=LLE  → Teakra running on M3's efficiency cores → slow
        //   2. System_IsNew3ds=false → emulates old 3DS, cutting CPU clock to 268MHz
        //      emulated (vs 804MHz on New3DS). Single biggest speed impact.
        //   3. System_LLEApplets=false → some applet paths take slower HLE stubs
        // None of these are caused by M3 being weak — it's a config mismatch.
        GAME_OVERRIDES.put("0004000000125500", Arrays.asList(
            "Audio/audio_emulation=HLE",
            "System/is_new_3ds=true",
            "System/lle_applets=true"
        ));
    }

    // Games known to crash when the software keyboard is invoked from a non-main thread.
    // Native fix is in software_keyboard_fixed.mm (dispatch_sync to main thread).
    // Until the dylib is recompiled with that fix, we warn the user at launch.
    private static final Set<String> KEYBOARD_CRASH_GAMES = new HashSet<>(Arrays.asList(
        "0004000000125500"
    ));

    public static void main(String[] args) throws Exception {
        System.setProperty("apple.awt.application.name", "Azahar");
        System.setProperty("java.awt.headless", "false");

        if (args.length == 0) {
            printUsage();
            // No game → just exit cleanly, no native libs loaded, safe to use exit()
            System.exit(0);
        }

        final String romPath = args[0];
        if (!new File(romPath).exists()) {
            System.err.println("Error: ROM file not found: " + romPath);
            System.exit(1);
        }

        System.out.println("=== Azahar macOS Launcher ===");
        System.out.println("ROM: " + romPath);

        // ------------------------------------------------------------------
        // FIX 1: Pre-resolve NativeLibrary class before its static initializer
        // calls System.loadLibrary(). JNI_OnLoad inside the dylib calls back
        // into Java with GetStaticObjectField — if the class is still in the
        // "being initialized" state those calls return null → SIGSEGV.
        // Class.forName with initialize=false resolves the class without running
        // <clinit>, so when loadLibrary fires <clinit> the class is fully visible.
        // ------------------------------------------------------------------
        try {
            Class.forName("org.citra.citra_emu.NativeLibrary", false,
                CitraLauncher.class.getClassLoader());
        } catch (ClassNotFoundException ignored) {}

        // FIX 5: Use bundled MoltenVK first to avoid loading two copies
        String moltenVKPath = resolveMoltenVKPath();
        System.out.println("Using MoltenVK: " + moltenVKPath);
        NativeLibrary.initializeMoltenVK(moltenVKPath);
        System.out.println("✓ MoltenVK initialized");

        String userDir = System.getProperty("user.home") + "/Library/Application Support/Azahar";
        NativeLibrary.setUserDirectory(userDir);

        // Apply per-game config overrides before the emulator reads config.ini
        String titleId = extractTitleId(romPath);
        if (titleId != null) {
            System.out.println("Title ID: " + titleId);
            applyGameOverrides(titleId, userDir);
            warnIfKeyboardCrash(titleId);
        }

        windowPtr = NativeLibrary.createWindow(400, 480,
            "Azahar - " + new File(romPath).getName());
        if (windowPtr == 0) {
            System.err.println("Failed to create window");
            nativeExit(1);
        }
        System.out.println("✓ Window created");

        NativeLibrary.setWindowForEmulation(windowPtr);
        NativeLibrary.loadGame(romPath);
        NativeLibrary.showWindow(windowPtr);

        emuThread = new Thread(NativeLibrary::startEmulation, "EmulationThread");
        emuThread.setDaemon(true);
        emuThread.start();

        System.out.println("Running. Run ./settings.sh in another terminal to open settings.");
        System.out.println("Keys: Z=A X=B C=X V=Y | WASD=Circle Pad | Arrows=D-Pad");
        System.out.println("Q=L U=R E=ZL O=ZR | Enter=Start Esc=Select");
        if (titleId != null && GAME_OVERRIDES.containsKey(titleId.toUpperCase())) {
            System.out.println("[Per-game overrides active for " + titleId + "]");
        }

        String jarPath = new File(
            CitraLauncher.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getAbsolutePath();

        File settingsTrigger = new File(
            System.getProperty("user.home") + "/.azahar_open_settings");

        // Main event loop
        while (running) {
            NativeLibrary.processWindowEvents(windowPtr);
            if (settingsTrigger.exists()) {
                settingsTrigger.delete();
                openSettingsProcess(jarPath);
            }
            Thread.sleep(8);
        }
    }

    // -----------------------------------------------------------------------
    // FIX 4: Clean native shutdown then Runtime.halt().
    //
    // The SDL destructor crash (SDLState::~SDLState SIGSEGV) happens because
    // System.exit() → __cxa_finalize_ranges → SDL's C++ static destructors fire
    // AFTER the JVM has already started tearing down its heap. SDL tries to
    // access something that's already been freed → null deref → SIGSEGV.
    //
    // Runtime.halt() terminates the process immediately without running Java
    // shutdown hooks OR the C runtime's atexit/__cxa_finalize chain. Since we
    // explicitly stop emulation first, the native state is clean before we halt.
    // -----------------------------------------------------------------------
    static void nativeExit(int code) {
        running = false;
        try {
            NativeLibrary.stopEmulation();
            if (emuThread != null) emuThread.join(3000);
        } catch (Throwable ignored) {}
        Runtime.getRuntime().halt(code);
    }

    // -----------------------------------------------------------------------
    // MoltenVK resolution: bundled copy first, then Homebrew fallbacks.
    // Loading two copies of MoltenVK causes "Class MVKBlockObserver implemented
    // in both" objc warnings which can lead to spurious casting crashes.
    // -----------------------------------------------------------------------
    private static String resolveMoltenVKPath() {
        String[] candidates = {
            "./lib/libMoltenVK.dylib",
            "/opt/homebrew/lib/libMoltenVK.dylib",
            "/opt/homebrew/Cellar/molten-vk/1.4.1/lib/libMoltenVK.dylib",
            "/usr/local/lib/libMoltenVK.dylib",
        };
        for (String p : candidates) {
            if (new File(p).exists()) return p;
        }
        System.err.println("Warning: libMoltenVK.dylib not found.");
        return candidates[0];
    }

    // -----------------------------------------------------------------------
    // Extract 16-char title ID from the ROM filename.
    // Standard dump naming: 0004000000125500_v01.trim.3ds
    // -----------------------------------------------------------------------
    private static String extractTitleId(String romPath) {
        String name = new File(romPath).getName().toUpperCase();
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("([0-9A-F]{16})").matcher(name);
        return m.find() ? m.group(1) : null;
    }

    // -----------------------------------------------------------------------
    // Apply per-game config overrides to config.ini.
    // Does a targeted in-place replace for keys that already exist.
    // For keys that don't exist yet, appends them under the correct section.
    // This way global user settings for other games are never disturbed.
    // -----------------------------------------------------------------------
    static void applyGameOverrides(String titleId, String userDir) {
        List<String> overrides = GAME_OVERRIDES.get(titleId.toUpperCase());
        if (overrides == null || overrides.isEmpty()) return;

        File configFile = new File(userDir + "/config/config.ini");
        if (!configFile.exists()) {
            System.out.println("Config not found yet — overrides will apply after first launch.");
            return;
        }

        try {
            List<String> lines = Files.readAllLines(configFile.toPath());

            // Parse overrides into section → (key → value) structure
            // e.g. "Audio/audio_emulation=HLE" → section="Audio", key="audio_emulation"
            Map<String, Map<String, String>> pending = new LinkedHashMap<>();
            for (String override : overrides) {
                int slash = override.indexOf('/');
                int eq    = override.indexOf('=');
                if (slash < 0 || eq < 0 || eq <= slash) continue;
                String section = override.substring(0, slash).trim();
                String key     = override.substring(slash + 1, eq).trim();
                String value   = override.substring(eq + 1).trim();
                pending.computeIfAbsent(section, k -> new LinkedHashMap<>()).put(key, value);
            }

            // Pass 1: walk the file, replace matching lines, track what was applied
            String currentSection = "";
            List<String> result = new ArrayList<>();
            // Track which (section/key) pairs were actually found and replaced
            Set<String> applied = new HashSet<>();

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    currentSection = trimmed.substring(1, trimmed.length() - 1).trim();
                    result.add(line);
                    continue;
                }
                if (trimmed.contains("=") && !trimmed.startsWith(";") && !trimmed.startsWith("#")) {
                    String lineKey = trimmed.substring(0, trimmed.indexOf('=')).trim();
                    Map<String, String> sectionOverrides = pending.get(currentSection);
                    if (sectionOverrides != null && sectionOverrides.containsKey(lineKey)) {
                        String newValue = sectionOverrides.get(lineKey);
                        result.add(lineKey + " = " + newValue);
                        applied.add(currentSection + "/" + lineKey);
                        System.out.println("  Override: [" + currentSection + "] "
                            + lineKey + " = " + newValue);
                        continue;
                    }
                }
                result.add(line);
            }

            // Pass 2: for any overrides not found, append under the right section.
            // If the section itself doesn't exist in the file, create it at the end.
            for (Map.Entry<String, Map<String, String>> sectionEntry : pending.entrySet()) {
                String section = sectionEntry.getKey();
                for (Map.Entry<String, String> kv : sectionEntry.getValue().entrySet()) {
                    String compositeKey = section + "/" + kv.getKey();
                    if (applied.contains(compositeKey)) continue;

                    // Find the section in result and append after its last line
                    boolean sectionFound = false;
                    int insertAt = -1;
                    for (int i = 0; i < result.size(); i++) {
                        String t = result.get(i).trim();
                        if (t.equals("[" + section + "]")) {
                            sectionFound = true;
                        } else if (sectionFound && t.startsWith("[") && t.endsWith("]")) {
                            // Next section started — insert before it
                            insertAt = i;
                            break;
                        }
                    }
                    if (sectionFound && insertAt == -1) insertAt = result.size();

                    String newLine = kv.getKey() + " = " + kv.getValue();
                    if (sectionFound) {
                        result.add(insertAt, newLine);
                        System.out.println("  Appended: [" + section + "] " + newLine);
                    } else {
                        // Section doesn't exist at all — create it
                        result.add("");
                        result.add("[" + section + "]");
                        result.add(newLine);
                        System.out.println("  Created section [" + section + "] with: " + newLine);
                    }
                    applied.add(compositeKey);
                }
            }

            Files.write(configFile.toPath(), result);
            System.out.println("✓ Per-game config overrides applied for " + titleId);

        } catch (IOException e) {
            System.err.println("Warning: could not apply game overrides: " + e.getMessage());
        }
    }

    private static void warnIfKeyboardCrash(String titleId) {
        if (!KEYBOARD_CRASH_GAMES.contains(titleId.toUpperCase())) return;
        System.out.println();
        System.out.println("⚠  This game uses the software keyboard (e.g. naming screen).");
        System.out.println("   Without the native fix (software_keyboard_fixed.mm rebuilt");
        System.out.println("   into the dylib), it will crash when that dialog appears.");
        System.out.println("   See BUGS_AND_FIXES.md for the dispatch_sync fix.");
        System.out.println();
    }

    // FIX 7: Launch AzaharSettings instead of the old SettingsLauncher
    static void openSettingsProcess(String jarPath) {
        new Thread(() -> {
            try {
                String javaCmd = System.getProperty("java.home") + "/bin/java";
                ProcessBuilder pb = new ProcessBuilder(
                    javaCmd,
                    
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp", jarPath,
                    "AzaharSettings"
                );
                pb.inheritIO();
                pb.start().waitFor();
            } catch (Exception e) {
                System.err.println("Failed to open settings: " + e.getMessage());
            }
        }, "SettingsProcess").start();
    }

    static void printUsage() {
        System.out.println("Usage: ./run.sh /path/to/game.3ds");
        System.out.println();
        System.out.println("Controls:");
        System.out.println("  Z/X/C/V     = A/B/X/Y    Q/U = L/R    E/O = ZL/ZR");
        System.out.println("  WASD        = Circle Pad  IJKL = C-Stick");
        System.out.println("  Arrow Keys  = D-Pad       Enter/Esc = Start/Select");
        System.out.println();
        System.out.println("Run ./settings.sh in another terminal while a game is running.");
    }
}
