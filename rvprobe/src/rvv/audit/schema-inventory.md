# Schema Inventory

Generated from `me.jiuyang.rvprobe.rvv.Schema`. Do not edit by hand.
To regenerate, run the `SchemaInventory` @main against the current `Schema.scala`.

## Summary

| Category   | Count |
|---|---|
| vsetvl* | 3 |
| FP | 5 |
| Load/store | 8 |
| Integer | 23 |
| **Total** | **39** |

## vsetvl*

| Schema enum | Format string |
|---|---|
| `Vsetvl` | `vsetvl` |
| `Vsetvli` | `vsetvli` |
| `Vsetivli` | `vsetivli` |

## FP

| Schema enum | Format string |
|---|---|
| `FdVs2` | `fd,vs2` |
| `VdFs1` | `vd,fs1` |
| `VdFs1Vs2Vm` | `vd,fs1,vs2,vm` |
| `VdVs2Fs1V0` | `vd,vs2,fs1,v0` |
| `VdVs2Fs1Vm` | `vd,vs2,fs1,vm` |

## Load/store

| Schema enum | Format string |
|---|---|
| `VdRs1m` | `vd,(rs1)` |
| `VdRs1mVm` | `vd,(rs1),vm` |
| `VdRs1mRs2Vm` | `vd,(rs1),rs2,vm` |
| `VdRs1mVs2Vm` | `vd,(rs1),vs2,vm` |
| `Vs3Rs1m` | `vs3,(rs1)` |
| `Vs3Rs1mVm` | `vs3,(rs1),vm` |
| `Vs3Rs1mRs2Vm` | `vs3,(rs1),rs2,vm` |
| `Vs3Rs1mVs2Vm` | `vs3,(rs1),vs2,vm` |

## Integer

| Schema enum | Format string |
|---|---|
| `RdVs2` | `rd,vs2` |
| `RdVs2Vm` | `rd,vs2,vm` |
| `VdImm` | `vd,imm` |
| `VdRs1` | `vd,rs1` |
| `VdVm` | `vd,vm` |
| `VdVs1` | `vd,vs1` |
| `VdVs1Vs2Vm` | `vd,vs1,vs2,vm` |
| `VdVs2` | `vd,vs2` |
| `VdVs2Imm` | `vd,vs2,imm` |
| `VdVs2ImmV0` | `vd,vs2,imm,v0` |
| `VdVs2ImmVm` | `vd,vs2,imm,vm` |
| `VdVs2Rs1` | `vd,vs2,rs1` |
| `VdVs2Rs1V0` | `vd,vs2,rs1,v0` |
| `VdVs2Rs1Vm` | `vd,vs2,rs1,vm` |
| `VdVs2Uimm` | `vd,vs2,uimm` |
| `VdVs2UimmVm` | `vd,vs2,uimm,vm` |
| `VdVs2Vm` | `vd,vs2,vm` |
| `VdVs2VmP2` | `vd,vs2,vm/2` |
| `VdVs2VmP3` | `vd,vs2,vm/3` |
| `VdVs2Vs1` | `vd,vs2,vs1` |
| `VdVs2Vs1V0` | `vd,vs2,vs1,v0` |
| `VdVs2Vs1Vm` | `vd,vs2,vs1,vm` |
| `VdRs1Vs2Vm` | `vd,rs1,vs2,vm` |

