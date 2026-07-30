    .section ".text"
    .globl _start
_start:
    la x4, buf
    addi x2, x0, 68
    sw x2, 0(x4)
    addi x7, x0, 170
    sb x7, 1(x4)
    addi x1, x0, 187
    sh x1, 2(x4)
    lw x3, 0(x4)
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
    .zero 64