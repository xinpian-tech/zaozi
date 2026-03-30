// SystemVerilog Constrained-Random — hazard hole closure for 21 RV32I instructions
//
// Fills the same 63 holes as handwrite.S and dsl.scala:
//   21 instructions × {WAR, WAW, NoHazard}
//
// Must write a separate constraint class per instruction format because
// each format has different register fields (rd/rs1/rs2 vs rd/rs1 vs rd only).

// ============================================================
// R-type constraint class (used by: add, sub, and, or, xor, sll, srl, sra, slt, sltu)
// ============================================================
class rtype_hazard_sequence #(int N = 6);

    typedef struct {
        rand bit [4:0] rd;
        rand bit [4:0] rs1;
        rand bit [4:0] rs2;
    } rtype_instr_t;

    rand rtype_instr_t instrs[N];

    // Non-zero registers
    constraint valid_regs {
        foreach (instrs[i]) {
            instrs[i].rd  inside {[1:31]};
            instrs[i].rs1 inside {[1:31]};
            instrs[i].rs2 inside {[1:31]};
        }
    }

    // --- Hazard index variables ---
    // SV has no existential quantifier, so we must manually designate
    // which adjacent pair produces each hazard type.
    rand int unsigned war_idx, waw_idx, nohaz_idx;

    constraint hazard_indices {
        war_idx   inside {[0:N-2]};
        waw_idx   inside {[0:N-2]};
        nohaz_idx inside {[0:N-2]};
        war_idx != waw_idx;
        war_idx != nohaz_idx;
        waw_idx != nohaz_idx;
    }

    // --- WAR: curr.rd ∈ {prev.rs1, prev.rs2} ∧ ¬RAW ---
    constraint war_hazard {
        (instrs[war_idx+1].rd == instrs[war_idx].rs1) ||
        (instrs[war_idx+1].rd == instrs[war_idx].rs2);
        // Exclude RAW (higher priority)
        instrs[war_idx].rd != instrs[war_idx+1].rs1;
        instrs[war_idx].rd != instrs[war_idx+1].rs2;
    }

    // --- WAW: prev.rd == curr.rd ∧ ¬RAW ∧ ¬WAR ---
    constraint waw_hazard {
        instrs[waw_idx].rd == instrs[waw_idx+1].rd;
        // Exclude RAW
        instrs[waw_idx].rd != instrs[waw_idx+1].rs1;
        instrs[waw_idx].rd != instrs[waw_idx+1].rs2;
        // Exclude WAR
        instrs[waw_idx+1].rd != instrs[waw_idx].rs1;
        instrs[waw_idx+1].rd != instrs[waw_idx].rs2;
    }

    // --- NoHazard: no dependency at all ---
    constraint no_hazard {
        instrs[nohaz_idx].rd   != instrs[nohaz_idx+1].rs1;
        instrs[nohaz_idx].rd   != instrs[nohaz_idx+1].rs2;
        instrs[nohaz_idx+1].rd != instrs[nohaz_idx].rs1;
        instrs[nohaz_idx+1].rd != instrs[nohaz_idx].rs2;
        instrs[nohaz_idx].rd   != instrs[nohaz_idx+1].rd;
    }

endclass
// ~65 lines per format


