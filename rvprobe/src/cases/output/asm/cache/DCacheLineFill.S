    .section ".text"
    .globl _start
_start:
    la x5, buf
    lw x10, 0(x5)
    lw x11, 4(x5)
    lw x12, 8(x5)
    lw x13, 60(x5)
    lw x10, 64(x5)
    lw x11, 68(x5)
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
    .zero 256
    .section ".tohost","aw",@progbits
    .align 6
    .globl tohost
tohost:
    .dword 0x0
    .align 6
    .globl fromhost
fromhost:
    .dword 0x0