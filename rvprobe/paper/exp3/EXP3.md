# Experiment 3: Performance Profiling and Scalability — Two-Stage SMT Pipeline

## Research Question (RQ3)

RVProbe 的两阶段 SMT 求解管线的运行时开销是否可控？各阶段的时间分布如何随约束复杂度和指令序列长度变化？

## Motivation

DAC26 reviewer 关注框架的工程化开销。Exp3 的关键在于证明：
1. 两阶段分解（opcode → args）的架构决策是否有效降低了求解复杂度
2. cross-index 约束（如 `coverRAW()`）是否引入了不可接受的性能退化
3. 端到端延迟是否满足交互式验证的需求（秒级响应）

## Background: Two-Stage SMT Pipeline

### 架构概述

RVProbe 采用 **两阶段约束求解架构**，将原本的单次大规模 SMT 求解分解为两个独立阶段：

```
Stage 1: solveOpcodes()          Stage 2: solveArgs(fixedOpcodes)
══════════════════════           ══════════════════════════════════
指令集约束 + 指令类型约束          固定的 opcode 结果 + 参数约束
       ↓                                ↓
Z3 Solver → Map[Int, Int]        Z3 Solver → Map[String, BigInt]
(index → nameId)                 (argName_idx → value)
```

### 设计优势

- **Stage 1** 仅处理离散的 opcode 选择问题（搜索空间小）
- **Stage 2** 在 opcode 已固定的前提下求解参数，避免了 opcode × args 的组合爆炸
- Cross-index 约束（如 `coverRAW()`）注册为 Stage 2 的延迟约束，在 opcode 确定后才生成

## Method

### 约束复杂度三级

| 级别 | 名称 | 约束内容 | 测试目标 |
|------|------|---------|---------|
| L1 | Basic | `instruction(i, isAddi()) { rdRange(1, 5) }` | Stage 1 最小负载 |
| L2 | Intra-instruction | `instruction(i, isAddi()) { rdRange(1,5) & rs1Range(1,10) & imm12Range(-100,100) }` | Stage 2 位向量范围组合 |
| L3 | Inter-instruction | L2 约束 + `sequence(i, i+1).coverRAW()` | Stage 2 cross-index 依赖图 |

### 序列长度

$N_{inst} \in \{10, 50, 100, 200, 500\}$

### Benchmark 实现

**位置**：`zaozi/rvprobe/tests/src/PerfBenchmark.scala`

- 基于 utest 框架（不依赖 JMH，避免外部依赖下载问题）
- 全局 JVM warmup（5 次 L1/100 预热）+ 每配置 3 次 warmup + 10 次测量取平均
- 逐阶段计时：`solveOpcodes()` → `solveArgs()` → `assembleInstructions()`
- 输出 CSV 到 `jmh_stage_results.csv`

**运行命令**：
```bash
nix develop zaozi -c mill rvprobe.tests -t "me.jiuyang.rvprobe.tests.PerfBenchmark"
```

