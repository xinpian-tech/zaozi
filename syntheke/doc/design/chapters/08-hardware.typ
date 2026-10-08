#import "../lib.typ": *

= 硬件边界 <ch-hardware>

协商结果通过一份可序列化的完整参数交给 zaozi 生成器。单个 IP 可以使用同一参数独立例化、测试和复现（@req-ip）。本章规定生成器契约、模块节点与生成器端口的对应关系、域结构的生成器投影，以及例化次序。

== 生成器的契约 <sec-generator-contract>

生成器以完整参数为输入并产出电路模块，必须满足以下契约：

+ 硬件接口、探针接口、FIRRTL 层结构与电路体都由完整参数确定。跨层端口规划使用 `ResolvedEdge` 中的 `ProtocolBundle`；生成器从 `FullParam` 重建实际端口后，以同一 `ProtocolBundle` 作为期望结构进行校验。
+ 同一生成器与相同完整参数产生结构相同的模块，模块定义据此按（生成器名字，规范化完整参数）的链接键共享（@sec-dedup）。
+ 生成器 API 以完整参数为输入；将完整参数写成 JSON 后，可以直接调用生成器完成独立例化与测试。

生成器没有隐式时钟与复位：物理时钟和复位输入一律声明为相应设计协议的 inward 节点，与其它节点一样对应端口和一次 bind。数据节点对时钟域或复位域的附着指向本模块这些 inward 节点；物理 outward 节点声明承载的域（@sec-domain-physical-carrier）。域附着不替代端口，也不生成连线。

每个生成器发布一个名字和 `FullParam` 的规范化序列化（编码与解码）。名字确定生成器实现和 `FullParam` 的序列化格式（@sec-dedup）。

`GeneratorEntry` 与 `ResolvedGeneratorModule` 的类型见 @sec-generator-records。`DesignBuilder` 把每个生成器条目登记到生成器注册表。同一生成器的所有模块引用同一个条目；两个不同条目使用同一名字时报告名字冲突（@sec-error-semantics）。

`ResolvedGeneratorModule.entry` 确定完整参数的类型，`fullParam` 采用该条目的 `FullParam`。记录按模块的层次树先序存入 `ResolvedDesign`。例化从条目取得生成器，参数导出从同一条目取得 codec。

单个 IP 从参数文件例化时，由生成器库提供包含该名字条目的注册表。

`computeFullParam` 将框架提供的 `EdgeView`、`DomainView` 与闭包中的用户参数直接合成 `FullParam`。独立例化按名字找到注册项，并用该项的序列化解码完整参数。`FullParam` 必须足以确定生成器全部设计端口与探针端口的名称、方向和接口结构。对于构建期动态声明的节点，`computeFullParam` 把节点名称、方向和 `ProtocolBundle` 约束转换为生成器自有字段，在 `FullParam` 中保留复现这些接口所需的值；若节点名称由生成器的固定接口决定，`FullParam` 只需保存决定接口结构的参数。生成器需要的域定值和本地同域关系同样进入 `FullParam`；全局 `DomainDeclId` 不进入，避免实例路径改变链接键。

#图([序列化边界。框架侧的生成器模块保存用户参数、边与域的声明及参数合并函数；zaozi 侧的生成器从完整参数确定接口、FIRRTL 层结构与电路体。全局域身份不穿越边界。])[
  #syn-canvas({
    import cetz.draw: *
    rect((0, 0), (5.0, 3.4), stroke: 0.7pt, radius: 0.1)
    content((2.5, 3.05), [*生成器模块*（框架侧）])
    for (y, t) in ((2.35, [用户参数（构建期声明）]), (1.65, [节点与域结构声明]), (0.95, [`EdgeView` + `DomainView`]), (0.25, [`computeFullParam`])) {
      content((2.5, y), text(size: 8.5pt, t))
    }
    rect((7.6, 0), (12.4, 3.4), stroke: 0.7pt, radius: 0.1)
    content((10.0, 3.05), [*生成器*（zaozi 侧）])
    for (y, t) in ((2.35, [硬件接口 $=f("完整参数")$]), (1.65, [探针接口 $=f("完整参数")$]), (0.95, [层结构 $=f("完整参数")$]), (0.25, [电路体 $=f("完整参数")$])) {
      content((10.0, y), text(size: 8.5pt, t))
    }
    line((6.3, -0.3), (6.3, 3.7), stroke: 2.2pt)
    line((5.15, 1.7), (7.45, 1.7), mark: (end: ">"), stroke: 1.1pt + c-edge)
    content((6.3, 2.12), text(fill: c-edge)[完整参数])
    content((6.3, 3.95), [序列化边界])
  })
]

