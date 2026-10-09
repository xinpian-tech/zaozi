
    .option norvc
    .section .text
    .globl _start

_start:
    lui   t0, %hi(UART_BASE)
    lui   t1, %hi(LOAD)
    addi  t1, t1, %lo(message)
next:
    lb    t2, 0(t1)
    beq   t2, x0, done
wait:
    lw    s0, 8(t0)
    andi  s0, s0, 1
    bne   s0, x0, wait
    sw    t2, 0(t0)
    addi  t1, t1, 1
    j     next

    .globl done
done:
    j     done

    .org 0x40
message:
    .asciz "hello world\n"
    .balign 4
