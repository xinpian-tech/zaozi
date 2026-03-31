# RVProbe: Sequence-Level Constraint Abstraction for Directed RISC-V Test Generation

## Abstract

约束随机验证（CRV）生成器在指令级操作：独立约束每条指令的字段，指令间关系交由随机性决定。然而，功能覆盖率模型中的关键目标——如数据冒险（hazard）组合——本质上是序列级属性，取决于相邻指令之间的寄存器依赖关系。这种**约束粒度与覆盖粒度之间的抽象鸿沟（abstraction gap）**，导致 CRV 在覆盖率饱和后留下系统性的空洞。

我们提出 RVProbe，一个基于 Scala 3 eDSL 的定向测试生成框架，将约束抽象从指令级提升到序列级。工程师以声明式 API（如 `coverWAR()`, `coverNoHazard()`）表达覆盖意图，由 SMT 求解器自动生成满足序列级约束的指令序列。此外，RVProbe 支持通过元编程自动注入从 HDL 提取的微架构信号，实现架构约束与实现细节的可组合白盒验证。

评估表明：(1) 在 riscv-dv 饱和后的 65 个 hazard 空洞上，RVProbe 相比手写汇编和 riscv-dv 扩展在代码量、正确性保证和可扩展性上具有显著优势；(2) 序列级约束与白盒信号的组合暴露了 T1 向量处理器中一个已确认的流水线缺陷；(3) 端到端生成延迟保持线性扩展，500 条指令下低于 2 秒。

---

## 1 Introduction

### 1.1 Problem: Abstraction Gap between Constraint Granularity and Coverage Granularity

功能验证是现代处理器设计的主要瓶颈 [1]。在覆盖率驱动验证（CDV）范式中，约束随机验证（CRV）是激励生成的事实标准 [19]。然而，我们观察到 CRV 生成器存在一个结构性局限——**约束粒度与覆盖粒度之间的抽象鸿沟**：

- **覆盖率模型定义在序列级**：功能覆盖率测试计划中的关键 bin（如 `WAR_HAZARD`, `WAW_HAZARD`, `NO_HAZARD`）描述的是相邻指令之间的寄存器依赖关系——这是一种**序列级属性**。
- **约束引擎操作在指令级**：主流 CRV 生成器（如 riscv-dv [9], Force-RISCV [10]）独立约束每条指令的字段（opcode, rd, rs1, rs2, imm），指令间的关系交由随机性决定。

**以数据冒险（hazard）覆盖为例**。riscv-dv 的功能覆盖率模型对每条指令定义了 `cp_gpr_hazard` coverpoint，根据相邻指令 `instr[i]` 和 `instr[i+1]` 的寄存器关系分类（hazard 归属于 `instr[i+1]`，优先级从高到低）：

```
RAW:       instr[i].rd ∈ {instr[i+1].rs1, instr[i+1].rs2}
WAR:       instr[i+1].rd ∈ {instr[i].rs1, instr[i].rs2}  ∧ ¬RAW
WAW:       instr[i].rd == instr[i+1].rd                   ∧ ¬RAW ∧ ¬WAR
NoHazard:  ¬RAW ∧ ¬WAR ∧ ¬WAW
```

每条规则都引用了**两条相邻指令**的字段——这就是"序列级属性"的含义：单看一条指令无法判定它的 hazard 类型。

然而 riscv-dv 的约束引擎对每条指令独立分配 `rd, rs1, rs2 ∈ [0, 31]`，两条相邻指令的寄存器关系完全交由随机性。对于 R-type 指令（3 个寄存器字段），随机分配下：

- P(RAW) ≈ 1 − (31/32)² ≈ 6.1%（至少一个 rs 碰上 prev.rd）——概率不高但样本足够多时必然覆盖
- P(WAR|¬RAW) 和 P(WAW|¬RAW∧¬WAR) 更低，且受优先级排斥条件的约束
- P(NoHazard) 看似最高，但 riscv-dv 的分类优先级使其实际标记率远低于预期

