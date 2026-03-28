# Experiment 1: Coverage Hole Closure — RVProbe vs SV Constraints vs Hand-Written Assembly

## Research Question

When riscv-dv (the industry-standard CRV generator) reaches coverage saturation, how do different directed methods compare in closing the remaining holes?

## Motivation

DAC26 reviewer #2 批评 "directed tests are created by manually adding constraints, so the increase in coverage is absolutely expected"。这说明仅展示 RVProbe 能填 hole 没有说服力——关键是展示**填 hole 时的约束表达成本差异**。

reviewer #4 批评 "comparison not clear with the state of the art"。用 riscv-dv 作为共同起点，对比三种填 hole 方法，直接回应这一批评。

## Hypothesis

riscv-dv 覆盖率饱和后，剩余的 hole 集中在**序列级属性**（hazard 组合、寄存器交叉覆盖）。这类 hole 的根本困难不在于"能不能填"，而在于表达序列级约束的代价：

- **手写汇编**：工程师需要在脑中求解 CSP（bin 覆盖 + hazard 约束同时满足），耗时且易错
- **SV constraints**：能表达单指令约束，但序列级约束需要 workaround（辅助索引变量、显式等式约束），每种指令格式需要独立的 constraint class
- **RVProbe**：序列级约束是一等公民（`coverRAW()`, `coverBins()`），求解器一次性保证全部满足

## Experimental Design

### Phase 1: riscv-dv Baseline Saturation

**目标**：建立共同起点，获取覆盖率饱和曲线和未覆盖 hole 清单。

**步骤**：
1. 使用 riscv-dv 的默认 RV32I 配置生成指令序列
2. 在目标 RTL（如 ibex/cv32e40p 或类似 RV32I core）上仿真，收集 riscv-dv 内置 covergroup 的覆盖率
3. 持续生成直到覆盖率不再增长（定义饱和窗口：连续 N 轮无新 bin 被覆盖）
4. 导出未覆盖 bin 清单

**预期结果**（基于 DAC26 数据）：
- 饱和点约 80% functional coverage（~22500 条指令后）
- 剩余 hole 主要分布在：
  - Hazard 组合 bin（特定指令对的 RAW/WAR/WAW）
  - 寄存器交叉覆盖（特定 rd×rs1×rs2 组合）
  - 立即数边界值

**产出**：
- `phase1_coverage_curve.csv` — 覆盖率随指令数的变化
- `phase1_uncovered_bins.txt` — 未覆盖 bin 清单（从 riscv-dv covergroup 报告提取）
- riscv-dv 配置文件和运行脚本

### Phase 2: Hole Analysis

**目标**：分类剩余 hole，确定填补目标。

将未覆盖 bin 分类为：
1. **寄存器 bin hole**：某指令的 rd/rs1/rs2 未覆盖全部 x1..x31
2. **Hazard bin hole**：某相邻指令对的 RAW/WAR/WAW/NoHazard 类型缺失
3. **立即数边界 hole**：imm12/imm20 的边界值未被命中
4. **不可达 bin**：架构上不可能覆盖的 bin（如 x0 作为 rd 的写入效果）

从中选取**代表性 hole 集**（覆盖多种指令格式），作为 Phase 3 三方对比的统一目标。

**选取原则**：
- 至少覆盖 R-type、I-type、Store 三种格式（与 DAC26 现有数据对齐）
- 包含纯寄存器 bin hole 和 hazard bin hole（展示序列级约束的难度差异）
- hole 数量足以产生统计意义上的 LOC 对比

### Phase 3: Three-Way Hole Closure Comparison

对 Phase 2 选取的同一批 hole，分别用三种方法填补：

#### (a) RVProbe eDSL

利用已有的 `CoverageLib`，为每种指令格式调用对应的库函数。

示例（R-type，如 add）：
```scala
object Add extends RVGenerator:
  val sets          = isRV64GC()
  def constraints() = rType(n, isAdd())
```

**特点**：call site 3 行，library 函数约 20 行（跨指令复用），求解器保证 SAT = 全覆盖。

#### (b) SV Constrained-Random

为每种指令格式编写 SV sequence class，表达同样的覆盖目标。