== 生成器模块的声明 <sec-generator-module>

生成器模块（@sec-module-kinds）在构建期声明契约数据与函数，包括用户参数、生成器注册表条目、inward 与 outward 节点列表、模块内部参数依赖、域声明、附着、要求、承载、同域组、探针源列表、已求解参数推导函数，以及用户参数和已求解数据到完整参数的合成函数。节点声明记录节点标识、名称、方向、协议、相应的 `dFn` 或 `uFn`、域附着和源码位置；域结构条目按 @sec-domain-model 记录稳定标识与源码位置。

协商器为每个生成器模块装配 `EdgeView` 与 `DomainView`，再调用该模块的 `computeFullParam`。

`nodes` 按声明顺序返回本模块的全部设计模块节点；`parameterDependencies` 按声明顺序返回本模块从 inward 节点到 outward 节点的依赖边，每条记录包含两端 `ModuleNodeId` 与源码位置。`OutwardNodeSpec` 必须携带 `dFn`，`InwardNodeSpec` 必须携带 `uFn`，函数字段不可选。构建 API 每声明一条依赖边，就同时返回两个带协议类型的读取句柄（只能读取指定节点参数的句柄），分别供 outward 节点函数读取 inward `Down`、供 inward 节点函数读取 outward `Up`；原始节点句柄没有参数读取操作。节点附着声明另返回该节点的类型化域定值读取句柄。端口参数函数只能使用这些句柄，因而可读集合由参数依赖与附着声明确定。函数返回类型由本节点协议确定。边界节点的函数从用户参数与本节点域定值产生初值。处理器、存储、桥、Xbar、NoC、直连和时钟树均通过这套公开构造方法声明节点和模块内部参数依赖。

`DesignBuilder` 根据当前模块的 `ModuleId` 与节点名派生 `ModuleNodeId`。同一模块内节点名唯一；每个节点恰好参与一次设计 bind。节点在生成器的端口中对应一个以节点声明名命名、由节点方向确定根方向的顶层 Bundle（@sec-port-naming）。

时钟与复位附着指向本模块相应的 inward 物理节点，沿该节点唯一 bind 的源侧承载声明解析；模块默认只是批量补写这一指向。调试传输同时具有 TAP 的 `tck` 和系统时钟时，两侧数据节点分别指向两个时钟 inward 节点。电源附着来自层次继承或显式覆盖，不声明电源 inward 节点。每个 `(subject, 域类)` 至多一个有效附着（@dec-domain-identity-attachment）。

产生或分发物理时钟、复位的 outward 节点声明 `realizes(domain)`。同域组按域类把本模块相关端口划成等价类；要求与附着分开声明，一个 subject 是否贡献一份要求不由其端口数量推断。框架检查这些声明和设计协议的 `DomainContract`，但不检查生成器内部 FIRRTL 是否存在未申报的跨域路径；生成器实现与声明的一致性属于 RTL 审计契约（@dec-domain-same-groups）。

`EdgeView` 是双向传播和逐边求解完成后按模块投影的数据。它按节点给出方向及唯一的已求解边；该边包含 `Down`、`Up`、`Edge` 与 `ProtocolBundle`。`DomainView` 给出本模块所声明域和各 subject 附着域的已验证定值，以及本地实际同域等价类，不含全局域身份。两份视图的记录见 @sec-generator-records。

本设计不表示未建模的域源。来自设计外部的时钟或复位域由拥有相应输入端口的平台或封装模块声明；仿真设计中通常是测试平台模块。裸设计根若只有接收侧 inward 端口，外部源便没有可产生稳定域身份并声明 `realizes` 的模块，因而不合法。需要这种顶层接口时，以显式平台或封装模块声明外部域和 outward 物理载体，再 bind 到设计根。

`dvSources` 声明探针源（@sec-dv-declarations）：接口在声明处由协议导出并检查，闭包中的用户参数足以在 `FullParam` 中复现这些探针端口。