结果：在我们的实验中，riscv-dv 生成 22 万条指令后，所有指令的 `RAW_HAZARD` bin 均已覆盖，但 21 条指令的 `WAR_HAZARD`、`WAW_HAZARD`、`NO_HAZARD` bin **全部缺失**——总计 65 个空洞。这不是随机样本不足的问题，而是约束粒度不够的结构性问题：生成器没有"相邻指令间寄存器关系"这个约束维度。

### 1.2 Our Approach

我们提出 **RVProbe**，一个将约束抽象从指令级提升到**序列级**的定向测试生成框架。RVProbe 基于 Scala 3 eDSL 构建，核心设计理念是：

**序列级约束抽象**：工程师不再需要手动推理相邻指令间的寄存器排斥条件（如"确保 prev.rd ∉ {curr.rs1, curr.rs2} 以排除 RAW"），而是直接声明覆盖意图（如 `coverWAR()`）。eDSL 自动将序列级属性分解为指令字段间的 SMT 约束，由求解器生成满足条件的指令序列。

**白盒信号的可组合注入**：RVProbe 利用 Scala 3 元编程从 HDL（如 Chisel 的 Object Model）自动提取微架构信号，将其提升为 eDSL 中的一等约束谓词。这使得架构级约束（如 hazard 类型）与实现级约束（如内部控制信号状态）可以自由组合，定向触发黑盒方法难以覆盖的 corner case。

### 1.3 Contributions

- **C1 序列级约束表达力**（→ RQ1）：我们识别了 CRV 生成器中约束粒度与覆盖粒度之间的抽象鸿沟。通过在 riscv-dv 饱和后的 65 个 hazard 空洞上进行三方对比（手写汇编 / riscv-dv Python 扩展 / RVProbe），我们实证了序列级约束抽象在代码量、正确性保证和格式适配上的优势。
- **C2 白盒约束可组合性**（→ RQ2）：我们展示了序列级约束与自动注入的微架构信号的组合能力，通过定向构造"架构冒险 × 微架构控制信号"的交集条件，暴露了 T1 向量处理器中一个已确认的流水线缺陷。
- **C3 可控的性能开销**（→ RQ3）：我们分析了 eDSL 编译栈各阶段的延迟分布，证明端到端生成时间随指令数线性扩展，在 500 条指令、最高约束复杂度下保持低于 2 秒。

---

## 2 Related Work

我们从约束粒度的角度审视相关工作，区分各方法在指令级与序列级约束能力上的定位。

**CRV 指令序列生成器（指令级约束）**：主流工业生成器如 RISCV-DV [9] 和 Force-RISCV [10] 是覆盖率驱动验证（CDV）[6] 的事实标准。这些工具在指令级约束上已经成熟——能精确控制单条指令的操作码、寄存器和立即数。然而，指令间的关系（如数据冒险类型）依赖随机性，缺乏显式的序列级约束机制 [19]。这导致覆盖率在序列级属性上系统性地停滞 [7]。本文将 riscv-dv 作为实验基线（Section 4.1），定量展示这一结构性局限。

**覆盖率引导的 Fuzzing（RTL 级反馈）**：DirectFuzz [NEW1] 和 RFUZZ [NEW2] 通过 RTL 仿真反馈引导测试生成，以覆盖率增益作为适应度信号来探索状态空间。这类方法在 RTL 级操作，优势在于无需人工定义覆盖目标——反馈信号自动驱动探索。然而，它们与 RVProbe 处于不同的抽象层：(1) Fuzzing 需要完整的 RTL 仿真循环，每次迭代开销大；(2) 生成的测试是隐式的（由突变算子驱动），工程师难以将覆盖意图显式编码为约束。RVProbe 在 ISA 级操作，通过纯约束求解生成测试，不依赖仿真反馈——两者是互补而非竞争的关系。

**微架构感知验证（跨层方法）**：μ-Spec [14] 提出 ISA 无关的描述语言来建模微架构事件，DiffSpec [13] 探索基于差异化规范的验证。这些方法追求跨 ISA 通用性，但往往牺牲了与特定实现的深度集成 [15, 16]。RVProbe 的白盒信号注入机制（Section 3.4）采取相反策略：通过元编程从具体 HDL 实现中提取信号，实现实现特定的精确定向。

