"""Rename the settings-menu title "Settings" -> "Hacked".

First on-screen patch; proved the patch-flash-observe loop on 2026-11-07.
Usage: python3 patches/settings_hacked.py firmware/u9-A25L16PT-2026-11-07.bin out.bin
"""
import sys

OFF = 0x64255  # "Settings\0" in the English menu-string table

src, dst = sys.argv[1:3]
d = bytearray(open(src, "rb").read())
assert d[OFF:OFF + 9] == b"Settings\0", "unexpected bytes; wrong image?"
d[OFF:OFF + 9] = b"Hacked\0\0\0"
open(dst, "wb").write(d)
