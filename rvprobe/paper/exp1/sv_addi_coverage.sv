// SystemVerilog Constrained-Random: ADDI (I-type ALU) coverage
// Coverage goals: same as RVProbe iTypeAlu(35, isAddi())
//   - rd  covers x1..x31
//   - rs1 covers x1..x31
//   - imm12 hits boundary values: -2048, -1, 0, 1, 2047
//   - RAW, WAR, WAW, NoHazard hazard types all present

class addi_sequence;
    rand bit [4:0] rd  [35];
    rand bit [4:0] rs1 [35];
    rand bit signed [11:0] imm12 [35];

    // Per-instruction constraints
    constraint valid_regs {
        foreach (rd[i])  rd[i]  inside {[1:31]};
        foreach (rs1[i]) rs1[i] inside {[1:31]};
    }

    // --- Register bin coverage ---
    // Problem: SV constraints cannot directly express "every value in
    // {1..31} appears at least once across the array."
    // Workaround: auxiliary assignment arrays.

    rand int unsigned rd_assign[31];
    rand int unsigned rs1_assign[31];

    constraint rd_cover {
        foreach (rd_assign[i]) {
            rd_assign[i] inside {[0:34]};
            rd[rd_assign[i]] == i + 1;
        }
    }

    constraint rs1_cover {
        foreach (rs1_assign[i]) {
            rs1_assign[i] inside {[0:34]};
            rs1[rs1_assign[i]] == i + 1;
        }
    }

    // --- Immediate boundary coverage ---
    // Must ensure each of {-2048, -1, 0, 1, 2047} appears at least once.
    // Again, SV has no "exists-in-array" constraint.

    rand int unsigned imm_neg2048_idx;
    rand int unsigned imm_neg1_idx;
    rand int unsigned imm_zero_idx;
    rand int unsigned imm_pos1_idx;
    rand int unsigned imm_pos2047_idx;

    constraint imm_boundary {
        imm_neg2048_idx inside {[0:34]};
        imm_neg1_idx    inside {[0:34]};
        imm_zero_idx    inside {[0:34]};
        imm_pos1_idx    inside {[0:34]};
        imm_pos2047_idx inside {[0:34]};

        imm12[imm_neg2048_idx] == -2048;
        imm12[imm_neg1_idx]    == -1;
        imm12[imm_zero_idx]    == 0;
        imm12[imm_pos1_idx]    == 1;
        imm12[imm_pos2047_idx] == 2047;
    }

    // --- Hazard constraints (same boilerplate as R-type) ---
    rand int unsigned raw_idx, war_idx, waw_idx, nohaz_idx;

    constraint hazard_indices {
        raw_idx   inside {[0:33]};
        war_idx   inside {[0:33]};
        waw_idx   inside {[0:33]};
        nohaz_idx inside {[0:33]};
        raw_idx != war_idx;
        raw_idx != waw_idx;
        raw_idx != nohaz_idx;
        war_idx != waw_idx;
        war_idx != nohaz_idx;
        waw_idx != nohaz_idx;
    }

    // RAW: rd[i] == rs1[i+1]  (I-type has only rs1, no rs2)
    constraint raw_hazard {
        rd[raw_idx] == rs1[raw_idx + 1];
    }

    // WAR: rs1[i] == rd[i+1] AND no RAW
    constraint war_hazard {
        rs1[war_idx] == rd[war_idx + 1];
        rd[war_idx] != rs1[war_idx + 1];
    }

    // WAW: rd[i] == rd[i+1]
    constraint waw_hazard {
        rd[waw_idx] == rd[waw_idx + 1];
    }

    // NoHazard: no overlap
    constraint no_hazard {
        rd[nohaz_idx]  != rs1[nohaz_idx + 1];
        rs1[nohaz_idx] != rd[nohaz_idx + 1];
        rd[nohaz_idx]  != rd[nohaz_idx + 1];
    }
endclass

// ============================================================
// Observations:
//
// 1. The immediate boundary coverage requires 5 additional auxiliary
//    variables + 10 lines of constraints. RVProbe: coverBins(_.imm12,
//    immBoundary12) — one line.
//
// 2. Every new field that needs bin coverage adds ~10 lines of
//    boilerplate (auxiliary array + constraint block).
//
// 3. The hazard constraints must be re-derived for each instruction
//    format: I-type has rs1 only (no rs2), so the RAW/WAR conditions
//    differ from R-type. In SV, each format needs a separate class.
//    RVProbe: coverRAW() auto-detects available fields via hasRd()/
//    hasRs1()/hasRs2().
//
// 4. Total: ~95 lines of SV for ONE instruction.
//    RVProbe: iTypeAlu(35, isAddi()) + library = ~15 effective lines.
// ============================================================