**SMT 在验证中的应用**：基于 SMT 的测试生成 [15, 26] 已有探索，但多依赖动态语言绑定（如 Z3-Python），缺乏编译期类型安全。RVProbe 利用 Scala 3 类型系统在编译期捕获约束逻辑错误，并通过 MLIR SMT Dialect [23] 实现内存中的约束构造，避免文本序列化开销。

**定位总结**：RVProbe 的独特定位在于**序列级约束抽象**——填补了指令级 CRV 生成器和 RTL 级 fuzzing 之间的空白。它不替代任一方，而是提供一种高效的中间层，以声明式方式精确定向序列级覆盖目标。

---

## 3 RVProbe Framework Design

本节介绍 RVProbe 的设计，聚焦于其核心贡献：序列级约束抽象。实现细节（编译栈、FFI 接口等）不在此详述。

### 3.1 Overview

RVProbe 是一个基于 Scala 3 的 eDSL 框架，将用户声明的验证意图编译为 SMT 约束，求解后生成可执行的指令序列。其工作流为：

1. **eDSL 规格**：工程师用 eDSL 声明约束（指令类型、寄存器范围、序列级属性）
2. **约束编译**：eDSL 编译为 MLIR SMT Dialect，再降低为 SMT-LIB2
3. **求解与重构**：SMT 求解器（如 Z3）返回模型，框架将变量赋值映射回具体指令

### 3.2 三层约束抽象

RVProbe 的 API 设计围绕三层抽象展开，对应验证意图的不同粒度（表1）：

> **表1**：RVProbe eDSL API 概览

| 抽象层 | API | 描述 | 示例 |
|--------|-----|------|------|
| 指令级 | `isAddi()` | 约束指令类型 | `instruction(0) { isAddi() }` |
| 指令级 | `rdRange(s, e)` | 约束目的寄存器范围 | `rdRange(1, 31)` |
| 指令级 | `rs1 === v` | 约束源寄存器 | `rs1 === 10` |
| 序列级 | `sequence(i, j)` | 定义指令序列约束上下文 | `sequence(0, 1).coverWAR()` |
| 序列级 | `coverRAW/WAR/WAW/NoHazard()` | 约束相邻指令间的冒险类型 | `seq.coverWAR()` |
| 测试级 | `test(name, isa)` | 定义测试目标和 ISA 配置 | `test("T", isRV32I()) { ... }` |

**指令级 API** 由编译期元编程从 `riscv-opcodes` 规范自动生成（Section 3.3），封装单条指令的类型和字段约束。

**序列级 API** 是 RVProbe 的核心差异化特性。回顾 Section 1.1 中 WAR 冒险的定义：

```
WAR: instr[i+1].rd ∈ {instr[i].rs1, instr[i].rs2} ∧ ¬RAW
```

工程师手写汇编时，必须为每对指令心算两个条件：(1) curr.rd 必须等于 prev 的某个 rs；(2) prev.rd 不能等于 curr 的任何 rs（否则会被优先分类为 RAW）。以 `add` 为例：

```asm
# 手写 WAR：工程师必须确保两个条件同时成立
    add x1, x5, x6      # prev: rd=x1, rs1=x5, rs2=x6
    add x5, x7, x8      # curr: rd=x5==prev.rs1 → WAR ✓
                         #       prev.rd=x1 ∉ {x7,x8} → ¬RAW ✓
```

在 RVProbe 中，同样的意图只需声明 `coverWAR()`，排斥条件由求解器自动处理：

```scala
// 用户写的（3 行 call site）：
object Add extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = rType(35, isAdd())

// 库函数 rType 中的序列级约束（复用于所有 R-type 指令）：
def rType(n: Int, opcode: ...)(using ...): Unit =
  (0 until n).foreach { i =>
    instruction(i, opcode) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
  }
  val seq = sequence(0, n)
  seq.coverRAW()        // → prev.rd ∈ {curr.rs1, curr.rs2}
  seq.coverWAR()        // → curr.rd ∈ {prev.rs1, prev.rs2} ∧ ¬RAW
  seq.coverWAW()        // → prev.rd == curr.rd ∧ ¬RAW ∧ ¬WAR
  seq.coverNoHazard()   // → ¬RAW ∧ ¬WAR ∧ ¬WAW
```

