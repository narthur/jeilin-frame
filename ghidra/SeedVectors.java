// Seed disassembly at the 8051 reset + interrupt vectors, then auto-analyze.
// A raw binary import has no entry point, so Ghidra finds nothing without this.
// analyzeHeadless <proj_dir> <proj> -process boot.bin -scriptPath ghidra -postScript SeedVectors.java
import ghidra.app.script.GhidraScript;

public class SeedVectors extends GhidraScript {
  public void run() throws Exception {
    for (int v : new int[]{0x00,0x03,0x0B,0x13,0x1B,0x23,0x2B,0x33,0x3B,0x43,0x4B}) {
      if ((getByte(toAddr(v)) & 0xff) == 0xff) continue; // erased slot
      disassemble(toAddr(v));
      createFunction(toAddr(v), String.format("vec_%02X", v));
    }
    analyzeAll(currentProgram);
    println("functions=" + currentProgram.getFunctionManager().getFunctionCount());
  }
}
