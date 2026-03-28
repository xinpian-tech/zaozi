# Experiment 1: Coverage Hole Closure — RVProbe vs SV Constraints vs Hand-Written Assembly

## Research Question

When riscv-dv (the industry-standard CRV generator) reaches coverage saturation, how do different directed methods compare in closing the remaining holes?

## Motivation

DAC26 reviewer #2 批评 "directed tests are created by manually adding constraints, so the increase in coverage is absolutely expected"。这说明仅展示 RVProbe 能填 hole 没有说服力——关键是展示**填 hole 时的约束表达成本差异**。

reviewer #4 批评 "comparison not clear with the state of the art"。用 riscv-dv 作为共同起点，对比三种填 hole 方法，直接回应这一批评。

## Hypothesis

riscv-dv 覆盖率饱和后，剩余的 hole 集中在**序列级属性**（hazard 组合）。这类 hole 的根本困难不在于"能不能填"，而在于表达序列级约束的代价：

- **手写汇编**：工程师需要在脑中求解 CSP（hazard 约束同时满足），耗时且易错
- **SV constraints**：能表达单指令约束，但序列级约束需要 workaround（辅助索引变量、显式等式约束），每种指令格式需要独立的 constraint class
- **RVProbe**：序列级约束是一等公民（`coverRAW()`, `coverWAR()`, `coverWAW()`, `coverNoHazard()`），求解器一次性保证全部满足

## Phase 1: riscv-dv Baseline Saturation (Done)

**配置**：riscv-dv 默认 RV32I 配置，pygen 后端
**数据**：`/root/riscv-dv/cov_out_2026-03-28/CoverageReport.txt`

### 结果概要

| 指标 | 值 |
|------|-----|
| 处理指令总数 | 334,177 |
| Covergroup 总数 | 27 |
| Coverpoint 总数 | 2,038 |
| 已覆盖 Coverpoint | 150 |
| Coverpoint 覆盖率 | 7.36% |
| 平均 Covergroup 分数 | 84.37% |

### 各 Covergroup 覆盖率

| Covergroup | Score | 缺失 Coverpoint |
|------------|------:|-----------------|
| mepc_alignment_cg | 100% | — |
| beq_cg | 93.75% | NO_HAZARD |
| add_cg | 90.62% | NO_HAZARD, WAR, WAW |
| sub_cg | 90.62% | NO_HAZARD, WAR, WAW |
| sra_cg | 90.62% | NO_HAZARD, WAR, WAW |
| srl_cg | 90.62% | NO_HAZARD, WAR, WAW |
| sll_cg | 90.62% | NO_HAZARD, WAR, WAW |
| slt_cg | 90.62% | NO_HAZARD, WAR, WAW |
| sltu_cg | 90.62% | NO_HAZARD, WAR, WAW |
| addi_cg | 89.29% | NO_HAZARD, WAR, WAW |
| slti_cg | 89.29% | NO_HAZARD, WAR, WAW |
| sltiu_cg | 89.29% | NO_HAZARD, WAR, WAW |
| and_cg | 86.11% | NO_HAZARD, WAR, WAW + logical(OPPOSITE, DIFFERENT) |
| xor_cg | 86.11% | NO_HAZARD, WAR, WAW + logical(OPPOSITE, DIFFERENT) |
| or_cg | 86.11% | NO_HAZARD, WAR, WAW + logical(OPPOSITE, DIFFERENT) |
| srli_cg | 85% | NO_HAZARD, WAR, WAW |
| srai_cg | 85% | NO_HAZARD, WAR, WAW |
| slli_cg | 85% | NO_HAZARD, WAR, WAW |
| ori_cg | 84.38% | NO_HAZARD, WAR, WAW + logical(OPPOSITE, DIFFERENT) |
| andi_cg | 84.38% | NO_HAZARD, WAR, WAW + logical(OPPOSITE, DIFFERENT) |
| csrrw_cg | 83.33% | WAR, WAW |
| xori_cg | 81.25% | NO_HAZARD, WAR, WAW + logical(IDENTICAL, OPPOSITE, DIFFERENT) |
| rv32i_misc_cg | 80% | ECALL |
| lui_cg | 75% | NO_HAZARD, WAR, WAW |
| auipc_cg | 75% | NO_HAZARD, WAR, WAW |
| jal_cg | 74.22% | rd=ZERO, rd_align(Aligned), imm_align(Aligned) |
| opcode_cg | 31.25% | 22 个未使用的 opcode 编码空间 |

