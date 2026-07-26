#import "../lib.typ": *

= 互连模型 <ch-interconnect>

一段互连承载着一套固有的逻辑结构:哪些部件能发起访问、各自能到达哪些部件;访问按什么地址译码到目的地;互连的每一部分属于哪个时钟域与电源域。这套结构由架构决定,是设计的固定事实——无论最终用什么硬件承载,它都同样成立,因此应当描述一次、始终保留。而由哪种硬件来承载它——crossbar、NoC、还是一组直连,乃至一个 NoC 内部如何再分区——是性质完全不同的另一个决定:它服从面积、频率与带宽的权衡,会随工艺与性能数据在设计过程中反复更改,因此必须能独立选择、独立替换。

传统流程把两件事写在同一处:连接结构直接表达为对某个具体互连部件的例化与逐端口连线,逻辑事实散落在互连实现的参数与连线里。于是"换一种互连"要连带重写连接结构,原本不该变的那部分被迫跟着变。#ref(<req-interconnect>)要求把两者分开。本章给出分离的机制:逻辑结构落在总线上,硬件承载落在 fabric 上;前者协商出全部参数,后者只消费协商的结果;两者之间只有一次单向的数据交接(@sec-manifest)。

== 总线与 fabric:两件分开的事 <sec-bus-fabric>

一条#term[总线][bus]是一个逻辑分组:它命名一组#term[agent][member],并承载关于这组 agent 的全部逻辑事实——谁能到达谁、地址如何译码、各 agent 属于哪个时钟域与电源域。参数协商就在总线的 agent 之上运行(@ch-negotiation):agent 的声明在总线上相遇,求解出每个 agent 的接口与整张地址图。总线自身不产生任何硬件——它没有端口、没有连线,是纯粹的规格对象。

一个 *fabric* 则是硬件的来源:一个消费总线协商结果、产出互连硬件的生成器。crossbar、NoC、一组点对点直连,都是 fabric 的具体形态。

#不变量[一条总线恰好对应一个 fabric:总线在构造时就选定它的 fabric 策略;设计中有多少条总线,就有多少个 fabric。]

选定发生在构造时,原因在于具名节点(@sec-placement)属于 fabric:bind 行要引用节点的名字,而暴露哪些节点由 fabric 策略给出,所以策略必须先于第一行 bind 确定:

```scala
val periph = Bus("periph", fabric = Crossbar())        // 由 crossbar 承载
val sys    = Bus("sys",    fabric = Noc(Mesh(2, 2)))   // 由 2×2 网格 NoC 承载
```

分离的动机在于参数的性质。位宽、地址图、标识空间、域归属是逻辑结构的属性:它们由 agent 的声明与"谁到达谁"的关系决定,与承载硬件无关——同一组 agent,交给 crossbar 与交给 NoC,算出的地址图与接口一模一样。计算既然相同,就应当只做一次:在总线上算,把结果交给承载这条总线的那个 fabric(@sec-manifest)。这一步分离之后,crossbar 与 NoC 之间的替换只触及 fabric 这一个对象;总线上的 agent、地址与协商结果原地不动,bind 行(@sec-attach)也不必改写。

#图([总线与 fabric。虚线框是逻辑总线:agent 的集合与它们的地址、可达、域事实,不产硬件。实线框是承载这条总线的 fabric:互连本体,外加每个 agent 恰好一个端口(小方块)。])[
  #syn-canvas({
    import cetz.draw: *
    let mx = (0.4, 3.1, 5.8, 8.5)
    let labs = ([cpu], [dma], [dram], [uart])
    for i in range(4) {
      let x = mx.at(i)
      rect((x, 2.7), (x + 1.9, 3.45), stroke: 0.7pt, radius: 0.08)
      content((x + 0.95, 3.075), labs.at(i))
      line((x + 0.95, 2.7), (x + 0.95, 2.05), stroke: 0.7pt)
      rect((x + 0.75, 0.62), (x + 1.15, 1.02), stroke: 0.6pt, fill: c-fill)
    }
    rect((0, 1.3), (10.8, 2.05), stroke: (dash: "dashed", thickness: 0.8pt), radius: 0.1)
    content((5.4, 1.675), [总线 `sys`(逻辑):agent 集合 · 地址译码 · 可达关系 · 域归属])
    line((5.4, 1.3), (5.4, 0.86), mark: (end: ">"), stroke: 1pt)
    content((6.55, 1.16), text(size: 8pt, fill: c-dim)[承载(一对一)])
    rect((0, -0.55), (10.8, 0.82), stroke: 1pt, radius: 0.1)
    content((5.4, 0.02), [fabric(crossbar / NoC / 直连):承载 `sys` 的互连硬件])
  })
]

