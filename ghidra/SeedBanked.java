// Seed a per-bank program (bankN.bin, loaded at CPU 0x0000) from the Keil
// banked-call stub table in the common area, then auto-analyze.
//
// Stub table (identical in every bank): 0x11E3-0x166F, 5-byte entries
//   MOV DPTR,#target ; AJMP switch_n
// switch_n lives at 0x1113 + 0x1A*n and writes n to SFR 0xA5 (SFR page 6).
//
// Every stub is named b<n>_<target>; functions are created at the targets
// that belong to this program's bank. Bank is read from the program name.
// analyzeHeadless <dir> <proj> -process 'bank*.bin' -scriptPath ghidra -postScript SeedBanked.java
import ghidra.app.script.GhidraScript;

public class SeedBanked extends GhidraScript {
  static final int TABLE_START = 0x11E3, TABLE_END = 0x1670;
  static final int SWITCH_BASE = 0x1113, SWITCH_STRIDE = 0x1A;

  public void run() throws Exception {
    String name = currentProgram.getName();
    if (!name.matches("bank[0-7]\\.bin")) { println("skip " + name); return; }
    int myBank = name.charAt(4) - '0';

    for (int n = 0; n < 8; n++) {
      int sw = SWITCH_BASE + SWITCH_STRIDE * n;
      disassemble(toAddr(sw));
      createFunction(toAddr(sw), "bank_switch_" + n);
    }
    int seeded = 0;
    for (int o = TABLE_START; o < TABLE_END; o += 5) {
      if ((getByte(toAddr(o)) & 0xff) != 0x90) throw new Exception("not a stub at " + Integer.toHexString(o));
      int target = ((getByte(toAddr(o + 1)) & 0xff) << 8) | (getByte(toAddr(o + 2)) & 0xff);
      int b3 = getByte(toAddr(o + 3)) & 0xff, b4 = getByte(toAddr(o + 4)) & 0xff;
      int ajmp = ((o + 5) & 0xF800) | ((b3 >> 5) << 8) | b4;
      int bank = (ajmp - SWITCH_BASE) / SWITCH_STRIDE;
      if ((b3 & 0x1F) != 0x01 || bank < 0 || bank > 7 || SWITCH_BASE + SWITCH_STRIDE * bank != ajmp)
        throw new Exception("bad stub at " + Integer.toHexString(o));

      String label = String.format("b%d_%04X", bank, target);
      disassemble(toAddr(o));
      createFunction(toAddr(o), label);
      if (bank == myBank) {
        disassemble(toAddr(target));
        if (getFunctionAt(toAddr(target)) == null) createFunction(toAddr(target), String.format("f_%04X", target));
        seeded++;
      }
    }
    analyzeAll(currentProgram);
    println(String.format("%s: bank %d, %d targets seeded, %d functions",
        name, myBank, seeded, currentProgram.getFunctionManager().getFunctionCount()));
  }
}
