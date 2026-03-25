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
- 8 个测试类（65 个测试）：RecipeTest、AsmApiTest、StatementTest、NopTest、SpecConstraintsTest、RVDecoderdbApiTest、PaperTest、CacheCaseTest
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
├── output/
│   ├── asm/                  — 预生成的 .S 汇编文件（镜像源码目录结构）
│   │   ├── privilege/Program.S
│   │   ├── cache/*.S
│   │   └── coverage/*.S
│   └── elf/                  — 由脚本从 `asm/` 批量编译出的 `.elf` / `.bin` / `.objdump`
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
- **伪指令 `li`/`la`**：已在 `Api.scala` 中作为 `Statement.Pseudo` 发射；`li(rd, imm: Long)` 立即数在 Scala 侧计算，输出 hex；`la(rd, symbol: String)` 加载符号地址。复杂表达式如 `~(3L << 11)` 直接用 Scala 表达式
- **fence 参数**：`fence(x0, x0, succ, pred, fm)` — succ/pred 用位掩码（R=1, W=2, RW=3, IORW=15）
- **同一文件多个 @main**：按功能分类组织，测试同类缓存行为的不同侧面放在同一文件中
- **公共模式提取**：重复的 prologue/epilogue/data section 应提取为库函数（参考 `CacheProbeLib`）
- **import 风格**：避免在函数签名中使用全限定类型名（如 `org.llvm.mlir.scalalib.capi.ir.Context`），应在文件顶部 import

### 测试运行注意事项

- **rvprobe 单元测试优先使用 forked 模式**：由于测试会加载 MLIR/CIRCT native 库，应优先运行 `mill rvprobe.tests.testForked`；`testLocal` 可能因 `java.library.path` 未继承完整 native 路径而失败

### VM 测试用例设计原则

- **代码页与数据页必须分离权限**：测试页表权限时，S-mode 代码页（包含 `s_code` 标签）必须保持可执行（X=1），仅对数据页（`buf`）施加受限权限。否则一进 S-mode 取指就触发 INSN_PAGE_FAULT(12) 死循环
- **使用两级页表（root→L2 megapage）**：`setupCodeDataPageTable(flags)` 创建 L2[0]（代码兆页，full perms）和 L2[1]（数据兆页，自定义权限），buf 通过 `.balign 0x200000` 放入第二个兆页
- **INSN_PAGE_FAULT 无法通过 mepc+=4 恢复**：当整个区域不可执行时，mepc+4 仍在同一区域。使用 mscratch 保存恢复地址，trap handler 对 INSN_PAGE_FAULT 改为读取 mscratch 返回 M-mode

### 页表静态验证（PageTableModel）

`PageTableModel` 在生成时自动验证代码区域的页表权限，防止 S-mode 代码页缺少 V+X+A 导致取指 fault 死循环。

**工作机制**：
- `textStartWithTrap()` 创建新的 `PageTableModel` 实例（ThreadLocal，线程安全）
- `mapGigapageIdentity(flags)` / `setupCodeDataPageTable(flags)` 注册映射到 model
- `switchToSMode(label)` 自动调用 `model.verifyCodeFetchable(label)` 检查代码区域 V+X+A
- 未注册映射时（非 privilege 测试）验证为 no-op

**局限性**：仅检查初始页表配置，不追踪运行时 PTE 修改（如 VMSfenceVmaRemap 中的 PTE 更新）。

**未来扩展方向**：对于 `cases/coverage/` 中 SMT 求解的指令，可通过 `addCrossIndexConstraint` 建模内存访问约束（fetch→X，load→R+A，store→W+A+D），使 Z3 在生成时报 UNSAT 而非运行时死循环

### 目录组织原则

- 源码（.scala）和产物（.S/.elf/.bin/.objdump）分离：源码在 `cases/<category>/`，汇编产物在 `cases/output/asm/<category>/`，编译产物在 `cases/output/elf/<category>/`
- 目录以被测功能命名（`cache/`、`privilege/`），不要嵌套，不加 `-probes` 后缀

## 变更记录

| 日期 | 内容 |
|------|------|
| 2026-03-24 | 清理测试 epilogue：finish() 吸收 fail()；新增 HTIFLib.assertEq 通用断言；verifyTrapCause 基于 assertEq 构建；删除 Program.scala；消除所有手写 bne+j("exit") 模式 |
| 2026-03-24 | 引入 freshReg() 符号化寄存器变量替代 FreeReg 哨兵；la/li 支持符号化 rd；Pseudo 指令参与 Stage 2 求解；自动 pairwise distinct；重构 10 个 cache 测试用例使用 freshReg() 数据流 |
| 2026-03-24 | 统一 API 职责划分：HTIFLib 管 HTIF 协议，PrivilegeProbeLib 管特权模式；删除 8 个冗余转发；textStartWithTrap(recordCause) 统一 trap handler 选择；新增 verifyTrapCause helper；run.py 重构+生成前清理 output；Z3 种子默认 0；spike 默认 nix run |
| 2026-03-24 | 修复 5 个 VM 测试用例的代码页/数据页权限混叠问题：引入两级页表（setupCodeDataPageTable）分离代码和数据权限，trap handler 增加 mscratch 恢复机制处理 INSN_PAGE_FAULT |
| 2026-03-24 | 重构 privilege 测试用例的内存访问处理，更新 ELF/binary 产物 |
| 2026-03-24 | 更新脚本文档，说明产物再生成的三阶段流程 |
| 2026-03-24 | 新增 privilege 测试用例并更新已有用例 |
| 2026-03-24 | 移除 RV32I 测试用例实现；重构退出序列改用 HTIFLib 方法 |
| 2026-03-23 | 新增 fence/atomic 缓存测试，更新 CacheCaseTest |
| 2026-03-23 | 添加 RVM.S 覆盖率汇编，删除过时的 Program.S |
| 2026-03-23 | coverage 输出重构为可链接 bare-metal 程序，linker script 增加 text/data 分段与 `_start` 入口约束 |
| 2026-03-23 | output 目录拆分为 asm/elf，新增 `rvprobe/scripts/asm2elf.py` 将 `.S` 批量编译为 `.elf` / `.bin` / `.objdump` |
| 2026-03-23 | 删除重复的 probes 目录并迁移 roundtrip 脚本到 privilege；新增 output/asm/coverage 预生成汇编 |
| 2026-03-21 | cache case 抽取公共几何/sets helper，`li/la` 改为 `Statement.Pseudo`，新增 20 个 cache golden tests |
| 2026-03-19 | 重构 AsmApi 寄存器参数类型：Register → Referable[SInt]，引入 FreeReg 哨兵值，cache 探针使用 instruction(i).rd 实现跨指令数据流 |
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

## TODO

- [x] 重新审阅 `rvprobe/src/cases/` 中的测试用例，重构 API 使其更统一、易读
  - 修复 5 个 VM 测试用例（VM4KBPage、VMReadOnlyPage、VMNoExecutePage、VMADAccessBit、VMSfenceVmaStale）的代码页/数据页权限混叠
  - 引入 `setupCodeDataPageTable` / `pageTableDataTwoLevel` 两级页表公共 helper
  - trap handler 增加 mscratch 恢复机制处理 INSN_PAGE_FAULT 死循环
- [ ] 将 rvprobe 发布为 Maven 包，支持 Chisel 等外部项目直接依赖引入（如 cases/cache 中的参数化方法可直接接受 Chisel 侧的 cache 配置）
- [x] 探索在 SMT 约束求解阶段建模页表语义，在生成时而非运行时捕获权限错误
  - 实现 `PageTableModel` 静态验证层：`switchToSMode` 自动检查代码区域 V+X+A
  - 新增 `mapGigapageIdentity` helper，替代手工 `la/li/sd` 页表配置
  - 迁移 6 个 gigapage 测试用例使用新 helper，获得自动验证
  - 8 个 PageTableModel 单元测试覆盖正反例
