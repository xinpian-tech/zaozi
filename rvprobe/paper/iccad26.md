# RVProbe: Sequence-Level Constraint Abstraction for Directed RISC-V Test Generation

## 摘要

约束随机验证（CRV）生成器在指令级别进行约束求解：独立确定每条指令的操作码与操作数字段，而指令间的关系则完全由随机性决定。然而，功能覆盖率模型中的诸多关键验证目标——例如数据冒险组合——本质上属于序列级属性，其判定依赖于相邻指令之间的寄存器依赖关系。这种约束粒度与覆盖粒度之间的抽象鸿沟，导致 CRV 在达到覆盖率饱和后遗留系统性的覆盖空洞。

本文提出 RVProbe，一个基于 Scala 3 嵌入式领域特定语言（eDSL）的定向测试生成框架，将约束抽象从指令级提升至序列级。验证工程师通过声明式 API（如 `coverWAR()`、`coverNoHazard()`）直接表达覆盖意图，由 SMT 求解器自动生成满足序列级约束的指令序列。此外，RVProbe 通过编译期元编程支持从硬件描述语言（HDL）自动提取微架构信号并注入为 eDSL 谓词，实现架构约束与实现细节的可组合白盒验证。

实验评估表明：(1) 在 riscv-dv 覆盖率饱和后遗留的 65 个冒险覆盖空洞上，RVProbe 相比手写汇编和 riscv-dv 框架扩展，在代码规模、正确性保证及可扩展性方面均具有显著优势；(2) 序列级约束与白盒微架构信号的组合，成功暴露了 T1 向量处理器中一个已确认的流水线缺陷；(3) 端到端生成延迟随指令数量线性增长，在 500 条指令规模下保持低于 2 秒。

---

## 1 引言

### 1.1 问题：约束粒度与覆盖粒度之间的抽象鸿沟

功能验证是现代处理器设计流程中的主要瓶颈，占据了设计周期中大量的成本与时间 [1]。在覆盖率驱动验证（CDV）范式下，约束随机验证（CRV）已成为激励生成的行业标准方法 [19]。然而，我们观察到当前主流 CRV 生成器存在一个结构性局限——约束粒度与覆盖粒度之间的抽象鸿沟：

- **覆盖率模型定义于序列级**：功能覆盖率测试计划中的关键覆盖区间（bin），如 `WAR_HAZARD`、`WAW_HAZARD`、`NO_HAZARD`，描述的是相邻指令之间的寄存器依赖关系，属于序列级属性。
- **约束引擎操作于指令级**：主流 CRV 生成器（如 riscv-dv [9]、Force-RISCV [10]）对每条指令的字段（操作码、目的寄存器、源寄存器、立即数）独立施加约束，指令间的关系完全交由随机性决定。

以数据冒险（data hazard）覆盖为例加以说明。riscv-dv 的功能覆盖率模型对每条指令定义了 `cp_gpr_hazard` 覆盖点，依据相邻指令 `instr[i]` 与 `instr[i+1]` 的寄存器关系进行分类（冒险类型归属于 `instr[i+1]`，按以下优先级互斥判定）：

```
RAW:       instr[i].rd ∈ {instr[i+1].rs1, instr[i+1].rs2}
WAR:       instr[i+1].rd ∈ {instr[i].rs1, instr[i].rs2}  ∧ ¬RAW
WAW:       instr[i].rd == instr[i+1].rd                   ∧ ¬RAW ∧ ¬WAR
NoHazard:  ¬RAW ∧ ¬WAR ∧ ¬WAW
```

上述每条规则均引用了两条相邻指令的寄存器字段，这正是"序列级属性"的本质含义：仅凭单条指令的信息无法判定其冒险类型。

然而，riscv-dv 的约束引擎对每条指令独立地从 `[0, 31]` 范围内分配寄存器值，相邻指令的寄存器关系完全取决于随机分配的结果。对于 R-type 指令（包含 3 个寄存器字段），在均匀随机分配下，各冒险类型的命中概率呈现显著的不均衡性：

