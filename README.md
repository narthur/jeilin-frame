# jeilin-frame

Reverse engineering the firmware of a 2007 digital picture frame (Photoco PhotoShare 7D) built on a **Jeilin JL4204A**, with the goal of turning it into a USB-tethered live display: a host pushes rendered images to the frame over USB through a small firmware patch, in the style of the AX206 picture-frame hack.

No public datasheet, pinout or prior hacking write-up for the JL4204A was found. If you have one of these chips or frames, issues are welcome.

## Status

- [x] Firmware dumped from the SPI flash, in-circuit, with a Raspberry Pi 4 and flashrom.
- [x] Patch, flash and observe loop proven: the settings-menu title was changed to "Hacked" and it showed on screen.
- [x] Map the code-bank switching so all 512 KB of code loads in Ghidra.
- [x] Find the USB mass-storage SCSI command handler.
- [ ] Find the display/framebuffer code.
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
  | **EB, EC** | vendor | **no handler.** At `bank2:9464` they skip the ILLEGAL REQUEST path and go straight to the CSW, so they succeed and do nothing. |

  Anything else gets sense key 5 / ASC `0x20`. **The planned hook** is to redirect the `JZ` at `bank2:9466` (opcode `0xEB`) to a new handler in bank 2's free space: 2,427 bytes at `0xF685`, the same bank, so no bank switch is needed.
- Free space in each bank's window, in bytes: 0: 14484, 1: 7054, 2: 2427, 3: 622, 4: 6999, 5: 2579, 6: 1446, 7: 3274. The common area has 1,110 bytes free at `0x2BAA`.
- The UI strings are plain ASCII in 36-byte slots. The English settings menu starts around `0x640C9`, and the title "Settings" is at `0x64255`.
- The firmware has an updater that loads from a memory card ("New firmware is found!" at `0x695EC`). The file name it looks for hasn't been found yet.
- Changing the menu strings doesn't stop the frame booting, so there's no boot-time checksum over at least that region.

## Patches

[`patches/settings_hacked.py`](patches/settings_hacked.py) renames the settings title to "Hacked". It reproduces the image that was flashed and tested, sha256 `ed69234648f22e1154ea79892627ca31cb1c688c57211933f57c451cc3726cb8`.

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
analyzeHeadless ~/ghidra-projects/jeilin jeilin -process -scriptPath ghidra -postScript SeedVectors.java
analyzeHeadless ~/ghidra-projects/jeilin jeilin -process 'bank*.bin' -scriptPath ghidra -postScript SeedBanked.java
```

[`SeedBanked.java`](ghidra/SeedBanked.java) names every banked-call stub `b<bank>_<target>` and seeds functions at the targets in each bank. That takes each bank from about 40 functions to 300–430. Ghidra doesn't follow calls between banks on its own, so a call to `b3_FD61` means: go to `bank3.bin`, at `0xFD61`.

## Copyright

The firmware image is the original vendor code: most likely Jeilin's reference firmware, perhaps with changes by Photoco. It's included here for interoperability and research on an obsolete product. It will be removed on request from the rights holder.
