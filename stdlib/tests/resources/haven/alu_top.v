// Vendored from the HAVEN benchmark suite (github.com/mcc311/haven, MIT), hdl/alu/rtl/alu_top.v,
// as an external SystemVerilog-only IP target for the SvImport formal flow.
//-----------------------------------------------------------------------------
// ALU Top Module
// 32-bit ALU with FP add/sub, integer logic, shifts, FP2INT, NOT
//-----------------------------------------------------------------------------
module alu_top (
    input  wire        clk,
    input  wire        rst_n,
    input  wire [31:0] a,
    input  wire [31:0] b,
    input  wire [3:0]  op,
    input  wire        start,
    output reg  [31:0] result,
    output reg  [3:0]  flags,
    output reg         done
);

    //-------------------------------------------------------------------------
    // Opcode definitions
    //-------------------------------------------------------------------------
    localparam [3:0] OP_FP_ADD  = 4'h0;
    localparam [3:0] OP_FP_SUB  = 4'h1;
    localparam [3:0] OP_AND     = 4'h2;
    localparam [3:0] OP_OR      = 4'h3;
    localparam [3:0] OP_XOR     = 4'h4;
    localparam [3:0] OP_SLL     = 4'h5;
    localparam [3:0] OP_SRL     = 4'h6;
    localparam [3:0] OP_SRA     = 4'h7;
    localparam [3:0] OP_FP2INT  = 4'h8;
    localparam [3:0] OP_NOT     = 4'h9;

    // Flag bit positions
    localparam FLAG_OVERFLOW  = 3;
    localparam FLAG_UNDERFLOW = 2;
    localparam FLAG_ZERO      = 1;
    localparam FLAG_INVALID   = 0;

    //-------------------------------------------------------------------------
    // Internal signals
    //-------------------------------------------------------------------------
    // Operation type decode
    wire is_int_op   = (op == OP_AND) || (op == OP_OR) || (op == OP_XOR) || (op == OP_NOT);
    wire is_shift_op = (op == OP_SLL) || (op == OP_SRL) || (op == OP_SRA);
    wire is_fp_op    = (op == OP_FP_ADD) || (op == OP_FP_SUB);
    wire is_fp2int   = (op == OP_FP2INT);
    wire is_reserved = (op >= 4'hA);

    // Integer unit results
    reg [31:0] int_result;

    // Shift unit results
    reg [31:0] shift_result;

    // FP unit signals
    reg [31:0] fp_result;
    reg [3:0]  fp_flags;
    reg        fp_done;
    reg [2:0]  fp_counter;

    // FP2INT unit signals
    reg [31:0] fp2int_result;
    reg        fp2int_invalid;
    reg        fp2int_done;
    reg        fp2int_counter;

    // Pipeline registers for FP
    reg        fp_active;
    reg [31:0] fp_a_reg, fp_b_reg;
    reg        fp_is_sub;

    // Pipeline registers for FP2INT
    reg        fp2int_active;
    reg [31:0] fp2int_a_reg;

    // Single-cycle done for int/shift ops
    wire       int_done_r;
    reg [3:0]  op_r;
    reg [31:0] a_r, b_r;

    //-------------------------------------------------------------------------
    // Integer Unit (combinational)
    //-------------------------------------------------------------------------
    always @(*) begin
        case (op)
            OP_AND:  int_result = a & b;
            OP_OR:   int_result = a | b;
            OP_XOR:  int_result = a ^ b;
            OP_NOT:  int_result = ~a;
            default: int_result = 32'h0;
        endcase
    end

    //-------------------------------------------------------------------------
    // Shift Unit (combinational)
    //-------------------------------------------------------------------------
    wire [4:0] shamt = b[4:0];
    wire signed [31:0] a_signed = a;

    always @(*) begin
        case (op)
            OP_SLL:  shift_result = a << shamt;
            OP_SRL:  shift_result = a >> shamt;
            OP_SRA:  shift_result = a_signed >>> shamt;
            default: shift_result = 32'h0;
        endcase
    end

    //-------------------------------------------------------------------------
    // FP Add/Sub Unit (4-cycle pipeline)
    //-------------------------------------------------------------------------
    // IEEE 754 field extraction
    wire        a_sign = fp_a_reg[31];
    wire [7:0]  a_exp  = fp_a_reg[30:23];
    wire [22:0] a_frac = fp_a_reg[22:0];
    wire        b_sign_raw = fp_b_reg[31];
    wire        b_sign = fp_is_sub ? ~b_sign_raw : b_sign_raw;
    wire [7:0]  b_exp  = fp_b_reg[30:23];
    wire [22:0] b_frac = fp_b_reg[22:0];

    // Special value detection
    wire a_is_zero = (a_exp == 8'h00) && (a_frac == 23'h0);
    wire b_is_zero = (b_exp == 8'h00) && (b_frac == 23'h0);
    wire a_is_inf  = (a_exp == 8'hFF) && (a_frac == 23'h0);
    wire b_is_inf  = (b_exp == 8'hFF) && (b_frac == 23'h0);
    wire a_is_nan  = (a_exp == 8'hFF) && (a_frac != 23'h0);
    wire b_is_nan  = (b_exp == 8'hFF) && (b_frac != 23'h0);
    wire a_is_denorm = (a_exp == 8'h00) && (a_frac != 23'h0);
    wire b_is_denorm = (b_exp == 8'h00) && (b_frac != 23'h0);

    // Mantissa with implicit 1 (or 0 for denormals/zero)
    wire [23:0] a_mant = a_is_zero ? 24'h0 : (a_is_denorm ? 24'h0 : {1'b1, a_frac});
    wire [23:0] b_mant = b_is_zero ? 24'h0 : (b_is_denorm ? 24'h0 : {1'b1, b_frac});

    // Stage 1-4 pipeline registers
    reg [7:0]  s1_exp_diff;
    reg [23:0] s1_large_mant, s1_small_mant;
    reg        s1_large_sign;
    reg [7:0]  s1_large_exp;
    reg        s1_is_nan, s1_is_inf, s1_inf_sign;
    reg        s1_is_zero_result;
    reg        s1_same_sign;

    reg [26:0] s2_aligned_small;
    reg [26:0] s2_large_mant_ext;
    reg        s2_large_sign;
    reg [7:0]  s2_exp;
    reg        s2_is_nan, s2_is_inf, s2_inf_sign;
    reg        s2_is_zero_result;
    reg        s2_same_sign;

    reg [27:0] s3_mant_sum;
    reg        s3_result_sign;
    reg [7:0]  s3_exp;
    reg        s3_is_nan, s3_is_inf, s3_inf_sign;
    reg        s3_is_zero_result;

    // Stage 4 normalization variables (moved outside for Verilog-2001 compatibility)
    reg [7:0]  s4_new_exp;
    reg [7:0]  s4_shift_amt;
    reg [22:0] s4_final_frac;
    reg        s4_round_up;
    reg [23:0] s4_rounded_mant;
    reg [27:0] s4_shifted_mant;

    // Leading zero count function
    function [4:0] lzc;
        input [27:0] val;
        integer i;
        begin
            lzc = 5'd28;
            for (i = 27; i >= 0; i = i - 1) begin
                if (val[i] && lzc == 5'd28)
                    lzc = 5'd27 - i[4:0];
            end
        end
    endfunction

    // FP pipeline state machine
    always @(posedge clk) begin
        if (!rst_n) begin
            fp_active <= 1'b0;
            fp_counter <= 3'd0;
            fp_done <= 1'b0;
            fp_result <= 32'h0;
            fp_flags <= 4'h0;
        end else begin
            fp_done <= 1'b0;

            if (start && is_fp_op && !fp_active) begin
                // Capture inputs
                fp_active <= 1'b1;
                fp_a_reg <= a;
                fp_b_reg <= b;
                fp_is_sub <= (op == OP_FP_SUB);
                fp_counter <= 3'd1;
            end else if (fp_active) begin
                fp_counter <= fp_counter + 1'b1;

                case (fp_counter)
                    3'd1: begin
                        // Stage 1: Unpack and compare
                        s1_is_nan <= a_is_nan || b_is_nan;
                        s1_is_inf <= a_is_inf || b_is_inf;

                        // Inf - Inf = NaN
                        if (a_is_inf && b_is_inf && (a_sign != b_sign)) begin
                            s1_is_nan <= 1'b1;
                            s1_is_inf <= 1'b0;
                        end

                        s1_inf_sign <= a_is_inf ? a_sign : b_sign;
                        s1_is_zero_result <= a_is_zero && b_is_zero;

                        // Compare magnitudes
                        if (a_exp > b_exp || (a_exp == b_exp && a_mant >= b_mant)) begin
                            s1_large_mant <= a_mant;
                            s1_small_mant <= b_mant;
                            s1_large_sign <= a_sign;
                            s1_large_exp <= a_exp;
                            s1_exp_diff <= a_exp - b_exp;
                        end else begin
                            s1_large_mant <= b_mant;
                            s1_small_mant <= a_mant;
                            s1_large_sign <= b_sign;
                            s1_large_exp <= b_exp;
                            s1_exp_diff <= b_exp - a_exp;
                        end
                        s1_same_sign <= (a_sign == b_sign);
                    end

                    3'd2: begin
                        // Stage 2: Align mantissas
                        s2_is_nan <= s1_is_nan;
                        s2_is_inf <= s1_is_inf;
                        s2_inf_sign <= s1_inf_sign;
                        s2_is_zero_result <= s1_is_zero_result;
                        s2_exp <= s1_large_exp;
                        s2_large_sign <= s1_large_sign;
                        s2_same_sign <= s1_same_sign;

                        // Extend mantissas with guard bits
                        s2_large_mant_ext <= {s1_large_mant, 3'b000};

                        // Shift smaller mantissa
                        if (s1_exp_diff >= 8'd27)
                            s2_aligned_small <= 27'h0;
                        else
                            s2_aligned_small <= {s1_small_mant, 3'b000} >> s1_exp_diff;
                    end

                    3'd3: begin
                        // Stage 3: Add/subtract mantissas
                        s3_is_nan <= s2_is_nan;
                        s3_is_inf <= s2_is_inf;
                        s3_inf_sign <= s2_inf_sign;
                        s3_is_zero_result <= s2_is_zero_result;
                        s3_exp <= s2_exp;
                        s3_result_sign <= s2_large_sign;

                        if (s2_same_sign) begin
                            // Addition
                            s3_mant_sum <= {1'b0, s2_large_mant_ext} + {1'b0, s2_aligned_small};
                        end else begin
                            // Subtraction (large - small, always positive)
                            s3_mant_sum <= {1'b0, s2_large_mant_ext} - {1'b0, s2_aligned_small};
                        end
                    end

                    3'd4: begin
                        // Stage 4: Normalize and round
                        fp_active <= 1'b0;
                        fp_done <= 1'b1;
                        fp_flags <= 4'h0;

                        if (s3_is_nan) begin
                            fp_result <= 32'h7FC0_0000; // Quiet NaN
                            fp_flags[FLAG_INVALID] <= 1'b1;
                        end else if (s3_is_inf) begin
                            fp_result <= {s3_inf_sign, 8'hFF, 23'h0};
                        end else if (s3_is_zero_result || s3_mant_sum == 0) begin
                            fp_result <= 32'h0;
                            fp_flags[FLAG_ZERO] <= 1'b1;
                        end else begin
                            // Normalize (using module-level regs for Verilog-2001)
                            if (s3_mant_sum[27]) begin
                                // Overflow from addition, shift right by 1
                                s4_new_exp = s3_exp + 1;
                                s4_final_frac = s3_mant_sum[26:4];
                                s4_round_up = s3_mant_sum[3] && (s3_mant_sum[2:0] != 0 || s3_mant_sum[4]);
                            end else if (s3_mant_sum[26]) begin
                                // Normal case, implicit 1 at bit 26
                                s4_new_exp = s3_exp;
                                s4_final_frac = s3_mant_sum[25:3];
                                s4_round_up = s3_mant_sum[2] && (s3_mant_sum[1:0] != 0 || s3_mant_sum[3]);
                            end else begin
                                // Need to normalize (shift left)
                                s4_shift_amt = {3'b0, lzc(s3_mant_sum)};
                                if (s4_shift_amt > s3_exp + 1) begin
                                    s4_new_exp = 8'h0;
                                    s4_final_frac = 23'h0;
                                    s4_round_up = 1'b0;
                                end else begin
                                    s4_shifted_mant = s3_mant_sum << (s4_shift_amt - 1);
                                    s4_new_exp = s3_exp - (s4_shift_amt - 1);
                                    s4_final_frac = s4_shifted_mant[25:3];
                                    s4_round_up = s4_shifted_mant[2] && (s4_shifted_mant[1:0] != 0 || s4_shifted_mant[3]);
                                end
                            end

                            // Apply rounding
                            s4_rounded_mant = {1'b0, s4_final_frac} + {23'b0, s4_round_up};

                            // Check for overflow from rounding
                            if (s4_rounded_mant[23]) begin
                                s4_new_exp = s4_new_exp + 1;
                                s4_final_frac = s4_rounded_mant[23:1];
                            end else begin
                                s4_final_frac = s4_rounded_mant[22:0];
                            end

                            // Check for exponent overflow/underflow
                            if (s4_new_exp >= 8'hFF) begin
                                fp_result <= {s3_result_sign, 8'hFF, 23'h0};
                                fp_flags[FLAG_OVERFLOW] <= 1'b1;
                            end else if (s4_new_exp == 0) begin
                                fp_result <= 32'h0;
                                fp_flags[FLAG_UNDERFLOW] <= 1'b1;
                            end else begin
                                fp_result <= {s3_result_sign, s4_new_exp, s4_final_frac};
                            end
                        end
                    end

                    default: begin
                        fp_active <= 1'b0;
                    end
                endcase
            end
        end
    end

    //-------------------------------------------------------------------------
    // FP2INT Unit (2-cycle pipeline)
    //-------------------------------------------------------------------------
    wire        f2i_sign = fp2int_a_reg[31];
    wire [7:0]  f2i_exp  = fp2int_a_reg[30:23];
    wire [22:0] f2i_frac = fp2int_a_reg[22:0];
    wire        f2i_is_nan = (f2i_exp == 8'hFF) && (f2i_frac != 23'h0);

    // FP2INT conversion variables (module-level for Verilog-2001)
    reg [31:0] f2i_mant_ext;
    reg [7:0]  f2i_shift_right;
    reg [31:0] f2i_int_val;
    wire        f2i_is_inf = (f2i_exp == 8'hFF) && (f2i_frac == 23'h0);
    wire        f2i_is_zero = (f2i_exp == 8'h00);

    always @(posedge clk) begin
        if (!rst_n) begin
            fp2int_active <= 1'b0;
            fp2int_counter <= 1'b0;
            fp2int_done <= 1'b0;
            fp2int_result <= 32'h0;
            fp2int_invalid <= 1'b0;
        end else begin
            fp2int_done <= 1'b0;

            if (start && is_fp2int && !fp2int_active) begin
                fp2int_active <= 1'b1;
                fp2int_a_reg <= a;
                fp2int_counter <= 1'b0;
            end else if (fp2int_active) begin
                fp2int_counter <= fp2int_counter + 1'b1;

                if (fp2int_counter == 1'b0) begin
                    // Stage 1: Check special cases
                    // (just pass through, actual conversion in stage 2)
                end else begin
                    // Stage 2: Convert
                    fp2int_active <= 1'b0;
                    fp2int_done <= 1'b1;
                    fp2int_invalid <= 1'b0;

                    if (f2i_is_nan) begin
                        fp2int_result <= 32'h0;
                        fp2int_invalid <= 1'b1;
                    end else if (f2i_is_zero) begin
                        fp2int_result <= 32'h0;
                    end else if (f2i_is_inf || f2i_exp >= 8'd158) begin
                        // Overflow: exp >= 127+31 = 158
                        fp2int_result <= f2i_sign ? 32'h8000_0000 : 32'h7FFF_FFFF;
                    end else if (f2i_exp < 8'd127) begin
                        // |value| < 1, truncate to 0
                        fp2int_result <= 32'h0;
                    end else begin
                        // Normal conversion (using module-level regs)
                        f2i_mant_ext = {1'b1, f2i_frac, 8'h0};
                        f2i_shift_right = 8'd158 - f2i_exp;

                        if (f2i_shift_right >= 32)
                            f2i_int_val = 32'h0;
                        else
                            f2i_int_val = f2i_mant_ext >> f2i_shift_right;

                        if (f2i_sign)
                            fp2int_result <= ~f2i_int_val + 1;
                        else
                            fp2int_result <= f2i_int_val;
                    end
                end
            end
        end
    end

    //-------------------------------------------------------------------------
    // Single-cycle operation done generation
    // For int/shift/reserved ops: done pulses 1 cycle after inputs are set
    //-------------------------------------------------------------------------
    reg [1:0] int_done_pipe;

    always @(posedge clk) begin
        if (!rst_n) begin
            int_done_pipe <= 2'b00;
            op_r <= 4'h0;
            a_r <= 32'h0;
            b_r <= 32'h0;
        end else begin
            // Pipeline stage 0: detect input change
            // Pipeline stage 1: generate done
            int_done_pipe[0] <= (is_int_op || is_shift_op || is_reserved) &&
                                ((a != a_r) || (b != b_r) || (op != op_r));
            int_done_pipe[1] <= int_done_pipe[0];

            op_r <= op;
            a_r <= a;
            b_r <= b;
        end
    end

    assign int_done_r = int_done_pipe[1];

    //-------------------------------------------------------------------------
    // Output Mux
    //-------------------------------------------------------------------------
    always @(posedge clk) begin
        if (!rst_n) begin
            result <= 32'h0;
            flags <= 4'h0;
            done <= 1'b0;
        end else begin
            done <= 1'b0;
            flags <= 4'h0;

            if (fp_done) begin
                result <= fp_result;
                flags <= fp_flags;
                done <= 1'b1;
            end else if (fp2int_done) begin
                result <= fp2int_result;
                flags[FLAG_INVALID] <= fp2int_invalid;
                flags[FLAG_ZERO] <= (fp2int_result == 32'h0);
                done <= 1'b1;
            end else if (int_done_r) begin
                case (op_r)
                    OP_AND, OP_OR, OP_XOR, OP_NOT: begin
                        result <= int_result;
                        flags[FLAG_ZERO] <= (int_result == 32'h0);
                    end
                    OP_SLL, OP_SRL, OP_SRA: begin
                        result <= shift_result;
                        flags[FLAG_ZERO] <= (shift_result == 32'h0);
                    end
                    default: begin
                        // Reserved opcodes
                        result <= 32'h0;
                        flags <= 4'h0;
                    end
                endcase
                done <= 1'b1;
            end
        end
    end

endmodule