关键点：`coverWAR()` 内部自动处理了 hazard 优先级排斥条件（必须同时排除 RAW）。对于 U-type 指令（如 `lui`，无 rs 字段），同一个 `coverWAR()` 会自动识别格式并引入跨格式前驱指令约束——这正是手写汇编中需要工程师心算的部分。

### 3.3 元数据驱动的 API 自动生成

RISC-V 的碎片化使手动维护指令约束库不可持续。RVProbe 利用 Scala 3 编译期宏将 `riscv-opcodes` 规范作为唯一真相来源，自动合成指令级 API：

```
// riscv-opcodes 输入：
addi rd rs1 imm12 14..12=0 6..2=0x04 1..0=3

// 自动生成的 eDSL API：
def isAddi(): Ref[Bool] =
    nameId(239) & hasRd() & hasRs1() & hasImm12()
```

当上游规范更新（如新增扩展），重新编译即自动生成新的 API，无需手动修改约束库。

### 3.4 白盒信号注入

除了架构级约束，RVProbe 支持从 HDL 自动提取微架构信号并注入为 eDSL 谓词。例如，从 Chisel 的 Object Model 中提取 T1 处理器的内部 `Reverse` 控制信号后，自动生成 `isReverse()` 谓词，使其可与序列级约束自由组合（详见 Section 4.2）。

---

## 4 Evaluation

### 4.1 RQ1: 序列级约束表达力对比

#### 4.1.1 Research Question

当 CRV 生成器达到覆盖率饱和后，不同的定向方法在闭合剩余空洞时的**约束表达成本**有何差异？

> 注意：reviewer #2 批评 "directed tests increase coverage is obvious"。本实验的重点不是"能不能填 hole"（任何定向方法都能），而是**填 hole 时的工程代价差异**——这正是序列级约束抽象的价值所在。

#### 4.1.2 Experimental Setup

**Phase 1: CRV 基线饱和**

使用 riscv-dv [9] 默认 RV32I 配置生成指令序列，直至覆盖率饱和。

| 指标 | 值 |
|------|-----|
| 处理指令总数 | 223,716 |
| 平均 Covergroup 分数 | 83.23% |
| 已饱和（多轮无新 bin） | 是 |

**Phase 2: Hole 分析**

饱和后，未覆盖 bin 集中在 `cp_gpr_hazard` coverpoint 的三种类型：

| 缺失 Hazard 类型 | 受影响指令数 | 缺失 bin 数 |
|---|---|---|
| NO_HAZARD | 21 | 21 |
| WAR_HAZARD | 22 | 22 |
| WAW_HAZARD | 22 | 22 |
| **合计** | | **65** |

**关键观察**：riscv-dv 22 万条指令后，`RAW_HAZARD` 全部覆盖（随机寄存器分配自然倾向产生 RAW），但 WAR/WAW/NoHazard **全部缺失**。这是抽象鸿沟的直接证据：hazard 类型是序列级属性（取决于相邻指令的寄存器依赖关系），但 riscv-dv 的约束引擎在指令级独立分配寄存器。

**Phase 3: 三种方法填 hole**

我们分别用三种方法闭合这 65 个 hazard 空洞：

1. **手写汇编**：验证工程师手动构造指令对，需逐对验证 hazard 优先级排斥条件
2. **riscv-dv Python 扩展**：在 riscv-dv 框架内编写 Python 定向流，利用现有基础设施
3. **RVProbe eDSL**：使用序列级 API 声明覆盖意图

#### 4.1.3 Results

> **表2**：三种定向方法对比

| 度量 | 手写汇编 | riscv-dv 扩展 | RVProbe |
|------|---------|--------------|---------|
| **代码量** | 128 行 asm | ~234 行 Python | 63 行 call site + 90 行 library |
| **覆盖保证** | 无（需人工逐对验证 ¬RAW/¬WAR 条件） | 无（依赖 Python 逻辑正确性） | SAT = 闭合（求解器保证） |
| **格式适配** | U-type 需手动引入 helper 指令 | 需按格式分别编写生成逻辑 | `coverWAR()` 自动处理跨格式依赖 |
| **新增指令扩展** | 6 行 asm + 重新验证 | 修改 Python 类 + 调试 | 3 行 call site，复用 library |
| **错误模式** | 静默：WAW 对误满足 RAW 条件时 hole 仍未填 | 运行时：逻辑错误需调试 | 编译期 + 求解器：UNSAT = 约束矛盾 |

