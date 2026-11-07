// Seed a per-bank program (bankN.bin, loaded at CPU 0x0000) from the Keil
// banked-call stub table in the common area, then auto-analyze.
//
// Stub table (identical in every bank): 0x11E3-0x166F, 5-byte entries
//   MOV DPTR,#target ; AJMP switch_n
// switch_n lives at 0x1113 + 0x1A*n and writes n to SFR 0xA5 (SFR page 6).
//
// Every stub is named b<n>_<target>; functions are created at the targets
// that belong to this program's bank. Bank is read from the program name.
//
// Keil's ?C?LSTKXDATA at 0x0B25 takes a 4-byte constant inline after the
// LCALL and returns past it. It is marked no-return only while disassembling so
// Ghidra doesn't decode the constant as code; each call then gets a dword
// plus a fall-through override to call+7, and analysis repeats until stable.
// Run on freshly imported programs; it can't remove code already decoded
// from those constants.
// analyzeHeadless <dir> <proj> -process 'bank*.bin' -scriptPath ghidra -postScript SeedBanked.java
import ghidra.app.cmd.function.CreateFunctionCmd;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;

public class SeedBanked extends GhidraScript {
  static final int TABLE_START = 0x11E3, TABLE_END = 0x1670;
  static final int SWITCH_BASE = 0x1113, SWITCH_STRIDE = 0x1A;
  static final int LSTKXDATA = 0x0B25;

  public void run() throws Exception {
    String name = currentProgram.getName();
    if (!name.matches("bank[0-7]\\.bin")) { println("skip " + name); return; }
    int myBank = name.charAt(4) - '0';

    Address lstk = toAddr(LSTKXDATA);
    disassemble(lstk);
    Function lf = createFunction(lstk, "C_LSTKXDATA");
    lf.setNoReturn(true);

    for (int n = 0; n < 8; n++) {
      int sw = SWITCH_BASE + SWITCH_STRIDE * n;
      disassemble(toAddr(sw));
      name(toAddr(sw), "bank_switch_" + n);
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
      name(toAddr(o), label);
      if (bank == myBank) {
        disassemble(toAddr(target));
        if (getFunctionAt(toAddr(target)) == null) name(toAddr(target), String.format("f_%04X", target));
        seeded++;
      }
    }
    analyzeAll(currentProgram);
    int fixed = 0;
    for (int pass = 0; pass < 20; pass++) {
      int n = fixInlineConstants(lstk);
      if (n == 0) break;
      fixed += n;
      analyzeAll(currentProgram);
    }
    lf.setNoReturn(false); // flow now continues via the overrides; the decompiler needs this
    for (Instruction i : currentProgram.getListing().getInstructions(true))
      if (i.getFlowType().isTerminal() && i.isFallThroughOverridden())
        i.setFlowOverride(FlowOverride.NONE); // drop the CALL_TERMINATOR left by the no-return marking
    for (Function f : currentProgram.getFunctionManager().getFunctions(true))
      CreateFunctionCmd.fixupFunctionBody(currentProgram, f, monitor); // bodies were cut at the no-return calls
    println(String.format("%s: bank %d, %d targets seeded, %d inline constants, %d functions",
        name, myBank, seeded, fixed, currentProgram.getFunctionManager().getFunctionCount()));
  }

  // Create the function, or rename it if earlier analysis already made one.
  void name(Address a, String label) throws Exception {
    Function f = getFunctionAt(a);
    if (f == null) createFunction(a, label);
    else f.setName(label, ghidra.program.model.symbol.SourceType.USER_DEFINED);
  }

  int fixInlineConstants(Address lstk) throws Exception {
    java.util.List<Instruction> todo = new java.util.ArrayList<>();
    for (Instruction i : currentProgram.getListing().getInstructions(true)) {
      if (!i.getMnemonicString().equals("LCALL")) continue;
      Address[] f = i.getFlows();
      if (f.length != 1 || !f[0].equals(lstk)) continue;
      if (!i.getMaxAddress().add(5).equals(i.getFallThrough())) todo.add(i);
    }
    int n = 0;
    for (Instruction i : todo) {
      Address k = i.getMaxAddress().add(1), next = k.add(4);
      clearListing(k, k.add(3));
      createDWord(k);
      i.setFallThrough(next);
      disassemble(next);
      n++;
    }
    return n;
  }
}