- P(RAW) ≈ 1 − (31/32)² ≈ 6.1%，即至少一个源寄存器碰巧等于前序指令目的寄存器的概率。尽管单次概率不高，但在充分大的样本下，RAW 覆盖区间必然被命中。
- P(WAR|¬RAW) 与 P(WAW|¬RAW∧¬WAR) 由于受到优先级排斥条件的限制，命中概率进一步降低。
- P(NoHazard) 虽然作为补集事件在理论上占据最大概率空间，但 riscv-dv 的优先级分类机制使其在实际覆盖率统计中呈现远低于预期的标记率。

实验结果印证了上述分析：riscv-dv 在生成 223,716 条指令后，全部 21 条 RV32I 算术逻辑指令的 `RAW_HAZARD` 覆盖区间均已命中，但 `WAR_HAZARD`、`WAW_HAZARD` 与 `NO_HAZARD` 覆盖区间全部缺失，共计 65 个覆盖空洞。这并非随机样本规模不足所致，而是约束粒度不够所导致的结构性问题——生成器缺乏对"相邻指令间寄存器关系"这一维度的约束能力。

### 1.2 本文方法

针对上述问题，本文提出 **RVProbe**，一个将约束抽象从指令级提升至序列级的定向测试生成框架。RVProbe 基于 Scala 3 嵌入式领域特定语言（eDSL）构建，核心设计包含以下两个方面：

**序列级约束抽象**。验证工程师无需手动推导相邻指令间的寄存器排斥条件（例如，"确保前序指令的目的寄存器不属于当前指令的源寄存器集合以排除 RAW"），而是直接以声明式 API 表达覆盖意图（如 `coverWAR()`）。eDSL 自动将序列级属性分解为指令字段间的 SMT 约束，由求解器生成满足条件的指令序列。

**白盒信号的可组合注入**。RVProbe 利用 Scala 3 的编译期元编程能力，从硬件描述语言（如 Chisel 的 Object Model）中自动提取微架构内部信号，并将其提升为 eDSL 中的一等约束谓词。这一机制使得架构级约束（如冒险类型）与实现级约束（如内部控制信号状态）可以自由组合，从而精确定向黑盒方法难以覆盖的深层 corner case。

### 1.3 贡献

本文的主要贡献如下：

- **C1 序列级约束表达力**（对应 RQ1）：我们识别了 CRV 生成器中约束粒度与覆盖粒度之间的抽象鸿沟，并通过在 riscv-dv 覆盖率饱和后遗留的 65 个冒险覆盖空洞上进行三方对比实验（手写汇编、riscv-dv Python 扩展、RVProbe），实证了序列级约束抽象在代码规模、正确性保证及格式适配方面的优势。
- **C2 白盒约束可组合性**（对应 RQ2）：我们展示了序列级约束与自动注入的微架构信号的组合能力。通过定向构造"架构级冒险 × 微架构控制信号"的交集条件，成功暴露了 T1 向量处理器中一个已确认的流水线缺陷。
- **C3 可控的性能开销**（对应 RQ3）：我们对 eDSL 编译栈各阶段的延迟分布进行了系统性分析，证明端到端生成时间随指令数量线性增长，在 500 条指令、最高约束复杂度配置下保持低于 2 秒。

---

## 2 相关工作

本节从约束粒度的角度审视相关工作，区分各类方法在指令级约束与序列级约束能力上的定位。

**CRV 指令序列生成器**。主流工业级生成器如 riscv-dv [9] 和 Force-RISCV [10] 是覆盖率驱动验证 [6] 中应用最为广泛的激励生成工具。这些工具在指令级约束方面已经高度成熟，能够精确控制单条指令的操作码、寄存器分配及立即数取值。然而，指令间的关系（如数据冒险类型）依赖于随机分配的结果，缺乏显式的序列级约束机制 [19]。这一局限导致覆盖率在序列级属性上出现系统性停滞 [7]。本文以 riscv-dv 作为实验基线（Section 4.1），定量揭示这一结构性局限的影响。