== bind:唯一的连接原语 <sec-attach>

把一个设备接入总线只有一种写法——用 `<-` 把它 bind 到总线的一个具名落点上:

```scala
sys.node("n_mem") <- dram
```

`sys.node("n_mem")` 是 `sys` 的 fabric 暴露的一个#term[具名节点][named node]——一个物理落点,可能是一个端口或一个路由器(@sec-placement);`<-` 把设备 `dram` bind 到这个落点上,读作"这个落点承载 dram"。bind 是本模型唯一的连接原语:设计的整张连接结构,就是全部 `<-` 行的集合。

*一个节点既是连接点,又是那束 IO 线。*一个设备靠它的一个#term[节点][node]接入总线——节点是设备的一个协议端口,声明自己服从的协议、以及自己的方向(发起者还是响应者)。这个节点在协商里求解出的参数,*就是*它端口的线形状:bind 一个节点,就接上了它的 IO,再没有一道"把节点另行映射到某个硬件字段"的手续。连接与硬件是同一个节点的两面(@ch-hardware)。

*方向来自节点的角色,不来自算子。*处理器的取指口声明自己发起访问,存储的口声明自己响应;数据往哪个方向流,由角色定死。所以 `<-` 只说"谁承载谁",不带方向——连接原语只有一个。(某个 fabric 若限定某落点只接特定方向,那是它自己的能力约束,在能力校验时裁决,@sec-manifest。)

*数量就是 bind 的条数。*一条总线上有多少设备、它的 fabric 要提供多少端口,答案就是数一数 `<-` 行。数量没有独立声明:增删一个设备就是增删一行,计数随之自动正确,不可能与事实失配。

*一个设备可以有多个节点。*一个 DMA 同时有数据口与配置口,就是两个节点,分别接到不同总线,各是一次独立的 bind;桥(@sec-flat-nest)正是这样一个双节点设备,在它接入的两条总线上各占一个节点。多数设备只有一个节点,写起来就是上面那一行。设备之间对总线完全同质——叶子设备也好、桥也好,总线不为谁设特例,清单的 agent 表(@sec-manifest)对全部 agent 只有一种表示。

=== 落点:具名节点 <sec-placement>

bind 行里的名字把 agent 放在 fabric 的一个具体#term[落点][placement]上。落点服从四条规则,每条各有动机。

*落点普适:每个 fabric 都暴露具名节点。*任何 fabric 都占物理面积,它的每个端口都是一个独立的物理位置与仲裁槽位——crossbar 的两个端口即便在可达性上完全对称,仍各占版图上的不同位置、仲裁上的不同槽,是两个独立的落点;NoC 的端口更是显然挂在不同的路由器上;直连 fabric 同样每个 agent 一个节点,名字标识那对连线的端点。因此不存在"无落点"的 fabric:落点是所有 fabric 的普适概念,只是在 NoC 上更醒目。

*落点显式:每个落点都由规格写出。*若允许自动布点,落点就成了 fabric 内部的隐藏决定:换一个 fabric 实现、增删一条无关的 bind,布点都可能悄悄变化,而挂在落点上的 floorplan 约束与仲裁配置(见下)随之失效,且没有任何一处规格改动能解释这次变化。落点必须出现在规格里,由写规格的人决定。

*身份是名字。*一个节点的身份是例化 fabric 时赋予的名字。编号做不成普适身份:不同 fabric 的坐标空间天然不同——crossbar 的端口排成一维序列,网格 NoC 的路由器是二维坐标,不规则拓扑的 NoC 根本没有可移植的编号方案。名字是唯一对所有 fabric 都成立的身份形式。结构化的 fabric 可以在名字之上提供坐标糖:网格 fabric 的 `mesh(x, y)` 解析到对应路由器的具名节点——糖只是查名字的另一种拼法。

