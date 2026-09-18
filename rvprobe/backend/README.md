# RVProbe witness 后端

这是 RVProbe 的 Python 运行库，不是实验脚本，也不需要 LLM 或 HAVEN。
Scala 的 `utlib/Gen` 仍生成原生 Cover，JG 先给出形式候选；本库负责候选到可回放 sequence 的验收边界。
没有重写 Scala LTL API，也没有把未知值辅助模型宣称为任意 RTL 的四态等价证明。

## 职责

- `runtime.WitnessBackend`：构造原始 LTL 监视元数据，要求逐拍输入和原始 Cover 的验收凭据，返回通过检查的 sequence。
- `selection`：有界去重、原生候选验收、未知输出触发的辅助求解；传输故障不触发重新生成 LTL。
- `candidates` / `encoding`：Yosys value/mask 编码、原生重复语法、候选多样化及 JG 调用。
- `initialization` / `past`：明确装载编码初始化状态、保留未知 RAM、输出别名和已知性检查、辅助历史状态。
- `replay` / `validation` / `cover`：来源校验、原始 Cover 的真实 IO 监视器、固定 wrapper 检查。
- `records` / `process`：保留所有尝试的时间/结果，并对工具执行设定时限。

以上模块只导入标准库或本包。可直接从仓库根目录 `import rvprobe.backend.runtime`，
无需把 `experiments` 加入模块路径。`experiments/backend_imports.py` 仅为直接执行的旧式脚本设置仓库路径。

## 适配器接口

`ReplayTransport` 提供三项操作：

1. `frames(candidate, segment)`：把候选转换成明确的时钟、复位和逐拍 IO 数据。
2. `render(frames, name, ordinal)`：确定性序列化；不得补事务握手或修改输入。
3. `measure(source, frames)`：在原始 DUT 上运行并返回输入传输与原始 LTL 的验收凭据。

核心校验凭据的原始属性哈希、label、四态 Cover 命中及传输通过状态；缺失、不匹配、
或者仿真器修改了记录中的输入时直接失败。适配器本身仍是需要测试的可信代码，凭据不是任意仿真器正确性的形式证明。
独立仿真将序列 ordinal 归零，合并时的 ordinal 仅用于记录，不改变原有 IO 时序。

`WitnessBackend.generate` 接收冻结目标和候选提供器，返回 `sequences`、`frames`、`metadata`，
写入 `backend.json` 与各目标的 `selection.json`。提供器可以继续采样或求解已知状态候选，
但无权自行宣布原始 LTL 已通过。实验适配器位于 `experiments/witness_backend_adapter.py`。

`encoding.solve` 还可单独用于离线诊断；其返回值是候选求解状态，**不是**验收成功。
不支持的时序/RTL 形式明确失败，辅助模型超时或不可达不等于原始意图不可达。

## 离线验证

```sh
PYTHONPATH=experiments python -m unittest discover -s experiments -p 'test_*.py' -q
python experiments/smoke_witness_backend.py --help
```

后一个入口从保存的原始候选开始，经过与正式实验相同的后端适配器，
能够覆盖“原生失败 → 已知状态求解 → 原始 LTL 验收”的完整路径。
它强制禁止模型请求，验证固定 Stage-1 哈希，不覆盖历史实验结果。
需要 EDA 的测试在 `nix develop` 中通过 `experiments/haven-python` 执行。

当前默认求解/采样预算未因迁移改变，HAVEN 的生成器、提示词与固定组件未改变。
独立诊断旧入口仍可使用，但没有原生回放器的正式 RVProbe 运行现在会在调用模型前失败。
