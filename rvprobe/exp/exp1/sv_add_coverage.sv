// SystemVerilog Constrained-Random approach for ADD instruction coverage
// Coverage goals: same as RVProbe rType(35, isAdd())
//   - rd  covers x1..x31
//   - rs1 covers x1..x31
//   - rs2 covers x1..x31
//   - RAW, WAR, WAW, NoHazard hazard types all present

// ============================================================
// Step 1: Define the instruction transaction
// ============================================================
class riscv_add_instr;
    rand bit [4:0] rd;
    rand bit [4:0] rs1;
    rand bit [4:0] rs2;

    // Constraint: no x0 for coverage bins
    constraint no_zero_rd  { rd  inside {[1:31]}; }
    constraint no_zero_rs1 { rs1 inside {[1:31]}; }
    constraint no_zero_rs2 { rs2 inside {[1:31]}; }
endclass

// ============================================================
// Step 2: Define coverage model
// ============================================================
covergroup add_coverage with function sample(
    bit [4:0] rd, bit [4:0] rs1, bit [4:0] rs2,
    bit [4:0] prev_rd, bit [4:0] prev_rs1, bit [4:0] prev_rs2
);
    // Register bin coverage
    cp_rd:  coverpoint rd  { bins regs[] = {[1:31]}; }
    cp_rs1: coverpoint rs1 { bins regs[] = {[1:31]}; }
    cp_rs2: coverpoint rs2 { bins regs[] = {[1:31]}; }

    // Hazard coverage — requires tracking previous instruction state
    cp_hazard: coverpoint get_hazard_type(rd, rs1, rs2, prev_rd, prev_rs1, prev_rs2) {
        bins raw      = {HAZARD_RAW};
        bins war      = {HAZARD_WAR};
        bins waw      = {HAZARD_WAW};
        bins no_hazard = {HAZARD_NONE};
    }
endgroup

// ============================================================
// Problem 1: SV constraints are PER-INSTRUCTION, not per-sequence.
// There is no built-in way to express "across 35 instructions,
// every rd bin x1..x31 must appear at least once."
//
// Workaround: Run many random iterations and HOPE coverage closes.
// Or write a custom sequence class:
// ============================================================
class add_sequence;
    rand riscv_add_instr instrs[35];

    // Attempting bin coverage as SV constraints:
    // "For each register x1..x31, at least one instruction has rd == x"
    // This requires quantifiers that SV constraints do NOT support natively.

    // Manual workaround: create auxiliary variables
    rand int unsigned rd_assignment[31];   // which instruction covers rd=x1..x31
    rand int unsigned rs1_assignment[31];
    rand int unsigned rs2_assignment[31];

    constraint rd_cover {
        foreach (rd_assignment[i]) {
            rd_assignment[i] inside {[0:34]};
            instrs[rd_assignment[i]].rd == i + 1;
        }
    }

    constraint rs1_cover {
        foreach (rs1_assignment[i]) {
            rs1_assignment[i] inside {[0:34]};
            instrs[rs1_assignment[i]].rs1 == i + 1;
        }
    }

    constraint rs2_cover {
        foreach (rs2_assignment[i]) {
            rs2_assignment[i] inside {[0:34]};
            instrs[rs2_assignment[i]].rs2 == i + 1;
        }
    }

    // ============================================================
    // Problem 2: Hazard constraints across adjacent instructions
    // Need: ∃ pair (i, i+1) with RAW, ∃ pair with WAR, etc.
    // SV has no existential quantifier over array elements.
    //
    // Workaround: designate specific indices for each hazard type.
    // ============================================================
    rand int unsigned raw_idx;  // index where RAW occurs with next
    rand int unsigned war_idx;
    rand int unsigned waw_idx;
    rand int unsigned nohaz_idx;

    constraint hazard_indices {
        raw_idx   inside {[0:33]};
        war_idx   inside {[0:33]};
        waw_idx   inside {[0:33]};
        nohaz_idx inside {[0:33]};
        // All different pairs
        raw_idx != war_idx;
        raw_idx != waw_idx;
        raw_idx != nohaz_idx;
        war_idx != waw_idx;
        war_idx != nohaz_idx;
        waw_idx != nohaz_idx;
    }

    // RAW: instrs[raw_idx].rd == instrs[raw_idx+1].rs1 or .rs2
    constraint raw_hazard {
        (instrs[raw_idx].rd == instrs[raw_idx + 1].rs1) ||
        (instrs[raw_idx].rd == instrs[raw_idx + 1].rs2);
    }

    // WAR: instrs[war_idx].rs1 == instrs[war_idx+1].rd (pure, no RAW)
    constraint war_hazard {
        (instrs[war_idx].rs1 == instrs[war_idx + 1].rd) ||
        (instrs[war_idx].rs2 == instrs[war_idx + 1].rd);
        // Exclude RAW
        instrs[war_idx].rd != instrs[war_idx + 1].rs1;
        instrs[war_idx].rd != instrs[war_idx + 1].rs2;
    }

    // WAW: instrs[waw_idx].rd == instrs[waw_idx+1].rd
    constraint waw_hazard {
        instrs[waw_idx].rd == instrs[waw_idx + 1].rd;
    }

    // NoHazard: no register dependency at all
    constraint no_hazard {
        instrs[nohaz_idx].rd != instrs[nohaz_idx + 1].rs1;
        instrs[nohaz_idx].rd != instrs[nohaz_idx + 1].rs2;
        instrs[nohaz_idx + 1].rd != instrs[nohaz_idx].rs1;
        instrs[nohaz_idx + 1].rd != instrs[nohaz_idx].rs2;
        instrs[nohaz_idx].rd != instrs[nohaz_idx + 1].rd;
    }
endclass

// ============================================================
// Summary of SV limitations encountered:
//
// 1. No sequence-level bin coverage constraint — SV covergroups
//    only OBSERVE coverage, they don't DRIVE generation.
//    Workaround: auxiliary index arrays (rd_assignment, etc.)
//    add ~30 lines of boilerplate per field.
//
// 2. No existential quantifier for hazard patterns — must manually
//    designate hazard indices and write explicit constraints.
//    Each hazard type requires ~5-10 lines.
//
// 3. Per-instruction-type boilerplate — R-type, I-type, S-type,
//    B-type all need different constraint classes. No metaprogramming
//    to auto-generate from riscv-opcodes.
//
// 4. Scalability — with 27 RV32I instructions × different formats,
//    this approach requires ~2700 lines of SV constraint code.
//
// Compare with RVProbe:
//   rType(35, isAdd())  — 1 line, all coverage goals guaranteed.
//   27 instructions     — 27 lines + ~50 lines of reusable library.
// ============================================================