*落点只属于构建期与例化期。*落点是构建期的人为输入;协商不读它——agent 落在哪个节点,既不改变它的接口,也不改变地址图,只改变事务在互连内部走的物理路径,而路由路径是 fabric 自己的事。落点原样穿过协商、抄入清单,在例化期被 fabric 消费(@sec-ic-phases)。

显式不等于逐行手敲。落点映射是构建期的普通代码,可以对 agent 列表循环、由函数算出:

```scala
for ((core, i) <- cores.zipWithIndex)
  sys.node(s"n_core_$i") <- core
```

这条规则约束的是决定者:落点由规格给出——它不约束写法的长短。

名字的回报是稳定身份:floorplan 约束按节点名下达,性能与拥塞报告按节点名组织,仲裁优先级按节点名配置。更换 fabric 时,bind 的写法不变;要重新审视的是名字与新 fabric 的拓扑——新 fabric 暴露哪些节点、agent 应当落在哪里。这正是换互连时本来就必须做的那个物理决定,机制把它显式摆上桌面。

#不变量[一个 agent 恰好 bind 一次,落在一个具名节点上;一个具名节点可承载一个或多个 agent——共享同一路由器的若干 agent 各在其上占一个端口;空置的具名节点合法。]

#图([bind 到具名节点。三行 bind:`sys.node("n_cpu") <- cpu`、`sys.node("n_mem") <- dram`、`sys.node("n_periph") <- periph`。实心圆是 fabric 暴露的具名节点;agent 落在节点上,方向来自 agent 的角色。])[
  #syn-canvas({
    import cetz.draw: *
    rect((0, 0), (10.4, 1.5), stroke: 1pt, radius: 0.1)
    content((5.2, 0.5), [`sys` 的 fabric])
    let px = (1.7, 5.2, 8.7)
    let plabs = ([`n_cpu`], [`n_mem`], [`n_periph`])
    let mlabs = ([cpu(发起者)], [dram(响应者)], [periph(桥)])
    for i in range(3) {
      let x = px.at(i)
      circle((x, 1.5), radius: 0.13, fill: black)
      content((x, 1.12), text(size: 8pt, plabs.at(i)))
      rect((x - 1.25, 2.55), (x + 1.25, 3.3), stroke: 0.7pt, radius: 0.08)
      content((x, 2.925), mlabs.at(i))
      line((x, 2.55), (x, 1.7), mark: (end: ">"), stroke: 0.8pt)
    }
    content((6.05, 2.12), text(size: 8pt, fill: c-dim)[bind `<-`])
  })
]

== 平总线与桥 <sec-flat-nest>

*总线永远是平的。*一条总线,所有 agent 直接 bind,由一个 fabric 承载——@sec-bus-fabric 的图正是这个形状,也是唯一的形状。agent 都是叶子设备(@sec-attach);总线不能作为 agent 接入另一条总线,也不存在跨总线的聚合。每条总线因此自成一个封闭的协商单元:一列 agent、一份清单(@sec-manifest)、一个 fabric。一个设计的连接结构,就是一条或多条这样的平总线。

*层次来自模块,不来自总线。*把一片设计收拢为可复用的单元,用的是模块:一个子系统就是一个模块,内部持有自己的一条平总线与一组设备。"连接不受层次约束"(@sec-two-graphs)说的正是这里:总线的 agent 可以位于层次树的不同子树,总线穿过模块边界不需要任何声明,跨界的端口由协商收尾的打洞遍统一规划(@ch-hierarchy)。这样的模块边界是*软*的:它组织代码、命名与所有权,不切断总线,也不引入任何硬件。

有的边界需要是*硬*的:两侧各是一条独立协商的总线。造出这种边界的手段只有一种——#term[桥][bridge],连接两条平总线的设备。桥在一条总线上 bind 为响应者,在另一条总线上 bind 为发起者,把响应侧收到的访问转发到发起侧;反方向的流量对称:再放一个桥,或用一个双向桥。对每条总线,桥都只是一个普通 agent:占一个具名节点,带自己声明的参数——响应侧是它转发的地址窗口,发起侧是它注入的标识空间——与别的设备无异(@sec-attach)。两条总线各自独立协商,互不知晓对方的内部。桥同时承担三件事:

