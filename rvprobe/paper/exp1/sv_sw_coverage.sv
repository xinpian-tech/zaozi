// SystemVerilog Constrained-Random: SW (Store) coverage
// Coverage goals: same as RVProbe store(35, isSw())
//   - rs1 covers x1..x31
//   - rs2 covers x1..x31
//   - At least one adjacent pair with NoHazard

class sw_sequence;
    rand bit [4:0] rs1 [35];
    rand bit [4:0] rs2 [35];

    constraint valid_regs {
        foreach (rs1[i]) rs1[i] inside {[1:31]};
        foreach (rs2[i]) rs2[i] inside {[1:31]};
    }

    // Register bin coverage (same workaround pattern)
    rand int unsigned rs1_assign[31];
    rand int unsigned rs2_assign[31];

    constraint rs1_cover {
        foreach (rs1_assign[i]) {
            rs1_assign[i] inside {[0:34]};
            rs1[rs1_assign[i]] == i + 1;
        }
    }

    constraint rs2_cover {
        foreach (rs2_assign[i]) {
            rs2_assign[i] inside {[0:34]};
            rs2[rs2_assign[i]] == i + 1;
        }
    }

    // NoHazard: stores have no rd, so there is trivially no
    // RAW/WAR/WAW between consecutive stores.
    // But does the SV engineer KNOW this?
    // They must reason about the instruction format to determine
    // which hazards are applicable — a manual step.
    //
    // In contrast, RVProbe's coverNoHazard() checks hasRd() at
    // runtime and automatically skips inapplicable hazard conditions.
    //
    // If the engineer incorrectly adds RAW constraints for stores,
    // the solver will either:
    //   (a) produce UNSAT (wasting debug time), or
    //   (b) silently constrain a non-existent rd field
endclass

// ============================================================
// Observations:
//
// 1. Simpler than R-type/I-type because stores have fewer fields.
//    But the engineer must KNOW this — SV doesn't help.
//
// 2. The bin coverage workaround is identical boilerplate repeated
//    for every instruction type. ~40 lines per instruction.
//
// 3. If the instruction format changes (e.g., a custom extension
//    adds an rd field to stores), ALL SV constraint classes must
//    be manually updated. RVProbe: auto-adapts via hasRd().
// ============================================================
