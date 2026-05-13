# Experiment 1: Closing CRV Structural Blind Spots

## Research Question

When riscv-dv (the industry-standard CRV generator) reaches coverage saturation, what **structural blind spots** remain, and how do different directed methods compare in closing them?

## Motivation

CRV 生成器在达到覆盖率饱和后，剩余的覆盖空洞并非随机采样不足导致，而是**工具设计决策**造成的结构性盲区：
- 寄存器分配策略（避免 SP 作为目标寄存器）
- 指令格式限制（U-type 无 rs 字段导致 hazard 模型不匹配）
- 覆盖模型与 ISA 规范的对齐偏差（opcode 编码空间、alignment bin）

这些盲区不会随指令数量增加而自然消失——即使 riscv-dv 自带了 `riscv_hazard_instr_stream` 等定向流来覆盖 hazard bin，仍有 30 个非 opcode 空洞残留。

DAC26 reviewer #2 批评 "directed tests are created by manually adding constraints, so the increase in coverage is absolutely expected"。我们的回应：关键不是"能不能填"，而是**识别盲区的系统性**和**填补时的约束表达成本差异**。

reviewer #4 批评 "comparison not clear with the state of the art"。用 riscv-dv 作为共同起点，对比手写汇编与 RVProbe 闭合同一组盲区时的 LOC、正确性保证和可扩展性。

## Key Observation

riscv-dv 的 `riscv_hazard_instr_stream` 本质上是嵌入在 CRV 框架中的定向 hazard 生成器。这说明**即使是 CRV 工具也承认纯随机不够，需要定向补充**。但 riscv-dv 只为 hazard 写了定向流，没有为其他结构性盲区（rd=SP、logical similarity、ECALL 等）做同样的工作。

## Phase 1: riscv-dv Baseline Saturation

**配置**：riscv-dv 默认 RV32I 配置，VCS + spike，`--start_seed 0`，`PYTHONHASHSEED=0`
**数据**：`riscv-dv/cov_out_exp1_rv32i/CoverageReport.txt`

### 结果概要

| 指标 | 值 |
|------|-----|
| 处理指令总数 | 429,028 |
| Covergroup 总数 | 27 |
| Coverpoint 总数 | 2,038 |
| 已覆盖 Coverpoint | 150 |
| 平均 Covergroup 分数 | 94.30% |
| 总 Unhit Bins | 52 |
| 其中 opcode 不可达 | 22 |
| 结构性盲区 | 30 |

### 各 Covergroup 覆盖率（< 100% 的部分）

| Covergroup | Score | 结构性盲区 |
|------------|------:|-----------|
| opcode_cg | 31.25% | 22 个未使用的 opcode 编码（架构不可达） |
| jal_cg | 75.00% | rd=ZERO, rd=SP, rd_align, imm_align |
| lui_cg | 91.67% | rd=SP, gpr_hazard=RAW |
| auipc_cg | 91.67% | gpr_hazard=RAW |
| rv32i_misc_cg | 80.00% | ECALL |
| csrrw_cg | 98.96% | rd=SP |
| ori/andi_cg | 96.48% | rd=SP, logical=OPPOSITE |
| xori_cg | 96.48% | rd=SP, logical=IDENTICAL/OPPOSITE |
| sub/sra/srl/sll/slt/sltu_cg | 96.88% | rd=SP |
| and/xor/or_cg | 99.65% | rd=SP |
| srli/srai/slli_cg | 99.38% | rd=SP |
| slti/sltiu_cg | 99.55% | rd=SP |

注：add/addi/beq/mepc_alignment 等 covergroup 已达 100%。所有 hazard bin（WAR/WAW/NoHazard/RAW）在 429K 指令后均已覆盖，因为 riscv-dv 自带 `riscv_hazard_instr_stream` 定向流。

## Phase 2: Structural Blind Spot Analysis

### 分类

riscv-dv 饱和后的 30 个非 opcode hole 可分为以下结构性盲区类别：

**类别 A: 寄存器分配策略盲区（19 bins）— cp_rd=SP**

