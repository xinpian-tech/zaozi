# RVProbe witness 后端迁移（2026-09-14）

本次把实验侧的候选验收和初始化语义处理迁入 `rvprobe/backend`，没有调用 DeepSeek，
没有改动原始 LTL、DUT、固定 Stage-1、HAVEN 生成器或其提示词。
尚未重跑正式 16 设计统计，也没有覆盖已有实验结果。

## 实际变化

- 核心模块只依赖 Python 标准库和本包，可在不加载实验代码/HAVEN 的情况下导入。
- `coverage_flow.py` 不再实现候选选择、失败后重试或未知状态编码。
  它通过 `witness_backend_adapter.py` 获取验收后的 sequences/frames 和统计。
- 初始化、past 辅助状态、输出别名修复、重复语法转换、JG/Yosys 调用、候选搜索、
  原始 Cover 来源和监视器校验、原生验收策略均位于核心。
- 原 Python 实现迁移后删除，没有保留兼容副本；原 CLI 只保留数据/命令行适配。
- Scala `Gen → Cover → JG` 接口不变。JG 返回形式候选，不冒充仿真验收成功。
- 正式 RVProbe 入口缺少原生回放器时，在调用模型前失败；不能走未检查的候选分支。
- 核心要求传输通过以及匹配原始属性哈希/label 的四态 Cover 命中凭据，
  并拒绝仿真器修改记录中的输入。返回的序列和调度摘要保存在 `backend.json`。
- 共享进程执行/记录模块只改代码归属，HAVEN 侧导入相应更新，执行语义保持不变。

## 离线结果

Python 回归：448 项，409 通过、39 跳过。新增测试覆盖无仿真器、无/错验收凭据、
调度被改动、原始目标冻结、未知值升级、无候选、路径校验、独立导入和 CLI 外参数检查。
Scala `utlib.tests` 的 4 项 `JasperGoldTest` 全部通过。

正式适配器 SPI 测试：26.83 秒，通过。先拒绝原始二态候选（64 个所需输出位未知），
随即进入已知状态编码；第二个候选在原始 DUT 上通过原始 LTL。没有消耗模型 token。
这条测试不是直接喂入已成功 witness，而是覆盖了完整失败恢复路径。

移入核心后的真实 VCS/JG 别名回归通过：13 次采样中旧编码有 9 次未知掩码错误，
修复版全部匹配；未写入的“已知真”不可达，写入后连续四拍的“已知真”可达。

另外，SPI、ETHMAC、UART 原始失败意图经过新核心编码和 `WitnessBackend` 验收均已通过，
分别为 17.58 秒、51.91 秒、527.43 秒。UART 使用单独的 600 秒诊断预算；
生产默认求解预算没有因此改变。UART 编码模型和复位序列与迁移前逐字节相同：
模型 SHA-256 为 `3a8156a946a368f679593f5eff52397f433a7eb6d9ae8c0323723ea8d6830ebf`，
复位序列为 `1135c0038b672000978f1b2a0ec3d259174c28295373408a4f1018e19320ee54`。

## 产物与复现

归档根目录：`/var/storage/workspaces/clo91eaf/witness-backend-20260914/`。

- `rvprobe-backend-production-spi-20260914-v1`：正式适配器的完整失败恢复路径，
  `round-offline/native-witness-search/rx_fifo_data_toggle_and_full/selection.json`
  记录两次候选和未知值升级原因。
- `rvprobe-backend-alias-test-20260914-v1`：值/未知掩码正反例回归。
- `rvprobe-backend-semantics-20260914-v1` 和 `rvprobe-backend-native-20260914-v1`：
  三个原始意图的编码与原生后端验收。

```sh
PYTHONPATH=experiments python -m unittest discover -s experiments -p 'test_*.py' -q
mill utlib.tests.testOnly me.jiuyang.utlib.JasperGoldTest
python experiments/smoke_witness_backend.py --help
```

正式适配器诊断通过 `smoke_witness_backend.py` 指定已保存的 `--source-batch`、
`--design spi --round round-2 --label rx_fifo_data_toggle_and_full`，再提供
`--haven-root`、`--yosys` 和新的 `--out`。在 flake 环境中使用 `experiments/haven-python`。

辅助模型的 async2sync、Z-as-X 和历史有效性边界依旧明确记录，
此次归属重构不等于证明任意 RTL 的四态等价，也没有删除原生回放检查。
