    .section .text
    .globl _start
_start:
    jal x1, code_area

    la x5, code_area
    li x6, 0x200513
    sw x6, 0(x5)
    fence.i
    jal x1, code_area

exit:
    addi x5, x0, 1
    la x6, tohost
    sd x5, 0(x6)
spin:
    j spin

    .align 2
code_area:
    addi x10, x0, 1
    jalr x0, x1, 0
    .section .tohost,"aw",@progbits
    .align 6
    .globl tohost
    .globl fromhost
tohost:
    .dword 0x0
fromhost:
    .dword 0x0