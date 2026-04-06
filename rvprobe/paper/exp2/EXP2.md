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

#### 基础矩阵格：2 行约束 → 2 条向量指令对

5 种基础依赖方法 + 15 个组合矩阵格方法。每格约束一对指令的操作码和寄存器依赖：

```scala
// D3×C6: WAR, Gather × ALU — 每格 1 行调用
def war_GatherxALU(reg: Int = 4) =
  instruction(0, isVrgatherVv()) { vs2Equal(reg.S) & vdRange(1, 32) & vmEqual(1) }
  instruction(1, isVaddVv())     { vdEqual(reg.S) & vs1Range(1, 32) & vs2Range(1, 32) & vmEqual(1) }
```

SMT 求解器自动填充寄存器索引，保证 WAR 依赖（B.vd == A.vs2）和微架构约束（A 为 gather 指令）。

#### 完整测试生成：`war_GatherxALU_full()` — 1 行调用 → 完整可执行测试

`war_GatherxALU_full()` ���成包含初始化、约束对、验证的完整序列：

```scala
def war_GatherxALU_full(vSrc: Int = 8, vIdx: Int = 2, ...)(using ...): Unit =
  // 1. 向量配置
  raw("li   x5, -1")
  raw("vsetvli x5, x5, e32, m1, ta, ma")

  // 2. 源数据初始化：v_src = {0, 1, ..., vl-1}
  raw(s"vid.v v$vSrc")

  // 3. 非顺序索引构造：v_idx = vid XOR (vl-1) → 反转
  //    迫使 gather 在最后一步读取 v_src[0]，而 elementMask 在第 0 步就标记元素 0 为 "完成"
  raw("addi x6, x5, -1")
  raw(s"vid.v v$vIdx")
  raw(s"vxor.vx v$vIdx, v$vIdx, x6")

  // 4. 安全的参考 gather（WAR 对之前）
  raw(s"vrgather.vv v$vRef, v$vSrc, v$vIdx")

  // 5. 毒化值初始化
  raw("li x6, 999")
  raw(s"vmv.v.x v$vPoison, x6")

  // 6. ★ 关键 WAR 对 — SMT 约束驱动 ★
  //    求解器保证：A 为 gather 指令（isVrgatherVv），B.vd == A.vs2（WAR 依赖）
  instruction(0, isVrgatherVv()) {
    vdEqual(vResult.S) & vs2Equal(vSrc.S) & vs1Equal(vIdx.S) & vmEqual(1)
  }
  instruction(1, isVaddVv()) {
    vdEqual(vSrc.S) & vs1Equal(vPoison.S) & vs2Equal(vPoison.S) & vmEqual(1)
  }

  // 7. 验证
  raw(s"vmsne.vv v$vCmp, v$vResult, v$vRef")
  raw(s"vcpop.m x6, v$vCmp")
```

**调用方式**：
```scala
object GatherWARBugTest extends RVGenerator:
  val sets = Seq(isRVI(), isRVV())
  def constraints() =
    textStart()
    war_GatherxALU_full()   // ← 一行调用生成完整测试
    raw("ret")
```

**生成的汇编**：
```asm
li   x5, -1
vsetvli x5, x5, e32, m1, ta, ma     # 配置
vid.v v8                              # 源数据 {0,1,...,vl-1}
addi x6, x5, -1
vid.v v2
vxor.vx v2, v2, x6                   # 反转索引 {vl-1,...,0}
vrgather.vv v6, v8, v2               # 参考结果（安全）
li x6, 999
vmv.v.x v7, x6                       # 毒化值
    vrgather.vv v1, v8, v2           # ← SMT 约束: isVrgatherVv() + WAR 依赖
    vadd.vv v8, v7, v7               # ← SMT 约束: isVaddVv() + vd=v8=A.vs2
vmsne.vv v0, v1, v6                  # 验证：比较 vs 参考
vcpop.m x6, v0                       # 统计 mismatch 数
ret
```

缩进的两行 = SMT 求解器约束的指令对。其余行 = `raw()` 辅助代码。

### 已实现的 15 格