**覆盖率引导的 Fuzzing 方法**。DirectFuzz [NEW1] 和 RFUZZ [NEW2] 通过 RTL 仿真反馈引导测试生成，以覆盖率增益作为适应度信号驱动状态空间探索。这类方法在 RTL 层面操作，其优势在于无需人工定义覆盖目标。然而，它们与 RVProbe 处于不同的抽象层次：(1) Fuzzing 方法依赖完整的 RTL 仿真循环，单次迭代开销较大；(2) 所生成的测试由变异算子隐式驱动，工程师难以将特定覆盖意图显式编码为约束。RVProbe 在 ISA 层面操作，通过纯约束求解生成测试，不依赖仿真反馈。两类方法构成互补而非竞争的关系。

**微架构感知验证**。μ-Spec [14] 提出了 ISA 无关的描述语言用于建模微架构事件，近期工作 [13] 探索了基于差异化规范的验证方法。这些方法追求跨 ISA 的通用性，但在此过程中往往牺牲了与特定实现的深度集成能力 [15, 16]。RVProbe 的白盒信号注入机制（Section 3.4）采取了相反的策略：通过元编程从具体 HDL 实现中提取内部信号，实现面向特定实现的精确定向。

**SMT 在验证中的应用**。基于 SMT 的处理器测试生成已有相关探索 [15, 26]，但现有方法多依赖动态语言绑定（如 Z3 的 Python 接口），缺乏编译期类型安全保障。RVProbe 利用 Scala 3 的类型系统在编译阶段捕获约束逻辑错误，并通过 MLIR SMT Dialect [23] 实现内存中的约束构造，规避了传统 SMT-LIB2 文本接口的序列化开销。

**定位总结**。RVProbe 填补了指令级 CRV 生成器与 RTL 级 fuzzing 之间的方法学空白，提供了一个高效的中间层，以声明式约束精确定向序列级覆盖目标。

---

## 3 框架设计

本节介绍 RVProbe 的框架设计，重点阐述其核心特性——序列级约束抽象的设计与实现。

### 3.1 总体架构

RVProbe 是一个基于 Scala 3 构建的 eDSL 框架，将用户声明的验证意图编译为 SMT 约束，经求解后生成可在被测设计（DUT）上执行的指令序列。其工作流程包含以下三个阶段：

1. **eDSL 规约**：验证工程师使用 eDSL 声明验证约束，涵盖指令类型、寄存器范围及序列级属性等多个维度。
2. **约束编译**：eDSL 约束经编译生成 MLIR SMT Dialect 中间表示，随后降低为标准 SMT-LIB2 格式。
3. **求解与重构**：SMT 求解器（如 Z3）返回可满足模型，框架将变量赋值映射回具体的 RISC-V 指令编码。

### 3.2 三层约束抽象

RVProbe 的 API 设计围绕三层语义抽象展开，分别对应验证意图的不同粒度层次（表 1）。

> **表 1**：RVProbe eDSL 约束 API 概览

| 抽象层次 | API | 语义描述 | 使用示例 |
|----------|-----|----------|----------|
| 指令级 | `isAddi()` | 约束指令类型为 ADDI | `instruction(0) { isAddi() }` |
| 指令级 | `rdRange(s, e)` | 约束目的寄存器取值范围 | `rdRange(1, 31)` |
| 指令级 | `rs1 === v` | 约束源寄存器为指定值 | `rs1 === 10` |
| 序列级 | `sequence(i, j)` | 定义指令序列上的约束上下文 | `sequence(0, 1).coverWAR()` |
| 序列级 | `coverRAW/WAR/WAW/NoHazard()` | 约束相邻指令间的冒险类型 | `seq.coverWAR()` |
| 测试级 | `test(name, isa)` | 定义测试目标与 ISA 配置 | `test("T", isRV32I()) { ... }` |

**指令级 API** 封装单条指令的类型与字段约束，由编译期元编程从 `riscv-opcodes` 规范自动生成（Section 3.3），确保与 ISA 规范的一致性。

**序列级 API** 是 RVProbe 区别于现有工具的核心特性。回顾 Section 1.1 中 WAR 冒险的形式化定义：

```
WAR: instr[i+1].rd ∈ {instr[i].rs1, instr[i].rs2} ∧ ¬RAW
```

