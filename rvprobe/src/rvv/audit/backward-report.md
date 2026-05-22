# Backward Audit Report (AC-4)

Each predicate's hit count across upstream tomls. AC-4 requires every non-`Lit`, non-`Random` (and non-`FpLit`) predicate to be justified by at least one upstream literal-tuple.

## Hit counts (descending)

| Predicate | Hits |
|---|---|
| `Zero` | 9254 |
| `Lit` | 7969 |
| `AllZero` | 3192 |
| `AllOnes` | 3101 |
| `MaxUnsigned` | 3101 |
| `MinusOne` | 3101 |
| `SmallSigned` | 3060 |
| `FpDecimal` | 2809 |
| `AllSame` | 2296 |
| `One` | 1659 |
| `MaxSigned` | 1449 |
| `AllAllOnes` | 1047 |
| `ZeroPlusSmall` | 1030 |
| `NormalPair` | 926 |
| `ShiftBySewOrAbove` | 796 |
| `SubnormalBoundary` | 404 |
| `NormalBoundary` | 392 |
| `PosZero` | 364 |
| `MinSigned` | 261 |
| `SignBitOnly` | 261 |
| `LargestSubnormal` | 202 |
| `NegInf` | 202 |
| `SmallestNonzero` | 202 |
| `QuietNan` | 202 |
| `SignalingNan` | 202 |
| `NegLargestSubnormal` | 202 |
| `InfPair` | 202 |
| `NegSmallestNonzero` | 202 |
| `NaNPair` | 202 |
| `NegNan` | 202 |
| `PosInf` | 202 |
| `QuietVsSignalingNan` | 202 |
| `Nan` | 202 |
| `NegSmallestNormal` | 196 |
| `NegMaxFinite` | 196 |
| `MaxFinite` | 196 |
| `SmallestNormal` | 196 |
| `BitPatternPair` | 184 |
| `BitPattern` | 184 |
| `AllOnesPlusAllOnes` | 148 |
| `MaxPlusSmallPositive` | 141 |
| `NegSmallPlusPosSmall` | 134 |
| `ShiftByOne` | 119 |
| `MaxPlusOne` | 92 |
| `NegZero` | 72 |
| `NearMaxSigned` | 69 |
| `ShiftByZero` | 59 |
| `ZeroDivAnything` | 40 |
| `DivByZero` | 40 |
| `MixedSignZeros` | 36 |
| `MinSignedDivZero` | 24 |
| `ShiftBySewMinus1` | 22 |

## Dead vocabulary (zero hits, excluding escape hatches): 0

All named predicates are exercised by upstream literals.
