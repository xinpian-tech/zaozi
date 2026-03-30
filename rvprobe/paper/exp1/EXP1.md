# Experiment 1: Coverage Hole Closure — RVProbe vs Hand-Written Assembly

## Research Question

When riscv-dv (the industry-standard CRV generator) reaches coverage saturation, how do different directed methods compare in closing the remaining holes?

## Motivation

DAC26 reviewer #2 批评 "directed tests are created by manually adding constraints, so the increase in coverage is absolutely expected"。这说明仅展示 RVProbe 能填 hole 没有说服力——关键是展示**填 hole 时的约束表达成本差异**。

reviewer #4 批评 "comparison not clear with the state of the art"。用 riscv-dv 作为共同起点，直接回应这一批评。

## Hypothesis

riscv-dv 覆盖率饱和后，剩余的 hole 集中在**序列级属性**（hazard 组合）。这类 hole 的根本困难不在于"能不能填"，而在于表达序列级约束的代价。

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

总计 **13 个未覆盖 logical similarity bin**。

**类别 C: 零散 hole（不纳入对比）**

- `opcode_cg`：22 个未使用的 opcode bin — 属于不可达（RV32I 不使用这些编码）
- `jal_cg`：rd=ZERO, 对齐 bin — jal 特有的边界情况
- `rv32i_misc_cg`：ECALL — 需要特权模式支持
- `csrrw_cg`：WAR/WAW — CSR 指令，不在基本 ALU 指令范围

### Hazard 定义（对齐 riscv-dv 的 `hazard_e`）

riscv-dv 中 `gpr_hazard` 的判定逻辑（相邻指令 i 和 i+1，hazard 归属于 i+1）：

```
RAW_HAZARD:  instr[i].rd ∈ {instr[i+1].rs1, instr[i+1].rs2}
WAR_HAZARD:  instr[i+1].rd ∈ {instr[i].rs1, instr[i].rs2} ∧ ¬RAW
WAW_HAZARD:  instr[i].rd == instr[i+1].rd               ∧ ¬RAW ∧ ¬WAR
NO_HAZARD:   ¬RAW ∧ ¬WAR ∧ ¬WAW
```

优先级：RAW > WAR > WAW > NoHazard（互斥分类）。

## Phase 3: Hole Closure

### 目标

为 21 条指令的每一条，构造指令序列使其 `cp_gpr_hazard` 覆盖 WAR、WAW、NoHazard 三个缺失 bin。

### 手写汇编填补方案

对于每条指令，需要构造 3 对相邻指令对（共 6 条指令），使第二条指令分别被 riscv-dv 分类为 WAR、WAW、NoHazard。

#### R-type（10 条：add, sub, and, or, xor, sll, srl, sra, slt, sltu）

格式：`op rd, rs1, rs2`

以 add 为例：

```asm
# --- WAR: curr.rd ∈ {prev.rs1, prev.rs2} ∧ ¬RAW ---
# prev.rd=x1 ∉ {curr.rs1=x7, curr.rs2=x8} → ¬RAW ✓
# curr.rd=x5 == prev.rs1=x5 → WAR ✓
    add x1, x5, x6
    add x5, x7, x8

# --- WAW: prev.rd == curr.rd ∧ ¬RAW ∧ ¬WAR ---
# prev.rd=x10 ∉ {x13,x14} → ¬RAW ✓
# curr.rd=x10 ∉ {x11,x12} → ¬WAR ✓
# prev.rd=x10 == curr.rd=x10 → WAW ✓
    add x10, x11, x12
    add x10, x13, x14

# --- NoHazard: ¬RAW ∧ ¬WAR ∧ ¬WAW ---
# x15 ∉ {x19,x20}, x18 ∉ {x16,x17}, x15≠x18
    add x15, x16, x17
    add x18, x19, x20
```

sub, and, or, xor, sll, srl, sra, slt, sltu 结构完全相同，仅替换助记符：