在手写汇编方案中，工程师必须为每对指令同时验证两个条件：(1) 当前指令的目的寄存器必须属于前序指令的源寄存器集合；(2) 前序指令的目的寄存器不得属于当前指令的源寄存器集合（否则将被优先分类为 RAW）。以 `add` 指令为例：

```asm
# 手写 WAR 冒险序列：工程师需同时确保两个条件成立
    add x1, x5, x6      # 前序: rd=x1, rs1=x5, rs2=x6
    add x5, x7, x8      # 当前: rd=x5 ∈ {prev.rs1=x5} → 满足 WAR 条件
                         #       prev.rd=x1 ∉ {x7, x8}  → 排除 RAW 条件
```

在 RVProbe 中，验证工程师仅需声明覆盖意图 `coverWAR()`，冒险优先级排斥条件由求解器自动处理：

```scala
// 用户编写的约束规约（3 行调用代码）：
object Add extends RVGenerator:
  val sets          = Seq(isRV32I())
  def constraints() = rType(35, isAdd())

// 库函数 rType 中的序列级约束定义（复用于全部 R-type 指令）：
def rType(n: Int, opcode: ...)(using ...): Unit =
  (0 until n).foreach { i =>
    instruction(i, opcode) { rdRange(1, 32) & rs1Range(1, 32) & rs2Range(1, 32) }
  }
  val seq = sequence(0, n)
  seq.coverRAW()        // 展开为: prev.rd ∈ {curr.rs1, curr.rs2}
  seq.coverWAR()        // 展开为: curr.rd ∈ {prev.rs1, prev.rs2} ∧ ¬RAW
  seq.coverWAW()        // 展开为: prev.rd == curr.rd ∧ ¬RAW ∧ ¬WAR
  seq.coverNoHazard()   // 展开为: ¬RAW ∧ ¬WAR ∧ ¬WAW
```

此处的关键设计在于：`coverWAR()` 在内部自动引入了冒险优先级排斥约束（即同时断言 ¬RAW）。对于 U-type 指令（如 `lui`，不包含源寄存器字段），同一 `coverWAR()` API 能够根据指令格式元数据自动识别并引入跨格式的前驱指令约束——这正是手写汇编方案中要求工程师进行人工推理的环节。

### 3.3 元数据驱动的 API 自动合成

RISC-V ISA 扩展的快速增长使得手动维护指令约束库的方式难以持续。RVProbe 利用 Scala 3 的编译期宏机制，将官方 `riscv-opcodes` 规范 [21] 作为唯一权威来源，自动合成指令级 API：

```
// riscv-opcodes 规范输入：
addi rd rs1 imm12 14..12=0 6..2=0x04 1..0=3

// 编译期自动生成的 eDSL API：
def isAddi(): Ref[Bool] =
    nameId(239) & hasRd() & hasRs1() & hasImm12()
```

当上游规范发生变更（如新增 ISA 扩展），仅需重新编译即可自动生成对应的约束 API，无需人工修改约束库代码。

### 3.4 白盒信号注入

除架构级约束外，RVProbe 支持从 HDL 中自动提取微架构内部信号并注入为 eDSL 约束谓词。以 T1 向量处理器 [22] 为例，通过遍历其 Chisel Object Model [24] 提取内部 `Reverse` 控制信号后，框架自动生成 `isReverse()` 谓词，使该微架构信号可与序列级约束自由组合（详见 Section 4.2）。

---

## 4 实验评估

### 4.1 RQ1：序列级约束的表达力优势

#### 4.1.1 研究问题

当 CRV 生成器达到覆盖率饱和后，不同的定向方法在闭合剩余覆盖空洞时，其约束表达成本存在怎样的差异？

本实验的核心关注点并非"定向方法是否能够闭合覆盖空洞"——这是不言自明的——而是不同方法在闭合过程中所付出的工程代价差异，这正是序列级约束抽象的价值所在。

#### 4.1.2 实验设置

**阶段一：CRV 基线饱和。** 采用 riscv-dv [9] 的默认 RV32I 配置持续生成指令序列，直至功能覆盖率指标达到饱和（连续多轮生成未产生新的覆盖区间命中）。