riscv-dv 默认避免将 SP（x2）用作目标寄存器，以防止破坏栈指针。这导致 19 条指令的 `cp_rd = riscv_reg_t.SP` bin 未覆盖。

受影响指令：sub, sra, srl, sll, slt, sltu, and, xor, or, ori, andi, xori, slti, sltiu, srli, srai, slli, lui, csrrw

**这是最大的盲区类别**，占非 opcode hole 的 63%。本质上是工具的安全策略（保护 SP）与覆盖完备性之间的矛盾。

**类别 B: 覆盖模型与 ISA 规范错位（2 bins）— U-type RAW**

| Unhit Bin | 说明 |
|-----------|------|
| lui_cg.cp_gpr_hazard = RAW_HAZARD | U-type 无 rs1/rs2 字段 |
| auipc_cg.cp_gpr_hazard = RAW_HAZARD | 同上 |

riscv-dv 的 hazard 判定：RAW 要求 `instr[i].rs1 == instr[i-1].rd`。但 lui/auipc 的 `has_rs1 = false`，RAW 条件永远不满足。这是覆盖模型为没有源寄存器的指令定义了不可达的 hazard bin。

**类别 C: 值级约束盲区（4 bins）— logical similarity**

| Unhit Bin | 说明 |
|-----------|------|
| ori_cg.cp_logical = OPPOSITE | rs1_value 与 imm 所有 32 位都不同 |
| andi_cg.cp_logical = OPPOSITE | 同上 |
| xori_cg.cp_logical = IDENTICAL | rs1_value == sign-extended imm |
| xori_cg.cp_logical = OPPOSITE | 同上（OPPOSITE） |

这些 bin 需要构造特定操作数值关系。riscv-dv 的随机值分配在 429K 指令后仍未碰巧产生恰好 32 位全不同的操作数对。

注：实验中发现 riscv-dv 的 `get_logical_similarity()` 存在 bug——`bin()` 对负数返回 `'-0b1'` 导致 OPPOSITE 永远不匹配。已修复。但修复后 coverage tool 仍无法从 spike trace 重建 rs1_value，导致这些 bin 在 pyflow 后端下不可观测。

**类别 D: 边界情况盲区（5 bins）**

| Unhit Bin | 说明 |
|-----------|------|
| jal_cg.cp_rd = ZERO | j 伪指令（rd=x0） |
| jal_cg.cp_rd = SP | jal x2, offset |
| jal_cg.cp_rd_align = Aligned | 需要 C 扩展产生 2-mod-4 地址 |
| jal_cg.cp_imm_align = Aligned | 同上 |
| rv32i_misc_cg.cp_misc = ECALL | coverage tool 未解析 ecall |

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
- Call site：21 × 3 = 63 行（其中 6 条逻辑指令从 rType/iTypeAlu 升级为 rTypeLogical/iTypeLogical）
- Library：~150 行（6 个格式函数：rType, rTypeLogical, iTypeAlu, iTypeLogical, shiftImm, uType）
- 覆盖保证：hazard SAT = 全部 63 bin 闭合；logical similarity 通过 li + op 模式覆盖 13 bin

### 对比

| 度量 | 手写汇编 | riscv-dv Python ext | RVProbe |
|------|---------|-------------------|---------|
| **Hazard LOC** | 128 行 asm | ~170 行 Python | 63 行 call site + ~90 行 library |
| **Logical LOC** | ~57 行 asm | ~42 行 Python | 升级 6 个 call site（+0 行）+ ~60 行 library |
| **总 LOC** | ~185 行 | ~212 行 | 63 行 call site + ~150 行 library |
| **Hazard 保证** | 无（需人工验证 ¬RAW/¬WAR） | 无（同样手动编码） | SAT = 闭合 |
| **Logical 保证** | 需手动计算互补值 | 同上 | 模式化（`rTypeLogical` 封装） |
| **格式适配** | U-type WAR 需跨格式推理 | 同上 | `coverWAR()` 自动处理 |
| **可扩展性** | 新指令 +6 行 + 重新验证 | 新指令需写新函数 | 新指令 +3 行 call site |

