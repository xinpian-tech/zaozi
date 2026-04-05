# Experiment 2: White-Box Constraint Composition — T1 Chaining Hazard Matrix

## Research Question (RQ2)

架构级冒险类型与微架构执行单元分类的正交组合，能否系统性地发现向量处理器中的 chaining 实现缺陷？

## Motivation

DAC26 reviewer #2 批评 "directed beats random is obvious"。Exp2 的关键不在于"定向测试能发现 bug"，而在于展示 **约束组合的系统性**——通过架构 × 微架构的正交矩阵，以每格 1 行约束的成本系统性覆盖 chaining 缺陷的高发区域。

reviewer #4 批评 "comparison not clear with the state of the art"。Exp2 通过黑盒触发概率分析，定量说明传统 CRV 几乎不可能覆盖这些 corner case。

## Background: RISC-V V 扩展与 Chaining 机制

### 向量指令基础
- **SEW（Selected Element Width）**：每个向量元素的位宽（8/16/32/64 位）
- **LMUL（Length Multiplier）**：寄存器分组因子（1/2/4/8）
- **vl（Vector Length）**：当前操作的活跃元素数量
- **v0 mask 机制**：当 vm=0 时，v0 作为隐式 mask 操作数

### Chaining 机制
Chaining 允许多条向量指令跨执行 slot 并发执行：当指令 A 产生了前几个元素的结果后，指令 B 可以立即开始消费这些结果。正确性依赖于 VRF 层面的元素级冒险检查。

### Chaining 冒险检查的 5 个复杂度维度
1. **多周期执行与元素级进度跟踪**：elementMask 位向量逐位追踪处理进度
2. **LMUL 寄存器组重叠**：LMUL>1 时物理寄存器组可能重叠
3. **v0 隐式 mask 依赖**：v0 不在显式操作数字段中，容易被检查遗漏
4. **非顺序访问模式**：vrgather 按索引向量读取，破坏 elementMask 顺序假设
5. **异构执行单元时序差异**：ALU（1 周期）vs Divider（20+ 周期）

### T1 的 Chaining 缺陷历史
T1 向量处理器 git 历史中记录了 **19 个 chaining 相关缺陷**（2022-2025），跨越上述全部 5 个复杂度维度。

## Method: Chaining 冒险矩阵

### 维度 1：数据依赖类型（来自架构规范）

| 编号 | 依赖类型 | 形式化定义 |
|------|---------|-----------|
| D1 | 显式 RAW | A.vd → B.vs1/vs2（B 的源操作数读取 A 的目的寄存器）|
| D2 | 隐式 RAW | A.vd=v0 → B.vm=0（A 写 v0，B 以 v0 为隐式 mask）|
| D3 | WAR | A.vs1/vs2 → B.vd（B 的目的寄存器覆写 A 的源操作数）|
| D4 | WAW | A.vd → B.vd（B 覆写 A 的目的寄存器）|
| D5 | 隐式 WAR | A.vm=0 → B.vd=v0（A 以 v0 为 mask，B 写 v0）|

### 维度 2：执行单元分类（从 T1 Object Model 自动提取）

| 编号 | 类别交叉 | OM 谓词 | 冒险检查的特殊性 |
|------|---------|--------|----------------|
| C1 | ALU × ALU | `isAdder()` | 基线：同类执行单元间的标准冒险 |
| C2 | ALU × LSU | — | load/store 有独立的地址计算通路 |
| C3 | ALU × Mask unit | `isMaskunit()` | mask 指令的 vd 固定为 v0 |
| C4 | Slow × Fast | `isDivider()` | 慢指令执行周期长，chaining 窗口大 |
| C5 | Widen × Normal | `isFirstwiden()` | 宽化指令的 vd 占用双倍寄存器组 |
| C6 | Gather × Normal | `isGather()` | 非顺序读取，破坏 elementMask 顺序假设 |
| C7 | Slide × LSU | `isSlid()` | slide 涉及跨 lane 数据移动 |
| C8 | Segment load × Normal | — | nf>0 时 vd 跨越多个寄存器组 |

### 正交组合：5 × 8 = 40 格

## Implementation

### OM Pipeline（已完成 ✅）

```
T1 Chisel source
  → Chisel OM annotations (~45 microarchitectural attributes per instruction)
  → MLIR OM dialect (compiled into binary IR)
  → OMReader (Project Panama FFI, JSON output)
  → UpdateT1Constraints.scala (parse JSON, generate SMT predicates)
  → T1Constraints.scala (auto-generated, ~54 predicates)
```

