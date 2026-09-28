/* SPDX-License-Identifier: Apache-2.0
 * SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
 *
 * A C ABI over Ramulator, so a caller that is not C++ can drive it.
 *
 * Ramulator's own interface is C++: classes, and a std::function invoked when a request completes. Neither crosses a C
 * ABI, so completions are queued here and taken by polling — the caller tags each request and gets its tags back.
 * Nothing else is added: no data, no address arithmetic, no policy. Those belong to whoever models the memory.
 */
#ifndef RAMULATOR_CAPI_H
#define RAMULATOR_CAPI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ramulator_t ramulator_t;

/** Build a memory system from an exported Ramulator configuration. NULL if it cannot be read. */
ramulator_t *ramulator_open(const char *config_path);
void ramulator_close(ramulator_t *r);

/** The DRAM clock period in nanoseconds, and the transaction granularity in bytes. */
double ramulator_tck_ns(const ramulator_t *r);
int32_t ramulator_tx_bytes(const ramulator_t *r);

/** Advance the memory system by one DRAM cycle. */
void ramulator_tick(ramulator_t *r);

/** Offer a request. Returns 0 when the controller's queue is full, in which case nothing was taken and the caller
 *  should try again after ticking. `tag` comes back from ramulator_poll when the request completes. */
int32_t ramulator_send(ramulator_t *r, int32_t is_write, uint64_t addr, int32_t size_bytes, uint64_t tag);

/** Take one completed request's tag, in completion order. Returns 0 when none is waiting. */
int32_t ramulator_poll(ramulator_t *r, uint64_t *tag);

#ifdef __cplusplus
}
#endif

#endif /* RAMULATOR_CAPI_H */
