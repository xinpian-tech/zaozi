Dear Jianhao Ye,

We regret to inform you that the following submission was not selected by the DAC 2026 Technical Program Committee for publication:

2284: RVProbe: An eDSL-based Framework for Directed Test Generation

The selection process was very competitive with a 22.3% acceptance ratio.

Nonetheless, we still hope you and your co-authors will attend DAC in July in Long Beach, CA. We would like to have you there and to participate in this excellent program. We hope that you will continue to submit your work to DAC and that you will be an author of a DAC paper in future.

Reviewer scores and comments on your submission are available below.

Thank you,

Natarajan Viswanathan, Jiang Hu, Rajiv Joshi
DAC 2026 TPC Chairs


===

============================================================================ 
DAC 2026 Reviews for Submission #2284
============================================================================ 

Title: RVProbe: An eDSL-based Framework for Directed Test Generation
Authors: Jianhao Ye, Yongjian Li, Yuhang Zeng, Xinrui Yang, Yang Liu, Jiuyang Liu and Shaowei Cai


============================================================================
                            REVIEWER #1
============================================================================

---------------------------------------------------------------------------
Reviewer's Scores
---------------------------------------------------------------------------
           Clarity / Writing Style (1-5): 3
      Originality / Innovativeness (1-5): 4
    Impact of Ideas and/or Results (1-5): 4
            OVERALL RECOMMENDATION (1-5): 3

Summarize shortly the contributions of the paper in your own words.
---------------------------------------------------------------------------
The aim of this work is to fill in the gaps left by Constrained Random Verification (CRV). The approach abstracts and automates the generation of test data using high-level abstractions. The approach is white-box, however - the user needs to know what to test.
---------------------------------------------------------------------------


Strengths
---------------------------------------------------------------------------
+ High-level representation, so not tied to a particular implementation
+ Automatic generation of tests
+ Complementary to CRV
---------------------------------------------------------------------------


Weaknesses
---------------------------------------------------------------------------
- A lot of material - the paper is hard to follow 
- Limited set of test cases
---------------------------------------------------------------------------


Main Discussion of Paper
---------------------------------------------------------------------------
CRV is limited by its very nature - random tests will not necessarily cover all functionality. The approach described here is complementary, in that it attempts to generate directed tests to cover specific conditions. The authors have chosen to take a high-level approach and to use existing tools and frameworks, where possible, to avoid becoming limited by specific representations of a RISC-V processor. This seems to me to be a laudable objective. 

The paper contains a lot of detail and is very dense. I had to work hard to understand the subtleties (and indeed, I may have missed something!). Nevertheless, I think the approach is shown to be effective. My main concern is that this appears to be limited to white-box testing. The user has to know which tests to generate. If I understand the paper correctly, the increase in functional coverage described in section 4.1.2 was achieved by knowing exactly what needed to be covered. Similarly, the error in 4.2.3 was demonstrated by specifying a directed test. I don't intend this to be a criticism of the work, as such, but it it is a clear limitation of the approach.

One small point - I think Fig. 3 is misleading at first sight. Table 2 suggests that the method scales linearly, and this is claimed in the text. But the choice of points on the x-axis of Fig. 3 makes the trend appear quadratic.
---------------------------------------------------------------------------



============================================================================
                            REVIEWER #2
============================================================================

---------------------------------------------------------------------------
Reviewer's Scores
---------------------------------------------------------------------------
           Clarity / Writing Style (1-5): 3
      Originality / Innovativeness (1-5): 3
    Impact of Ideas and/or Results (1-5): 2
            OVERALL RECOMMENDATION (1-5): 1

Summarize shortly the contributions of the paper in your own words.
---------------------------------------------------------------------------
The paper presents a framework for directed test generation that uses an  embedded domain-specific language. According to the introduction, the contribution is three-fold: 1) A type-safe eDSL design, 2) an extensible design based on meta-programming, and 3) a high-perfomance constraint flow. The authors describe their approach and evaluate it. Therefore, the authors investigate three research questions: RQ1) Can RVProbe resolve functional coverage holes? RQ2) Is the engineering cost of directed generation justifiable? RQ3) What is the end-to-end generation latency?
---------------------------------------------------------------------------


Strengths
---------------------------------------------------------------------------
+ Interesting and important topic
+ Collecting knowledge explicitly using DSLs is a good idea
---------------------------------------------------------------------------


Weaknesses
---------------------------------------------------------------------------
- Contributions are not supported by research questions
- Methodology description lacks details, leading to a bad reproducibility
- Directed tests are created by manually adding constraints, so the increase in coverage is absolutely expected.
---------------------------------------------------------------------------