| 矩阵格 | 约束方法 | 对应 Bug |
|--------|---------|---------|
| D1×C1 | `explicitRAW_ALUxALU()` | — (baseline) |
| D1×C2 | `explicitRAW_ALUxLSU()` | — |
| D1×C3 | `explicitRAW_MaskxALU()` | mask unit 不报告完成状态 (2023-06) |
| D1×C4 | `explicitRAW_SlowxFast()` | 慢指令 chaining 窗口低估 (2023-08) |
| D1×C5 | `explicitRAW_WidenxNormal()` | 宽化检查仅覆盖 vd 不覆��� vd+1 (2023-06) |
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
- `regVal()` 增加 `v` 前缀识别（vd/vs1/vs2 → v0/v1/v2）
- `argFmt()` 增加向量寄存器前缀
- 新增 VV/VX/VI/load/store/unary/indexed 等向量格式渲染分支
- 修复系统指令 guard 条件（排除向量指令误匹配 "no register args" 分支）

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

## T1 VCS 仿真

### 环境配置

```bash
export VC_STATIC_HOME=/opt/synopsys/vc_static/V-2023.12
export SNPSLMD_LICENSE_FILE=27000@license0.caat
export DWBB_DIR=/opt/synopsys/prime/V-2023.12-SP5/dw
```

### 构建 VCS Emulator

```bash
cd t1
# Emulator binary (指定 commit 构建)
nix build ".?rev=<full-commit-hash>#t1.<config>.t1emu.vcs-emu" --impure --no-link --print-out-paths
# DPI cosim library
nix build ".?rev=<full-commit-hash>#t1.<config>.t1emu.vcs-dpi-lib" --impure --no-link --print-out-paths
```

### 运行仿真

```bash
<vcs-emu-path>/bin/t1emu-vcs-simulator \
  -sv_lib <dpi-lib-path>/lib/libdpi_t1emu \
  +t1_elf_file=<test>.elf \
  +t1_rtl_event_path=rtl-event.jsonl \
  +t1_timeout=100000 \
  -no_save
```

### Post-fix 仿真结果（blastoise 配置）

```
EXIT=0
Simulation time: 14590000 ps (14590 cycles)
RTL event trace: 880 events
```

14 格全部通过（D1×C5 vwadd 跳过，blastoise 的 Spike 不支持）。

### 注意事项

- T1 是 **XLEN=32**，必须用 `rv32gcv` 编译
- `t1_main.S` 需要 `__t1_init_array` 符号（提供 `ret` stub）
- VCS 需要 Synopsys 环境变量 + 许可证
- 旧版 T1 (pre-Feb 2025): 使用 `+t1_rtl_event_path`（不带 `dev`）
- 新版 T1 (master): 使用 `+t1_dev_rtl_event_path`

## Bug 复现：D3×C6 Gather WAR ⭐

### 概述

**Fix commit**：`50986c9d913fc45b82f346e71f01c398a7566b7c` "[rtl] fix WAR check for gather read."
**Pre-fix**：`097ec761e1ad0b03ce026584a9afb7ad42dc9f62`
**配置**：`benchmark_dlen128_vlen4096_fp`（DLEN=128, VLEN=4096 → 128 elements at SEW=32）

### Bug 根因

```scala
// Pre-fix WriteCheck.scala:
val hitVs2: Bool = (checkOH & maskForVs2) === 0.U && check.vd(4,3) === record.bits.vs2(4,3)

// Post-fix:
val hitVs2: Bool = ((checkOH & maskForVs2) === 0.U || record.bits.gather) && ...
```

`elementMask` 按顺序处理追踪进度（步骤 0 完成 → bit 0 置位），但 `vrgather` 的读取顺序由索引向量决定（非顺序）。当索引为 `{vl-1, ..., 1, 0}`（反转）时：
- 步骤 0 读取 v8[127]（高地址）
- 步骤 127 读取 v8[0]（低地址）
- 但 elementMask 在步骤 0 完成后标记 bit 0 为 "done"
- WriteCheck 误判 "元素 0 已读完"，允许 vadd 写入 v8[0]
- 步骤 127 读到被破坏的 v8[0]

### 触发条件

1. **DLEN=128**：每 lane 每周期处理 1 个 SEW=32 元素 → gather 需要 ~128 周期 → 足够大的 chaining 窗口
2. **反转索引** `{127, 126, ..., 0}`：高编号步骤读取低编号元素 → elementMask 误判
3. **Back-to-back 发射**：gather (special slot) 和 vadd (normal slot) 可并发执行