**关键命令**：
```bash
# 1. Build OM reader
nix build .#t1.blastoise.t1emu.omreader
# 2. Extract instruction JSON
./result/bin/omreader | jq '{instructions}' > /tmp/instruction.json
# 3. Generate T1Constraints.scala
nix develop zaozi -c mill rvprobe.runMain me.jiuyang.rvprobe.scripts.UpdateT1Constraints /tmp/instruction.json
```

**生成的谓词示例**：
- `isReverse()` → `smtOr(isVrsubVi(), isVrsubVx()) & !smtOr(...其余所有...)`
- `isDivider()` → `smtOr(isVdivVv(), isVdivVx(), ...) & !smtOr(...)`
- `isGather()` → `smtOr(isVrgatherVi(), isVrgatherVv(), isVrgatherVx(), isVrgatherei16Vv()) & !smtOr(...)`

### ChainingLib 约束库（已完成 ✅）

**位置**：`zaozi/rvprobe/src/cases/chaining/ChainingLib.scala`

5 种基础依赖方法 + 15 个组合矩阵格方法：

```scala
// D1: Explicit RAW — A.vd → B.vs2
def explicitRAW(reg, opcodeA, opcodeB) =
  instruction(0, opcodeA) { vdEqual(reg.S) & hasVd() & vs1Range(1, 32) & vs2Range(1, 32) & vmEqual(1) }
  instruction(1, opcodeB) { vs2Equal(reg.S) & hasVs2() & vdRange(1, 32) & vmEqual(1) }

// D2: Implicit v0 mask RAW — A writes v0, B uses v0 as mask
def implicitV0RAW(opcodeA, opcodeB) =
  instruction(0, opcodeA) { vdEqual(0.S) & ... & vmEqual(1) }
  instruction(1, opcodeB) { vdRange(1, 32) & ... & vmEqual(0) }

// 组合矩阵格：每格 1 行调用
def explicitRAW_ALUxALU(reg) = explicitRAW(reg, isVaddVv(), isVsubVv())
def explicitRAW_SlowxFast(reg) = explicitRAW(reg, isVdivVv(), isVaddVv())
def war_GatherxALU(reg) =
  instruction(0, isVrgatherVv()) { vs2Equal(reg.S) & vdRange(1, 32) & vmEqual(1) }
  instruction(1, isVaddVv())     { vdEqual(reg.S) & vs1Range(1, 32) & vs2Range(1, 32) & vmEqual(1) }
```

### 已实现的 15 格

| 矩阵格 | 约束方法 | 对应 Bug |
|--------|---------|---------|
| D1×C1 | `explicitRAW_ALUxALU()` | — (baseline) |
| D1×C2 | `explicitRAW_ALUxLSU()` | — |
| D1×C3 | `explicitRAW_MaskxALU()` | mask unit 不报告完成状态 (2023-06) |
| D1×C4 | `explicitRAW_SlowxFast()` | 慢指令 chaining 窗口低估 (2023-08) |
| D1×C5 | `explicitRAW_WidenxNormal()` | 宽化检查仅覆盖 vd 不覆盖 vd+1 (2023-06) |
| D1×C7 | `explicitRAW_SlidexStore()` | slide RAW 窗口低估 (2023-06) |
| D2×C1 | `implicitV0RAW_ALUxALU()` | v0 mask RAW 遗漏 (commit 0dd6e504) |
| D3×C1 | `war_ALUxALU()` | — (baseline) |
| D3×C2 | `war_StorexALU()` | store 不注册 chaining record (2023-06) |
| D3×C4 | `war_SlowxFast()` | — |
| D3×C6 | `war_GatherxALU()` | **gather 乱序读 WAR (commit 50986c9d)** ⭐ |
| D4×C1 | `waw_ALUxALU()` | — (baseline) |
| D4×C2 | `waw_SlowxLoad()` | WAW 未覆盖 slow→LSU 路径 (2023-08) |
| D4×C4 | `waw_SlowxFast()` | — |
| D5×C1 | `implicitV0WAR_ALUxALU()` | — |

### 向量指令汇编渲染修复（已完成 ✅）

