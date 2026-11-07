"""Add vendor SCSI command 0xEB 0x01 (read SDRAM over USB) to bank 2.

Assembles sdram_read.asm with sdcc's sdas8051, drops it into bank 2's free
space at 0xF685, points the dispatcher's vendor-opcode check at it, and hooks
the dispatcher's expected-length/direction functions so it accepts EB's data-in.
Usage: python3 patches/sdram_read.py in.bin out.bin
in.bin: the original or settings_hacked.py image; one that already has this
patch is refused.
"""
import os, re, subprocess, sys, tempfile

BANK2 = 2 * 0x10000 + 0x1000          # flash offset of bank 2's CPU 0x0000
ORG, FREE_END = 0xF685, 0x10000       # free space in bank 2's window
HOOK = 0x9464                         # XRL A,#0xEB ; JZ ... ; MOV A,R7 ; XRL A,#0xEC ; JZ ...
HOOK_OLD = bytes.fromhex("64eb6075ef64ec6070")
# expected-length (b117) and expected-direction (ee27) functions; both start MOV DPTR,#0x146F
LEN_FN, DIR_FN, FN_OLD = 0xB117, 0xEE27, bytes.fromhex("90146f")

def assemble():
    here = os.path.dirname(os.path.abspath(__file__))
    with tempfile.TemporaryDirectory() as t:
        rel = os.path.join(t, "h.rel")
        subprocess.run(["sdas8051", "-plosgff", "-o", rel, os.path.join(here, "sdram_read.asm")], check=True)
        subprocess.run(["sdld", "-i", os.path.join(t, "h.ihx"), rel], check=True, capture_output=True)
        mem = {}
        for line in open(os.path.join(t, "h.ihx")):
            n, a, typ = int(line[1:3], 16), int(line[3:7], 16), int(line[7:9], 16)
            if typ == 0:
                for k in range(n):
                    mem[a + k] = int(line[9 + 2 * k:11 + 2 * k], 16)
        sym = dict((m[1], int(m[2], 16)) for m in re.finditer(r"(\w+)\s+00([0-9A-F]{4}) R", open(os.path.join(t, "h.sym")).read()))
    lo, hi = min(mem), max(mem) + 1
    assert lo == ORG and hi <= FREE_END and len(mem) == hi - lo, (hex(lo), hex(hi))
    return bytes(mem[a] for a in range(lo, hi)), sym

def main():
    src, dst = sys.argv[1:3]
    d = bytearray(open(src, "rb").read())
    code, sym = assemble()
    at = BANK2 + ORG
    assert d[BANK2 + HOOK:BANK2 + HOOK + 9] == HOOK_OLD, "dispatcher bytes differ; wrong image or already patched?"
    for fn in (LEN_FN, DIR_FN):
        assert d[BANK2 + fn:BANK2 + fn + 3] == FN_OLD, f"bytes at {fn:04X} differ"
    assert set(d[at:at + len(code)]) == {0xFF}, "free space not free"
    d[at:at + len(code)] = code
    d[BANK2 + HOOK:BANK2 + HOOK + 3] = bytes([0x02, ORG >> 8, ORG & 0xFF])  # LJMP handler
    for fn, target in ((LEN_FN, sym["len_hook"]), (DIR_FN, sym["dir_hook"])):
        d[BANK2 + fn:BANK2 + fn + 3] = bytes([0x02, target >> 8, target & 0xFF])
    open(dst, "wb").write(d)
    print(f"handler {len(code)} bytes at bank2:{ORG:04X} (flash 0x{at:06X}); hooks at bank2:{HOOK:04X}, {LEN_FN:04X}, {DIR_FN:04X}")

if __name__ == "__main__":
    main()