blastoise 配置（DLEN=256, VLEN=512, 16 elements）无法触发，因为 gather 仅需 ~2 周期，窗口太小。

### eDSL 测试生成

```scala
// GatherWARBug.scala — 一行调用生成完整测试
object GatherWARBugTest extends RVGenerator:
  val sets = Seq(isRVI(), isRVV())
  def constraints() =
    textStart()
    war_GatherxALU_full()   // 生成配置+初始化+WAR对+验证
    raw("ret")
```

```bash
mill rvprobe.runMain me.jiuyang.rvprobe.cases.chaining.GatherWARBug /tmp/GatherWARBug.S
```

### RTL 仿真验证（已确认 ✅）

在 `benchmark_dlen128_vlen4096_fp` 配置上运行 pre-fix/post-fix 对比仿真。通过 RTL 事件日志（VRF write trace）验证 gather 输出的正确性。

**验证方法**：T1 的 VCS emulator 输出逐周期 VRF 写入日志（`rtl-event.jsonl`），记录每次 VRF 写入的目标寄存器、元素偏移、数据值和时钟周期。我们检查 gather 输出寄存器（v1）的写入值是否与预期一致。

**Pre-fix (097ec761) — bug 触发**：
```
gather (issue_idx=5) 写 v1: cycle 260-322
vadd  (issue_idx=6) 写 v8: cycle 267-329（毒化为 0x7CE=1998）
时序重叠: cycle 267-322（55 周期窗口内两条指令同时操作 v8）

gather 输出 v1 的最后 44 个元素 = 0x7CE（1998，vadd 写入的毒化值）
gather 输出 v1 的正确值应为 0-43（v8 反转后的低位元素）
→ 128 个元素中 44 个值错误
```

**Post-fix (50986c9d) — bug 已修复**：
```
gather 输出 v1 的最后 4 个元素 = {3, 2, 1, 0}（正确的反转序列）
128 个元素中 0 个值错误
```

**Pre-fix vs Post-fix 对比**（RTL 事件日志，gather 最后一组写入）：
```json
// Pre-fix: gather 读到 vadd 写入的毒化值（应为 {3,2,1,0}，实际为 {1998,1998,1998,1998}）
{"event":"VrfWrite","issue_idx":5,"vd":1,"offset":31,"data":"000007ce","lane":0,"cycle":322}
{"event":"VrfWrite","issue_idx":5,"vd":1,"offset":31,"data":"000007ce","lane":1,"cycle":322}
{"event":"VrfWrite","issue_idx":5,"vd":1,"offset":31,"data":"000007ce","lane":2,"cycle":322}
{"event":"VrfWrite","issue_idx":5,"vd":1,"offset":31,"data":"000007ce","lane":3,"cycle":322}

// Post-fix: gather 正确读到原始 v8 值（WriteCheck 阻塞了 vadd 直到 gather 完成）
{"event":"VrfWrite","issue_idx":5,"vd":1,"offset":31,"data":"00000003","lane":0,"cycle":322}
{"event":"VrfWrite","issue_idx":5,"vd":1,"offset":31,"data":"00000002","lane":1,"cycle":322}
{"event":"VrfWrite","issue_idx":5,"vd":1,"offset":31,"data":"00000001","lane":2,"cycle":322}
{"event":"VrfWrite","issue_idx":5,"vd":1,"offset":31,"data":"00000000","lane":3,"cycle":322}
```

### 复现方法论说明

验证采用 **RTL 事件日志分析**（VRF write trace comparison）方法：在 pre-fix 和 post-fix 两个 T1 版本上运行相同测试，对比 VRF 写入值的正确性。这是硬件验证中标准的波形/trace 分析方法。

T1 的 `t1emu` cosim（Spike + RTL 差分测试）由于其 VRF 状态同步机制，在 retire 边界会将 Spike 参考值写回 RTL 上下文，导致 chaining 级别的数据损坏无法跨指令边界传播到 cosim 的比较点。这是 cosim 架构的已知限制，不影响 RTL trace 级别的 bug 确认。

### 一键复现