每条设计边在源、目标生成器的端口中各对应一个顶层 Bundle；每个探针源按信号叶对应若干纯 `Probe` 端口。节点和探针源的声明名称在模块内共用同一唯一性约束，重复时在声明处当场报错（@sec-error-semantics）。参与框架连线的每个生成器端口必须能由相应 `ModuleNodeId` 或探针源声明唯一还原。设计边端口的期望结构来自 `interfaceOf(edge)`；探针源的期望结构来自声明处导出的接口。

#决策([端口结构校验在例化期进行])[
  生成器的设计端口和验证端口必须与相应 `ProtocolBundle` 完全一致：设计 bind 的源端根方向为 Output，目标端为 Input；探针源按接口叶展开，每叶一个 Output 纯 `Probe` 端口，名称为源名加叶路径段（@sec-port-naming）；字段名称、顺序和方向（`Flipped`），`Bundle`、`Vec`、`Bits`、`UInt`、`SInt`、`Bool`、`Clock`、`Reset`、`Probe` 类型构造器，Vec 长度、整数宽度与符号，以及 Probe 的 `LayerPath` 均逐层相同。声明端口缺失、参与连线的顶层 Bundle 没有对应声明或结构失配时，错误包含端点稳定标识、声明的源码位置以及期望结构与实际结构的差异路径。
] <dec-binding-check>

端口失配在实施阶段报出，与 @sec-error-semantics 的协商错误分属不同异常。

== 例化流程 <sec-elaboration-flow>

例化期对层次树自底向上执行，每个模块一步：

+ *生成器模块*：读取 `ResolvedDesign` 中的完整参数并调用生成器；按 @dec-binding-check 校验设计节点与探针源端口，并登记设计边与探针路由的硬件端点。
+ *结构模块*：按#ref(<ch-hierarchy>)的端口与连线计划发射端口、子实例与连线。连线按 Bundle 整体连接，由 zaozi 根据字段方向展开。

自底向上的次序保证子模块先于父模块发射，父模块生成连线时可以直接引用子实例端口。

生成器模块的定义与其实例引用按模块名链接，模块名因此是链接键：它必须忠实编码（生成器名字、规范化完整参数），不同身份不得产生相同模块名。生成器模块名到此才确定，结构模块名与生成器模块名同处一个平坦符号空间，因此在此处核对二者不相犯（@dec-wrapper-module-name）。链接同时把各 per-module circuit 中的 FIRRTL 层定义并入设计 circuit——生成器可以携带与探针路由无关的内部层。

生成器内部还可以实例化真外部 Verilog 模块：它以 extmodule 声明进入电路，声明即定义，链接原样保留。RTL 无法企及之处——时钟的起源、仿真的输出、调试器的线缆、片外的存储器——由这类模块的行为级定义补上，与发射出的 Verilog 一起交给仿真器。仿真之外的工具由此可以接进来：线缆的另一端是真的调试器，存储端口的另一端是真的 DRAM 仿真器，设计里不必再放一个。

链接后的电路以 mlirbc 交出，与各 per-module circuit 同一种形式；FIRRTL 只有字节码，没有文本。Verilog 是一组文件而非一段文本：每个模块一个文件，加上层与探针的附属文件——发布构建取前者，验证构建再加上后者。

== 序列化范围 <sec-serialization-list>

#table(
  columns: (auto, 1fr, 1fr),
  table.header([类别], [内容], [约束]),
  [*必须可序列化*], [完整参数（用户参数与已求解参数合并后）。], [生成器以完整参数为输入；单个 IP 独立例化使用同一参数作为命令行输入。全局 `DomainDeclId` 不因附着进入完整参数（@req-ip）。],
  [*可序列化、按需导出*], [稳定标识、域声明与 `ResolvedDomain`、附着、要求、承载、同域等价类、设计边的 `Down`、`Up` 与 `Edge`、验证协议的 `Down`、`InterfacePath`、`ProtocolBundle`、`EdgeView`、`DomainView`、端口与连线计划、FIRRTL 层声明树和诊断信息表。], [`ResolvedDesign` 使用这些数据类型；工具文件按需生成（@ch-tooling）。],
  [*进程内函数*], [参数变换、域定值与要求计算、域验证、合并与推导函数。], [这些闭包的生命周期限于当前设计进程；生成器输入采用完整参数值。],
)