> **图2**：覆盖率对比柱状图（待实验数据）

#### 4.1.4 Analysis

**发现1：hole 的本质是序列级的。** riscv-dv 22 万条指令覆盖了所有 RAW bin 但遗漏了所有 WAR/WAW/NoHazard bin。这不是随机样本不足——而是生成策略缺乏序列级约束维度。增加更多随机指令不会改变这一结构性缺陷。

**发现2：手写汇编看似简单，但有隐性成本。** 对于 R-type 指令，模板化的 6 行一组可以机械复制。但：
- 每对必须手动验证 hazard 优先级排斥条件（WAR 要求 ¬RAW，WAW 要求 ¬RAW ∧ ¬WAR）
- U-type 指令（如 `lui`，无 rs 字段）无法在同格式内产生 WAR，工程师必须识别这一点并引入跨格式 helper 指令
- 错误是静默的：如果不慎让 WAW 对同时满足 RAW（如 `add x10, x11, x12; add x10, x10, x14`），riscv-dv 会将其分类为 RAW 而非 WAW，hole 仍未填但无任何报错

**发现3：RVProbe 消除了两个核心人工负担。**
- 不需要验证 hazard 优先级排斥——`coverWAR()` 自动生成 `¬RAW` 约束，求解器保证正确性
- 不需要跨格式推理——`coverWAR()` 根据指令格式元数据自动决定约束哪些字段

### 4.2 RQ2: 白盒约束可组合性

#### 4.2.1 Research Question

序列级约束与微架构白盒信号的组合，能否系统性地暴露黑盒方法难以触发的实现相关缺陷？

#### 4.2.2 Setup

我们选择 T1 开源 RISC-V 向量处理器 [22] 作为 DUT。T1 在 Chisel [25] 中实现，提供机器可读的 Object Model（OM）[24]。

**白盒工作流**：

1. **信号提取**：通过遍历 T1 的 OM，提取内部控制信号及其激活条件。例如，`Reverse` 信号——控制 ALU 操作数交换——仅由 `VrsubVi` 和 `VrsubVx` 激活。
2. **API 注入**：利用元编程将提取的信号映射为 eDSL 谓词（如 `isReverse()`），将内部 RTL 信号提升为一等验证约束。
3. **约束组合**：将架构级序列约束（如 RAW 冒险）与微架构信号约束自由组合。

#### 4.2.3 Test Construction and Bug Discovery

> 注：以下为 DAC26 版本的案例。ICCAD 版本将重新实现一个完整的 T1 测试用例，使用白盒信号注入。（TODO）

```scala
// 组合约束：架构级 RAW 冒险 × 微架构 Reverse 信号
instruction(0) { isLw() & rd === 10 }
instruction(1) {
    isReverse() &  // 微架构约束：定向 Reverse 控制逻辑
    rs1 === 10     // 架构约束：与前一条形成 RAW 依赖
}
```

SMT 求解器生成满足序列（`lw x10, ...` → `vrsub.vx v1, v2, x10`）。RTL 仿真暴露了一个关键缺陷：T1 流水线在标量数据前递与 `Reverse` 操作数路由的交互中错误地冲刷了向量指令。

**为什么黑盒方法难以触发？** 概率估算：T1 支持 ~300 条向量指令，其中仅 2 条激活 `Reverse`；同时需要特定标量寄存器上的 RAW 依赖。随机命中概率约为 (2/300) × (1/32) ≈ 0.02%，且需要在特定流水线状态下触发——实际概率远低于此。

#### 4.2.4 Key Insight

RVProbe 的价值在于**可组合性**：工程师可以将任意数量的架构级约束（hazard 类型、寄存器模式）与微架构约束（内部信号状态）正交组合，系统性地遍历 corner case 空间。这种组合在手写测试中是指数级困难的，但在声明式 eDSL 中只是约束的逻辑与（`&`）。