修复了 `RVGenerator.toGasLine()` 中向量指令的渲染：
- `regVal()` 增加 `v` 前缀识别
- `argFmt()` 增加向量寄存器前缀
- 新增 VV/VX/VI/load/store/unary/indexed 等向量格式渲染分支
- 修复系统指令 guard 条件（排除向量指令误匹配）

### T1 兼容测试格式（已完成 ✅）

**位置**：`zaozi/rvprobe/src/cases/chaining/ChainingT1Test.scala`

输出格式兼容 T1 测试约定：
- `test()` 函数入口（由 `t1_main.S` 调用）
- `vsetvli` 前导配置（SEW=32, LMUL=1, vl=max）
- `.data` 段提供 256 字节内存 buffer（用于 load/store 格）
- 链接脚本 `t1.ld`（SRAM at 0x20000000）

**构建命令**：
```bash
# 1. 生成汇编
nix develop zaozi -c mill rvprobe.runMain \
  me.jiuyang.rvprobe.cases.chaining.ChainingT1Test /tmp/ChainingT1Test.S

# 2. 交叉编译（rv32gcv，T1 是 XLEN=32）
riscv64-unknown-linux-gnu-gcc -march=rv32gcv -mabi=ilp32d -nostdlib -nostartfiles -static \
  -T t1/tests/t1.ld t1/tests/t1_main.S stubs.S ChainingT1Test.S -o ChainingT1Test.elf
```

## T1 VCS 仿真（已完成 ✅）

### 环境配置

```bash
export VC_STATIC_HOME=/opt/synopsys/vc_static/V-2023.12
export SNPSLMD_LICENSE_FILE=27000@license0.caat
export DWBB_DIR=/opt/synopsys/prime/V-2023.12-SP5/dw
```

### 构建 VCS Emulator

```bash
cd t1
# Emulator binary
nix build .#t1.blastoise.t1emu.vcs-emu --impure --no-link --print-out-paths
# DPI cosim library (Spike reference model)
nix build .#t1.blastoise.t1emu.vcs-dpi-lib --impure --no-link --print-out-paths
```

### 运行仿真

```bash
<vcs-emu-path>/bin/t1emu-vcs-simulator \
  -sv_lib <dpi-lib-path>/lib/libdpi_t1emu \
  +t1_elf_file=ChainingT1Test.elf \
  +t1_dev_rtl_event_path=rtl-event.jsonl \
  +t1_timeout=100000 \
  -no_save
```

### Post-fix 仿真结果（T1 master, blastoise 配置）

```
EXIT=0
Simulation time: 14590000 ps (14590 cycles)
RTL event trace: 880 events
```

**14 格全部通过**（D1×C5 vwadd 跳过，blastoise 的 Spike 不支持该指令）。

### 注意事项

- T1 是 **XLEN=32**，必须用 `rv32gcv` 编译
- `t1_main.S` 需要 `__t1_init_array` 符号（提供 `ret` stub）
- VCS 需要 Synopsys 环境变量 + 许可证
- `+t1_dev_rtl_event_path` 是必需参数

## Bug 复现流程

### 目标 Bug：D3×C6 Gather WAR（直接发现 ⭐）

**Fix commit**：`50986c9d` "[rtl] fix WAR check for gather read."
**Pre-fix**：`097ec761`

**Bug 根因**：
```scala
// Pre-fix WriteCheck.scala:
val hitVs2: Bool = (checkOH & maskForVs2) === 0.U && check.vd(4,3) === record.bits.vs2(4,3)
// elementMask 假设元素按顺序处理，但 vrgather 按索引向量乱序读取。
// 当 mask 位 0-3 已置位（顺序步骤 0-3 完成），gather 可能在步骤 5 才读元素 2（index[5]=2），
// 此时 hitVs2=false（误判为安全），写被放行，数据被破坏。

// Post-fix:
val hitVs2: Bool = ((checkOH & maskForVs2) === 0.U || record.bits.gather) && ...
// 新增 || record.bits.gather 条件：gather 指令时无条件阻塞写入
```

**RVProbe 测试用例**：
```scala
// D3×C6: WAR, Gather × ALU
def war_GatherxALU(reg: Int = 4) =
  instruction(0, isVrgatherVv()) { vs2Equal(reg.S) & vdRange(1, 32) & vmEqual(1) }
  instruction(1, isVaddVv())     { vdEqual(reg.S) & vs1Range(1, 32) & vs2Range(1, 32) & vmEqual(1) }
```

