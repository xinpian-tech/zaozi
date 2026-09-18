# RVProbe 直接生成 token 优化记录（2026-09-18）

本记录保存本轮受控优化的独立结果，不覆盖原始 HAVEN/RVProbe 论文数据，也不把不同运行拼接成一条“最佳”实验。所有运行使用固定 Stage-1、`deepseek-v4-flash-vision-exp`、temperature 0.3、`low` reasoning、最多三轮、每个 intent 最多 4 条 sequence、原生四态回放；GPIO、ETHMAC、CAN、UART 使用增量对话和 `evidence_steps=0`，即模型只接收规格、IO 和当前反馈，直接输出 LTL，不发起 RTL 取证请求，最终请求上限为 32768。SPI 是此前的对照批次，使用 16384 上限和 1 个证据步，单独列出，不与直接生成配置混同。

## 成功运行

| Design | HAVEN tokens | RVProbe tokens | 比例 | Calls | Prompt In | Cache hit In | Cache miss In | Out | Reasoning | USD* | 最终覆盖率 | 有效轮数 | 回放 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GPIO | 117884 | 62822 | 0.533x | 4 | 31852 | 20992 | 10860 | 30970 | 26667 | 0.135160 | 95.254801% | 3 | 全部通过 |
| ETHMAC | 1232803 | 115008 | 0.093x | 4 | 68822 | 40320 | 28502 | 46186 | 44482 | 0.214052 | 93.310552% | 3 | 全部通过 |
| CAN | 844655 | 106711 | 0.126x | 3 | 61330 | 54400 | 6930 | 45381 | 38265 | 0.189542 | 97.437349% | 3 | 全部通过 |
| SPI | 160029 | 119311 | 0.746x | 6 | 88705 | 34432 | 54273 | 30606 | 29038 | 0.177386 | 92.265678% | 3 | 全部通过 |
| UART | 277265 | 87223 | 0.315x | 3 | 35942 | 21376 | 14566 | 51281 | 49121 | 0.220118 | 94.912780% | 3 | 全部通过 |
| UE_UART | 146464 | 82588 | 0.564x | 3 | 46028 | 21120 | 24908 | 36560 | 34713 | 0.171570 | 86.154515% | 3 | 全部通过 |
| Simple SPI | 141938 | 68941 | 0.486x | 3 | 27753 | 13184 | 14569 | 41188 | 38579 | 0.179585 | 90.595261% | 3 | 全部通过 |

`*` USD 列实际按当前实验约定的人民币单价计算：缓存命中输入 0.02 元/百万 token、未命中输入 1 元/百万 token、输出 4 元/百万 token；因此列名沿用历史表格，但数值单位是元。

逐轮覆盖率如下：

| Design | Round 1 | Round 2 | Round 3 |
|---|---:|---:|---:|
| GPIO | 87.244839% | 90.353674% | 95.254801% |
| ETHMAC | 92.552976% | 92.552976% | 93.310552% |
| CAN | 97.301479% | 97.301479% | 97.437349% |
| SPI | 83.661697% | 90.382240% | 92.265678% |
| UART | 91.635900% | 93.558977% | 94.912780% |
| UE_UART | 74.298252% | 84.800672% | 86.154515% |
| Simple SPI | 81.852956% | 87.641316% | 90.595261% |

## 失败/框架诊断记录

- GPIO 直接模式的 v1 使用 12288 总生成上限；首个请求在 reasoning 阶段耗尽预算，返回 0 个 LTL 字符（21350 tokens，`finish_reason=length`）。这不是有效实验结果。将最终上限提高到 32768 后得到上表的成功运行；v1 的账单和响应仍保留在 `rvprobe-token-opt-gpio-direct-20260918-v1`。
- CAN 直接模式的 v1 不是模型失败，而是启动参数把 Yosys 可执行文件写成了不存在的目录路径（缺少 `/bin/yosys`），在第一轮完成后终止；该 37078-token 请求保留在 `rvprobe-token-opt-can-direct-20260918-v1`。修正为 `/nix/store/ywdz7hiyhb7hkp0vyzqfdx3fmrf1v8pa-yosys-0.67/bin/yosys` 后得到上表结果。
- UE_UART 直接模式的 v1 在模型请求前因批处理子进程误用系统 Python、缺少 HAVEN 的 `pydantic` 依赖而终止；没有 provider 请求和新增 token，失败目录仍保留在 `rvprobe-token-opt-ue-uart-direct-20260918-v1`。改用 HAVEN 锁定的 `.venv` 后 v2 完成。

## 归档位置

- GPIO：`/var/storage/workspaces/clo91eaf/rvprobe-token-opt-gpio-direct-20260918-v2`
- ETHMAC：`/var/storage/workspaces/clo91eaf/rvprobe-token-opt-ethmac-direct-20260918-v1`
- CAN：`/var/storage/workspaces/clo91eaf/rvprobe-token-opt-can-direct-20260918-v2`
- SPI：`/var/storage/workspaces/clo91eaf/rvprobe-token-opt-spi-20260917-v2`
- UART：`/var/storage/workspaces/clo91eaf/rvprobe-token-opt-uart-direct-20260918-v1`
- UE_UART：`/var/storage/workspaces/clo91eaf/rvprobe-token-opt-ue-uart-direct-20260918-v2`
- Simple SPI：`/var/storage/workspaces/clo91eaf/rvprobe-token-opt-simple-spi-direct-20260918-v1`

以上归档中的 `flow/paired/summary.json` 是逐设计的权威账本；失败运行没有从成功运行的 token 或覆盖率中扣除或替换。五个成功运行的 token 均低于对应 HAVEN 的 1.3 倍上限。