**JMH Benchmark 备选**（需要网络下载依赖）：
```bash
nix develop zaozi -c mill rvprobe.benchmark.runJmh -wi 2 -i 3 -f 1 -t 1
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

### 性能数据

| Complex. | N_Inst | T_Opc (ms) | T_Arg (ms) | T_Asm (ms) | Total (ms) |
|----------|--------|------------|------------|------------|------------|
| L1 | 10 | 19.7 | 100.2 | 75.3 | 195.2 |
| L1 | 50 | 17.5 | 84.2 | 66.6 | 168.3 |
| L1 | 100 | 19.7 | 89.6 | 65.9 | 175.1 |
| L1 | 200 | 25.1 | 101.4 | 68.6 | 195.1 |
| L1 | 500 | 40.6 | 129.2 | 68.1 | 237.9 |
| L2 | 10 | 16.5 | 93.3 | 74.3 | 184.2 |
| L2 | 50 | 19.3 | 96.2 | 70.4 | 185.9 |
| L2 | 100 | 26.3 | 122.0 | 72.5 | 220.8 |
| L2 | 200 | 30.5 | 142.2 | 76.1 | 248.8 |
| L2 | 500 | 49.0 | 209.9 | 80.4 | 339.3 |
| L3 | 10 | 15.7 | 82.0 | 63.8 | 161.5 |
| L3 | 50 | 29.1 | 108.0 | 67.3 | 204.4 |
| L3 | 100 | 24.0 | 99.0 | 67.0 | 190.0 |
| L3 | 200 | 28.4 | 121.1 | 69.8 | 219.3 |
| L3 | 500 | 53.3 | 198.0 | 100.3 | 351.7 |

### 与旧版对比

| 指标 | 旧版（4-stage） | 新版（2-stage） | 变化 |
|------|----------------|----------------|------|
| 最慢配置 (L3, 500) | ~1935 ms | ~352 ms | **5.5x 加速** |
| Pipeline 阶段数 | 4 (MLIR→SMT→Z3→Inst) | 3 (Opc→Arg→Asm) | 简化 |
| 瓶颈 | T_Inst (模型重建) | T_Arg (参数求解) | 瓶颈转移至核心求解 |

## Analysis

### 两阶段分解有效性

- **Stage 1 ($T_{Opc}$)**：16-53 ms，轻量级。Opcode 选择是小规模离散搜索，与 $N_{inst}$ 近似线性增长。
- **Stage 2 ($T_{Arg}$)**：82-210 ms，主导总时间。参数求解包含 cross-index 依赖但在 opcode 固定后复杂度可控。
- **Assembly ($T_{Asm}$)**：64-100 ms，基本恒定。仅做机械性的值→编码转换。

### 近线性扩展特性

所有复杂度级别下，$N_{inst}$ 从 10 增长到 500 时，总时间仅增长 ~1.5-2x（而非指数增长）。这验证了：
1. SMT 约束公式避免了指数爆炸
2. 两阶段分解成功消除了 opcode × args 的组合搜索空间

### Cross-Index 约束的影响

**反直觉发现**：L3（含 `coverRAW()`）在多个 $N_{inst}$ 下反而快于 L2。

原因分析：
- RAW 约束固定了相邻指令间的寄存器关系（`rd[i] == rs1[i+1] || rd[i] == rs2[i+1]`）
- 这减少了 Z3 的搜索空间，相当于额外的传播提示（propagation hints）
- L2 的 `rs1Range(1, 10)` 约束在无 cross-index 约束时增加了搜索自由度

### 性能提升来源

与旧版 4-stage 架构相比，5.5x 加速主要来自：
1. **消除 MLIR 序列化开销**：旧版需要 MLIR→SMTLIB2 文本序列化，新版直接 in-memory 求解
2. **两阶段分解**：避免一次性求解所有约束的组合爆炸
3. **模型重建优化**：旧版 T_Inst 瓶颈（字符串解析）已被优化为高效的二进制编码

## Status

| 项目 | 状态 |
|------|------|
| PerfBenchmark 测试 | ✅ 完成，数据已收集 |
| JMH Benchmark 更新（per-stage timing） | ✅ 代码更新（需网络下载依赖） |
| Paper Exp3 节重写 | ✅ 完成（dac26.tex Section 4.3） |
| 旧版图表（4.2(2).png）| ⚠️ 需更新或删除（旧 4-stage 数据） |
| 新版可视化图表 | ⏳ 待生成（plot_detailed_results.py 需适配新数据格式） |

## Files

```
zaozi/rvprobe/tests/src/PerfBenchmark.scala    # 基于 utest 的性能测试（不依赖 JMH）
zaozi/rvprobe/benchmark/src/Benchmark.scala     # JMH benchmark（per-stage timing）
zaozi/rvprobe/paper/exp3/EXP3.md               # 本文档
riscv-dv/paper/exp3/performance_results.csv     # 旧版性能数据（待更新）
riscv-dv/paper/exp3/plot_detailed_results.py    # 可视化脚本（待适配新格式）
scripts/run-exp3.sh                            # 自动化运行脚本（待更新）
```