**关键难点**（需要在文件中展示并注释）：
1. **Bin 覆盖 → 辅助索引数组**：SV 的 `covergroup` 是 observe-only，不驱动 `randomize()`。要保证"35 条指令中 rd 覆盖 x1..x31"需要引入 `rand int unsigned rd_assignment[31]` 辅助变量 + foreach 约束。
2. **Hazard 覆盖 → 显式索引约束**：SV 没有存在量词，"存在一对相邻指令满足 RAW"需要 `rand int unsigned raw_idx` + 手写等式约束。
3. **每种格式独立编写**：R-type 有 rd/rs1/rs2，Store 只有 rs1/rs2，U-type 只有 rd/imm20 —— 每种都需要不同的 constraint class。

#### (c) Hand-Written Assembly

手工编写满足覆盖目标的指令序列。

**关键难点**（需要在文件中展示并注释）：
1. **CSP 本质**：bin 覆盖和 hazard 约束相互交互。例如 rd 必须覆盖 x1..x31（顺序递增最简单），但 WAW 要求相邻指令 rd 相同（打破顺序递增）。
2. **验证成本**：写完后必须手动或用脚本验证正确性。
3. **出错静默**：汇编器不会报告"你漏了 x17 的 rs2 覆盖"。

### Metrics

| 度量 | 说明 | 为什么重要 |
|------|------|-----------|
| **约束规约 LOC** | 表达覆盖目标的代码行数（不含 boilerplate/框架） | 核心度量：约束表达的简洁性 |
| **Library LOC** | 可复用的库代码行数 | 摊薄成本：跨指令格式的复用性 |
| **序列级约束表达方式** | 原生 / workaround / 脑内求解 | 定性度量：抽象层次是否匹配 |
| **覆盖闭合保证** | 编译时（SAT）/ 运行时（仿真后统计）/ 手动验证 | 正确性信心 |
| **指令格式适配** | 自动适配 / 每格式独立编写 | 可扩展性 |

### 不需要度量

- ~~生成指令条数~~：三种方法填同一批 hole，指令数应大致相同
- ~~运行时性能~~：这是 exp 的 RQ（求解延迟），不是 exp1 的关注点
- ~~覆盖率百分比~~：Phase 1 之后覆盖率起点相同，填完应该都是 100%

## File Plan

```
rvprobe/paper/exp1/
├── EXP1.md                      ← 本文件（实验设计）
├── phase1/
│   ├── README.md                ← riscv-dv 运行配置和步骤
│   ├── coverage_curve.csv       ← 覆盖率饱和曲线数据
│   └── uncovered_bins.txt       ← 未覆盖 bin 清单
├── phase3_rvprobe/
│   └── (指向 rvprobe/src/cases/coverage/RV32I.scala 即可)
├── phase3_sv/
│   ├── sv_add_coverage.sv       ← R-type SV constraints (已有，需更新上下文)
│   ├── sv_addi_coverage.sv      ← I-type SV constraints (已有)
│   ├── sv_sw_coverage.sv        ← Store SV constraints (已有)
│   └── ...                      ← 按需补充更多格式
├── phase3_handwritten/
│   ├── handwritten_add.S        ← R-type 手写汇编 (已有)
│   ├── handwritten_addi.S       ← I-type 手写汇编 (已有)
│   ├── handwritten_sw.S         ← Store 手写汇编 (已有)
│   └── ...                      ← 按需补充更多格式
├── verify/
│   ├── verify_add.py            ← 验证脚本 (已有)
│   ├── verify_addi.py           ← (已有)
│   └── verify_sw.py             ← (已有)
└── results/
    └── comparison_table.md      ← 最终对比表
```

## Current Status

- [x] R-type (add): SV + 手写汇编 + 验证脚本
- [x] I-type ALU (addi): SV + 手写汇编 + 验证脚本
- [x] Store (sw): SV + 手写汇编 + 验证脚本
- [x] RVProbe CoverageLib: 全部 7 种指令格式的库函数已实现
- [x] RVProbe RV32I.scala: 27 条指令的 call site 已实现
- [ ] Phase 1: 运行 riscv-dv 获取饱和曲线和 hole 清单
- [ ] Phase 2: 分类 hole，选取代表性目标
- [ ] Phase 3: 更新 SV/手写汇编的上下文（从"从零生成"改为"填 riscv-dv 留下的 hole"）
- [ ] 补充更多指令格式的 SV/手写对比（lui, lw, beq 等）
- [ ] 最终对比表
