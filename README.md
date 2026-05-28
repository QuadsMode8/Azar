# Azar - Java experimental Emulator for macOS

A macOS port of the Azahar emulator using JNI and Vulkan via MoltenVK.

## Requirements

- **Apple Silicon Mac only** (M1/M2/M3/M4) — Intel Macs not supported
- macOS 13 (Ventura) or later
- No additional software needed — Java and MoltenVK are bundled

## First Time Setup

Open Terminal and run this to clear the quarantine flag macOS puts on downloaded files:xattr -rd com.apple.quarantine /path/to/Azahar-macOS

Replace `/path/to/Azahar-macOS` with the actual path to the unzipped folder.

Then make the scripts executable:chmod +x /path/to/Azahar-macOS/run.sh
chmod +x /path/to/Azahar-macOS/settings.sh

## Running a Game

From Terminal:cd /path/to/Azahar-macOS
./run.sh /path/to/your/game.3ds

Supported ROM formats: `.3ds`, `.cxi`

## Controls

| Key            | 3DS Button     |
|----------------|----------------|
| Z              | A              |
| X              | B              |
| C              | X              |
| V              | Y              |
| Q              | L              |
| U              | R              |
| E              | ZL             |
| O              | ZR             |
| W/A/S/D        | Circle Pad     |
| I/J/K/L        | C-Stick        |
| Arrow Keys     | D-Pad          |
| Enter          | Start          |
| Esc            | Select         |

Click on the game window to give it keyboard focus before pressing keys.

## Settings

While a game is running, open a second Terminal tab and run:cd /path/to/Azahar-macOS
./settings.sh

The Settings window lets you change:
- **Controls** — remap any button to any key
- **Graphics** — resolution (1x–6x), VSync, shader options
- **Screen Layout** — Default, Large Screen, Side by Side, etc.

Restart the emulator after changing graphics or layout settings.

## System Files (Optional)

For best game compatibility, place 3DS system files in:~/Library/Application Support/Azahar/sysdata/

AES keys go in:~/Library/Application Support/Azahar/sysdata/aes_keys.txt

## Save Files and Data

| What          | Location                                               |
|---------------|--------------------------------------------------------|
| Save files    | `~/Library/Application Support/Azahar/sdmc/`          |
| Config        | `~/Library/Application Support/Azahar/config/config.ini` |
| Shader cache  | `~/Library/Application Support/Azahar/shaders/`       |
| Cheats        | `~/Library/Application Support/Azahar/cheats/`        |

## Troubleshooting

**Black screen or immediate crash**
- Make sure you ran the `xattr` command above
- Confirm you are on Apple Silicon (M1 or newer)

**Audio sounds choppy at startup**
- Normal — the DSP takes a few seconds to initialize, audio stabilizes on its own

**Input not responding**
- Click directly on the game window to give it focus

**Settings window does not appear**
- Make sure the game is already running first
- Run `./settings.sh` from a separate Terminal tab

**First launch is slow**
- Normal — shaders compile on first run and are cached for future launches

## Notes

- This build is Apple Silicon (arm64) only and will not run on Intel Macs
- Close the Terminal window or press Ctrl+C to quit the emulator
- The emulator stores all data in `~/Library/Application Support/Azahar/`
  so uninstalling is as simple as deleting that folder and the Azahar-macOS folder

## Credits

- Citra Emulator Project
- Azahar Emulator Project  
- MoltenVK by The Brenwill Workshop
