# jeilin-frame

Reverse engineering the firmware of a 2007 digital picture frame (Photoco PhotoShare 7D) built on a **Jeilin JL4204A**, with the goal of turning it into a USB-tethered live display: a host pushes rendered images to the frame over USB through a small firmware patch, in the style of the AX206 picture-frame hack.

No public datasheet, pinout or prior hacking write-up for the JL4204A was found. If you have one of these chips or frames, issues are welcome.

## Status

- [x] Firmware dumped from the SPI flash, in-circuit, with a Raspberry Pi 4 and flashrom.
- [x] Patch, flash and observe loop proven: the settings-menu title was changed to "Hacked" and it showed on screen.
- [x] Map the code-bank switching so all 512 KB of code loads in Ghidra.
- [x] Find the USB mass-storage SCSI command handler.
- [x] Read SDRAM over USB with a debug vendor command (`patches/sdram_read.*`).
- [ ] Find the display/framebuffer code. Two screen-sized image buffers have been found in SDRAM (below); which one is displayed, and how, isn't known yet.
- [ ] Add a vendor SCSI command that writes pixels.

## Hardware

| Part | Marking | Notes |
|---|---|---|
| SoC (U1) | Jeilin `JL4204A`, `0741A85243` | 8051-family core. The firmware contains the string `JEILIN JL4203A`. |
| Flash (U9) | AMIC `A25L16PTM-F` | 16 Mbit (2 MB) SPI, 3.3 V, SOIC-8. flashrom: `A25L16PT`. |
| SDRAM (U8) | Hynix `HY57V641620FTP-6` | 8 MB, holds no firmware. |
| Board | `PL-7B_EMI_E Ver:1.2`, Yung Fu Tech, 2007/11/01 | |
| Panel | 7", CCFL backlight | **The backlight inverter runs at high voltage.** |

USB ID `0979:0410`. The frame shows up as a mass-storage card reader with three LUNs (SM, SD/MMC, MS) and blanks its screen while USB is connected.

## Reading and writing the flash

The flash can be read and written in-circuit with the frame unplugged and powered from the Pi's 3.3 V. No legs need lifting.

U9 pinout (standard SPI flash): 1 CS, 2 DO, 3 WP, 4 GND, 5 DI, 6 CLK, 7 HOLD, 8 VCC.

| U9 pin | Pi physical pin |
|---|---|
| 1 CS | 24 (CE0) |
| 2 DO | 21 (MISO) |
| 3 WP | not connected |
| 4 GND | 25 |
| 5 DI | 19 (MOSI) |
| 6 CLK | 23 (SCLK) |
| 7 HOLD | 17 (3.3 V) |
| 8 VCC | 1 (3.3 V) |

Enable SPI with `dtparam=spi=on` in `config.txt`. Then:

```sh
P="-p linux_spi:dev=/dev/spidev0.0,spispeed=1000 -c A25L16PT"
sudo flashrom $P -r dump.bin      # read; do it twice and compare
sudo flashrom $P -w patched.bin   # write; erases only changed blocks, then verifies
```

flashrom 1.4.0 lists the A25L16PT as untested. Probe, read, erase and write all worked on this chip. The clip loses contact easily, and the chip then reads as all `0xFF`. Probe, and compare a fresh read against the backup, before every write.

## Firmware

[`firmware/u9-A25L16PT-2026-11-07.bin`](firmware/u9-A25L16PT-2026-11-07.bin) is the original, unmodified 2 MB flash image.
sha256 `a8fa459d406738e396d9750df2b2e48573b8c394d5124c2d6a5e83ec18a9a93b`

- It starts with `02 00 4E` (`LJMP 0x004E`). The code at 0x004E is the stock Keil C51 startup: it clears IDATA, sets SP to 0x73, then walks the init table. So the firmware is Keil C on an 8051.
- **Flash layout.** The code layout was worked out from the bank-switch code. The data regions are inferred from byte statistics only:

  | Flash | Contents |
  |---|---|
  | `0x000000–0x000FFF` | first-stage boot code, with its own vectors and Keil startup |
  | `bank×0x10000 + 0x1000 + addr` | main firmware: CPU `addr`, `bank` = 0–7 |
  | `0x081000–0x15FFFF` | looks like bitmap fonts |
  | `0x160000–0x1DFFFF` | high entropy, probably compressed UI images |
  | `0x1E0000–0x1FFFFF` | erased |

