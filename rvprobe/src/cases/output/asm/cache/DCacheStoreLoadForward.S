    .section ".text"
    .globl _start
_start:
    la x5, buf
    addi x1, x0, 42
    sw x1, 0(x5)
    lw x11, 0(x5)
exit:
    li x5, 0x0
    slli x5, x5, 1
    ori x5, x5, 1
    la x6, tohost
    sd x5, 0(x6)
spin:
    j spin

    .section .data
    .balign 64
buf:
    .zero 64
    .section ".tohost","aw",@progbits
    .align 6
    .globl tohost
tohost:
    .dword 0x0
    .align 6
    .globl fromhost
fromhost:
    .dword 0x0