+ *连接。*两条总线之间的全部流量经桥转发;两侧接口各由各自的总线求解,地址、位宽、乃至协议(两侧总线协议不同时,机制见 @sec-protocol-object)的转换都在桥内完成。
+ *交付边界。*桥朝外一侧的接口固定下来,就是子系统的边界参数:给定它,桥连同它这一侧的总线与设备可以脱离另一侧独立协商、独立例化、独立测试(@sec-ic-phases)。这正是#ref(<req-ip>)要求的交付形态。
+ *域穿越点。*两侧属于不同时钟域或电源域时,跨时钟域同步器与电源隔离单元集中装在桥内这一处。

值得放一个桥的情形恰好三种,各有明确的收益:

+ *独立交付的子系统边界。*一个子系统要作为独立单元构建、验证,或复制多份接到不同环境(@req-ip)。桥给它一份固定的边界参数与一份只含自己 agent 的清单(@sec-manifest),于是它能脱离整机、以固定参数独立例化与测试(@sec-ic-phases)。

+ *时钟域或电源域改变的边界。*域穿越硬件需要一个确定的安放位置;总线在域边界断开,穿越集中在桥内一处。

+ *更换 fabric 的边界。*fabric 的选择粒度是总线(@sec-bus-fabric),一片区域要用与骨干不同的 fabric 承载,必然意味着另一条总线,两者相接处即一个桥。典型情形:骨干是 NoC,而少数几个慢速外设不值得各占一对 NoC 的出入端口——把它们放上一条由 crossbar 承载的总线,经一个桥接到骨干,整组外设在骨干上只占一个端口。

于是一个子系统的完整形状是:一个模块,内部一条平总线,总线的 agent 是若干设备加一个桥;从外面那条总线看,整个子系统就是这个桥——一个普通 agent。层次由模块给出,连接由平总线与桥给出,两者各归其位。

域改变时总线可以不断开。若只是同一条总线上少数 agent 位于别的域,清单里的域字段已把事实交给 fabric(@sec-manifest),fabric 在互连内部为这些 agent 安放跨域同步即可;只有当域边界同时应当是一个交付或组织边界时,才值得放一个桥。放不放桥,由设计者显式决定,规格里看得见。

反面规则同样明确:不要仅仅为了分组而断开总线。一组 agent 如果应当共用一个 fabric,就把它们直接 bind 上去;命名与组织的需求由模块层次满足——那是软边界的事,没有硬件开销。一个桥意味着又一条总线、又一个 fabric 与一级转发硬件,这笔开销必须由上述三种边界之一偿付,单纯的命名整洁不构成理由。

#图([一条平总线(左)与两条总线经桥相连(右)。右图中桥以响应者身份占 `sys` 的一个节点,又以发起者身份占 `periph` 的一个节点;两条总线各自独立协商,各由自己的 fabric 承载。])[
  #syn-canvas({
    import cetz.draw: *
    // 左:一条平总线
    rect((0, 2.2), (4.6, 2.9), stroke: (dash: "dashed", thickness: 0.8pt), radius: 0.1)
    content((2.3, 2.55), [总线 `sys`])
    let lx = (0.1, 1.65, 3.2)
    let ll = ([cpu], [dram], [dma])
    for i in range(3) {
      let x = lx.at(i)
      rect((x, 0.95), (x + 1.3, 1.7), stroke: 0.7pt, radius: 0.08)
      content((x + 0.65, 1.325), text(size: 8pt, ll.at(i)))
      line((x + 0.65, 1.7), (x + 0.65, 2.2), stroke: 0.7pt)
    }
    content((2.3, 0.35), text(size: 8pt, fill: c-dim)[一个 fabric 承载全部 agent])
    // 右:两条总线经桥相连
    rect((6.2, 2.2), (11.4, 2.9), stroke: (dash: "dashed", thickness: 0.8pt), radius: 0.1)
    content((8.8, 2.55), [总线 `sys`])
    rect((6.4, 0.95), (7.7, 1.7), stroke: 0.7pt, radius: 0.08)
    content((7.05, 1.325), text(size: 8pt)[cpu])
    line((7.05, 1.7), (7.05, 2.2), stroke: 0.7pt)
    rect((8.0, 0.95), (9.3, 1.7), stroke: 0.7pt, radius: 0.08)
    content((8.65, 1.325), text(size: 8pt)[dram])
    line((8.65, 1.7), (8.65, 2.2), stroke: 0.7pt)
    rect((9.7, 0.95), (11.1, 1.7), stroke: 0.8pt + c-edge, radius: 0.08)
    content((10.4, 1.325), text(size: 8pt, fill: c-edge)[桥])
    line((10.4, 1.7), (10.4, 2.2), stroke: 0.7pt)
    line((10.4, 0.95), (10.4, 0.5), stroke: 0.7pt)
    rect((8.4, -0.2), (11.4, 0.5), stroke: (dash: "dashed", thickness: 0.8pt), radius: 0.1)
    content((9.9, 0.15), [总线 `periph`])
    let sx = (8.5, 9.5, 10.5)
    let sl = ([uart], [spi], [gpio])
    for i in range(3) {
      let x = sx.at(i)
      rect((x, -1.15), (x + 0.85, -0.55), stroke: 0.6pt, radius: 0.05)
      content((x + 0.425, -0.85), text(size: 7pt, sl.at(i)))
      line((x + 0.425, -0.55), (x + 0.425, -0.2), stroke: 0.6pt)
    }
    content((6.9, 0.15), text(size: 8pt, fill: c-dim)[各自独立协商])
  })
]