### 4.3 Performance Profiling and Scalability

#### 4.3.1 Objective and Setup

为评估 eDSL 架构引入的运行时开销，我们进行了全面的性能分析：

- **RQ3.1（延迟）**：典型定向测试场景的端到端生成延迟是多少？
- **RQ3.2（瓶颈分析）**：计算时间在 eDSL 降低各阶段的分布如何？

测量条件：AMD Ryzen 9 7940HS CPU，32 GB RAM，Arch Linux，OpenJDK 17，Z3 v4.15 via FFI。100 次运行取平均值。

评估矩阵跨两个维度：
- **序列长度（N_inst）**：10 到 500 条指令
- **约束复杂度**：三个级别
  - *L1（基础）*：仅指令类型约束（如 `isAddi()`）
  - *L2（指令内）*：加入 `rd`, `rs1`, `imm12` 的复合约束
  - *L3（指令间）*：引入 `hasRAW()` 全局依赖约束

```scala
// [L1] Basic: 单操作码约束
(0 until nInst).foreach { i =>
  instruction(i) { isAddi() }
}

// [L2] Intra-Instruction: 字段范围与逻辑
(0 until nInst).foreach { i =>
  instruction(i) { isAddi() & rdRange(1, 5) & imm12Range(-100, 100) }
}

// [L3] Inter-Instruction: 全局冒险
(0 until nInst).foreach { i =>
  instruction(i) { isAddi() & rdRange(1, 5) & imm12Range(-100, 100) }
}
(0 until nInst - 1).foreach { i =>
  sequence(i, i+1).hasRAW()
}
```

#### 4.3.2 Results and Analysis

| Complex. | N_Inst | T_MLIR | T_SMT | T_Z3 | T_Inst |
|----------|--------|--------|-------|------|--------|
| L1 | 10 | 11.8 | 2.7 | 21.5 | 68.2 |
| L1 | 50 | 47.6 | 10.1 | 34.1 | 100.4 |
| L1 | 100 | 106.8 | 19.6 | 50.2 | 149.4 |
| L1 | 200 | 223.0 | 40.5 | 84.6 | 246.7 |
| L1 | 500 | 585.8 | 112.5 | 192.7 | 556.6 |
| L2 | 10 | 14.2 | 2.7 | 21.0 | 66.2 |
| L2 | 50 | 64.6 | 11.6 | 36.3 | 108.5 |
| L2 | 100 | 133.1 | 23.3 | 55.1 | 164.4 |
| L2 | 200 | 271.6 | 49.2 | 97.1 | 279.6 |
| L2 | 500 | 699.7 | 137.5 | 228.5 | 636.3 |
| L3 | 10 | 17.4 | 2.9 | 21.9 | 66.9 |
| L3 | 50 | 69.9 | 12.7 | 40.7 | 113.0 |
| L3 | 100 | 143.1 | 25.7 | 64.0 | 171.0 |
| L3 | 200 | 289.7 | 53.1 | 114.7 | 292.6 |
| L3 | 500 | 740.4 | 153.9 | 356.4 | 684.2 |

> **图3**：延迟分解与可扩展性分析。堆叠柱状图展示 MLIR 生成（T_MLIR）、SMT 降低（T_SMT）、Z3 求解（T_Z3）和指令解析（T_Inst）在不同序列长度和复杂度下的时间占比。

**复杂度影响（横向对比）**：复杂度略增总时间。L3 约束需要在 MLIR 阶段构造更复杂的依赖图，导致更高的 T_MLIR（N_inst=500 时 L3 为 740.4ms vs L1 为 585.8ms）。关键是求解器时间（T_Z3）保持相对稳定，表明生成的约束在各复杂度级别都经过良好优化。

**可扩展性趋势（纵向对比）**：框架展现*线性扩展特性*。即使在最苛刻的配置下（L3，500 条指令），总生成时间仍低于 2 秒（约 1935ms）。这证实了基于 FFI 的架构有效扩展，成功避免了优化不佳的 SMT 公式化常见的指数级爆炸。

