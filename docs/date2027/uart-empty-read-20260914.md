# UART empty FIFO read: evidence and bounded-solver reporting

Original experiment: `rvprobe-failed5-backend-20260914-v2`, UART round 1,
`rx_data_available_irq_iir_rbr_lsr_read`. Original model response and failed
experiment records are unchanged. No model was called during this investigation.

## Concrete cause

The saved LTL requires an IIR read of `0xC4`, RBR reads of `0x5A` and `0xFF`
(the second with interrupt low), then an LSR read of `0x60`.
The saved formal candidate reads the first byte, empties the FIFO, then reads
an unwritten location. Native DUT waveform snapshots, in ps:

| Time | RX FIFO count | Read/write pointers | ACK | Data output |
| --- | ---: | --- | ---: | --- |
| 1,890,001 | 1 | 0 / 1 | 1 | `0x5A` |
| 1,900,001 | 0 | 1 / 1 | 0 | previous `0x5A` |
| 1,910,001 | 0 | 1 / 1 | 1 | `xxxxxxxx` |

`uart_fifo.v` resets pointers/count but not `mem[0:15]`, and continuously
assigns `data_out = mem[rd_ptr]`. `uart_top.v` reads this output even when the
FIFO is empty. Thus the X is observed in the real internal FIFO, not invented
by the replay checker. The formal trace selects `0xFF` for the second read;
this exact candidate must remain rejected. Unknown bits must not be zero-filled,
masked away, or initialized by altering the DUT/Stage 1.

This does not prove the LTL unreachable: a different legal input history might
initialize the later-read memory. The original auxiliary known-state search
stopped at the explicit 120-second JG limit with `undetermined`.

## Framework changes

- Add independently configurable, recorded `--encoded-witness-time-limit` through
  the batch/paired/coverage/offline entry points. Default remains 120 seconds.
- Preserve solver status, termination reason, per-attempt time and selected
  budget in auxiliary records. Propagate them into witness exhaustion diagnostics.
- Distinguish an observed JG time-limit termination from an unspecified
  `undetermined` result. Neither proves original-intent unreachability.
- Add a waveform-only switch to saved-simulation replay; unchanged input source
  and schedule hashes are still checked.

## Checks

The unchanged candidate again fails native LTL acceptance with waveform dumping
enabled. An independent RAM/partial-reset regression passes 13 native/encoded
samples; JG proves the unwritten-known-value cover unreachable and finds the
genuinely-written-value cover. Its old alias-path negative control differs in
9 checks. None of these synthetic regression inputs enter prompts or skills.

The original UART goal was evaluated with an explicit 600-second diagnostic
budget. JG again returned `undetermined` at the time limit (610.1 seconds total,
including preparation). No new witness was produced, so no native replay success
is claimed. This is not evidence of unreachability. Its result is separate from
the paid run and does not replace the original timeout or costs.

Python regression: 462 tests, 423 passed, 39 optional tool tests skipped.
The RAM/initialization EDA regression above ran separately. All diagnostic
artifacts are archived under
`/var/storage/workspaces/clo91eaf/uart-empty-read-20260914/`; corresponding scratch
paths are links. The native-failure evidence and the indeterminate solve are
preserved as failures/indeterminate, not relabeled successful experiments.

Conclusion: the configured search has not recovered this UART intent. The
changes improve budget control and diagnostics, not solver completeness. There
is no demonstrated replay implementation error to repair by changing DUT state.