// ============================================================
// I-type ALU constraint class (used by: addi, andi, ori, xori, slti, sltiu)
// ============================================================
class itype_hazard_sequence #(int N = 6);

    typedef struct {
        rand bit [4:0]  rd;
        rand bit [4:0]  rs1;
        rand bit [11:0] imm;
    } itype_instr_t;

    rand itype_instr_t instrs[N];

    constraint valid_regs {
        foreach (instrs[i]) {
            instrs[i].rd  inside {[1:31]};
            instrs[i].rs1 inside {[1:31]};
        }
    }

    rand int unsigned war_idx, waw_idx, nohaz_idx;

    constraint hazard_indices {
        war_idx   inside {[0:N-2]};
        waw_idx   inside {[0:N-2]};
        nohaz_idx inside {[0:N-2]};
        war_idx != waw_idx;
        war_idx != nohaz_idx;
        waw_idx != nohaz_idx;
    }

    // WAR: curr.rd == prev.rs1 ∧ ¬RAW
    // Note: I-type has only rs1, no rs2 — different from R-type
    constraint war_hazard {
        instrs[war_idx+1].rd == instrs[war_idx].rs1;
        instrs[war_idx].rd   != instrs[war_idx+1].rs1;  // ¬RAW
    }

    // WAW: prev.rd == curr.rd ∧ ¬RAW ∧ ¬WAR
    constraint waw_hazard {
        instrs[waw_idx].rd   == instrs[waw_idx+1].rd;
        instrs[waw_idx].rd   != instrs[waw_idx+1].rs1;  // ¬RAW
        instrs[waw_idx+1].rd != instrs[waw_idx].rs1;     // ¬WAR
    }

    // NoHazard
    constraint no_hazard {
        instrs[nohaz_idx].rd   != instrs[nohaz_idx+1].rs1;
        instrs[nohaz_idx+1].rd != instrs[nohaz_idx].rs1;
        instrs[nohaz_idx].rd   != instrs[nohaz_idx+1].rd;
    }

endclass
// ~55 lines — note the different WAR/WAW constraints due to missing rs2


// ============================================================
// Shift-imm constraint class (used by: slli, srli, srai)
// Register fields identical to I-type, so the constraint structure is the same.
// In a real codebase this would be a copy-paste of itype_hazard_sequence
// with the imm field changed to shamt. No reuse mechanism in SV.
// ============================================================
class shiftimm_hazard_sequence #(int N = 6);

    typedef struct {
        rand bit [4:0] rd;
        rand bit [4:0] rs1;
        rand bit [4:0] shamt;
    } shiftimm_instr_t;

    rand shiftimm_instr_t instrs[N];

    constraint valid_regs {
        foreach (instrs[i]) {
            instrs[i].rd  inside {[1:31]};
            instrs[i].rs1 inside {[1:31]};
        }
    }

    rand int unsigned war_idx, waw_idx, nohaz_idx;

    constraint hazard_indices {
        war_idx   inside {[0:N-2]};
        waw_idx   inside {[0:N-2]};
        nohaz_idx inside {[0:N-2]};
        war_idx != waw_idx;
        war_idx != nohaz_idx;
        waw_idx != nohaz_idx;
    }

    // Same as I-type (only rs1, no rs2)
    constraint war_hazard {
        instrs[war_idx+1].rd == instrs[war_idx].rs1;
        instrs[war_idx].rd   != instrs[war_idx+1].rs1;
    }

    constraint waw_hazard {
        instrs[waw_idx].rd   == instrs[waw_idx+1].rd;
        instrs[waw_idx].rd   != instrs[waw_idx+1].rs1;
        instrs[waw_idx+1].rd != instrs[waw_idx].rs1;
    }

    constraint no_hazard {
        instrs[nohaz_idx].rd   != instrs[nohaz_idx+1].rs1;
        instrs[nohaz_idx+1].rd != instrs[nohaz_idx].rs1;
        instrs[nohaz_idx].rd   != instrs[nohaz_idx+1].rd;
    }

endclass
// ~50 lines — structurally identical to I-type, but SV has no way to share