**组件分布（瓶颈分析）**：分解显示模型重构阶段（T_Inst）占总延迟的显著比例。这一开销主要归因于从 SMT 模型解析原始变量赋值和将其格式化为有效汇编语法的字符串操作的实现成本。**关键是，这一开销展现严格的线性可扩展性（O(N)）**。与涉及 NP 完全问题求解、有指数复杂度爆炸固有风险的约束求解阶段（T_Z3）不同，重构成本是可预测的。因此，虽然 T_Inst 代表一个常量实现伪影，但它不构成复杂验证场景的基本可扩展性障碍。

---

## 5 Conclusion

我们识别了 CRV 生成器中**约束粒度与覆盖粒度之间的抽象鸿沟**：覆盖率目标定义在序列级，但约束引擎操作在指令级。RVProbe 通过将约束抽象提升到序列级来弥合这一鸿沟。

实验表明：(1) 在 riscv-dv 饱和后的 65 个 hazard 空洞上，序列级约束相比手写汇编和框架扩展在工程成本和正确性保证上具有显著优势；(2) 序列级约束与白盒微架构信号的可组合性，能系统性地暴露黑盒方法难以触发的实现缺陷；(3) 框架的性能开销线性可控。

RVProbe 不替代 CRV 或 RTL fuzzing，而是提供一个介于两者之间的精确定向层——以声明式约束闭合序列级覆盖空洞。

---

## References

[1] A. Akram and L. Sawalha, "A Survey of Computer Architecture Simulation Techniques and Tools," IEEE Access, 2019.
[2] M. Chupilko et al., "Open-Source Validation Suite for RISC-V," MTV 2019.
[3] R. Chen and I. Sander, "Towards Coherent Semantics: A Quantitatively Typed EDSL for Synchronous System Design," DATE 2025.
[4] A. Cimatti, "Application of SMT solvers to hybrid system verification," FMCAD 2012.
[5] C. Lattner et al., "MLIR: A compiler infrastructure for the end of Moore's law," arXiv 2020.
[6] S. Yang et al., "Determining Cases of Scenarios to Improve Coverage in Simulation-based Verification," SBCCI 2014.
[7] J. Wang et al., "A UVM Verification Platform for RISC-V SoC from Module to System Level," ICICM 2020.
[9] Google RISCV-DV. https://github.com/google/riscv-dv
[10] Force-RISCV. https://github.com/openhwgroup/force-riscv
[11] B. W. Mezger et al., "A Survey of the RISC-V Architecture Software Support," IEEE Access, 2022.
[12] V. Herdt et al., "Towards Specification and Testing of RISC-V ISA Compliance," DATE 2020.
[13] T. Lu et al., "Comprehensive RISC-V Floating-Point Verification," DATE 2025.
[14] Y. Hsiao et al., "Synthesizing Formal Models of Hardware from RTL," MICRO 2021.
[15] B. Campbell and I. Stark, "Randomised testing of a microprocessor model using SMT-solver state generation," SCP 2016.
[16] Y. Katz et al., "Learning micro-architectural behaviors to improve stimuli generation quality," DAC 2011.
[17] C. Tain et al., "Survey of Verification of RISC-V Processors," JEST 2025.
[18] S. Ahmadi-Pour et al., "Constrained random verification for RISC-V," MBMV 2021.
[19] N. Kitchen and A. Kuehlmann, "Stimulus generation for constrained random simulation," ICCAD 2007.
[22] J. Liu et al., "Titan-I: An Open-Source, High Performance RISC-V Vector Core," MICRO 2025.
[23] MLIR SMT Dialect. https://mlir.llvm.org/docs/Dialects/SMT
[24] Chisel Object Model. https://www.chisel-lang.org/docs/cookbooks/objectmodel
[25] J. Bachrach et al., "Chisel: constructing hardware in a Scala embedded language," DAC 2012.
[26] L. de Moura and N. Bjørner, "Z3: An Efficient SMT Solver," TACAS 2008.
[27] CIRCT. https://circt.llvm.org
[NEW1] S. Hur et al., "DirectFuzz: Automated Test Generation for RTL Designs using Directed Graybox Fuzzing," DAC 2021.
[NEW2] K. Laeufer et al., "RFUZZ: Coverage-Directed Fuzz Testing of RTL on FPGAs," ICCAD 2018.
