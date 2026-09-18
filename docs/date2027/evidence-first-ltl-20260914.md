# LTL token 开销优化：离线验证

当前契约 `runtime-ltl-v3`，上下文策略 `evidence-first-ltl-v3`，任务证据
`compact-evidence-first-ltl-v2`，skill 协议 `frozen-inline-ltl-skill-v3`。
没有调用 DeepSeek，没有重算 v11 费用，也没有重新测量覆盖率。HAVEN 未修改。

## 改动与边界

- 取证顺序：prompt/skill 引导模型先识别缺失事实、批量取得必要 RTL 再构造 LTL。
  已有证据足够时直接回答，不强制额外请求、不降低推理档位、不删除工具协议中的历史字段。
- 确定性解包：只接受整个回答恰好是一个完整的无标签或 `scala` 三反引号代码块，
  块外仅可有空白。块内源码（包括中文、空白和 CRLF）逐字节保留。
  多块、说明文字、未知标签或未闭合围栏拒绝；不是从任意回答中搜索并挑选代码。
- 审计：`response.txt` 保留完整原回答，`response-normalization.json` 记录策略、
  原回答/块内源码哈希、字符起止偏移和起始行。resume 校验记录，`model.ltl` 与固定 UT
  继续执行源码完整性、编译和回放检查。没有修改 Gen 条件、标签或求解预算。
- 修复工具分流：明确的 `response-envelope` 错误只开放完整诊断，禁止框架参考与 RTL 工具。
  混合/未知错误仍保留正常语法与类型修复，不自动把语义错误解释成格式问题。
- 输入去重：后续轮的完整覆盖报告复用无损列式 gaps 编码，保留行顺序、重复行、
  缺失/null、额外字段和全部元数据。完整报告已内联时不再在摘要重复 bins/percent/score。
  原始 coverage JSON 仍可读取，超限仍分页。spec 不改写、不删节；已读 RTL 继续按原文与哈希合并，
  没有用模型摘要替代事实或携带其他实验答案。

skill-creator 指导本次只增加通用的取证与包装修复规则，不加入 DUT 名称、目标或历史操作数。
LTL API 参考摘录未改变，RAG 语料版本仍为 16。

## 保存样本上的效果

读取 v11 的原始 manifest、反馈及本次运行历史，重建新版初始证据包，核对完整覆盖表能精确还原，
并核对 RTL 目录、已读 RTL、已接受 LTL、物理环境和基线内容不变：

| v11 轮次 | 旧证据包字符 | 新证据包字符 | 减少 |
| --- | ---: | ---: | ---: |
| 1 | 4,127 | 4,126 | 基本不变，仅策略名长度变化 |
| 2 | 42,988 | 32,694 | 23.95% |
| 3 | 36,436 | 35,426 | 2.77% |

这些数字仅是初始 evidence 消息字符数，不是整个请求、provider token 或金额。
每轮约 2.43 万字符的 spec 原文保持不变；本次没有宣称可以消除跨请求的全部重复输入。

v11 第二轮第一次原始回答通过新版解包与真实 Nix/bubblewrap/scalac 编译，只移除包装，
不调用模型。历史的两次包装修复曾花费 14,842 token；现在这份保存回答可跳过该修复，
但不能据此直接从 v11 总数扣费，或保证新模型轨迹能省下同样数量。没有重跑该回答的 JG/回放，
不假定其覆盖率与历史修复后回答相同。

## 验证

- Python 全量 432 项：393 通过、39 项按开关跳过，无失败。
- 实际隔离编译：带 CRLF 的围栏 LTL 编译通过，块内字节不变。
- 实际保存回答编译：v11 第二轮原回答一次通过，0 模型请求、0 token。
- 测试覆盖完整/多块/截断围栏、非法语句和目标预算不被绕过、单次模型桩响应无需修复、
  包装修复拒绝框架工具、无损反馈还原及历史样本只读检查。
- 原有供应商协议字段保留及工具预算测试继续通过。skill 格式校验、`git diff --check` 通过。

产物：

- `/var/storage/workspaces/clo91eaf/ltl-evidence-first-offline-20260914-v2.json`
- `out/experiments/evidence-first-v11-round2-20260914/`

复现无模型回归：

```bash
PYTHONPATH=experiments experiments/haven-python /path/to/haven \
  -m unittest discover -s experiments -p 'test_*.py' -q
nix develop -c env PYTHONPATH=experiments RVPROBE_RUN_TOOL_TESTS=1 \
  experiments/haven-python /path/to/haven -m unittest \
  test_sequence_framework.RuntimeToolTest.test_single_fenced_ltl_compiles_without_body_rewriting -v
PYTHONPATH=experiments experiments/haven-python /path/to/haven \
  experiments/profile_ltl_evidence.py --generation /path/to/round-1/generation \
  /path/to/round-2/generation /path/to/round-3/generation --out /new/path/profile.json
```

剖析脚本只读历史数据，校验 RTL 哈希与非覆盖证据，不接受覆盖已有或写入历史轮次目录的输出路径。