Main Discussion of Paper
---------------------------------------------------------------------------
The paper is well written in terms of language. However, the presentation of the work and the research results could be improved. Some sections of the text lack precision, e.g., Section 1.1 refers to a "low abstraction level" without explaining more precisely what the abstraction level refers to. Another example is the description of the layers: "A metadata-driven Scala eDSL that prioritizes expressiveness and correctness." The paper does not explain what metadata is in this context, where it originates from, and how expressiveness and correctness are supported. Such imprecicenesses occur several times throughout the entire paper and the reader has to fill these gaps, which makes the paper not easy to follow. Furthermore, the contributions and the research questions examined are not fully aligned. RQ1 and RQ2 do not support any of the mentioned contributions. RQ3 (the measurement of latency) relates to contribution 3.

Section 3, Design and Implementation, introduces the proposed framework. The section focuses on the program data pipeline, which is an interesting aspect, but it neglects a thorough introduction to the methodology used. (Why do the arrows in Figure 1 point in both directions? Is it possible to translate SMT expressions into the DSL representation?) The mentioned DSL, one of the key selling points, is not introduced in detail. There is no grammar, nor is the DSL embedding explained in detail. Based on the explanation, it is not clear how the approach works in detail, and the reproducibility is not guaranteed.

The evaluation, contained in Section 4, investigates three main research questions. To answer the first research question, the authors "employed a two-phase complementary verification flow". Neither the methodology nor the evaluation describes in detail how the proposed approach supports directed testing. The second research question is not answered explicitly. The only information related to additional effort is "RVProbe required only 1,000 additional directed instructions". Section 4.2 of the evaluation is not associated with any research question.

While test generation is an interesting and relevant topic, the presented work is not yet ready for publication. The main research idea needs further refinement and alignment with the evaluation to make a valuable conference contribution. 

Strengths
+ Interesting and important topic
+ Collecting knowledge explicitly using DSLs is a good idea

Weaknesses
- Contributions are not supported by research questions
- Methodology description lacks details, leading to a bad reproducibility
- Directed tests are created by manually adding constraints, so the increase in coverage is absolutely expected.
---------------------------------------------------------------------------



============================================================================
                            REVIEWER #3
============================================================================

---------------------------------------------------------------------------
Reviewer's Scores
---------------------------------------------------------------------------
           Clarity / Writing Style (1-5): 3
      Originality / Innovativeness (1-5): 3
    Impact of Ideas and/or Results (1-5): 4
            OVERALL RECOMMENDATION (1-5): 3

Summarize shortly the contributions of the paper in your own words.
---------------------------------------------------------------------------
RVProbe framework to create targeted tests for RISCV processors. Using scala based language hit corner cases in testing instead of traditional random testing.
---------------------------------------------------------------------------


Strengths
---------------------------------------------------------------------------
+high level constraints being written which makes it easier to write and more thorough.
+Scala 3 language to generate APIs to reduce the manual effort
+Improves coverage closure.
---------------------------------------------------------------------------


Weaknesses
---------------------------------------------------------------------------
-Relying on Scala 3 which may limits is usage
-Setup time is large
-relying on SMT solvers making it bottleneck for complex constraints
---------------------------------------------------------------------------


Main Discussion of Paper
---------------------------------------------------------------------------
RVProbe take abstract test cases to low level constraints instead of manually writing them. Results show this can close coverage gaps much faster than random testing. White-box example exposes real pipeline bugs. Heavy reliance on Scala may pose learning curve for existing teams.
---------------------------------------------------------------------------



============================================================================
                            REVIEWER #4
============================================================================

---------------------------------------------------------------------------
Reviewer's Scores
---------------------------------------------------------------------------
           Clarity / Writing Style (1-5): 3
      Originality / Innovativeness (1-5): 3
    Impact of Ideas and/or Results (1-5): 2
            OVERALL RECOMMENDATION (1-5): 1

Summarize shortly the contributions of the paper in your own words.
---------------------------------------------------------------------------
The paper focuses on generating the directed test to analyse the corner cases that are hard to identify using constrained random verification.
---------------------------------------------------------------------------


Strengths
---------------------------------------------------------------------------
Directed test generation is indeed useful for identifying the corner cases that are typically missed by the CRV
---------------------------------------------------------------------------


Weaknesses
---------------------------------------------------------------------------
Novelty is not clear

Comparison not clear with the state of the art
---------------------------------------------------------------------------


Main Discussion of Paper
---------------------------------------------------------------------------
The paper motivates that the work is based on the idea of identify hard to find coroner cases. However, it then moves to describing the advantages of using Scala and MLIR over Python. 

The advantages shown for MLIR and Scala are indeed known, and hence, there have been several works that have used these in the design and verification processes. However, the paper does not mention the approach that allowed them to boost the coverage as compared to CRV.

The prior literature is not reviewed, and comparisons with the state-of-the-art techniques are missing. Hence, it is unclear how the proposed methodology advances the field of directed testing. There are several approaches to implementing directed testing, using techniques such as "DirectFuzz: Automated Test Generation for RTL Designs using Directed Graybox Fuzzing", etc., that have proposed methods on how to identify corner cases and enable directed testing.
---------------------------------------------------------------------------


-- 
DAC 2026 - https://softconf.com/dac26/research
