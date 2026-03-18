# rvprobe 开发进展

## 模块概述

rvprobe 是基于两阶段 SMT 约束求解的 RISC-V 指令生成器，用于生成满足特定约束条件的 RISC-V 汇编程序。

## 已完成功能

### 核心求解引擎 (RVGenerator)
- 两阶段 SMT 求解架构：Stage 1 求解操作码（nameId），Stage 2 求解指令参数
- 支持 GAS 汇编输出（AsmMode）和二进制输出（BinMode）
- NOP 填充支持

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
- 分裂立即数自动合并：`ImmMergeRule` 将 rvdecoderdb 的 hi/lo 字段（imm12hi/lo、bimm12hi/lo）合并为单一语义参数（offset），如 `sd(rs1, rs2, offset)` 而非 `sd(imm12lo, rs1, rs2, imm12hi)`

### 覆盖率约束 (CoverApi.scala)
- `coverBins()` — 确保所有 bin 被覆盖
- `coverNoHazard()` — 无数据冒险
- `coverRAW()` / `coverWAR()` / `coverWAW()` — RAW/WAR/WAW 冒险检测

### 约束自动生成
- **RVConstraints.scala** — 从 rvdecoderdb 自动生成范围/等值/存在性约束（6000+ 行）
- **T1Constraints.scala** — T1 核心特定约束（自动生成）
- **SpecConstraints.scala** — 手写的规范强制约束（FENCE、C.ADDI4SPN、C.ADDIW 等）

### 压缩指令 (RVC)
- 汇编器中支持压缩指令处理

### 测试与基准测试
- 7+ 测试文件：RecipeTest、AsmApiTest、StatementTest、NopTest、SpecConstraintsTest、RVDecoderdbApiTest、PaperTest
- JMH 基准测试（L1/L2/L3 复杂度级别）

### 示例用例
- **RV32I.scala** — 35+ RVGenerator 实现（Slli、Srai、Add 等）
- **Program.scala** — 完整 RISC-V 程序示例（陷阱处理、页表配置、用户态代码）

## 近期提交 (rvprobe 分支)

| 提交 | 日期 | 内容 |
|------|------|------|
| 5d5181b | 2026-03-18 | 合并分裂立即数（ImmMergeRule），AsmApi 生成 GAS 风格语义参数 |
| 347ceac | 2026-03-17 | 增强 API 与约束脚本，更新 AsmApi 和 RVConstraints 生成逻辑 |
| cd9348b | 2026-03-17 | 添加 dword/zero/balign 指令，标签引用支持，Statement 枚举更新 |
| 6bf8095 | 2026-03-16 | 重构 UpdateAsmApi 和 UpdateRVConstraints 脚本 |
| 4241b63 | 2026-03-15 | 简化 UpdateRVConstraints 指令类型生成 |
| f242553 | 2026-03-14 | 添加压缩指令支持 |

## 待完成 / 已知限制

- [ ] 需要支持额外扩展集：Zfa（fround.s）、Zicsr（csr*）、System、S 模式
- [ ] balign/zero 指令在最终汇编处理中尚未完全建模
- [ ] 标签替换正则仅处理 bimm/jimm，尚未覆盖 imm12
- [ ] jalr/jr 因伪指令问题在 AsmApi 中被跳过
