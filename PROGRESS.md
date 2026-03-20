# rvprobe 开发进展

## 模块概述

rvprobe 是基于两阶段 SMT 约束求解的 RISC-V 指令生成器，用于生成满足特定约束条件的 RISC-V 汇编程序。

## 已完成功能

### 核心求解引擎 (RVGenerator)
- 两阶段 SMT 求解架构：Stage 1 求解操作码（nameId），Stage 2 求解指令参数
- 支持 GAS 汇编输出（AsmMode）和二进制输出（BinMode）
- NOP 填充支持
- Z3 随机种子注入（`smt.random_seed` / `sat.random_seed`），每次求解产生不同结果

### 约束类型系统 (Tpe.scala)
- 基于 opaque type 的类型安全约束表示：Constraint、InstConstraint、OpcodeConstraint、SetConstraint、ArgConstraint
- `SpecFor[T]` typeclass 关联规范强制约束与指令类型

### DSL API (Api.scala)
- `instruction[T]()` — 创建带约束的指令
- `sequence(start, end)` — 获取指令索引范围
- `distinct()` — 跨指令字段唯一性约束
- `isRV64G()` / `isRV64GC()` — 指令集扩展约束

### 汇编指令支持 (Statement.scala)
- 完整的汇编语句 ADT：Inst、Label、Section、Global、Align、Word、Dword、Zero、Balign、Pseudo、Raw、LabelRef
- 标签引用与立即数替换（bimm/jimm）

### 汇编风格 API (AsmApi.scala, 自动生成)
- 为每条 RISC-V 指令生成便捷函数，如 `addi(rd, rs1, imm12)`
- 分支/跳转指令自动生成标签重载版本
- 分裂立即数自动合并：`ImmMergeRule` 将 hi/lo 字段合并为单一语义参数，如 `sd(rs1, rs2, offset)`

### 覆盖率约束 (CoverApi.scala)
- `coverBins()` — 确保所有 bin 被覆盖
- `coverNoHazard()` — 无数据冒险
- `coverRAW()` / `coverWAR()` / `coverWAW()` — RAW/WAR/WAW 冒险检测

### 约束自动生成
- **RVConstraints.scala** — 从 rvdecoderdb 自动生成范围/等值/存在性约束（6000+ 行）
- **T1Constraints.scala** — T1 核心特定约束（自动生成）
- **SpecConstraints.scala** — 手写的规范强制约束（FENCE、C.ADDI4SPN、C.ADDIW 等）

### 测试与基准测试
- 7 个测试类（41 个测试）：RecipeTest、AsmApiTest、StatementTest、NopTest、SpecConstraintsTest、RVDecoderdbApiTest、PaperTest
- JMH 基准测试（L1/L2/L3 复杂度级别）

### 测试用例 (cases/)

目录结构：
```
cases/
├── privilege/
│   ├── PrivilegeProbeLib.scala — 公共辅助库（CSR/Cause 常量、trap handler、PMP、Sv39、模式切换）
│   ├── Program.scala          — 集成测试（FP + 页表 + 模式切换，已重构使用 PrivilegeProbeLib）
│   ├── PMPNapot.scala         — PMP NAPOT 区域允许/拒绝/越界（3 个 @main）
│   ├── PMPTor.scala           — PMP TOR 边界测试（2 个 @main）
│   ├── PMPPermissions.scala   — PMP R/W/X 权限组合（3 个 @main）
│   ├── PMPLock.scala          — PMP 锁定位测试（2 个 @main）
│   ├── VMSv39Basic.scala      — Sv39 恒等映射 load/store（2 个 @main）
│   ├── VMPageSizes.scala      — gigapage/megapage/4KB 三级页表（3 个 @main）
│   ├── VMPermissions.scala    — PTE 权限位测试（3 个 @main）
│   ├── VMADFlags.scala        — A/D 位行为（2 个 @main）
│   └── VMSfenceVma.scala      — sfence.vma TLB 失效（2 个 @main）
├── cache/
│   ├── CacheProbeLib.scala    — 公共汇编片段库
│   ├── DCacheBasic.scala      — hit/miss、line fill、跨行（A, B）
│   ├── DCacheReplacement.scala — 冲突替换、LRU（C, D）
│   ├── DCacheWrite.scala      — 写命中/失效/回写（E, F, G）
│   ├── DCachePartialWrite.scala — SB/SH/SW 混合字节掩码（H）
│   ├── DCacheStoreLoad.scala  — store-load 转发（M, N）
│   ├── DCacheCapacity.scala   — 顺序/步长/容量扫描（J, K, L）
│   ├── ICache.scala           — 顺序取指、跳转冷区、自修改+fence.i（O, P, Q）
│   ├── DCacheFence.scala      — fence rw/tso（R）
│   └── DCacheAtomic.scala     — lr/sc、AMO（U）
├── coverage/
│   ├── RV32I.scala            — RV32I 指令覆盖率（27 条指令，coverBins + 冒险覆盖）
│   ├── RV64I.scala            — RV64I word 操作覆盖率（addw/subw/sllw/srlw/sraw/addiw/slliw/srliw/sraiw）
│   ├── RVM.scala              — M 扩展覆盖率（mul/div/rem 全系列 + 64 位 word 变体）
│   └── RVLoadStore.scala      — 加载/存储覆盖率（lb/lbu/lh/lhu/lw/lwu/ld/sb/sh/sw/sd）
├── output/                    — 预生成的 .S 汇编文件（镜像源码目录结构）
│   ├── privilege/Program.S
│   └── cache/*.S
```