== 参数的走向:协商、清单、承载 <sec-manifest>

bind 建立的 agent 关系,首先被协商消费。总线在它的 agent 上运行双向参数协商——逐边求解的算法在 @ch-negotiation,本章只用它的形状:下行方向承载发起者一侧的供给,例如它占用的标识空间;上行方向承载响应者一侧的供给与要求,例如它服务的地址区域、它是否要求保序、它接受的最大传输尺寸。两股声明在总线上相遇,为每个 agent 求解出一个具体接口——端口上每一根线的形状——并求解出一张地址图与可达关系:哪个 agent 能到达哪些地址。

求解结果打包成一份#term[清单][manifest]:总线交给它的 fabric 的那份数据,两者之间唯一的一次交接。清单由两部分构成。

其一,*agent 表*,同质。每个 agent 一项,内容为:

- 方向:发起者还是响应者;
- 求解出的接口:该 agent 端口的线形状;
- 它的地址区域(响应者)或标识空间(发起者);
- 它的时钟域与电源域(协商得出,见下);
- 它落在哪个具名节点(@sec-placement)。

表中没有任何字段区分叶子设备与桥——从 fabric 的视角,二者是同一种东西:都是一个带接口、带地址、落在某个节点上的端口。

其二,*可达表*,由求解出的地址图导出:每个发起 agent 到达哪些地址区域、对应哪个 agent。每个 agent(桥也一样)各自声明它服务的地址区域,可达表按这些区域把地址派给 agent。不落入任何 agent 区域的地址有两种处置:总线可指定至多一个 agent 为*默认 agent*(通常是通向外部的桥),兜住这些地址,本总线访问外部就走它;未指定默认 agent 时,这类地址即不可达,协商期报错(@sec-error-semantics)。桥声明的转发窗口是它自身的参数,不从对面总线流过来;它与对面总线实际服务的地址是否吻合,由设计者保证——如同硬件里的译码窗口可以比实际从设备更宽,空洞地址返回错误响应。跨总线的这层一致性,工具可交叉核对(@ch-tooling),但不在逐总线协商的捕获范围内。

agent 表里的时钟域与电源域,和地址、接口一样是*协商结果*。时钟与电源本身也是协议(@ch-protocol):一个时钟域就是一条时钟总线——时钟源供给频率能力,用钟的 agent 把自己的时钟节点 bind 上去、协商出频率与域归属;电源域同理。一个设备因此同时是几条总线的 agent:数据节点接数据总线,时钟节点接某条时钟总线,电源节点接某个电源域,各是一次独立的 bind(@sec-attach)。这几张总线同在一张连接结构里、同一遍协商跑完,所以数据清单里的域字段与时钟、电源那侧的求解天然一致,不需要一道单独的对齐工序;数据 fabric 拿这个字段,是为了在域边界安放跨时钟域同步器或电源隔离单元。时钟总线与电源总线同样各有自己的 fabric——时钟树、电源网格——所以"互连可替换"(@req-interconnect)对它们一并成立。

