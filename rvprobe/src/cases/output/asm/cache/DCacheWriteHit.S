    .section .text
    .globl _start
_start:
    la      x5, buf
    lw x10, 0(x5)
    li      x11, 0x11223344
    sw x11, 0(x5)
    lw x12, 0(x5)
exit:
    addi x5, x0, 1
    la      x6, tohost
    sd x5, 0(x6)
spin:
    j spin

    .section .data
    .balign 64
buf:
    .word 0x12345678
    .zero 60
    .section .tohost,"aw",@progbits
    .align 6
    .globl tohost
    .globl fromhost
tohost:
    .dword 0x0
fromhost:
    .dword 0x0