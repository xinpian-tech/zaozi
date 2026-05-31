# Experiment 3: Performance Profiling and Scalability — Two-Stage SMT Pipeline

## Research Question (RQ3)

RVProbe 的两阶段 SMT 求解管线的运行时开销是否可控？与工业级 CRV 生成器（riscv-dv）相比是否可接受？端到端的实际工作负载延迟是否满足交互式验证的需求？

## Motivation

DAC26 reviewer 关注框架的工程化开销和与 SOTA 的对比。Exp3 的关键在于证明：
1. 两阶段分解（opcode → args）在序列长度增长时保持近线性扩展，避免指数爆炸
2. 与 riscv-dv（VCS 后端）的生成时间可比，额外开销换来确定性覆盖率闭合
3. 实际工作负载（chaining matrix + coverage generators）的端到端延迟合理

## Background: Two-Stage SMT Pipeline

### 架构概述

RVProbe 采用 **两阶段约束求解架构**，将原本的单次大规模 SMT 求解分解为两个独立阶段：

```
Stage 1: solveOpcodes()          Stage 2: solveArgs(fixedOpcodes)
══════════════════════           ══════════════════════════════════
指令集约束 + 指令类型约束          固定的 opcode 结果 + 参数约束
       ↓                                ↓
Z3 Solver → Map[Int, Int]        Z3 Solver → Map[String, BigInt]
(index → opcode)                 (argName_idx → value)
```

### 设计优势

- **Stage 1** 仅处理离散的 opcode 选择问题（搜索空间小）
- **Stage 2** 在 opcode 已固定的前提下求解参数，避免了 opcode × args 的组合爆炸
- Cross-index 约束（如 `coverRAW()`）注册为 Stage 2 的延迟约束，在 opcode 确定后才生成

## Sub-experiments

| 子实验 | 内容 | 目的 |
|--------|------|------|
| **3a** | Real Workload End-to-End Time | 测量真实工作负载的 `toRecipeAsm()` 延迟 |
| **3b** | Scalability + Baseline Comparison | L1/L2/L3 × {10,50,100,200,500} 逐阶段分析 + riscv-dv 对比 |

> **注**：原计划的 3c（消融实验，对比旧版 SMTLIB+subprocess 路径）已取消。旧路径内部实际同样通过 FFI 调用 Z3 进行求解，因此作为 baseline 不公平，无法反映真实的架构差异。

## Method

### Exp3a: Real Workload End-to-End Time

测量 `toRecipeAsm()` 方法在以下两类真实工作负载上的端到端时间：

**工作负载 1：Chaining Matrix Cells（7 格）**

来自 Exp2 的 chaining 冒险矩阵，每个 cell 对应一种 D×C 依赖–执行单元组合：

| Cell | 依赖类型 | 执行单元组合 |
|------|---------|------------|
| D1C1 | 显式 RAW | ALU × ALU |
| D1C3 | 显式 RAW | Mask × ALU |
| D1C4 | 显式 RAW | Slow × Fast |
| D2C1 | 隐式 RAW (v0 mask) | ALU × ALU |
| D3C6 | WAR | Gather × ALU |
| D4C1 | WAW | ALU × ALU |
| D5C1 | 隐式 WAR (v0 mask) | ALU × ALU |

**工作负载 2：Coverage Generators（5 个）**

来自 Exp1 的 RISC-V 指令覆盖率生成器，按指令类型和约束复杂度递增：

| Generator | 指令类型 | 约束复杂度 | N_inst |
|-----------|---------|----------|--------|
| Cov_Lui_uType | U-type | 低（1 个操作数） | 35 |
| Cov_Addi_iType | I-type | 低-中（2 个操作数） | 35 |
| Cov_Slli_shiftImm | shift-imm | 中（移位立即数约束） | 35 |
| Cov_Add_rType | R-type | 中-高（3 个操作数） | 35 |
| Cov_And_rTypeLogical | R-type logical | 高（逻辑类型 + 覆盖 bins） | 35 |

### Exp3b: Scalability + Baseline

**RVProbe 可扩展性**：三种约束复杂度级别 × 五种序列长度，逐阶段计时。

| 级别 | 名称 | 约束内容 |
|------|------|---------|
| L1 | Basic | `instruction(i, isAddi()) { rdRange(1, 5) }` |
| L2 | Intra-instruction | L1 + `rs1Range(1,10) & imm12Range(-100,100)` |
| L3 | Inter-instruction | L2 + `sequence(i, i+1).coverRAW()` |

序列长度：$N_{inst} \in \{10, 50, 100, 200, 500\}$