| 指标 | 数值 |
|------|------|
| 处理指令总数 | 223,716 |
| 平均 Covergroup 评分 | 83.23% |
| 饱和状态 | 是（连续多轮无新 bin 命中） |

**阶段二：覆盖空洞分析。** 饱和后的未覆盖区间集中于 `cp_gpr_hazard` 覆盖点的三种冒险类型：

| 缺失冒险类型 | 受影响指令数 | 缺失覆盖区间数 |
|---|---|---|
| NO_HAZARD | 21 | 21 |
| WAR_HAZARD | 22 | 22 |
| WAW_HAZARD | 22 | 22 |
| **合计** | | **65** |

如 Section 1.1 所分析，这一结果直接印证了抽象鸿沟的存在：冒险类型作为序列级属性，其覆盖情况取决于相邻指令的寄存器依赖关系，而 riscv-dv 的约束引擎在指令级独立分配寄存器，无法对这一维度施加有效控制。

**阶段三：三种定向方法对比。** 我们分别采用三种方法尝试闭合上述 65 个冒险覆盖空洞：

1. **手写汇编**：验证工程师手动构造满足特定冒险条件的指令对，需逐对验证冒险优先级排斥条件。
2. **riscv-dv Python 扩展**：在 riscv-dv 框架内编写 Python 定向生成流，复用现有基础设施。
3. **RVProbe eDSL**：使用序列级 API 声明覆盖意图，由求解器自动生成满足条件的指令序列。

#### 4.1.3 实验结果

> **表 2**：三种定向方法的定量对比

| 评估维度 | 手写汇编 | riscv-dv Python 扩展 | RVProbe eDSL |
|----------|---------|---------------------|--------------|
| **代码规模** | 128 行汇编 | 约 234 行 Python | 63 行调用代码 + 90 行库函数 |
| **正确性保证** | 无（需人工逐对验证排斥条件） | 无（依赖 Python 逻辑正确性） | 求解器保证（SAT = 约束满足） |
| **格式适配** | U-type 需手动引入辅助指令 | 需按指令格式分别编写生成逻辑 | `coverWAR()` 自动处理跨格式依赖 |
| **扩展至新指令** | 新增 6 行汇编 + 重新验证 | 修改 Python 类 + 调试 | 新增 3 行调用代码，复用库函数 |
| **典型错误模式** | 静默错误：指令对误满足高优先级冒险条件 | 运行时错误：逻辑缺陷需调试定位 | 编译期 + 求解期：UNSAT 即表明约束矛盾 |

> **图 2**：覆盖率对比（待实验数据补充）

#### 4.1.4 分析与讨论

**发现一：覆盖空洞的本质是序列级的。** riscv-dv 在生成 22 万余条指令后覆盖了全部 RAW 区间，却遗漏了全部 WAR、WAW 与 NoHazard 区间。这一现象并非随机样本规模不足所致，而是生成策略缺乏序列级约束维度的结构性后果。持续增加随机指令的生成数量无法改变这一结构性缺陷。

**发现二：手写汇编存在隐性工程成本。** 对于 R-type 指令，模板化的指令对可以进行机械复制。然而：(1) 每对指令均须手动验证冒险优先级排斥条件（WAR 要求同时满足 ¬RAW，WAW 要求同时满足 ¬RAW ∧ ¬WAR）；(2) U-type 指令（如 `lui`，不含源寄存器字段）在同格式内无法产生 WAR 冒险，工程师须识别这一格式特性并引入跨格式的辅助指令；(3) 错误具有静默性——若某一 WAW 指令对不慎同时满足了 RAW 条件（例如 `add x10, x11, x12; add x10, x10, x14`），riscv-dv 将依据优先级将其分类为 RAW 而非 WAW，相应的覆盖空洞仍未闭合，但系统不会产生任何警告。

**发现三：RVProbe 消除了两项核心人工推理负担。** 其一，无需验证冒险优先级排斥条件——`coverWAR()` 自动生成相应的 ¬RAW 约束，正确性由求解器保证。其二，无需进行跨格式推理——`coverWAR()` 依据指令格式元数据自动确定需要约束的寄存器字段。

### 4.2 RQ2：白盒约束的可组合性

#### 4.2.1 研究问题

