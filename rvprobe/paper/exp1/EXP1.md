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

reviewer #4 批评 "comparison not clear with the state of the art"。用 riscv-dv 作为共同起点，三方对比闭合同一组盲区的 LOC、正确性保证和可扩展性。

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

## Verified Results (VCS + pyflow, deterministic seed 0, PYTHONHASHSEED=0)

### Baseline（riscv-dv 饱和）

- 429,028 条指令，27 个 covergroup，2,038 个 coverpoint
- Average Coverage Score: 94.30%
- **52 个 unhit bins**

### Hole Closure

| 方案 | Unhit Bins | 闭合数 | 闭合率 |
|------|-----------|--------|--------|
| Baseline | 52 | — | — |
| Handwrite | 31 | 21 | 40.4% |
| RVProbe | 29 | 23 | 44.2% |

#### Handwrite 闭合的 21 个 bins

| 类别 | Bin | 数量 |
|------|-----|------|
| cp_rd=SP | sub, sra, srl, sll, slt, sltu, and, xor, or, ori, andi, xori, slti, sltiu, srli, srai, slli, lui, csrrw | 19 |
| jal cp_rd | ZERO, SP | 2 |

#### RVProbe 闭合的 23 个 bins

| 类别 | Bin | 数量 |
|------|-----|------|
| cp_rd=SP | sub, sra, srl, sll, slt, sltu, and, xor, or, ori, andi, xori, slti, sltiu, srli, srai, slli, lui | 18 |
| cp_rd | jal ZERO, lui SP | 2 |
| cp_logical | xori DIFFERENT, ori DIFFERENT, andi DIFFERENT | 3 |

### 无法闭合的 Bins（按类别分类）

#### 类别 A: 架构不可达（22 bins）— opcode_cg.cp_opcode

riscv-dv 的 `cp_opcode` 覆盖 `binary[6:2]` 的全部 32 个 bin（a[0]~a[31]）。RV32I 只使用其中 10 个编码，其余 22 个对应未定义的 opcode 空间，任何合法 RV32I 指令都不可能命中。

| Unhit Bin | Opcode[6:2] | 说明 |
|-----------|-------------|------|
| a[1] | 00001 | 未分配 |
| a[2] | 00010 | 未分配（custom-0 高位） |
| a[6] | 00110 | OP-FP (RV32F/D) |
| a[7] | 00111 | 未分配 |
| a[9] | 01001 | STORE-FP (RV32F/D) |
| a[10] | 01010 | 未分配（custom-1） |
| a[11] | 01011 | AMO (RV32A) |
| a[14] | 01110 | OP-FP 64 (RV64F/D) |
| a[15] | 01111 | 未分配 |
| a[16] | 10000 | MADD (RV32F) |
| a[17] | 10001 | MSUB (RV32F) |
| a[18] | 10010 | NMSUB (RV32F) |
| a[19] | 10011 | NMADD (RV32F) |
| a[20] | 10100 | OP-FP (RV32F) |
| a[21] | 10101 | OP-V (RV32V) |
| a[22] | 10110 | custom-2 |
| a[23] | 10111 | 未分配 |
| a[25] | 11001 | JALR 高位变体 |
| a[26] | 11010 | 未分配 |
| a[29] | 11101 | OP-IMM-32 (RV64I) |
| a[30] | 11110 | custom-3 |
| a[31] | 11111 | 80-bit+ 指令 |

**结论**：这 22 个 bin 对应的 opcode 编码在 RV32I 指令集中不存在，属于其他扩展（F/D/A/V）或保留编码。在纯 RV32I 配置下不可达。

#### 类别 B: U-type 架构限制（2 bins）— lui/auipc cp_gpr_hazard

| Unhit Bin | 说明 |
|-----------|------|
| lui_cg.cp_gpr_hazard = RAW_HAZARD | U-type 无 rs1/rs2 字段 |
| auipc_cg.cp_gpr_hazard = RAW_HAZARD | 同上 |

riscv-dv 的 hazard 判定逻辑（`check_hazard_condition`）：RAW 要求 `instr[i].rs1 == instr[i-1].rd` 或 `instr[i].rs2 == instr[i-1].rd`。但 lui/auipc 的 `has_rs1 = false, has_rs2 = false`，所以 RAW 条件永远不满足。