```asm
# sub
    sub x1, x5, x6
    sub x5, x7, x8          # WAR
    sub x10, x11, x12
    sub x10, x13, x14       # WAW
    sub x15, x16, x17
    sub x18, x19, x20       # NoHazard

# and
    and x1, x5, x6
    and x5, x7, x8          # WAR
    and x10, x11, x12
    and x10, x13, x14       # WAW
    and x15, x16, x17
    and x18, x19, x20       # NoHazard

# or
    or x1, x5, x6
    or x5, x7, x8           # WAR
    or x10, x11, x12
    or x10, x13, x14        # WAW
    or x15, x16, x17
    or x18, x19, x20        # NoHazard

# xor
    xor x1, x5, x6
    xor x5, x7, x8          # WAR
    xor x10, x11, x12
    xor x10, x13, x14       # WAW
    xor x15, x16, x17
    xor x18, x19, x20       # NoHazard

# sll
    sll x1, x5, x6
    sll x5, x7, x8          # WAR
    sll x10, x11, x12
    sll x10, x13, x14       # WAW
    sll x15, x16, x17
    sll x18, x19, x20       # NoHazard

# srl
    srl x1, x5, x6
    srl x5, x7, x8          # WAR
    srl x10, x11, x12
    srl x10, x13, x14       # WAW
    srl x15, x16, x17
    srl x18, x19, x20       # NoHazard

# sra
    sra x1, x5, x6
    sra x5, x7, x8          # WAR
    sra x10, x11, x12
    sra x10, x13, x14       # WAW
    sra x15, x16, x17
    sra x18, x19, x20       # NoHazard

# slt
    slt x1, x5, x6
    slt x5, x7, x8          # WAR
    slt x10, x11, x12
    slt x10, x13, x14       # WAW
    slt x15, x16, x17
    slt x18, x19, x20       # NoHazard

# sltu
    sltu x1, x5, x6
    sltu x5, x7, x8         # WAR
    sltu x10, x11, x12
    sltu x10, x13, x14      # WAW
    sltu x15, x16, x17
    sltu x18, x19, x20      # NoHazard
```

**小计**：10 条指令 × 6 行 = **60 条汇编指令**

#### I-type ALU（6 条：addi, andi, ori, xori, slti, sltiu）

格式：`op rd, rs1, imm12`（无 rs2，hazard 只涉及 rd 和 rs1）

```asm
# addi
    addi x1, x5, 10
    addi x5, x7, 20         # WAR: rd=x5==prev.rs1; prev.rd=x1≠rs1=x7 → ¬RAW
    addi x10, x11, 30
    addi x10, x13, 40       # WAW: rd=x10==prev.rd; x10≠x13 → ¬RAW; x10≠x11 → ¬WAR
    addi x15, x16, 50
    addi x18, x19, 60       # NoHazard

# andi
    andi x1, x5, 10
    andi x5, x7, 20         # WAR
    andi x10, x11, 30
    andi x10, x13, 40       # WAW
    andi x15, x16, 50
    andi x18, x19, 60       # NoHazard

# ori
    ori x1, x5, 10
    ori x5, x7, 20          # WAR
    ori x10, x11, 30
    ori x10, x13, 40        # WAW
    ori x15, x16, 50
    ori x18, x19, 60        # NoHazard

# xori
    xori x1, x5, 10
    xori x5, x7, 20         # WAR
    xori x10, x11, 30
    xori x10, x13, 40       # WAW
    xori x15, x16, 50
    xori x18, x19, 60       # NoHazard

# slti
    slti x1, x5, 10
    slti x5, x7, 20         # WAR
    slti x10, x11, 30
    slti x10, x13, 40       # WAW
    slti x15, x16, 50
    slti x18, x19, 60       # NoHazard

# sltiu
    sltiu x1, x5, 10
    sltiu x5, x7, 20        # WAR
    sltiu x10, x11, 30
    sltiu x10, x13, 40      # WAW
    sltiu x15, x16, 50
    sltiu x18, x19, 60      # NoHazard
```

**小计**：6 条指令 × 6 行 = **36 条汇编指令**

#### Shift-imm（3 条：slli, srli, srai）

格式：`op rd, rs1, shamt`（寄存器字段与 I-type 相同）

```asm
# slli
    slli x1, x5, 1
    slli x5, x7, 2          # WAR
    slli x10, x11, 3
    slli x10, x13, 4        # WAW
    slli x15, x16, 5
    slli x18, x19, 6        # NoHazard

# srli
    srli x1, x5, 1
    srli x5, x7, 2          # WAR
    srli x10, x11, 3
    srli x10, x13, 4        # WAW
    srli x15, x16, 5
    srli x18, x19, 6        # NoHazard

# srai
    srai x1, x5, 1
    srai x5, x7, 2          # WAR
    srai x10, x11, 3
    srai x10, x13, 4        # WAW
    srai x15, x16, 5
    srai x18, x19, 6        # NoHazard
```

**小计**：3 条指令 × 6 行 = **18 条汇编指令**

#### U-type（2 条：lui, auipc）

格式：`op rd, imm20`（**无 rs1, 无 rs2**）

两条 U-type 之间不可能产生 RAW 或 WAR（没有 rs 字段）。要覆盖 WAR，**必须用其他格式指令作前驱**。

