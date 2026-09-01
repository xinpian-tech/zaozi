// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
//
// What the demo SoC runs once a debugger has downloaded it and let a hart go: walk a string and put each byte on the
// UART, polling the busy bit between them. RV32E, so only x0–x15 exist; this uses t0, t1, t2 and s0.
//
// UART_BASE and LOAD are handed to the assembler by the build, which reads them from the design: the address map is
// the SoC's, and a program that disagreed with it would be a program for another chip.

    .option norvc                       // the trace log reads better one 32-bit instruction per line
    .section .text
    .globl _start

_start:
    lui   t0, %hi(UART_BASE)            // t0 = the UART
    lui   t1, %hi(LOAD)
    addi  t1, t1, %lo(message)          // t1 = the string, which the linker placed at LOAD + %lo
next:
    lb    t2, 0(t1)                     // the next character
    beq   t2, x0, done                  // NUL ends the walk
wait:
    lw    s0, 8(t0)                     // STATUS
    andi  s0, s0, 1                     // bit 0: the transmitter is busy
    bne   s0, x0, wait
    sw    t2, 0(t0)                     // TXDATA
    addi  t1, t1, 1
    j     next

    // Where hart 0 parks, and where hart 1 arrives when the string is done. The debugger starts hart 0 here.
    .globl done
done:
    j     done

    .org 0x40
message:
    .asciz "hello world\n"
    .balign 4                           // the debugger downloads whole words