**riscv-dv Baseline**：从 Exp1 的 VCS 仿真日志中提取 riscv-dv 生成 500 条 RV32I 指令的 CPU 时间（`riscv_arithmetic_basic_test`, `+instr_cnt=500`）。

### Benchmark 实现

**位置**：`zaozi/rvprobe/tests/src/PerfBenchmark.scala`

- 基于 utest 框架（不依赖 JMH，避免外部依赖下载问题）
- Exp3a：每个工作负载 2 次 warmup + 5 次测量取平均
- Exp3b：全局 JVM warmup（5 次 L1/100 预热）+ 每配置 3 次 warmup + 10 次测量取平均
- Exp3b 逐阶段计时：`T_Opc`（solveOpcodes）、`T_Arg`（solveArgs）、`T_Asm`（assembleInstructions）

**运行命令**：

```bash
# 运行全部 exp3 benchmark
nix develop zaozi -c mill rvprobe.tests -t "me.jiuyang.rvprobe.tests.PerfBenchmark"

# 单独运行 Exp3a
nix develop zaozi -c mill rvprobe.tests -t "me.jiuyang.rvprobe.tests.PerfBenchmark.exp3a_realWorkloads"

# 单独运行 Exp3b
nix develop zaozi -c mill rvprobe.tests -t "me.jiuyang.rvprobe.tests.PerfBenchmark.exp3b_scalability"

# 从已提交 CSV 生成图表（纯 derivation）
nix build .#exp3-report

# 运行 benchmark + 生成图表（完整流程）
nix run .#exp3
```

## Results

### 测试环境

| 项目 | 配置 |
|------|------|
| CPU | AMD Ryzen 9 7940HS |
| RAM | 32 GB |
| OS | NixOS (Linux 6.16.7) |
| JDK | OpenJDK 21 (preview features enabled) |
| Z3 | v4.15 (via MLIR SMT Dialect FFI) |
| Scala | 3.6.2 |

### Exp3a: Real Workload Results

**Chaining Matrix Cells（端到端时间，ms）：**

| Cell | Time (ms) |
|------|-----------|
| D1C1 | 162 |
| D1C3 | 161 |
| D1C4 | 159 |
| D2C1 | 160 |
| D3C6 | 159 |
| D4C1 | 161 |
| D5C1 | 162 |

所有 chaining cell 均在 **~160 ms** 完成，方差极小（±3 ms），表明框架对不同依赖类型和执行单元组合的处理开销高度一致。

**Coverage Generators（端到端时间，ms）：**

| Generator | Time (ms) |
|-----------|-----------|
| Cov_Lui_uType | 362 |
| Cov_Addi_iType | 410 |
| Cov_Slli_shiftImm | 788 |
| Cov_Add_rType | 1634 |
| Cov_And_rTypeLogical | 2155 |

覆盖率生成器的时间随约束复杂度递增，最复杂的 `Cov_And_rTypeLogical`（35 条指令，逻辑类型覆盖 bins）约需 **2.2 秒**，仍在交互式验证的可接受范围内。

### Exp3b: Scalability Results

**逐阶段性能数据（ms）：**

| Complex. | N_Inst | T_Opc | T_Arg | T_Asm | Total |
|----------|--------|-------|-------|-------|-------|
| L1 | 10 | 18.5 | 91.0 | 71.1 | 180.5 |
| L1 | 50 | 18.5 | 85.7 | 65.3 | 169.5 |
| L1 | 100 | 21.3 | 92.9 | 66.9 | 181.2 |
| L1 | 200 | 26.0 | 99.0 | 66.1 | 191.1 |
| L1 | 500 | 41.9 | 131.7 | 68.6 | 242.2 |
| L2 | 10 | 16.6 | 83.7 | 63.6 | 163.9 |
| L2 | 50 | 18.5 | 92.6 | 66.1 | 177.1 |
| L2 | 100 | 22.5 | 107.8 | 67.4 | 197.7 |
| L2 | 200 | 30.4 | 134.8 | 70.3 | 235.5 |
| L2 | 500 | 49.9 | 209.2 | 78.9 | **338.0** |
| L3 | 10 | 16.6 | 81.9 | 63.8 | 162.3 |
| L3 | 50 | 18.8 | 89.1 | 64.5 | 172.3 |
| L3 | 100 | 22.4 | 102.8 | 65.8 | 191.0 |
| L3 | 200 | 30.4 | 126.5 | 71.7 | 228.6 |
| L3 | 500 | 53.9 | 185.3 | 81.4 | 320.5 |