## Phase 2: Hole Analysis

### Hole 分类

**类别 A: Hazard bins（主要目标，21 条指令）**

riscv-dv 33 万条指令后，几乎所有指令的 `cp_gpr_hazard` 只命中了 `RAW_HAZARD`。缺失：

| 缺失 Hazard 类型 | 受影响指令 | 数量 |
|---|---|---|
| NO_HAZARD | add, sub, sra, srl, sll, slt, sltu, addi, slti, sltiu, and, xor, or, ori, andi, xori, srli, srai, slli, lui, auipc | 21 |
| WAR_HAZARD | 同上 + csrrw | 22 |
| WAW_HAZARD | 同上 + csrrw | 22 |

总计 **65 个未覆盖 hazard bin**。

**这是 exp1 的核心数据点**：hazard 是序列级属性（取决于相邻指令间的寄存器依赖关系），riscv-dv 的随机生成只碰巧覆盖了 RAW（最常见的依赖模式），无法系统性地闭合 WAR/WAW/NoHazard。

**类别 B: Logical similarity bins（6 条逻辑指令）**

| 缺失 | 受影响指令 |
|---|---|
| OPPOSITE, DIFFERENT | and, or, xor, ori, andi |
| IDENTICAL, OPPOSITE, DIFFERENT | xori |

总计 **13 个未覆盖 logical similarity bin**。这需要操作数值域约束（rs1 和 rs2 的位模式关系），是指令级属性但需要值域控制。

**类别 C: 零散 hole（不纳入对比）**

- `opcode_cg`：22 个未使用的 opcode bin — 属于不可达（RV32I 不使用这些编码）
- `jal_cg`：rd=ZERO, 对齐 bin — jal 特有的边界情况
- `rv32i_misc_cg`：ECALL — 需要特权模式支持
- `csrrw_cg`：WAR/WAW — CSR 指令，不在基本 ALU 指令范围

### Phase 3 目标选定

**聚焦类别 A 的 hazard hole**，原因：

1. **数量大且模式统一**：21 条指令 × 3 种 hazard = 63 个 hole，足以做定量对比
2. **完美展示序列级约束**：hazard 定义在相邻指令对上，是本质的跨指令属性
3. **覆盖多种指令格式**：
   - R-type（10 条）：add, sub, sra, srl, sll, slt, sltu, and, xor, or
   - I-type ALU（6 条）：addi, slti, sltiu, ori, andi, xori
   - Shift-imm（3 条）：slli, srli, srai
   - U-type（2 条）：lui, auipc

4. **riscv-dv 的结构性盲点**：随机生成天然倾向 RAW（后一条指令读前一条写的寄存器很常见），但 WAR（后写前读的寄存器）、WAW（前后写同一寄存器）、NoHazard（完全无依赖）需要刻意构造

### Hazard 定义（对齐 riscv-dv 的 `hazard_e`）

riscv-dv 中 `gpr_hazard` 的判定逻辑（相邻指令 i 和 i+1）：

```
RAW_HAZARD:  instr[i].rd ∈ {instr[i+1].rs1, instr[i+1].rs2}
WAR_HAZARD:  instr[i+1].rd ∈ {instr[i].rs1, instr[i].rs2} ∧ ¬RAW
WAW_HAZARD:  instr[i].rd == instr[i+1].rd
NO_HAZARD:   ¬RAW ∧ ¬WAR ∧ ¬WAW
```

注意：这里的优先级是 RAW > WAR > WAW > NoHazard（riscv-dv 的实现中 RAW 优先判定）。

## Phase 3: Three-Way Hole Closure

### 目标

为每条受影响指令，生成一个短序列（≥4 条同类指令），使得相邻指令对中至少出现一次 WAR_HAZARD、一次 WAW_HAZARD、一次 NO_HAZARD。

### (a) RVProbe eDSL

已有代码，无需额外编写。`CoverageLib` 中的 `rType()`, `iTypeAlu()`, `shiftImm()`, `uType()` 已经包含 `coverWAR()`, `coverWAW()`, `coverNoHazard()` 约束。

以 add 为例（call site，3 行）：
```scala
object Add extends RVGenerator:
  val sets          = isRV64GC()
  def constraints() = rType(35, isAdd())
```

