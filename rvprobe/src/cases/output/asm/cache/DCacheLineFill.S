    .section ".text"
    .globl _start
_start:
    la x2, buf
    lw x3, 0(x2)
    lw x1, 4(x2)
    lw x7, 8(x2)
    lw x4, 60(x2)
    lw x8, 64(x2)
    lw x9, 68(x2)
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