**生成的汇编**：
```asm
vrgather.vv v1, v4, v0   # A: 非顺序读 vs2=v4（读取顺序由 v0 的值决定）
vadd.vv v4, v1, v1        # B: 写 vd=v4 → 与 A 的 vs2 构成 WAR
```

### 一键复现

```bash
export VC_STATIC_HOME=/opt/synopsys/vc_static/V-2023.12
export SNPSLMD_LICENSE_FILE=27000@license0.caat
export DWBB_DIR=/opt/synopsys/prime/V-2023.12-SP5/dw

./scripts/reproduce-gather-war.sh
```

**脚本流程**：
1. 生成 D3×C6 测试用例汇编
2. 交叉编译为 rv32gcv ELF
3. `nix build ".?rev=097ec761...#t1.blastoise.t1emu.vcs-emu" --impure` → 构建 pre-fix emulator
4. `nix build ".?rev=50986c9d...#t1.blastoise.t1emu.vcs-emu" --impure` → 构建 post-fix emulator
5. 分别运行仿真，对比结果

**预期结果**：
- Pre-fix (097ec761): **FAIL** — Spike 与 RTL 结果不一致（WAR 冒险未正确检查）
- Post-fix (50986c9d): **PASS** — gather 条件已修复

### 其他可复现 Bug

| Bug | Fix Commit | Pre-fix | 矩阵格 |
|-----|-----------|---------|--------|
| v0 mask RAW | `0dd6e504` | `1437af0e` | D2×C1 |
| mask unit 完成状态 | `44d0d3b4` | `6b943dd3` | D1×C3 |
| store WAR | `26837bc1` | `9ca094d0` | D3×C2 |
| widen 寄存器组 | `787b974f` | `641207d7` | D1×C5 |
| slide RAW | `3a8b08b9` | `d3edb529` | D1×C7 |
| store RAW hazard | `d93fa2a2` | `4c45c46c` | D1×C4 |
| store WAR hazard | `e7c52f00` | `e68853c4` | D4×C2 |

注意：早期 commit（2023 年）的 T1 flake 结构可能与当前不同，可能需要调整构建命令。

## 黑盒触发概率分析

| 缺陷 | 触发所需条件 | 随机概率上界 | RVProbe |
|------|------------|------------|---------|
| D2×C1 v0 mask RAW | vd=v0 (1/32) × vm=0 (1/2) | ≈ 1.6% | 100% |
| D3×C6 gather WAR | gather (3/300) × 寄存器重叠 (1/32) × vm (1/2) | ≈ 0.016% | 100% |
| D3×C2 store WAR | store (1/2) × 寄存器重叠 (1/32) | ≈ 1.6% | 100% |

注：上界仅考虑指令选择与寄存器分配，未计入时序窗口、vl/SEW/LMUL 配置及 VRF 初始状态。实际触发概率远低于此。

## Status

| 项目 | 状态 |
|------|------|
| OM Pipeline (T1Constraints 生成) | ✅ 完成 |
| ChainingLib (5种依赖 × 15个组合方法) | ✅ 完成 |
| 15 格矩阵入口 (ChainingMatrix + ChainingT1Test) | ✅ 完成 |
| ReverseBug 白盒案例 | ✅ 完成 |
| 向量指令汇编渲染 | ✅ 修复 |
| T1 VCS 仿真（post-fix, 14格） | ✅ 通过，14590 cycles |
| Bug 复现脚本 | ✅ `scripts/reproduce-gather-war.sh` |
| Pre-fix 仿真验证 | ⏳ VCS emulator 构建中 |

## Files

```
zaozi/rvprobe/src/cases/chaining/
├── ChainingLib.scala        # 矩阵约束库（5种依赖 + 15个组合格）
├── ChainingMatrix.scala     # 15格入口（standalone 格式，含 HTIF）
├── ChainingT1Test.scala     # 14格入口（T1兼容格式，test()入口）
└── ReverseBug.scala         # Reverse 信号白盒案例

zaozi/rvprobe/src/constraints/
└── T1Constraints.scala      # 自动生成的 ~54 个微架构谓词

zaozi/rvprobe/src/RVGenerator.scala  # 向量指令渲染修复

scripts/
├── reproduce-gather-war.sh  # D3×C6 一键复现
└── run-exp2.sh              # Exp2 完整流程
```