### 关键发现

1. **CRV 结构性盲区的本质是工具设计决策**：riscv-dv 429K 指令后，hazard bin 全部覆盖（因为自带 `riscv_hazard_instr_stream` 定向流），但 rd=SP 的 19 个 bin 全部缺失。这不是随机采样不足，而是工具**策略性避免 SP 寄存器**的结果。这类盲区不会随指令数量增加而自然消失。

2. **riscv-dv 自身已证明纯随机不够**：`riscv_hazard_instr_stream` 本质上是嵌入在 CRV 框架中的手写定向流。riscv-dv 团队为 hazard 写了定向生成器，但没有为 rd=SP、logical similarity、ECALL 等做同样的工作。

3. **手写汇编能闭合盲区但有局限**：
   - rd=SP 的 19 个 bin 可以通过 `op x2, ...` 机械覆盖
   - 但每条指令都需要手动选择 SP 作为目标寄存器，且需要考虑是否会破坏程序运行状态
   - U-type 和 jal 的边界情况需要格式特定的推理

4. **RVProbe 的 `freshReg()` 自动覆盖 SP**：
   - RVProbe 的寄存器约束求解器在 `rdRange(1, 32)` 下自动包含 SP
   - 不需要显式指定 SP——solver 会系统性地覆盖所有寄存器
   - `coverWAR()`/`coverWAW()` 等 API 仍然有效（虽然 baseline 已覆盖 hazard，但 RVProbe 的序列级约束能力是通用的）

5. **Coverage tool 本身存在 bug**：
   - `get_logical_similarity()` 的 `bin()` 负数处理错误（已修复）
   - pyflow 后端无法从 spike trace 重建 rs1_value（logical bin 不可观测）
   - ECALL 指令在 trace 中存在但 coverage tool 未解析

## Verified Results (VCS SV Coverage, 47 covergroups, deterministic seed 0)

> Source of truth: `paper/data/exp1_summary.csv` (2026-05-12). The older 62-hole/45-closure numbers below were retired because they came from an intermediate coverage report and are not used by `paper/iccd26.md`.

### Baseline（riscv-dv 饱和）

- 47 个 covergroup（VCS SystemVerilog coverage）
- Total Coverage Score: 95.67%
- **64 个 unhit bins**

### Hole Closure

| 方案 | Score | Total Holes | Closed | Remaining | Closure |
|------|------:|------------:|-------:|----------:|--------:|
| Baseline | 95.67% | 64 | 0 | 64 | 0.0% |
| Handwrite | 96.32% | 64 | 41 | 23 | 64.1% |
| RVProbe | 96.18% | 64 | 27 | 37 | 42.2% |

### 为什么 RVProbe-core 闭合数少于 Handwrite

Handwrite 当前是 **coverage-report patch**：它直接针对 VCS coverage report 中的剩余 bin 写补丁，包括 `rd=SP`、load/store scratch-buffer、CSR、JAL/JALR、ECALL/trap handler 等模型特定用例。因此 handwrite 闭合数更高。

RVProbe-core 当前是 **sequence-level generator**：`rvprobe.scala` 主要覆盖 21 条普通 RV32I 指令的 R/I/shift/U 格式生成器和 logical pattern，重点展示 `coverWAR()`/`coverWAW()`/`coverNoHazard()` 这类序列级约束的表达成本和求解器保证。它不包含 handwrite 中那些 coverage-model 特化补丁（JAL/JALR/CSR/ECALL/store-ZERO 等），所以总闭合数低于 handwrite。

`RVProbe+patch` 已作为独立路径加入：`CoverageLib.rv32iCoverageModelPatches()` 发射 SP/ZERO/JAL/JALR/CSR/ECALL/scratch-buffer 补丁，`RV32I` 入口通过第二个参数选择是否追加这些补丁。生成命令为：

```bash
mill rvprobe.runMain me.jiuyang.rvprobe.cases.coverage.RV32I /tmp/RV32I_patch.S true
```