**riscv-dv Baseline 对比（N_inst=500）：**

| 工具 | 生成方式 | Time (ms) | 覆盖保证 |
|------|---------|-----------|---------|
| riscv-dv (VCS) | 随机约束 | **280** | 无（概率性） |
| RVProbe (L2) | SMT 确定性 | **338** | 有（solver 保证） |
| RVProbe (L3 + coverRAW) | SMT 确定性 + hazard | **321** | 有 + hazard 覆盖 |

RVProbe 的开销（338 ms）与 riscv-dv（280 ms）可比，但 RVProbe 提供确定性覆盖率闭合，而 riscv-dv 在 22.3 万条指令后仍有 63 个 hazard coverage hole 未覆盖（见 Exp1）。

## Analysis

### 两阶段分解有效性

- **Stage 1 ($T_{Opc}$)**：17–54 ms，轻量级。Opcode 选择是小规模离散搜索，与 $N_{inst}$ 近似线性增长。
- **Stage 2 ($T_{Arg}$)**：82–209 ms，主导总时间。参数求解包含 cross-index 依赖，但在 opcode 固定后复杂度可控。
- **Assembly ($T_{Asm}$)**：64–81 ms，基本恒定。仅做机械性的值→编码转换。

Stage 1 仅占总时间 ~15%，说明两阶段分解合理——将大问题分解为一个小搜索（opcode）+ 一个受限搜索（args with fixed opcodes）。

### 近线性扩展特性

所有复杂度级别下，$N_{inst}$ 从 10 增长到 500 时，总时间仅增长约 1.5–2×（而非指数增长）。这验证了：
1. SMT 约束公式避免了指数爆炸
2. 两阶段分解成功消除了 opcode × args 的组合搜索空间

### Baseline 对比

RVProbe L2@500 = 338ms vs riscv-dv VCS@500 = 280ms，差距仅 ~20%。考虑到 RVProbe 额外提供了：
- 确定性覆盖率闭合（vs 概率性）
- 序列级约束表达（`coverRAW/WAR/WAW`）
- 编译期类型安全
这一开销完全可接受。

### 实际工作负载特性

- **Chaining cells 高度一致**：7 格均约 160 ms，说明架构级依赖类型（RAW/WAR/WAW）和微架构执行单元组合对求解时间无显著影响。
- **Coverage generators 随约束复杂度递增**：从 362 ms（U-type）到 2155 ms（R-type logical），增长符合预期。
- **最复杂场景 ~2.2 秒**：`Cov_And_rTypeLogical` 包含 35 条指令的逻辑类型 + 覆盖 bins 约束，仍在交互式验证（<5 秒）的合理范围内。

### Cross-Index 约束的影响

**反直觉发现**：L3（含 `coverRAW()`）在 $N_{inst}=500$ 时反而快于 L2（321ms vs 338ms）。

原因：RAW 约束固定了相邻指令间的寄存器关系（`rd[i] ∈ {rs1[i+1], rs2[i+1]}`），减少了 Z3 的搜索空间，相当于额外的传播提示（propagation hints），抵消了约束本身的开销。

## Status

| 项目 | 状态 |
|------|------|
| Exp3a PerfBenchmark 实现 | ✅ 完成 |
| Exp3b PerfBenchmark 实现 | ✅ 完成 |
| Exp3a 数据收集 | ✅ 完成（exp3a_real_workloads.csv） |
| Exp3b 数据收集 | ✅ 完成（exp3b_scalability.csv） |
| riscv-dv Baseline 数据 | ✅ 从 Exp1 VCS 日志提取（280ms/500inst） |
| 可视化脚本 | ✅ 完成（plot_detailed_results.py） |
| Nix build 集成 | ✅ 完成（`nix build .#exp3-report`） |
| Paper Exp3 节 | ✅ 完成（dac26.tex Section 4.3） |
| Ablation（3c）| ❌ 已取消（旧路径内部使用 FFI，baseline 不公平） |

## Files

```
zaozi/rvprobe/tests/src/PerfBenchmark.scala    # exp3a + exp3b benchmarks
zaozi/rvprobe/paper/exp3/EXP3.md               # 本文档
riscv-dv/paper/exp3/plot_detailed_results.py   # 可视化脚本
reports/exp3/exp3a_real_workloads.csv          # 真实工作负载数据
reports/exp3/exp3b_scalability.csv             # 可扩展性数据
reports/exp3/performance_results.csv           # 可扩展性数据（兼容旧格式）
scripts/run-exp3.sh                            # 自动化运行脚本
```
