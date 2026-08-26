# DATE 2027 — 实验设计草案 (2026-08-26)

对齐 `paper-outline-v2.md` 的贡献 3（Systematic Evaluation）：四个方法臂 × 四个评测维度，
外加支撑实验所需的 `date` 分支基础设施清单。

## 0. 论文逻辑的三个必须被实验支撑的主张

| # | Intro 中的主张 | 必须的实验证据 |
|---|----------------|----------------|
| C1 | 类型系统检查验证对象及关系的合法性（贡献 1） | 量化：LLM 生成错误被类型检查拦截的比例、错误反馈后的修复轮数/成功率 — 对比 SV constraint 的"仿真期才暴露"路径 |
| C2 | 统一表达和组合值/对象/时序/状态多维语义（第 3-4 段） | 任务集必须覆盖全部四维，且含 baseline 表达不了或表达笨拙的 case |
| C3 | SMT 求解生成满足约束的激励（贡献 2） | 求解成功率与时间；含随机/拒绝采样难命中的窄解空间 case |

对 reviewer 攻击面的预判：
- HAVEN 是 UVM 全组件合成（agent/driver/monitor + 协议 DSL 序列），我们的比较边界要
  收敛到"验证意图 → 激励"这一子问题，比较章节要明说边界，否则 apples-to-oranges。
- 现 `ut` 分支只有组合逻辑例子（AbsVal）；C2 的时序主张需要时序 DUT 案例落地。

## 1. 方法臂（4 个）

| 臂 | 表示 | 流程 | 落地方式 |
|----|------|------|----------|
| A0 Direct Stimulus | 具体激励值 | LLM → stimulus.txt/JSON → Model B testbench 回放 | 现有 Model B 通路即可 |
| A1 Direct SV Constraint | SV class + constraint | LLM → SV 约束 → `randomize()` → 激励 | **需要带约束求解的仿真器**（VCS/Xcelium；Verilator 不支持）— 待定 license |
| A2 HAVEN | 协议 DSL + Jinja2 模板 | LLM agents → DSL 序列 → 模板展开 → UVM | 优先跑其公开实现；不可得则在共同 IP 上引用其报告数字并缩小比较维度 |
| A3 Ours | zaozi typed DSL（UT 模块 + SVA Assume/Assert） | LLM → typed DSL → 类型检查（scalac）→ CIRCT lowering → SMT/btor2 → witness → stimulus | 需补 SMT 腿（见 §4） |

公平性控制：同一 LLM 组（至少 2 个：一个 frontier + 一个开源）、相同任务描述文本、
相同 k（每任务采样数）与温度、相同错误反馈轮数上限（如 3 轮）。每臂的 prompt 只换
表示规格说明，不换任务语义。

## 2. 任务集（三档复杂度，覆盖 C2 的四个语义维度）

- **T1 值约束**（单对象字段级）：奇偶/范围/对齐/位模式。DUT：AbsVal、Incrementer。
  各臂都能表达 — 用于 correctness/cost 主对比。
- **T2 跨对象与全局约束**（序列级关系）：元素唯一、递增、求和上限、RAW 型依赖
  （后包字段引用前包结果）。DUT：Queue/dwbb FIFO。SV constraint 表达开始吃力
  （跨 randomize 调用的关系需手工 state），HAVEN 模板无对应 step type。
- **T3 时序与状态约束**：握手协议合法序列（Decoupled ready/valid）、burst 长度与
  间隔、到达特定状态机状态的输入序列（可达性 → BMC 展开求解）。DUT：Decoupled
  接口模块 + 移植 1-2 个 HAVEN 用过的 Wishbone/AXI4-Lite IP（连接到 A2 的比较）。

每任务提供：自然语言验证需求（喂 LLM 的唯一输入）、golden checker（参考 SVA/断言，
判定"激励是否满足 intent"，独立于各臂自身的表示）、functional covergroup 定义。

## 3. 指标（对齐论文四维度）

1. **生成成本**：LLM tokens、调用次数、到 first-valid 的迭代数、端到端 wall time。
2. **生成正确性**：
   - 语法/类型层 pass@k（A3 = scalac+类型检查；A1 = 编译；A0 = 格式解析）
   - 语义正确率：golden checker 通过率（激励真的满足 intent）
   - 修复效率：错误信息反馈后的修复成功率与轮数（直接支撑 C1）
3. **约束求解能力**：每档任务求解成功率、求解时间；专设窄解空间 case
   （解密度 < 1e-6，拒绝采样近乎不可行）与长序列跨包等式 case。
4. **覆盖率收敛**：覆盖率-vs-激励数曲线；到 90%/back-to-back 100% 所需激励数；
   Verilator functional coverage（covergroup 由 golden 定义统一注入）。

## 4. `date` 分支需要补的基础设施（按依赖顺序）

1. **SMT 求解腿**（核心，C3）：UT 模块的 Assume → CIRCT lowering（verif.assume，
   `harvest` 分支 utlib 的 MlirInvariant/lowering 管线可移植）→ btor2 →
   btormc/circt-bmc 取 witness/model → 解码为 stimulus.txt（对接 Model B testbench）。
   `formalUT` 分支（"unify check + generate on circt-bmc"）是另一个移植源，先评估
   两者取哪个。时序 case 需要 BMC 展开 k 步取输入序列。
2. **覆盖率腿**：从 `harvest` utlib 移植 verilator coverage.dat 解析 +
   覆盖循环（closeCoverageByDiversity 的采集部分即可，diversity 策略不是本文重点）。
3. **时序 DUT + UT 案例**：T2/T3 的 3-4 个 UT 模块（FIFO、Decoupled、1-2 个移植 IP）。
4. **LLM 实验 harness**（Python，`experiments/` 目录）：各臂 prompt 模板、k 采样、
   错误反馈修复循环、打分与曲线绘制脚本；结果落 CSV，图表脚本可重跑。

## 5. 待作者拍板

1. **A1 的仿真器**：有 VCS/Xcelium license 吗？没有的话 A1 用什么落地
   （开源里没有完整 SV constraint solver；可选：缩小 A1 的约束子集用
   pyvsc/cocotb 近似，并在 threats-to-validity 里声明）？
2. **benchmark 口径**：以 zaozi 原生 DUT 集为主 + 移植 1-2 个 HAVEN IP（推荐，
   工作量可控），还是全面对齐 HAVEN 的 19 IP（需要 SV blackbox 导入 zaozi，工作量大）？
3. **LLM 与预算**：用哪些模型、每任务 k 取多少、修复轮上限？

## 参考

- HAVEN: Hybrid Automated Verification ENgine for UVM Testbench Synthesis with
  LLMs — arXiv:2604.27643（19 IP，Direct/Wishbone/AXI4-Lite，100% 编译、90.6%
  code cov、87.9% functional cov）
- LLM4DV: Using Large Language Models for Hardware Test Stimuli Generation —
  arXiv:2310.04535（Direct Stimulus 臂的代表作与可复用 prompting 结构）