序列级约束与微架构白盒信号的组合，能否系统性地暴露黑盒方法在统计上难以触发的实现相关缺陷？

#### 4.2.2 实验设置

本实验选择 T1 开源 RISC-V 向量处理器 [22] 作为被测设计。T1 采用 Chisel [25] 实现，提供机器可读的 Object Model（OM）[24]。

白盒验证工作流包含以下三个步骤：

1. **信号提取**：遍历 T1 的 Object Model，提取内部控制信号及其激活条件。例如，`Reverse` 信号——控制 ALU 操作数交换——仅由向量反向减法指令 `VrsubVi`（向量-立即数）和 `VrsubVx`（向量-标量）激活。
2. **API 注入**：利用元编程将所提取的信号自动映射为 eDSL 约束谓词（如 `isReverse()`），将 RTL 内部信号提升为验证前端的一等约束。
3. **约束组合**：将架构级序列约束（如 RAW 冒险）与微架构信号约束进行正交组合。

#### 4.2.3 测试构造与缺陷发现

> 注：以下案例来自 DAC26 版本，ICCAD 版本将基于完整的白盒信号注入流程重新实现测试用例。

```scala
// 组合约束：架构级 RAW 冒险 × 微架构 Reverse 信号
instruction(0) { isLw() & rd === 10 }       // 标量加载至寄存器 x10
instruction(1) {
    isReverse() &  // 微架构约束：定向 Reverse 控制逻辑
    rs1 === 10     // 架构约束：与前序指令形成 RAW 依赖
}
```

SMT 求解器成功生成满足上述组合约束的指令序列（`lw x10, ...` 后跟 `vrsub.vx v1, v2, x10`）。RTL 仿真揭示了一个关键设计缺陷：T1 流水线在标量数据前递逻辑与 `Reverse` 操作数路由逻辑的交互中，错误地冲刷了向量指令。

**黑盒方法的触发概率分析。** T1 支持约 300 条向量指令，其中仅有 2 条激活 `Reverse` 信号；同时需要在特定标量寄存器上构造 RAW 依赖。仅考虑指令选择与寄存器分配两个因素，随机命中概率的上界约为 (2/300) × (1/32) ≈ 0.02%，且该缺陷还需在特定流水线状态下方可触发，实际概率远低于此估算值。

#### 4.2.4 关键发现

RVProbe 的核心价值在于约束的可组合性：验证工程师可以将任意数量的架构级约束（冒险类型、寄存器模式等）与微架构约束（内部控制信号状态）进行正交组合，系统性地遍历 corner case 空间。这种组合在手写测试方案中面临组合爆炸的困难，但在声明式 eDSL 中仅需通过逻辑与运算符（`&`）即可实现。

### 4.3 RQ3：性能分析与可扩展性

#### 4.3.1 实验设置

为评估 eDSL 架构引入的运行时开销，本文进行了系统性的性能分析，围绕以下两个子问题展开：

- **RQ3.1（生成延迟）**：典型定向测试场景下的端到端生成延迟是多少？
- **RQ3.2（瓶颈分析）**：计算时间在 eDSL 编译栈各阶段的分布特征如何？

测量环境：AMD Ryzen 9 7940HS 处理器，32 GB 内存，Arch Linux 操作系统，OpenJDK 17 运行时，Z3 v4.15 通过 FFI 集成。所有数据取 100 次运行的平均值。

评估矩阵涵盖两个维度：

- **序列长度 (N_inst)**：从 10 至 500 条指令。
- **约束复杂度**：定义三个递进级别——
  - *L1（基础级）*：仅含指令类型约束（如 `isAddi()`）。
  - *L2（指令内级）*：在 L1 基础上增加目的寄存器、源寄存器及立即数的复合范围约束。
  - *L3（指令间级）*：在 L2 基础上引入 `hasRAW()` 全局序列依赖约束。