- **Code banking.** CPU `0x0000–0x2FFF` is common and stored identically in every bank. `0x3000–0xFFFF` is a 52 KB banked window. The bank register is **SFR `0xA5` on SFR page 6**; SFR `0x94` selects the SFR page. Banked calls go through Keil-style 5-byte stubs at `0x11E3–0x166F` (233 of them), each `MOV DPTR,#target ; AJMP switch_n`. The `switch_n` routines are at `0x1113 + 0x1A·n`. The main firmware's reset vector is `LJMP 0x26D9` (Keil startup), and `main` is at `0x01AD`.
- **USB mass storage** lives in bank 2. The device descriptors are at `bank2:3000`, two with PID `0x0270` and two with `0x0410`. The command dispatcher `bank2:8E7E` reads the 31-byte CBW from the FIFO SFR `0xBB` (page 8) into XDATA `0x1451`. It copies the opcode into XDATA `0x0203`, a shared scratch variable, and builds the CSW at XDATA `0x1461`; the CSW status is at `0x1460`. Handlers in bank 2:

  | Opcode | Command | Handler |
  |---|---|---|
  | 00 | TEST UNIT READY | `ccc7` |
  | 03 | REQUEST SENSE | `eef8` |
  | 12 | INQUIRY | `dab5` |
  | 15 / 1A / 5A | MODE SELECT / MODE SENSE(6) / MODE SENSE(10) | inline / `f42a` / inline |
  | 1B | START STOP UNIT | `f1ca` |
  | 1E | PREVENT/ALLOW REMOVAL | `f57e` |
  | 23 | READ FORMAT CAPACITIES | `d09b` |
  | 25 | READ CAPACITY | `e471` |
  | 28 / 2A | READ(10) / WRITE(10) | `c126` / `bb6f` |
  | 2F | VERIFY | `dda2` |
  | **EB, EC** | vendor | **no handler** in the stock image. At `bank2:9464` they skip the ILLEGAL REQUEST path and go straight to the CSW, so they succeed and do nothing. |

  Anything else gets sense key 5 / ASC `0x20`. Before dispatching, the dispatcher does the Bulk-Only Transport "13 cases" check: `bank2:b117` returns the data length the device expects for the opcode, `bank2:ee27` the direction, and the command only runs if the host's CBW matches exactly. Otherwise it fails with ASC `0x24` (or a phase error). A data-in handler must first select the bulk-in endpoint, as INQUIRY does through `bank3:FA97(1, 0)` (page 8: `0xB1` = 1, `0xB2` = 0), then write bytes to `0xBC`.
- **DMA engine.** The driver is `bank4:D2C4`, on SFR page `0x0B`. It takes a 32-bit bus address in R4–R7 and a descriptor in XDATA: `0x0690` length (32-bit), `0x0694` mode, and `0x0695` a third 32-bit value. It starts the transfer by setting bit 0 of a control register and polls bit 1 until it finishes. Bus addresses reach the 8 MB SDRAM. The modes seen so far:
  - **Mode 1** moves USB data. WRITE(10) moves each 64-byte packet to SDRAM `0x00152600 + i·0x40`.
  - **Mode 2** fills `length` bytes with the third value. It's used to clear buffers at `0x006C7200–0x00734400`. The text renderer `bank3:49AD` clears `0x00734400` (0x3400 or 0xA000 bytes) before drawing glyphs.
- **SDRAM contents** (from a full dump over USB with the debug command, taken while the frame was connected and its screen dark):

  | SDRAM | Contents |
  |---|---|
  | `0x000000–0x1DFFFF` | copy of flash from `0x1000` on (code, fonts, compressed images) |
  | `~0x240000–0x2EFFFF` | a second copy of the font data |
  | `0x2F5E00` | image buffer A: 480×234 Y plane, then 480×234×2 bytes of chroma at `0x3114C0` |
  | `0x3BBE00` | image buffer B, same layout, chroma at `0x3D74C0` |
  | `~0x760000–0x7DFFFF` | decoded UI icons, 1,440 bytes per row |

  Both buffers held the blue swoosh menu background. The pixel format looks like **YUV, 480×234: a Y plane followed by a plane of interleaved V,U per pixel (4:4:4, 960 bytes per row)**, inferred from the plane sizes. Chroma order is unconfirmed: V first decodes as a blue background, U first as orange. The buffer addresses don't appear as constants in the code.