每个目录以被测功能命名（cache、privilege），未来可扩展更多功能目录（如 mmu、vector 等）。

## 开发备忘

### 指令集扩展与 sets 约束对应关系

编写 RVGenerator 时，`val sets` 必须包含所有用到的指令所属的扩展集，否则 SMT 求解会返回 Unsat。常见易错映射：

| 指令 | 需要的扩展集 | 错误用法 |
|------|-------------|---------|
| `rdcycle` | `isRVZICNTR()` | ~~`isRVZICSR()`~~ |
| `fenceI` | `isRVZIFENCEI()` | ~~`isRVZICSR()`~~ |
| `fenceTso` | `isRVZIFENCEI()` | — |
| `lrW`/`scW`/`amoaddW` | `isRVA()`（已含于 `isRV64GC()`） | — |

### DSL 使用注意事项

- **store 指令参数顺序**：`sw(base_rs1, value_rs2, offset)` — base 在前，value 在后
- **伪指令 `li`/`la`**：已在 `Api.scala` 中实现为 raw 辅助函数。`li(rd, imm: Long)` 立即数在 Scala 侧计算，输出 hex；`la(rd, symbol: String)` 加载符号地址。复杂表达式如 `~(3L << 11)` 直接用 Scala 表达式
- **fence 参数**：`fence(x0, x0, succ, pred, fm)` — succ/pred 用位掩码（R=1, W=2, RW=3, IORW=15）
- **同一文件多个 @main**：按功能分类组织，测试同类缓存行为的不同侧面放在同一文件中
- **公共模式提取**：重复的 prologue/epilogue/data section 应提取为库函数（参考 `CacheProbeLib`）
- **import 风格**：避免在函数签名中使用全限定类型名（如 `org.llvm.mlir.scalalib.capi.ir.Context`），应在文件顶部 import

### 目录组织原则

- 源码（.scala）和产物（.S）分离：源码在 `cases/<category>/`，产物在 `cases/output/<category>/`
- 目录以被测功能命名（`cache/`、`privilege/`），不要嵌套，不加 `-probes` 后缀

## 变更记录

| 日期 | 内容 |
|------|------|
| 2026-03-19 | AsmApi 返回 Int idx 支持 CoverApi；提取 CoverageLib 重构覆盖率测试 |
| 2026-03-19 | 新建 cases/coverage/ 目录，迁移 RV32I，添加 RV64I/RVM/RVLoadStore 覆盖率测试 |
| 2026-03-18 | 添加 23 个特权模式测试探针（PMP + 虚拟内存），提取 PrivilegeProbeLib，重构 Program.scala |
| 2026-03-18 | 添加 `li(Long)`/`la(String)` 伪指令到 Api.scala，消除所有 `raw()` 的 li/la 调用 |
| 2026-03-18 | 目录重命名：cache-probes→cache、probes→privilege，包名同步更新 |
| 2026-03-17 | 实现 20 个缓存验证探针测试用例，提取 CacheProbeLib，重组目录结构 |
| 2026-03-17 | Z3 求解结果随机化（smt.random_seed / sat.random_seed） |
| 2026-03-17 | Round-trip 测试框架（113 条指令匹配），修复 asm2dsl 和 RVGenerator 渲染 |
| 2026-03-17 | 过滤 ISA 后缀指令变体（如 bclri.rv32），减少 13 条重复指令 |
| 2026-03-16 | 修复 getInstructions() 排序一致性，RecipeTest 改为语义断言 |
| 2026-03-16 | 合并 RVConstraints 为单文件，添加 asm2dsl.py 脚本 |
| 2026-03-16 | 合并分裂立即数（ImmMergeRule），AsmApi 生成 GAS 风格语义参数 |
| 2026-03-15 | 添加 dword/zero/balign 指令，标签引用支持 |
| 2026-03-15 | 重构 UpdateAsmApi 和 UpdateRVConstraints 脚本 |
| 2026-03-14 | 添加压缩指令支持 |
