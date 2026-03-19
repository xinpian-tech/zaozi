    .section .text
    .globl _start
_start:
    la      x5, buf
    sw x0, 0(x5)
    addi x10, x0, 170
    sb x10, 1(x5)
    lw x11, 0(x5)
exit:
    addi x5, x0, 1
    la      x6, tohost
    sd x5, 0(x6)
spin:
    j spin

    .section .data
    .balign 64
buf:
    .zero 64
    .section .tohost,"aw",@progbits
    .align 6
    .globl tohost
    .globl fromhost
tohost:
    .dword 0x0
fromhost:
    .dword 0x0