fabric 是清单与策略的函数。策略是 fabric 自己的构建期参数:选定哪种 fabric 及其配置——crossbar 的仲裁方式、NoC 的拓扑与虚通道数、直连的成对方式。这个函数分两步,横跨两个阶段。第一步*能力校验*在协商期、清单装配之后立即运行(@ch-negotiation):它检查这份清单是否落在本 fabric 的能力上限内——端口数、标识宽度、地址图的形状。任一项超限,协商期就报错并停下,指出清单中哪一项超出哪条限制。这类失败在协商期定案,例化期不会再遇到。第二步*产出硬件*在例化期执行:以已通过校验的清单例化互连本体,外加每个 agent 恰好一个端口,端口的线形状就是该 agent 在清单里求解出的接口,逐线一致。

清单是纯数据:方向、线形状、地址、域、节点名,没有闭包、没有对规格对象的引用。把 fabric 当作生成器看,清单与策略合起来就是它的完整参数——参数进、模块出,与任何生成器无异(@sec-serialization-boundary):可以序列化存档,可以离开整机、单独喂给一个 fabric 做单元测试。

fabric 位于协商的下游:它消费已求解的结果,不重开协商。清单里的接口是定论:fabric 不得为任何 agent 端口增删一根线、改动一位宽。

#不变量[凡会影响任何 agent 接口的事实,都是总线契约的条款,在协商中求解;fabric 不引入、也不修改任何出现在 agent 端口上的参数。]

这条不变量是可替换性的全部来源,值得把因果链讲透。fabric 之间真实存在会影响接口的差异:crossbar 天然保持同一对端点之间的事务顺序,自适应路由的 NoC 则可能乱序送达;某种 NoC 的内部流控可能希望事务标识更宽。若这些事实由 fabric 私自决定,agent 的接口就成了 fabric 的函数——把 crossbar 换成 NoC,某个响应者突然要应对乱序,某个发起者端口上的标识线多出两根:agent 看到的硬件变了,"替换 fabric 不动 agent"随即破产。因此本模型把一切此类事实收拢为总线契约的条款,在协商中求解:事务保序与否,是 agent 在协商里声明、总线上求解的性质;标识宽度由协商从全体发起者的声明算出。fabric 拿到的清单已经含着这些条款,它的义务是承载:NoC 若不天然保序,就配置确定性路由、或自带重排序缓冲;两样都做不到,就在能力校验一步拒绝这份清单。承载的代价与手段封在 fabric 内部——它为内部流控多用几位标识也无妨,那些位不出现在任何 agent 端口上。于是同一份清单交给 crossbar 与交给 NoC,每个 agent 看到的端口逐线相同;全部差异被关在互连本体之内。替换因此成立。

#图([参数的走向。agent 声明双向相遇(蓝:下行;红:上行),在总线上求解;清单是唯一的交接;fabric 做能力校验后产出互连硬件与每 agent 一个端口(绿)。])[
  #syn-diagram(
    spacing: (10mm, 8mm),
    node((0, 0), [agent 声明], name: <m>),
    node((1.5, 0), [协商 \ (在总线上)], name: <n>),
    node((3, 0), [清单 \ agent 表 + 可达表], name: <mf>, shape: fletcher.shapes.pill, fill: c-fill),
    node((4.6, 0), [fabric \ f(清单, 策略)], name: <f>),
    node((6.1, 0), [互连硬件 \ 每 agent 一端口], name: <o>),
    node((4.6, 1), [fabric 策略(构建期)], name: <p>, stroke: (dash: "dashed")),
    edge(<m>, <n>, "-|>", stroke: c-down, bend: 22deg, label: text(fill: c-down, size: 8pt)[下行:供给], label-side: left),
    edge(<m>, <n>, "-|>", stroke: c-up, bend: -22deg, label: text(fill: c-up, size: 8pt)[上行:供给与要求], label-side: right),
    edge(<n>, <mf>, "-|>"),
    edge(<mf>, <f>, "-|>"),
    edge(<p>, <f>, "-|>"),
    edge(<f>, <o>, "-|>", stroke: c-edge, label: text(fill: c-edge, size: 8pt)[求解接口,逐线一致], label-side: left),
  )
]

