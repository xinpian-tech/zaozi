# IOMux

`IOMuxParameter` defines HS routes, optional LS pools, MMIO width and optional GPIO, interrupts, inversion and pad controls.

```scala
val parameter = IOMuxParameter(
  pinCount = 3,
  routes = Seq(IOMuxRoute(pin = 2, slot = 1)),
  hsSlots = 2,
  dataWidth = 32,
  addressWidth = 12,
  lsPools = Seq(IOMuxLsPool(Seq(0, 1), Seq(IOMuxLsChannel(2, receive = true)))),
  option = IOMuxOption(gpio = true, interrupt = true)
)
val mux = IOMux.instantiate(parameter)
```

HS signal bit `i` belongs to `routes(i)`. LS signal bits use global channel IDs. Each LS pool reserves HS slot 0 on its member pins. RX selection is independent of TX selection. An undeclared TX selection supplies a zero slot bundle. HS RX always uses its declared pin. An out-of-pool LS RX selection supplies zero before override and inversion. Connect constants and endpoint inversions with the existing bit operations.

GPIO reads use two reset-zero input registers. Interrupts share this sample and store high, low, rising and falling events independently of their enables. W1C clears pending bits. A simultaneous event sets pending. Each interrupt output covers `dataWidth` consecutive pins.

| Source | Input enable | Output value | Output enable |
|---|---|---|---|
| 0 | Slot input enable | Slot output value | Slot output enable |
| 1 | GPIO register | GPIO register | GPIO register |
| 2 | Reserved | Slot input enable | Slot output value |
| 3 | Reserved | Slot output enable | Zero |

Runtime inversion follows source selection. RX override precedes RX inversion. A route or LS receiver `tie` sets its override source and value at reset and requires `rxOverride`. Software can release the tie.

`pad` assigns a class to every pin. Each class defines pull and control tables. Row names select table entries in route and safe requests. `modeOrder` and `controlOrder` preserve global index holes. JSON encodes row `BigInt` values as strings.

`IOMuxPadSelect(off, Some(on), invert)` adds a one-bit route input. Its endpoint and runtime inversions precede row selection. `padControl` enables register takeover. It does not disable route requests or the table mapper. Safe values have final priority over TX roles and pad codes.

`pad.control_i` contains four code bits per pin for global control index `i`. `pad.pin_p_control_i` contains the mapped table bits. Pull outputs follow the same per-pin convention. Missing class controls produce zero codes; single-row controls always produce their fixed values. Invalid codes preserve register readback and select the table default. Unsupported pull modes select `none`.

Woven keeper and oscillator require up/down tables, a receiver, no native keeper or oscillator, and a non-driver pull table. Feedback uses the raw receiver value without a clock. A single linked pull selector expresses one on/off pair.

The `header` command emits constants from the RTL layout. Addresses use bytes on both 32-bit and 64-bit buses. Blocks and per-pin records align to eight bytes. Disabled blocks occupy no space; enabled arrays retain pin and global channel ID holes. Configuration changes can move later addresses. Use the header from the same configuration as the RTL. Multi-beat writes are not atomic.

The local MMIO request carries a byte address and byte write mask. Aligned words inside the window return zero for holes and ignore writes to them. Non-word addresses inside the window use the same empty response. Addresses outside the window return an error.