库函数（`CoverageLib.rType`，23 行，跨 10 条 R-type 指令复用）：
```scala
def rType(n: Int, opcode: ...)(using ...): Unit =
  (0 until n).foreach { i =>
    instruction(i, opcode) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
  }
  val seq = sequence(0, n)
  seq.coverBins(_.rd, allRegs)
  seq.coverBins(_.rs1, allRegs)
  seq.coverBins(_.rs2, allRegs)
  seq.coverRAW()
  seq.coverWAR()    // ← 直接表达
  seq.coverWAW()    // ← 直接表达
  seq.coverNoHazard() // ← 直接表达
```

**21 条指令的总 call-site LOC**：21 × 3 = 63 行
**Library LOC**：~90 行（4 个格式函数，跨所有指令复用）
**覆盖保证**：SAT = 全部 hazard bin 闭合，零迭代

### (b) SV Constrained-Random

需要为每种指令格式编写 sequence class + hazard 约束。

**难点展示**（以 R-type 为例）：

```systemverilog
// 问题 1: hazard 约束需要手动指定索引
rand int unsigned war_idx, waw_idx, nohaz_idx;
constraint hazard_indices {
    war_idx   inside {[0:N-2]};
    waw_idx   inside {[0:N-2]};
    nohaz_idx inside {[0:N-2]};
    war_idx != waw_idx; war_idx != nohaz_idx; waw_idx != nohaz_idx;
}

// 问题 2: 每种 hazard 都要手写等式约束
constraint war_hazard {
    (instrs[war_idx].rs1 == instrs[war_idx+1].rd) ||
    (instrs[war_idx].rs2 == instrs[war_idx+1].rd);
    instrs[war_idx].rd != instrs[war_idx+1].rs1;  // 排除 RAW
    instrs[war_idx].rd != instrs[war_idx+1].rs2;
}
constraint waw_hazard {
    instrs[waw_idx].rd == instrs[waw_idx+1].rd;
}
constraint no_hazard {
    instrs[nohaz_idx].rd != instrs[nohaz_idx+1].rs1;
    instrs[nohaz_idx].rd != instrs[nohaz_idx+1].rs2;
    instrs[nohaz_idx+1].rd != instrs[nohaz_idx].rs1;
    instrs[nohaz_idx+1].rd != instrs[nohaz_idx].rs2;
    instrs[nohaz_idx].rd != instrs[nohaz_idx+1].rd;
}

// 问题 3: U-type 没有 rs1/rs2，constraint class 完全不同
// 问题 4: covergroup 只观测不驱动，上面全是额外的 workaround
```

**每种格式的 SV constraint class**（估算）：
- R-type：~100 行（3 寄存器 + 4 种 hazard）
- I-type ALU：~85 行（2 寄存器 + 4 种 hazard）
- Shift-imm：~80 行（同 I-type 但无 imm 约束）
- U-type：~60 行（1 寄存器 + WAW/NoHazard，WAR 需要看前一条指令的 rs）

**21 条指令的总 LOC**：4 × ~80 + 21 × ~10（call site）≈ **530 行**
**覆盖保证**：求解器保证（如果约束写对了），但每种格式需要独立编写和调试

### (c) Hand-Written Assembly

为每条指令手写满足 hazard 覆盖的序列。

**难点展示**（以 add 为例）：

```asm
# 需要：相邻指令对中出现 WAR, WAW, NoHazard
# WAW: inst1.rd == inst2.rd
    add x5, x1, x2      # rd=x5
    add x5, x3, x4      # rd=x5 → WAW ✓
# WAR: inst2.rd ∈ {inst1.rs1, inst1.rs2} ∧ ¬RAW
    add x6, x7, x8      # rs1=x7
    add x7, x9, x10     # rd=x7 → WAR ✓ (且 x6 ∉ {x9,x10} → ¬RAW)
# NoHazard: 完全无依赖
    add x11, x12, x13
    add x14, x15, x16   # 无交集 → NoHazard ✓
```

看似简单，但：
1. 如果同时要求寄存器 bin 覆盖（rd 覆盖 x1..x31），WAW 要求 rd 重复，与 bin 覆盖矛盾 → 需要额外指令补偿
2. U-type（lui/auipc）只有 rd，WAR 需要判断"前一条指令是否用了本条的 rd 作为 rs" → 工程师必须理解每种格式的 hazard 语义
3. 写完后需要逐条检查或编写验证脚本