== 落到三阶段 <sec-ic-phases>

本章的机制沿三阶段流水线(@sec-triptych)各就各位:

- *构建。*构造每条总线并选定 fabric 策略;书写 bind 行,把 agent 落在具名节点上。此时存在的只有意图:agent 集合、落点映射、策略选择。没有硬件,也没有任何求解出的参数。
- *协商。*在每条总线的 agent 上做纯计算,求解接口与地址图,为每条总线装配一份清单,并就地对清单做 fabric 能力校验(@sec-manifest)——承载不了的清单在此报出。落点不参与计算,原样抄入清单(@sec-placement)。
- *例化。*每个 fabric 消费自己的清单,产出硬件。两条总线经一个桥相连时(@sec-flat-nest),桥在一条总线上是响应者、在另一条总线上是发起者,它两侧的接口各是两条总线独立协商的结果;两侧不一致时——位宽不同、域不同——转换在桥内完成,一致时桥退化为直连。

一个直接推论:*fabric 可以各自独立例化。*协商整体先于例化,例化开始时每份清单都已完整;桥两侧的两条总线,各自的 fabric 是各自那份清单的纯函数,两个函数的输入互不引用对方的输出,例化因此没有先后依赖——可以分开执行、分开缓存,乃至分别交给不同团队。一个子系统能够脱离整机独立构建、独立测试(@req-ip),机制根源就在这里:它需要的一切都在自己那份清单里。

以一个最小而完整的双总线设计走一遍全流程。总线 `sys` 由 NoC 承载,agent 是 `cpu`、`dram` 与一个桥;`periph` 是独立的另一条平总线,由 crossbar 承载,agent 是三个外设与同一个桥。桥 `periphBridge` 是一个持有两个节点的设备(@sec-attach):一个节点 bind 在 `sys` 上,方向为响应者,声明它转发的外设地址窗口;另一个节点 bind 在 `periph` 上,方向为发起者,把来自 `sys` 的访问注入:

```scala
val periph = Bus("periph", fabric = Crossbar())
periph.node("p_uart")   <- uart          // 响应者,服务 0x1000_0000 起 4 KiB
periph.node("p_spi")    <- spi           // 响应者,服务 0x1000_1000 起 4 KiB
periph.node("p_gpio")   <- gpio          // 响应者,服务 0x1000_2000 起 4 KiB
periph.node("p_bridge") <- periphBridge  // 桥的 periph 侧节点:发起者

val sys = Bus("sys", fabric = Noc(Mesh(2, 2)))
sys.node("n_cpu")    <- cpu           // 发起者
sys.node("n_mem")    <- dram          // 响应者,服务 0x8000_0000 起
sys.node("n_bridge") <- periphBridge  // 桥的 sys 侧节点:响应者,转发外设地址窗口
```

注释里的地址是各 agent 自己的声明,bind 行只给落点;`sys` 的 fabric 策略把网格的三个路由器命名为 `n_cpu`、`n_mem`、`n_bridge`,第四个路由器空置。`periph` 不在 `sys` 之内:两条总线地位对等,唯一的关联是桥同时是二者的 agent。

*协商。*两条总线各自独立协商,互不引用对方的求解。`sys` 总线上,桥作为响应者声明它转发的窗口 `0x1000_0000`–`0x1000_2FFF`——这是桥自身的参数,不从 `periph` 聚合而来;`cpu` 的可达关系求解为:`0x8000_0000` 起的区域到 agent `dram`,`0x1000_0000`–`0x1000_2FFF` 到桥;桥的这条边求解为 64 位数据、主时钟域。`periph` 总线上,三个外设各自声明地址区域,桥作为发起者注入访问;桥的这条边求解为 32 位数据、外设时钟域。产出两份清单。`sys` 的清单三项 agent——表中桥一项与 `dram` 一项结构完全相同,都是"响应者 + 地址区域 + 接口 + 节点"。`periph` 的清单四项 agent——`uart`、`spi`、`gpio` 各为响应者,桥为发起者。64→32 的位宽转换与跨时钟域穿越都在桥内,不出现在任何一条总线的协商里。

