    .section ".text"
    .globl _start
_start:
    la x7, buf
    lw x1, 0(x7)
    lw x2, 4(x7)
    lw x3, 8(x7)
    lw x4, 60(x7)
    lw x5, 64(x7)
    lw x6, 68(x7)
exit:
    li x5, 0x0
    slli x5, x5, 1
    ori x5, x5, 1
    la x6, tohost
    sd x5, 0(x6)
spin:
    j spin

fail:
    li x5, 0x1
    slli x5, x5, 1
    ori x5, x5, 1
    la x6, tohost
    sd x5, 0(x6)
spin_fail:
    j spin_fail

    .section ".tohost","aw",@progbits
    .align 6
    .globl tohost
tohost:
    .dword 0x0
    .align 6
    .globl fromhost
fromhost:
    .dword 0x0
    .section .data
    .balign 64
buf:
    .zero 256