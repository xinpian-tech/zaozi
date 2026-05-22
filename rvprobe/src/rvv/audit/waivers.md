# Audit Waivers

Entries here record literal-tuples from upstream `riscv-vector-tests/configs/` that
intentionally fall outside any named `TuplePred` / `ValuePred` / `FpValuePred` /
`FpTuplePred` predicate AND need to be explicitly waived (rather than carried as
`Lit` classifications) because they represent test intent we deliberately do not
plan to capture in the rvprobe vocabulary.

Format (required for every entry, per AC-13c):

```
## <stable-id>
Owner: <github handle or email>
Reason: <one-paragraph rationale>
Date: <YYYY-MM-DD ISO-8601>
Tracking-ID: <issue/PR ref>
Source: <ext>/<insn>.toml row indices, or `*` for all rows
```

## Entries

<!-- Initial round-4 state: 775 of 14573 upstream rows classified as Lit-only
across 165 tomls. None are formally waived yet; `Lit(BigInt, rationale)`
classification is acceptable under AC-3 per DEC-1. This list grows when a
specific row's `Lit` rationale is upgraded to a hard waiver (e.g., when we
decide a curator literal will never be re-classified into a named predicate). -->

<!-- No entries at audit closeout. -->
