# LTL-only 接口收敛（2026-09-13）

当前契约为 `runtime-ltl-v1`；这是无模型框架验证，不是新一轮 DeepSeek/HAVEN 配对实验。
本次没有调用远程模型，也没有重新标记旧实验结果或宣称 token 已下降。

## 模型边界

- 输入：DUT spec、规范化 IO、冻结的 LTL skill、当前覆盖/物理环境证据；RTL 按需读取。
- 输出：原始 LTL 片段，包含局部谓词/helper 和 `Gen(expression, "label")`；无新目标时为 `STOP`。
- 框架生成唯一 UT、imports、接线、时钟/复位上下文及 runner。模型不返回完整 UT JSON、模块名、重复标签或 proof 分类。
- 原始片段保存为 `model.ltl`，生成代码为 `ModelUT.scala`，分别记录哈希。编译诊断映射回片段行列；原始编译日志另存。
- 后续轮次只继承当前 run 已接受的 LTL。skill 直接传原文，哈希保存在产物，不再把正文包进第二层 JSON。
- RAG v15 只开放 Gen 接受类型和 LTL API 第 2–5 节的原文摘录。skill 保留符号化示例与类型经验，不包含设计答案。
- HAVEN prompt、DSL、固定 Stage-1、模型与求解/采样预算未因本次接口变更而改变。

旧模型 UT JSON 入口及序列化示例已经移除；历史结果和负例归档仍保留，代码可从 Git 历史恢复。
详细契约见 [PROMPT.md](../../experiments/PROMPT.md)。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| Python 全量离线回归 | 419 项，383 通过，36 项按开关跳过，无失败 |
| 实际 skill helper 经固定 UT 编译 | 正确 Referable helper 通过；错误 datatype helper 被拒绝 |
| Scala FrameworkLtlTest / FrameworkExamplesTest | 6 项通过 |
| JG 四类目标求解导出 | 输入谓词、输出谓词、sequence、past 均通过 |
| 同 UT 互斥目标 | 分别求解成功，不作为彼此假设 |
| VCS/URG 逐周期回放及负例 | 13 类目标回放通过；故意改错输出被拒绝 |
| 同 intent 采样和 LTL 来源校验 | history intent 得到 4 条不同 sequence；观察器提取通过 |
| skill 格式 / git diff --check | 通过 |

真实 JG/回放检查对应 3 个测试方法，共耗时约 162 秒；不是模型实验运行时间。
采样和观察器检查不是这 4 条采样各自的完整原生回放测量，不能混称为完整配对结果。

本次工具产物：

- `out/experiments/runtime-ut-regression-ush2rg8q/`：四类目标与互斥目标求解。
- `out/experiments/cycle-replay-regression-0n9g_lte/`：回放及负例。
- `/var/storage/workspaces/clo91eaf/rvprobe-ltl-only-check-20260913/`：采样池及观察器来源校验。

## 无模型复现

```bash
PYTHONPATH=experiments python -m unittest discover -s experiments -p 'test_*.py' -q
nix develop -c mill experiments.tests.testOnly FrameworkLtlTest FrameworkExamplesTest
```

Python 全量测试需要 HAVEN 的依赖环境，可用 `experiments/haven-python /path/to/haven` 替换 python。
真实工具检查需要 flake 环境与 EDA 许可证，仍不会调用模型：

```bash
nix develop -c env PYTHONPATH=experiments RVPROBE_TEST_SKILL_COMPILE=1 \
  experiments/haven-python /path/to/haven \
  -m unittest test_rvprobe_skill.SkillHelperCompileTests -v

nix develop -c env PYTHONPATH=experiments \
  RVPROBE_RUN_TOOL_TESTS=1 RVPROBE_RUN_JG_TESTS=1 RVPROBE_RUN_REPLAY_TESTS=1 \
  experiments/haven-python /path/to/haven -m unittest \
  test_sequence_framework.RuntimeToolTest.test_four_intents_solve_and_export_from_original_rtl \
  test_sequence_framework.RuntimeToolTest.test_mutually_exclusive_goals_in_one_ut_are_solved_independently \
  test_cycle_replay.LicensedReplayTest.test_unified_goals_and_independent_reset_replay_then_negative_output_check -v
```
