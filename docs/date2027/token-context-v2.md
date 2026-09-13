# 公共上下文与空输出处理修复（2026-09-09）

本轮修改框架并离线验证，没有调用模型、运行新 EDA 实验或改写历史成绩。
上一轮组件补丁的九个文件仍全部通过安装哈希校验。

## 已实施

- `prompt_context.py` 定义 `common-evidence-v2`，在配对后端分派前统一应用。
  两侧保留全部实测覆盖缺口、原始 RTL/规格、协议、基线 DSL、item 字段及事务契约。
  不再重复发送编译后的基线 UVM、完整 blueprint 和派生 structured_spec；原始产物留在 bundle 并记录哈希。
  这是显式证据投影，不声称省略内容可以无损还原。没有按设计名或已知答案选择字段。
- HAVEN 提示词中的基线、协议和映射改为引用同一公共上下文，仅另列该臂后续生成的 DSL；
  补入与 RVProbe 相同的原始规格正文。原生 DSL schema 和生成规则仍保留。
- RVProbe prompt/skill 要求每次表达一批有限 IO/LTL 意图，而非穷尽覆盖计划或证明全部残余不可达。
  仍由模型编写一个完整 UT，框架没有接管意图或 UT 主体；proofObligations 默认可为空。
  完成本批不是 model_stop。覆盖指标、停止规则、模型、修复次数及每 intent 4 条采样均未改变。
- 两侧记录 `finish_reason`、输出状态及长度。空正文、截断、过滤保留成本后明确失败，
  不进入源码修复、不消耗网络重试预算重生成；普通 resume 也不会再次发送已知不完整的请求。
  这不能避免第一次请求耗尽推理预算，不是新的硬性 token 上限。
- 按 skill-creator 的精简原则，只给 skill 增加有限任务范围说明；保留符号化 LTL 示例，
  没有加入设计操作数、历史 UT 或覆盖答案。

## 离线结果

使用设计 5 已保存的同一任务、完整 RTL、binding 和原始 RAG 检索结果，比较单条任务 prompt：

| 后端 / 轮次 | 旧字符数 | 新字符数 | 减少 |
| --- | ---: | ---: | ---: |
| RVProbe / 1 | 148,444 | 105,554 | 28.9% |
| RVProbe / 2 | 137,860 | 94,970 | 31.1% |
| RVProbe / 3 | 126,067 | 83,177 | 34.0% |
| HAVEN / 1 | 139,182 | 92,004 | 33.9% |

这些是文本字符，不是供应商 token、成本或覆盖效果。RVProbe 对比每轮首个 attempt，
不把重试、bootstrap、skill 交换计入字符收益；修正了旧分析器一律假设旧任务发送两次的计数错误。
HAVEN 使用本地原生提示词模板和历史响应 schema 离线重建，没有模型调用。

可复用命令：

```sh
python3 experiments/profile_prompt_cost.py --design DESIGN.json \
  --generation OLD_GENERATION_DIR --bundle SHARED_BUNDLE.json --out NEW_REPORT.json
python3 -m unittest discover -s experiments -p 'test_*.py'
```

回归共 177 项：159 通过、18 项环境相关测试跳过。包括公共证据同源且仅展开一次、
保留全部缺口和后端限制、空输出在真实生成入口不进入编译/修复、resume 不重新请求、
失败用量不丢失、不落盘推理正文，以及轻量 bootstrap 不被误算为双份任务。
skill 通过 skill-creator 的 quick_validate；当前环境的系统 Python 无 PyYAML，改用已有 HAVEN 环境完成校验。

下一次效果实验必须重新生成修复后的共享 Stage-1，并在新目录运行双方；旧设计 5 结果仍是修复前结果。