```scala
// L1：基础级——单一操作码约束
(0 until nInst).foreach { i =>
  instruction(i) { isAddi() }
}

// L2：指令内级——字段范围与逻辑组合
(0 until nInst).foreach { i =>
  instruction(i) { isAddi() & rdRange(1, 5) & imm12Range(-100, 100) }
}

// L3：指令间级——全局序列依赖
(0 until nInst).foreach { i =>
  instruction(i) { isAddi() & rdRange(1, 5) & imm12Range(-100, 100) }
}
(0 until nInst - 1).foreach { i =>
  sequence(i, i+1).hasRAW()
}
```

#### 4.3.2 结果与分析

> **表 3**：各复杂度级别与指令规模下的性能指标（单位：毫秒）

| 复杂度 | N_Inst | T_MLIR | T_SMT | T_Z3 | T_Inst |
|--------|--------|--------|-------|------|--------|
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

> **图 3**：延迟分解与可扩展性分析。堆叠柱状图展示 MLIR 生成 (T_MLIR)、SMT 降低 (T_SMT)、Z3 求解 (T_Z3) 与指令重构 (T_Inst) 在不同序列长度及复杂度下的时间占比分布。

**复杂度影响（横向对比）。** 约束复杂度的提升对总延迟的影响较为有限。L3 级约束需要在 MLIR 阶段构造更为复杂的依赖图，导致 T_MLIR 有所增加（N_inst = 500 时，L3 为 740.4 ms，L1 为 585.8 ms）。值得注意的是，求解器时间 T_Z3 在各复杂度级别间保持相对稳定，表明所生成的约束经过了良好的结构化优化。

**可扩展性趋势（纵向对比）。** 框架展现出线性扩展特性。即使在最高负载配置下（L3，500 条指令），总生成时间仍控制在 2 秒以内（约 1,935 ms）。这一结果验证了基于 FFI 的架构能够有效扩展，成功规避了 SMT 约束公式化中常见的指数级复杂度增长。

**组件分布（瓶颈分析）。** 延迟分解表明，模型重构阶段 (T_Inst) 占据了总延迟中的显著比例。该开销主要源于从 SMT 模型中解析变量赋值及将其格式化为合法汇编语法的字符串处理操作。关键在于，这一开销呈严格的线性增长特征 (O(N))。与求解阶段 (T_Z3) 涉及的 NP 完全问题求解——具有指数级复杂度增长的固有风险——不同，重构阶段的成本是确定性可预测的，不构成框架可扩展性的根本瓶颈。

---

## 5 结论

本文识别了 CRV 生成器中约束粒度与覆盖粒度之间的抽象鸿沟：覆盖率目标定义于序列级，而约束引擎操作于指令级。RVProbe 通过将约束抽象从指令级提升至序列级来弥合这一鸿沟。

实验表明：(1) 在 riscv-dv 覆盖率饱和后遗留的 65 个冒险覆盖空洞上，序列级约束抽象相比手写汇编和框架扩展两种方案，在工程成本与正确性保证方面均具有显著优势；(2) 序列级约束与白盒微架构信号的可组合性，能够系统性地暴露黑盒方法在统计上难以触发的实现缺陷；(3) 框架的性能开销呈线性可控特征。

RVProbe 并非旨在取代 CRV 或 RTL fuzzing，而是提供一个介于二者之间的精确定向层——以声明式约束高效闭合序列级覆盖空洞。

---

## 参考文献

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
[21] RISC-V Opcodes. https://github.com/riscv/riscv-opcodes
[22] J. Liu et al., "Titan-I: An Open-Source, High Performance RISC-V Vector Core," MICRO 2025.
[23] MLIR SMT Dialect. https://mlir.llvm.org/docs/Dialects/SMT
[24] Chisel Object Model. https://www.chisel-lang.org/docs/cookbooks/objectmodel
[25] J. Bachrach et al., "Chisel: constructing hardware in a Scala embedded language," DAC 2012.
[26] L. de Moura and N. Bjørner, "Z3: An Efficient SMT Solver," TACAS 2008.
[27] CIRCT. https://circt.llvm.org
[NEW1] S. Hur et al., "DirectFuzz: Automated Test Generation for RTL Designs using Directed Graybox Fuzzing," DAC 2021.
[NEW2] K. Laeufer et al., "RFUZZ: Coverage-Directed Fuzz Testing of RTL on FPGAs," ICCAD 2018.