*例化。*`sys` 的 NoC 从 `sys` 清单产出:2×2 网格,三个端口分别在 `n_cpu`、`n_mem`、`n_bridge`,线形状取自各 agent 的求解接口。`periph` 的 crossbar 从 `periph` 清单产出:四个端口。两次例化互不等待,先后皆可。桥同样只是一个以固定参数例化的设备:它在 `sys` 侧的接口(64 位、主时钟域)与在 `periph` 侧的接口(32 位、外设时钟域)都已在协商期定案,64→32 的位宽转换与跨时钟域同步是它的内部实现;两侧接口一致时,桥退化为直连。

#图([例题。上:`sys` 的 NoC,三个具名节点在用;下:总线 `periph` 的 crossbar。绿色是桥:两侧接口各由两条总线独立求解,位宽与域的转换在桥内完成。])[
  #syn-canvas({
    import cetz.draw: *
    // cpu / dram agent
    rect((1.0, 6.6), (3.0, 7.35), stroke: 0.7pt, radius: 0.08)
    content((2.0, 6.975), text(size: 8pt)[cpu(发起者)])
    rect((7.2, 6.6), (9.6, 7.35), stroke: 0.7pt, radius: 0.08)
    content((8.4, 6.975), text(size: 8pt)[dram:`0x8000_0000` 起])
    line((2.0, 6.6), (2.0, 6.18), mark: (end: ">"), stroke: 0.8pt)
    line((8.4, 6.6), (8.4, 6.18), mark: (end: ">"), stroke: 0.8pt)
    // sys 的 fabric
    rect((0, 4.2), (10.4, 6.0), stroke: 1pt, radius: 0.1)
    content((5.2, 5.1), [`sys` 的 fabric:2×2 网格 NoC(一路由器空置)])
    circle((2.0, 6.0), radius: 0.13, fill: black)
    content((2.0, 5.62), text(size: 8pt)[`n_cpu`])
    circle((8.4, 6.0), radius: 0.13, fill: black)
    content((8.4, 5.62), text(size: 8pt)[`n_mem`])
    circle((5.2, 4.2), radius: 0.13, fill: black)
    content((5.2, 4.56), text(size: 8pt)[`n_bridge`])
    // 桥
    line((5.2, 4.2), (5.2, 3.62), stroke: 1pt + c-edge)
    rect((4.55, 3.02), (5.85, 3.62), stroke: 0.8pt + c-edge, radius: 0.08)
    content((5.2, 3.32), text(size: 8pt)[桥])
    content((7.7, 3.32), text(size: 8pt, fill: c-dim)[桥:64→32 位 · 域穿越])
    line((5.2, 3.02), (5.2, 2.44), stroke: 1pt + c-edge)
    // periph 的 fabric
    rect((1.6, 0.5), (8.8, 2.3), stroke: 1pt, radius: 0.1)
    content((5.2, 1.4), [总线 `periph` 的 fabric:crossbar])
    circle((5.2, 2.3), radius: 0.13, fill: black)
    content((5.2, 1.92), text(size: 8pt)[`p_bridge`])
    let bx = (2.6, 5.2, 7.8)
    let bl = ([uart \ `0x1000_0000`], [spi \ `0x1000_1000`], [gpio \ `0x1000_2000`])
    for i in range(3) {
      let x = bx.at(i)
      circle((x, 0.5), radius: 0.13, fill: black)
      rect((x - 1.05, -1.5), (x + 1.05, -0.55), stroke: 0.7pt, radius: 0.08)
      content((x, -1.025), text(size: 8pt, bl.at(i)))
      line((x, -0.55), (x, 0.32), mark: (end: ">"), stroke: 0.8pt)
    }
  })
]

至此机制闭合。逻辑结构——agent、可达、地址、域——在总线上描述一次,在总线上协商一次;清单是那唯一而完整的一次交接;fabric 的职责止于承载清单。更换互连,改的是构造总线时的 fabric 策略,以及随新拓扑重定的节点名(@sec-placement);总线承载的逻辑事实与协商结果一个字不动。谁到达谁属于总线,如何到达属于 fabric——#ref(<req-interconnect>)要求的分离,由这条单向的数据流达成。