```asm
# lui — WAR 需要前驱指令有 rs 字段
    addi x1, x5, 0          # helper: rs1=x5
    lui x5, 0x12345          # WAR: rd=x5==prev.rs1=x5; lui 无 rs → ¬RAW ✓

# lui — WAW 和 NoHazard 可以 lui-lui
    lui x10, 0xAAAAA
    lui x10, 0xBBBBB         # WAW: rd=x10==prev.rd=x10; 无 rs → ¬RAW ¬WAR ✓
    lui x15, 0x11111
    lui x18, 0x22222         # NoHazard: x15≠x18 ✓

# auipc — 同理
    addi x2, x6, 0          # helper: rs1=x6
    auipc x6, 0x12345        # WAR
    auipc x20, 0xAAAAA
    auipc x20, 0xBBBBB       # WAW
    auipc x25, 0x11111
    auipc x28, 0x22222       # NoHazard
```

**小计**：2 × 6 行 + 2 个 helper addi = **14 条汇编指令**

**U-type 的关键观察**：WAR 覆盖需要跨格式推理。工程师必须认识到"两条 lui 之间永远不会产生 WAR"，然后手动选择合适的前驱指令类型。这个推理过程是隐式的、格式特定的。

### 手写汇编总计

| 格式 | 指令数 | 汇编行数 | 备注 |
|------|--------|----------|------|
| R-type | 10 | 60 | 同格式模板化 |
| I-type ALU | 6 | 36 | 同上 |
| Shift-imm | 3 | 18 | 同上 |
| U-type | 2 | 14 | 需要 2 个 helper 指令 |
| **合计** | **21** | **128** | |

### RVProbe 对应实现

已有代码，无需额外编写。`CoverageLib` 中的 `rType()`, `iTypeAlu()`, `shiftImm()`, `uType()` 已经包含 `coverWAR()`, `coverWAW()`, `coverNoHazard()` 约束。

以 add 为例（call site，3 行）：
```scala
object Add extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = rType(35, isAdd())
```

库函数 `CoverageLib.rType`（23 行，跨 10 条 R-type 指令复用）：
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
  seq.coverWAR()
  seq.coverWAW()
  seq.coverNoHazard()
```

**RVProbe 总 LOC**：
- Call site：21 × 3 = 63 行
- Library：~90 行（4 个格式函数，复用）
- 覆盖保证：SAT = 全部 hazard bin 闭合

### 对比

| 度量 | 手写汇编 | RVProbe |
|------|---------|---------|
| **总行数** | 128 行 asm | 63 行 call site + 90 行 library |
| **覆盖保证** | 无（需人工验证每对的 ¬RAW/¬WAR 条件） | SAT = 闭合 |
| **格式适配** | U-type 需要手动引入 helper 指令 | `coverWAR()` 自动处理跨格式依赖 |
| **新增指令** | 6 行 asm + 重新验证 hazard 分类 | 3 行 call site，复用已有 library |
| **模板化程度** | 同格式可复制粘贴改助记符，但每对仍需验证 | 同格式共享一个库函数 |

### 关键发现

1. **hole 的本质是序列级的**：riscv-dv 33 万条指令只覆盖了 RAW，WAR/WAW/NoHazard 全部缺失。这不是随机数不够的问题，而是生成策略没有 hazard-aware 的序列级约束。

2. **手写汇编看似简单但有隐患**：
   - 模板化的 6 行一组可以机械复制，但每对必须验证 hazard 优先级排除条件（¬RAW、¬WAR）
   - U-type 的 WAR 需要跨格式推理，工程师必须理解"lui 没有 rs 字段所以不会产生 WAR"
   - 错误是静默的：如果不小心让 WAW 对同时满足 RAW（如 `add x10, x11, x12; add x10, x10, x14`），riscv-dv 会将其分类为 RAW 而非 WAW，hole 仍然未填

3. **RVProbe 消除了手写的两个核心负担**：
   - 不需要验证 hazard 优先级排除——求解器自动处理
   - 不需要跨格式推理——`coverWAR()` 根据指令格式自动决定约束哪些字段

## Status

- [x] Phase 1: riscv-dv 饱和实验完成，334K 指令，覆盖率报告已有
- [x] Phase 2: Hole 分析完成，确认 21 条指令 × 3 种 hazard = 63 个 hole
- [x] Phase 3: 手写汇编填补方案（128 行）+ RVProbe 对应实现（已有）
- [ ] 将手写汇编喂给 riscv-dv coverage 工具验证是否真正闭合 hole
- [ ] RVProbe 生成的汇编同样喂给 riscv-dv coverage 工具验证