- **Not yet found:** which buffer is displayed, the confirmed pixel format, the LCD controller registers, and what the firmware does to the display when USB connects (the screen goes dark).
- Free space in each bank's window in the stock image, in bytes: 0: 14484, 1: 7054, 2: 2427, 3: 622, 4: 6999, 5: 2579, 6: 1446, 7: 3274. The common area has 1,110 bytes free at `0x2BAA`.
- The UI strings are plain ASCII in 36-byte slots. The English settings menu starts around `0x640C9`, and the title "Settings" is at `0x64255`.
- The firmware has an updater that loads from a memory card ("New firmware is found!" at `0x695EC`). The file name it looks for hasn't been found yet.
- Changing the menu strings doesn't stop the frame booting, so there's no boot-time checksum over at least that region.

## Patches

[`patches/settings_hacked.py`](patches/settings_hacked.py) renames the settings title to "Hacked". It reproduces the image that was flashed and tested, sha256 `ed69234648f22e1154ea79892627ca31cb1c688c57211933f57c451cc3726cb8`.

[`patches/sdram_read.py`](patches/sdram_read.py) assembles [`sdram_read.asm`](patches/sdram_read.asm) (needs sdcc's `sdas8051`) and adds a debug vendor command that reads SDRAM over USB. Apply it after `settings_hacked.py`: `python3 patches/sdram_read.py hacked.bin out.bin`. Command `EB 01 a3 a2 a1 a0 n1 n0`: read `n` bytes (even, up to 65534; tested from 32) from SDRAM bus address `a3..a0`, both big-endian. The even-count rule is the patch's own; it assumes the 16-bit SDRAM port moves words, which wasn't tested. The example pads the CDB to 12 bytes, which is the form that was tested. For example, with sg3-utils:

```sh
sudo sg_raw -r 32768 -o chunk.bin /dev/sg0 EB 01 00 2F 5E 00 80 00 00 00 00 00
```

The host must ask for exactly `n` bytes; anything else is rejected. A full 8 MB dump in 32 KB chunks takes about 25 s. Interrupting a read mid-transfer will probably hang the frame until it's power-cycled: the copy loop has no timeout.

## Ghidra

This was done with Ghidra 12.2, which needs JDK 25. For headless use, set `JAVA_HOME_OVERRIDE` in `support/launch.properties`. Split the dump into one program for the boot stage and one per bank, each loaded at CPU address `0x0000`:

```sh
python3 - firmware/u9-A25L16PT-2026-11-07.bin <<'PY'
import sys; d = open(sys.argv[1], "rb").read()
open("boot.bin", "wb").write(d[:0x1000])
for k in range(8): open(f"bank{k}.bin", "wb").write(d[k*0x10000 + 0x1000 : k*0x10000 + 0x11000])
PY
analyzeHeadless ~/ghidra-projects/jeilin jeilin -import boot.bin bank?.bin \
  -loader BinaryLoader -processor 8051:BE:16:default -noanalysis
analyzeHeadless ~/ghidra-projects/jeilin jeilin -process boot.bin -scriptPath ghidra -postScript SeedVectors.java
analyzeHeadless ~/ghidra-projects/jeilin jeilin -process 'bank*.bin' -scriptPath ghidra \
  -preScript SeedVectors.java -postScript SeedBanked.java
```

[`SeedBanked.java`](ghidra/SeedBanked.java) names every banked-call stub `b<bank>_<target>` and seeds functions at the targets in each bank. That takes each bank from about 40 functions to 310–445. Ghidra doesn't follow calls between banks on its own, so a call to `b3_FD61` means: go to `bank3.bin`, at `0xFD61`. The script also handles Keil's `?C?LSTKXDATA` helper at `0x0B25`, which takes a 4-byte constant inline after the `LCALL`. Without that, Ghidra decodes the constant as code, garbling about 1,400 call sites. Run the script on freshly imported programs.

## Copyright

The firmware image is the original vendor code: most likely Jeilin's reference firmware, perhaps with changes by Photoco. It's included here for interoperability and research on an obsolete product. It will be removed on request from the rights holder.