```bash
export VC_STATIC_HOME=/opt/synopsys/vc_static/V-2023.12
export SNPSLMD_LICENSE_FILE=27000@license0.caat
export DWBB_DIR=/opt/synopsys/prime/V-2023.12-SP5/dw

./scripts/reproduce-gather-war.sh
```

## 其他可复现 Bug

| Bug | Fix Commit | Pre-fix | 矩阵格 |
|-----|-----------|---------|--------|
| v0 mask RAW | `0dd6e504` | `1437af0e` | D2×C1 |
| mask unit 完成状态 | `44d0d3b4` | `6b943dd3` | D1×C3 |
| store WAR | `26837bc1` | `9ca094d0` | D3×C2 |
| widen 寄存器组 | `787b974f` | `641207d7` | D1×C5 |
| slide RAW | `3a8b08b9` | `d3edb529` | D1×C7 |
| store RAW hazard | `d93fa2a2` | `4c45c46c` | D1×C4 |
| store WAR hazard | `e7c52f00` | `e68853c4` | D4×C2 |
| gather16 WAR | `6a817b80` | `50986c9d` | D3×C6 (vs1) |

注意：早期 commit（2023 年）的 T1 flake 结构和 cosim 接口与当前不同，可能需要调整构建命令。

## 黑盒触发概率分析

| 缺陷 | 触发所需条件 | 随机概率上界 | RVProbe |
|------|------------|------------|---------|
| D2×C1 v0 mask RAW | vd=v0 (1/32) × vm=0 (1/2) | ≈ 1.6% | 100% |
| D3×C6 gather WAR | gather (3/300) × 寄存器重叠 (1/32) × 反转索引 | ≈ 0.016% | 100% |
| D3×C2 store WAR | store (1/2) × 寄存器重叠 (1/32) | ≈ 1.6% | 100% |

注：上界仅考虑指令选择与寄存器分配，未计入时序窗口（chaining 窗口内才能触发）、vl/SEW/LMUL 配置（需要 DLEN=128+VLEN=4096）及 VRF 初始状态。实际触发���率远低于此。

## Status

| 项目 | 状态 |
|------|------|
| OM Pipeline (T1Constraints 生成) | ✅ 完成 |
| ChainingLib (5种依赖 × 15个组合方法) | ✅ 完成 |
| `war_GatherxALU_full()` 完整测试生成 | ✅ 完成 |
| 15 格矩阵入口 (ChainingMatrix + ChainingT1Test) | ✅ 完成 |
| ReverseBug 白盒案例 | ✅ 完成 |
| 向量指令汇编��染 | ✅ 修复 |
| T1 VCS ��真（post-fix, 14格, blastoise） | ✅ 通过，14590 cycles |
| D3×C6 Bug 复现（RTL 事件日志确认） | ✅ **44 corrupted writes** |
| Bug 复现脚本 | ✅ `scripts/reproduce-gather-war.sh` |

## Files

```
zaozi/rvprobe/src/cases/chaining/
├── ChainingLib.scala        # 矩阵约束库（基础方法 + 15格 + war_GatherxALU_full）
├── ChainingMatrix.scala     # 15格入口（standalone 格式，含 HTIF）
├── ChainingT1Test.scala     # 14格入口（T1兼容格式，test()入口���
├── GatherWARBug.scala       # D3×C6 完整测试（eDSL 驱动）
└── ReverseBug.scala         # Reverse 信号白盒案例

zaozi/rvprobe/paper/exp2/
├── EXP2.md                  # 本文件
└── evidence/
    ├── GatherWAR_vv_xor.S           # 触发 bug 的手写汇编（对照）
    ├─��� pre_fix_rtl_events.jsonl     # Pre-fix RTL 事件日志（含 corrupted writes）
    ├── post_fix_rtl_events.jsonl    # Post-fix RTL 事件日志（正确）
    └── analysis.txt                 # 数据分析摘要

zaozi/rvprobe/src/constraints/
└── T1Constraints.scala      # 自动生成的 ~54 个微架构谓词

zaozi/rvprobe/src/RVGenerator.scala  # 向量指令渲染修复

scripts/
├── reproduce-gather-war.sh  # D3×C6 一键复现
└── run-exp2.sh              # Exp2 完整流程
```