**每条指令最少 4 条 asm**（WAR + WAW + NoHazard 各一对，共 3 对 = 4~6 条）
**21 条指令的总 asm 行数**：21 × ~6 = **~126 行 asm + ~126 行注释 + 验证脚本**
**覆盖保证**：无。必须手动验证或编写脚本。

## Metrics

| 度量 | RVProbe | SV Constraints | Hand-Written ASM |
|------|---------|---------------|-----------------|
| **Call-site LOC** | 63（21×3） | ~210（21×10） | ~252（21×12 含注释） |
| **Library/Framework LOC** | ~90（4 个格式函数，复用） | ~320（4 个 constraint class） | 0（无可复用代码） |
| **总 LOC** | ~153 | ~530 | ~252 + 验证脚本 |
| **序列级约束** | 原生（`coverWAR()` 一行） | Workaround（辅助变量 + 手写约束） | 脑内求解 |
| **覆盖保证** | SAT = 闭合 | 求解器保证（如果约束正确） | 无（需手动验证） |
| **格式适配** | 自动（`hasRd()`/`hasRs1()`） | 每格式独立 class | 每格式独立手写 |
| **新增指令的边际成本** | 3 行 | ~10 行 + 复用 class | ~12 行 + 重新验证 |

### 关键发现

1. **hole 的本质是序列级的**：riscv-dv 33 万条指令只覆盖了 RAW，WAR/WAW/NoHazard 全部缺失。这不是随机数不够的问题，而是 riscv-dv 的生成策略没有 hazard-aware 的序列级约束。

2. **三种方法都能填 hole，但代价不同**：
   - RVProbe：约束即覆盖目标，无翻译损耗
   - SV：需要将序列级目标手动编码为单指令级约束的组合
   - 手写：需要在脑中求解 CSP，且无正确性保证

3. **可扩展性差异**：新增一条 R-type 指令，RVProbe 加 3 行；SV 复用已有 class 加 ~10 行；手写需要重新构造满足约束的序列（~12 行 + 重新验证）。

## File Plan

```
rvprobe/paper/exp1/
├── EXP1.md                          ← 本文件（实验设计 + 结果）
├── phase1/
│   └── CoverageReport.txt           ← riscv-dv 覆盖率报告（链接或拷贝）
├── phase3_rvprobe/
│   └── → rvprobe/src/cases/coverage/RV32I.scala（已有）
│   └── → rvprobe/src/cases/coverage/CoverageLib.scala（已有）
├── phase3_sv/
│   ├── sv_rtype_hazard.sv           ← R-type hazard closure (新)
│   ├── sv_itype_hazard.sv           ← I-type ALU hazard closure (新)
│   ├── sv_shiftimm_hazard.sv        ← Shift-imm hazard closure (新)
│   └── sv_utype_hazard.sv           ← U-type hazard closure (新)
├── phase3_handwritten/
│   ├── handwritten_rtype_hazard.S   ← R-type 手写序列 (新)
│   ├── handwritten_itype_hazard.S   ← I-type 手写序列 (新)
│   ├── handwritten_shiftimm_hazard.S← Shift-imm 手写序列 (新)
│   └── handwritten_utype_hazard.S   ← U-type 手写序列 (新)
└── legacy/
    ├── handwritten_add.S            ← (旧版，保留参考)
    ├── handwritten_addi.S
    ├── handwritten_sw.S
    ├── sv_add_coverage.sv
    ├── sv_addi_coverage.sv
    ├── sv_sw_coverage.sv
    ├── verify_add.py
    ├── verify_addi.py
    └── verify_sw.py
```

## Status

- [x] Phase 1: riscv-dv 饱和实验完成，334K 指令，覆盖率报告已有
- [x] Phase 2: Hole 分析完成，确认 21 条指令 × 3 种 hazard = 63 个 hole
- [x] RVProbe 侧：CoverageLib + RV32I.scala 已有完整实现
- [ ] Phase 3 SV: 编写 4 个格式的 hazard closure constraint class
- [ ] Phase 3 手写: 编写 4 个格式的 hazard closure 汇编
- [ ] LOC 统计与对比表
- [ ] 旧文件迁移到 legacy/