在重新跑 VCS coverage 之前，论文仍应使用表中的 RVProbe-core 数据（27/64），不能把 patch 路径的预期效果写成实测结果。

论文处理：RQ1 不应声称 RVProbe 闭合更多 holes，也不应声称达到 handwrite 相同覆盖率。RQ1 应聚焦于 **对已知序列级覆盖目标，RVProbe 用更少代码表达核心跨指令关系，并由 SMT 检查排斥条件**。Handwrite 的更高闭合数用于说明 coverage-model patch 能力强，但需要更多人工编码。

### RVProbe+patch 方案

为了让 RVProbe 的覆盖数量向 handwrite 对齐，已新增一组 RVProbe/raw-ASM hybrid patch，而不是把这些模型特定用例混入现有序列级 generator：

- `rd=SP` / `rs1=SP` / `rs2=ZERO` patches: 用 eDSL/AsmApi 显式发射 `x2`/`x0` 相关指令。
- Load/store patches: 使用 `_scratch_buf` 并保存/恢复 SP。
- CSR patches: 覆盖 `csrrw/csrrs/csrrc` 与 CSR-immediate 的 rd bins。
- JAL/JALR patches: 发射 label + `jalr` 特定 rd/rs1 组合。
- ECALL patch: 插入 trap handler 并执行 `ecall`。

这样做预期能提高 RVProbe 的闭合数，但论文中必须诚实标注这些是 "coverage-model patches"，不能把它们当作序列级约束抽象的核心贡献。否则会把 RQ1 稀释成“谁抄 coverage report 补丁更全”。

### 当前无法/不应作为 RVProbe 核心能力声称的 Bins

#### 类别 A: Hazard 模型不可达（5 bins）

| Unhit Bin | 说明 |
|-----------|------|
| lui_cg.cp_gpr_hazard = RAW | U-type 无 rs 字段，RAW 不可达 |
| auipc_cg.cp_gpr_hazard = RAW | 同上 |
| csrrwi_cg.cp_gpr_hazard = RAW | CSR-imm 无 rs 字段 |
| csrrsi_cg.cp_gpr_hazard = RAW | 同上 |
| csrrci_cg.cp_gpr_hazard = RAW | 同上 |

这些指令没有源寄存器字段（U-type 无 rs1/rs2，CSR-imm 用 uimm 代替 rs1），所以 RAW hazard 条件（`instr[i].rs1 == instr[i-1].rd`）永远不满足。

#### 类别 B: Opcode 不可达（4 bins）

opcode_cg 的 4 个 bin（a[2], a[9], a[21], a[31]）对应 RV32I 不使用的 opcode 编码空间。

#### 类别 C: 边界寄存器 / coverage-model patch

`rd=SP`、`rs1=SP`、`rs2=ZERO`、JAL/JALR 特定交叉等 bin 可以通过显式补丁闭合。Handwrite 已覆盖其中一部分；RVProbe-core 不纳入这些 raw patches，`RVProbe+patch` 则已可生成对应补丁，等待 VCS coverage 重跑确认闭合数。

#### 类别 D: 对齐/交叉覆盖

- mepc_alignment_cg = alignment_2（需 C 扩展）
- jal alignment bins（2 个，需 2-mod-4 地址）
- jalr 交叉覆盖（1 个特定 rd×rs1 组合）

## Status

- [x] Phase 1: riscv-dv baseline — 47 covergroups (VCS SV)，64 unhit bins
- [x] Phase 2: Hole closure 汇总与 `paper/data/exp1_summary.csv` 对齐
- [x] Phase 3: Handwrite remaining 23 (closed 41), RVProbe remaining 37 (closed 27)
- [x] 论文使用二方对比：handwrite 覆盖更多模型特定 bin；RVProbe 用更少代码表达核心序列级关系
- [x] 新增 RVProbe+patch coverage-model patch generator（默认 RVProbe-core 不追加）
- [x] 本地验证：`rvprobe.compile`、core/patch ASM 生成、RISC-V gcc 汇编检查、`rvprobe.tests`
- [ ] 重新跑 VCS coverage，确认 RVProbe+patch 的实测 closed/remaining