// ============================================================
// U-type constraint class (used by: lui, auipc)
//
// U-type has ONLY rd — no rs1, no rs2.
// Between two U-type instructions:
//   RAW is impossible (no rs to read from)
//   WAR is impossible (no rs to conflict with)
//   Only WAW (same rd) and NoHazard (different rd) are possible.
//
// To cover WAR, the PREVIOUS instruction must be a different format.
// This requires a CROSS-FORMAT sequence class — a fundamentally
// different constraint structure that cannot reuse any of the above.
// ============================================================
class utype_hazard_sequence #(int N = 7);

    typedef struct {
        rand bit [4:0]  rd;
        rand bit [19:0] imm;
    } utype_instr_t;

    // For WAR coverage, we need a non-U-type predecessor.
    // Model it as a separate instruction with rs1 field.
    typedef struct {
        rand bit [4:0] rd;
        rand bit [4:0] rs1;
    } helper_instr_t;

    rand helper_instr_t helper;      // predecessor for WAR pair
    rand utype_instr_t  war_target;  // the U-type instruction classified as WAR
    rand utype_instr_t  waw_pair[2]; // two U-type instructions for WAW
    rand utype_instr_t  nohaz_pair[2]; // two U-type instructions for NoHazard

    constraint valid_regs {
        helper.rd       inside {[1:31]};
        helper.rs1      inside {[1:31]};
        war_target.rd   inside {[1:31]};
        waw_pair[0].rd  inside {[1:31]};
        waw_pair[1].rd  inside {[1:31]};
        nohaz_pair[0].rd inside {[1:31]};
        nohaz_pair[1].rd inside {[1:31]};
    }

    // WAR: war_target.rd == helper.rs1
    // (RAW impossible — U-type has no rs fields)
    constraint war_hazard {
        war_target.rd == helper.rs1;
    }

    // WAW: same rd
    constraint waw_hazard {
        waw_pair[0].rd == waw_pair[1].rd;
    }

    // NoHazard: different rd
    constraint no_hazard {
        nohaz_pair[0].rd != nohaz_pair[1].rd;
    }

endclass
// ~50 lines — completely different structure from R/I/Shift types


// ============================================================
// Usage: instantiate per instruction
// ============================================================
//
// rtype_hazard_sequence  add_seq  = new();  add_seq.randomize();
// rtype_hazard_sequence  sub_seq  = new();  sub_seq.randomize();
// rtype_hazard_sequence  and_seq  = new();  and_seq.randomize();
// rtype_hazard_sequence  or_seq   = new();  or_seq.randomize();
// rtype_hazard_sequence  xor_seq  = new();  xor_seq.randomize();
// rtype_hazard_sequence  sll_seq  = new();  sll_seq.randomize();
// rtype_hazard_sequence  srl_seq  = new();  srl_seq.randomize();
// rtype_hazard_sequence  sra_seq  = new();  sra_seq.randomize();
// rtype_hazard_sequence  slt_seq  = new();  slt_seq.randomize();
// rtype_hazard_sequence  sltu_seq = new();  sltu_seq.randomize();
// itype_hazard_sequence  addi_seq = new();  addi_seq.randomize();
// itype_hazard_sequence  andi_seq = new();  andi_seq.randomize();
// itype_hazard_sequence  ori_seq  = new();  ori_seq.randomize();
// itype_hazard_sequence  xori_seq = new();  xori_seq.randomize();
// itype_hazard_sequence  slti_seq = new();  slti_seq.randomize();
// itype_hazard_sequence  sltiu_seq= new();  sltiu_seq.randomize();
// shiftimm_hazard_sequence slli_seq = new(); slli_seq.randomize();
// shiftimm_hazard_sequence srli_seq = new(); srli_seq.randomize();
// shiftimm_hazard_sequence srai_seq = new(); srai_seq.randomize();
// utype_hazard_sequence lui_seq   = new();  lui_seq.randomize();
// utype_hazard_sequence auipc_seq = new();  auipc_seq.randomize();
//
// ============================================================
// Summary
//
// 4 constraint classes: ~220 lines total
// 21 instantiation lines
// Total: ~240 lines
//
// Key problems:
// 1. Each format needs a separate class (no polymorphism over register fields)
// 2. Hazard priority exclusion must be manually encoded (¬RAW, ¬WAR conditions)
// 3. U-type WAR requires a completely different class structure (cross-format)
// 4. Shift-imm is a copy-paste of I-type — SV cannot abstract over field names
// 5. covergroup is observe-only; all above constraints are EXTRA boilerplate
//    on top of what riscv-dv already has
// ============================================================
