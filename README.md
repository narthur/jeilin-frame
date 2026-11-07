# jeilin-frame

Reverse engineering the firmware of a 2007 digital picture frame (Photoco PhotoShare 7D) built on a **Jeilin JL4204A**, with the goal of turning it into a USB-tethered live display: a host pushes rendered images to the frame over USB through a small firmware patch, in the style of the AX206 picture-frame hack.

No public datasheet, pinout or prior hacking write-up for the JL4204A was found. If you have one of these chips or frames, issues are welcome.

## Status

- [x] Firmware dumped from the SPI flash, in-circuit, with a Raspberry Pi 4 and flashrom.
- [x] Patch, flash and observe loop proven: the settings-menu title was changed to "Hacked" and it showed on screen.
- [ ] Map the code-bank switching so all 512 KB of code loads in Ghidra.
- [ ] Find the USB mass-storage SCSI command handler and the display/framebuffer code.
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
- **Layout.** This is inferred from byte statistics and not confirmed:

  | Range | Contents |
  |---|---|
  | `0x000000–0x07FFFF` | code (8 × 64 KB banks) |
  | `0x080000–0x15FFFF` | looks like bitmap fonts |
  | `0x160000–0x1DFFFF` | high entropy, probably compressed UI images (a JPEG at `0x34139`) |
  | `0x1E0000–0x1FFFFF` | erased |

- The UI strings are plain ASCII in 36-byte slots. The English settings menu starts around `0x640C9`, and the title "Settings" is at `0x64255`.
- The firmware has an updater that loads from a memory card ("New firmware is found!" at `0x695EC`). The file name it looks for hasn't been found yet.
- Changing the menu strings doesn't stop the frame booting, so there's no boot-time checksum over at least that region.

## Patches

[`patches/settings_hacked.py`](patches/settings_hacked.py) renames the settings title to "Hacked". It reproduces the image that was flashed and tested, sha256 `ed69234648f22e1154ea79892627ca31cb1c688c57211933f57c451cc3726cb8`.

## Ghidra

This was done with Ghidra 12.2, which needs JDK 25. For headless use, set `JAVA_HOME_OVERRIDE` in `support/launch.properties`. Import the first 64 KB as a raw binary with language `8051:BE:16:default`. Then run [`ghidra/SeedVectors.java`](ghidra/SeedVectors.java), because a raw import has no entry point:

```sh
head -c 65536 firmware/u9-A25L16PT-2026-11-07.bin > bank00.bin
analyzeHeadless ~/ghidra-projects/jeilin jeilin -import bank00.bin \
  -loader BinaryLoader -processor 8051:BE:16:default
analyzeHeadless ~/ghidra-projects/jeilin jeilin -process bank00.bin \
  -scriptPath ghidra -postScript SeedVectors.java
```

## Copyright

The firmware image is the original vendor code: most likely Jeilin's reference firmware, perhaps with changes by Photoco. It's included here for interoperability and research on an obsolete product. It will be removed on request from the rights holder.
