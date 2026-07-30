    .section ".text"
    .globl _start
_start:
    jal x1, code_area

    la x5, code_area
    li x6, 0x200513
    sw x6, 0(x5)
    fence.i
    jal x1, code_area

exit:
    li x5, 0x0
    slli x5, x5, 1
    ori x5, x5, 1
    la x6, tohost
    sd x5, 0(x6)
spin:
    j spin

    .balign 8
code_area:
    .dword 0x806700100513
    .section ".tohost","aw",@progbits
    .align 6
    .globl tohost
tohost:
    .dword 0x0
    .align 6
    .globl fromhost
fromhost:
    .dword 0x0