# ICCAD 2026 改论文进展

截止日期：2026-04-14

## DAC26 被拒核心问题

1. Contribution 与 RQ 不对齐
2. "Directed beats random is obvious" — 缺乏方法间对比
3. 缺少 SOTA 对比（DirectFuzz 等）
4. 工程细节过多（5-layer arch, FFI, MLIR）

## 改写计划

### 1. 重写 Title + Abstract + Contributions
- 标题：从 "eDSL-based Framework" 改为突出 "sequence-level constraint abstraction"
- Abstract：去掉 Scala/MLIR/FFI 工程术语，聚焦 impedance mismatch 论点
- Contributions 对齐 RQ：
  - C1 → RQ1：序列级约束表达力对比（三种方法填同样的 hole）
  - C2 → RQ2：白盒约束组合发现 T1 bug
  - C3 → RQ3：性能/可扩展性
- [ ] 完成

### 2. 重写 Section 4.1（Exp 1）为三方对比 ⬅ 最高优先级
- 替换 "Phase 1 random + Phase 2 RVProbe" 叙事
- 新结构：
  - Phase 1: riscv-dv 饱和（224K 指令，83.23% avg covergroup score，63 个 hazard hole）
  - Phase 2: 三种方法填 hole
    - 手写 asm：128 行
    - riscv-dv Python extension：~234 行
    - RVProbe eDSL：63 行 call site + 90 行 library
  - 新 Table：LOC、覆盖保证、格式适配、可扩展性
  - 关键论点：hole 本质是序列级的，手写/riscv-dv 需心算 hazard 排除条件，RVProbe `coverWAR()` 自动处理
- 待完成：
  - [ ] 跑 coverage 验证（手写 asm 喂 riscv-dv coverage tool）
  - [ ] 跑 coverage 验证（RVProbe 产物喂 riscv-dv coverage tool）
  - [ ] 撰写 Section 4.1 正文

### 3. 大幅削减 Section 3（Design）
- 砍掉 5-layer architecture 细节、FFI/MLIR 实现细节
- 保留：eDSL primitive table（Table 1）、一个 sequence-level API code example
- 压缩到 ~1 页
- [ ] 完成

### 4. 提升 Section 4.2（T1 Bug）为正式 RQ2
- 从 "case study" 提升为 RQ2：白盒约束组合能否系统性暴露实现相关缺陷？
- 补充 CRV 不太可能覆盖的概率估算
- **新增**：重做 T1 实验——实现一个真实的 T1 测试用例，使用白盒信号注入
  - [ ] 从 T1 OM 提取白盒信号
  - [ ] 实现 RVProbe 测试用例
  - [ ] 跑仿真验证
- [ ] 完成

### 5. 重写 Related Work
- 添加 DirectFuzz、RFUZZ 对比定位
- DirectFuzz = RTL-level fuzzing（需仿真反馈），RVProbe = ISA-level directed（纯约束求解）
- 补充 riscv-dv 作为 baseline 的定位
- [ ] 完成

### 6. 更新会议元信息
- DAC → ICCAD 模板/格式
- 检查页数限制
- 更新引用格式
- [ ] 完成

### 7. 代码匿名化
- 将代码放到匿名平台上（anonymous GitHub 或类似）
- [ ] 完成

## Exp 1 已完成工作

- [x] Phase 1: riscv-dv 饱和实验（224K 指令），覆盖率报告在 `/root/riscv-dv/cov_out_exp1_rv32i/CoverageReport.txt`
- [x] Phase 2: Hole 分析 — 21 条指令 × 3 种 hazard = 63 个 hole
- [x] Phase 3: 三种方法实现
  - 手写汇编：`paper/exp1/handwrite.S`（128 行）
  - riscv-dv Python extension：`paper/exp1/riscv_dv_hazard_stream.py`（~234 行）
  - RVProbe eDSL：`paper/exp1/rvprobe.scala` + `paper/exp1/rvprobe.S`
- [ ] Coverage 验证（手写 asm + RVProbe 产物）

## 时间线

| 阶段 | 内容 | 截止 |
|------|------|------|
| Week 1 (3/30-4/5) | Exp1 coverage 验证 + 重写 Sec 4.1 + 重写 Contributions/Abstract | 4/5 |
| Week 2 (4/6-4/12) | 削减 Sec 3 + 提升 Sec 4.2 + 重写 Related Work + 更新模板 | 4/12 |
| 4/13-14 | 全文 polish + coauthor review | 4/14 |
