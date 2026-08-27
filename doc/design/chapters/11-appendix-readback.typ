#import "../lib.typ": *

= 附录 A：从图读回参数 <apx-readback>

Diplomacy 的惰性求值允许例化代码在任意位置读回图上任意已求解的值。典型惯用法是 PLIC：`nDevices = intnode.edges.in.map(_.source.num).sum`——设备数不存在于任何配置字段，从图上读回，并直接决定是否生成扇入仲裁硬件。本附录说明该类需求在 Syntheke 的显式两遍协商（@sec-propagation）下如何承接。按信息的来源分三种情形。

== 端口数量 <apx-readback-count>

设备直挂控制器时，"有几个设备"是拓扑数据。节点的有无、数量和名字只依赖用户参数、先于协商固定（@sec-build），因此该数量不经协商获得：构造控制器模块和书写 bind 使用同一个设备列表（@sec-attach），数量由单一事实来源给定，不存在对不上的可能。

== 经聚合层传播的汇总值 <apx-readback-propagated>

设备经中间聚合模块（如中断 Xbar）汇入控制器时，控制器只有一个 inward 节点、一条边，"到达它的中断线总数"不再是拓扑数据，而是整张子图的计算结果。这正是 `Down` 传播的本职：协议把该值建模进 `Down` 类型（@sec-three-param-kinds 的中断行——供出的中断线数量与触发语义）；每级聚合模块的 outward 节点 `dFn` 折叠其可达 inward 节点的 `Down`；到达控制器的那条边求解后，边参数已含全图贡献。控制器的 `computeFullParam` 只读本模块 `EdgeView`（@dec-pp-local）即可取得该值——"依赖图的计算结果"与"只读本模块"并不矛盾，因为传播已把图折叠进边。

该值随后进入完整参数，由生成器决定硬件形态（例如设备数为零则不生成仲裁树）。与 Diplomacy 的差别只在信息的存放位置：Diplomacy 中它是图上一个无名的中间值，离开整张图无法复现；Syntheke 把它物化进完整参数，换取单个 IP 的独立例化（@req-ip）与按链接键共享定义（@sec-dedup）。

汇总所用的字段在协议的 `Down` 类型中定义一次，不由各模块自行发明；聚合模块的 `dFn` 调用协议库提供的标准合并函数（@sec-interconnect-flow），自己只声明哪些 inward 到达哪些 outward。

== 整机汇总 <apx-readback-global>

跨越多个模块的全局汇总（如 boot ROM 需要整机地址映射）在单轮协商内不可得：`dFn` 只读 `Down`、`uFn` 只读 `Up`、协议参数只读本模块视图，没有整机回读。这类产物由工具从导出数据生成，需要进入硬件时作为用户参数进入下一轮构建（@sec-settle-pp、@sec-export）。这是 @sec-diplomacy 声明的取舍中唯一需要两轮的情形。

综上，"从图读回"不是被删除的能力，而是被拆到两个显式位置：传播折叠进的*边参数*，与按模块投影的*协议参数*。前两种情形单轮闭环，仅第三种需要显式的第二轮。