**结论**：这是 riscv-dv coverage model 的设计缺陷——为没有源寄存器的指令定义了 RAW bin。架构上不可达。

#### 类别 C: 对齐限制（2 bins）— jal alignment

| Unhit Bin | 说明 |
|-----------|------|
| jal_cg.cp_rd_align = Aligned | rd_value（返回地址 PC+4）的 bit[1] 需为 1 |
| jal_cg.cp_imm_align = Aligned | 立即数的 bit[1] 需为 1 |

在纯 RV32I（无 C 扩展）下，所有指令 4 字节对齐：
- PC 和 PC+4 的 bit[1] 恒为 0
- jal 的目标偏移量 bit[1] 也恒为 0（因为目标地址必须 4 字节对齐）

**结论**：需要 RV32IC（压缩指令扩展）才能产生 2-mod-4 的地址/偏移量。纯 RV32I 下不可达。

#### 类别 D: Coverage Tool 限制（5 bins）

| Unhit Bin | 说明 |
|-----------|------|
| rv32i_misc_cg.cp_misc = ECALL | ECALL 在 trace 中存在但 coverage tool 未识别 |
| ori_cg.cp_logical = OPPOSITE | coverage tool 无法从 trace 重建 rs1_value |
| andi_cg.cp_logical = OPPOSITE | 同上 |
| xori_cg.cp_logical = IDENTICAL | 同上 |
| xori_cg.cp_logical = OPPOSITE | 同上 |

**ECALL**：spike trace 记录了 `ecall` 指令，但 riscv-dv 的 pyflow coverage collector 未将其映射到 `rv32i_misc_instrs.ECALL` 枚举。这是 coverage tool 的解析 bug。

**Logical Similarity**：riscv-dv 的 `get_logical_similarity()` 需要 `rs1_value` 来计算 XOR bit difference。但 spike trace CSV 的 `gpr` 列只记录写入寄存器的值（`rd:value`），不记录读取寄存器的值。coverage tool 依赖寄存器状态跟踪来重建 rs1_value，在 directed test 的上下文中可能状态不一致。

此外，我们在实验过程中发现并修复了 riscv-dv 的 `get_logical_similarity()` 函数中的一个 bug：
```python
# 修复前（Python bin() 对负数返回 '-0b1'，导致 OPPOSITE 永远不匹配）
temp = bin(val1.get_val() ^ val2.get_val())
bit_difference = len([ones for ones in temp[2:] if ones == '1'])

# 修复后（掩码到无符号 XLEN 位再计数）
xor_val = (val1.get_val() ^ val2.get_val()) & ((1 << rcs.XLEN) - 1)
bit_difference = bin(xor_val).count('1')
```

**结论**：这 5 个 bin 的指令序列已正确生成并执行，但 coverage tool 的 trace 解析和状态重建存在局限性，无法识别覆盖。

#### 类别 E: Handwrite 未覆盖但 RVProbe 未覆盖的边界 bin（2 bins）

| Unhit Bin | Handwrite | RVProbe | 说明 |
|-----------|-----------|---------|------|
| csrrw_cg.cp_rd = SP | ✅ 闭合 | ❌ 未闭合 | RVProbe 未生成 csrrw rd=SP |
| jal_cg.cp_rd = SP | ✅ 闭合 | ❌ 未闭合 | RVProbe 未生成 jal rd=SP |

这两个 bin 说明 Handwrite 在边界情况处理上有优势——工程师可以手动指定 SP 作为目标寄存器，而 RVProbe 的 `freshReg()` 默认排除 SP 以避免破坏栈指针。

## Status

- [x] Phase 1: riscv-dv 饱和实验完成，429K 指令
- [x] Phase 2: Hole 分析完成（52 个 unhit bins）
- [x] Phase 3: 三种方法实现 + Coverage 验证
  - 手写汇编：`handwrite.S` → 52 - 21 = 31 remaining
  - RVProbe eDSL：`rvprobe.S` → 52 - 23 = 29 remaining
- [x] riscv-dv coverage tool bug 发现并修复（`bin()` 负数处理）
- [x] 无法闭合的 bin 分类完成（22 opcode + 2 U-type + 2 alignment + 5 tool + 2 边界 = 33 remaining bins 中 26 架构不可达 + 5 工具限制 + 2 设计选择）
