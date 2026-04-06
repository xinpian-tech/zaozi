    .text
    .globl test
    .p2align 2
test:
    li   t0, -1
    vsetvli t0, t0, e32, m1, ta, ma

    # v8 = {0, 1, 2, ..., 127}
    vid.v v8

    # Index vector: v2 = vid XOR 127 = {127, 126, ..., 0} (reversed)
    # This is THE pattern that triggers the bug:
    # - Step 0 (element 0): reads v8[127] (high element)
    # - Step 127 (element 127): reads v8[0] (low element!)
    # - elementMask at step 1: bit 0 set ("element 0 done")
    # - WriteCheck for v8[0]: mask says done → allow write
    # - But gather still needs v8[0] at step 127!
    vid.v v2
    li   t1, 127
    vxor.vx v2, v2, t1        # v2 = {127, 126, ..., 1, 0}

    # Reference (safe, no concurrent write)
    vrgather.vv v6, v8, v2    # v6 = {127, 126, ..., 0}

    # Poison
    li   t1, 999
    vmv.v.x v7, t1

    # === Critical WAR pair ===
    vrgather.vv v1, v8, v2    # A: reversed gather (step 127 reads v8[0])
    vadd.vv v8, v7, v7        # B: writes v8[0]=1998 early (step ~0)

    # If bug: v1[127] = 1998 instead of 0 (gather read corrupted v8[0])
    vmsne.vv v0, v1, v6
    vcpop.m t2, v0

    ret
