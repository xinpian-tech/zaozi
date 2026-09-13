# Spec / IO 初始任务与按需 RTL

本次只修改模型上下文与只读工具协议，没有发起 DeepSeek 或商业 EDA 调用，没有更新历史覆盖成绩。

RVProbe 现在每轮直接携带同一份冻结 skill，提供 LTL API 语义、类型、历史引用、有限时序用法及通用示例；
随后的任务包含 DUT 原始 spec、IO/binding、框架示例和输出契约。RTL 全文、覆盖报告源码片段、
完整反馈、基线 DSL 和组件声明不再内联。

模型通过 `list_rtl` / `search_rtl` / `read_rtl` 读取需要的实现，
通过 `read_context` 读取 coverage、environment、baseline 或自身 history。
工具只读固定 manifest 中的 HDL 文件，不接受任意路径或命令；读取时校验哈希。
每页最多 12,000 字符，包含续读偏移；每个对话最多 24 次模型请求和 64 次任务工具调用。
请求 usage、工具参数及返回内容都留档，失败也保留已报告成本。

按用户要求，HAVEN 不作这项改动：保留原来的完整 RTL/反馈 prompt、DSL API/schema 和无工具请求。
仅 RVProbe 使用按需工具；已取消重复的 skill 引导请求。两侧的资料访问方式不同，实验记录明确区分。
新策略 `frozen-inline-skill-v1` 保留本次已接受 UT 的按需历史，不继承全部聊天；旧模型费用及结果不改写。
公共证据投影恢复 `common-evidence-v6`。Stage-1 保持固定，不新增实验答案或设计专属示例。

## 离线验证

`test_task_context.py` 覆盖 RVProbe 多轮工具交换、usage 汇总、调用预算、截断停止、路径拒绝、
错误参数纠正、哈希变化拒绝、长行/Unicode 分页，并验证 HAVEN worker 仍只发送一次无工具请求。
原先要求全文出现在 prompt 的回归测试已改为检查初始边界及按需可访问性。

审计重新构建首轮 prompt：检查 RVProbe 不内联 RTL、分页无损，以及 HAVEN 保留完整 RTL/反馈。
最初两边同时改动的 v1 离线记录已经被撤回方案替代，不代表当前 HAVEN 流程；没有据此启动模型实验。
更新后的[16 设计审计记录](../../out/experiments/spec-io-on-demand-audit-20260910-v2-rvprobe-only/summary.json)全部通过。
使用各次保存的原输入重建，近期 12 个设计的 HAVEN prompt 与历史正文逐字一致；
ALU/AES/SHA3 的更早版本不作该一致性声明，AXIL 没有对应首轮 prompt 可比较。
完整 Python 回归为 319 项：303 通过、16 跳过；包括 HAVEN 无工具请求路径的回归测试。

| 设计 | RVProbe 旧首轮字符数 | 新首轮字符数 | HAVEN |
| --- | ---: | ---: | --- |
| UART | 103,657 | 41,123 | 保持原流程 |
| ETHMAC | 653,711 | 64,083 | 保持原流程 |

以上仅比较 task prompt 文本，不包含 skill、工具往返、重试或输出成本，不是已测得的 token 节省或覆盖提升。
新 prompt 为离线重建，不是已提交给模型的实验请求；原始实验记录保持不变。

复现：在 flake 环境中，用 HAVEN 的依赖环境运行：

```sh
experiments/haven-python HAVEN_ROOT experiments/audit_prompt_access.py \
  --tracking out/experiments/fixed16-tracking-20260910.json \
  --haven-root HAVEN_ROOT --out NEW_OUTPUT_DIRECTORY
```

调用预算、上下文策略及 skill/框架哈希进入新运行指纹；不要直接 resume 旧 prompt 的实验目录。
