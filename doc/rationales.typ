= Rationales

== Operand-Agnostic Domain APIs

Domain-level API bundles such as `UIntApi`, `SIntApi`, `BitsApi`, and `BoolApi` describe which operations exist for a data domain, not the concrete shapes of the operands in a particular call. Operand roles belong only to the operators that need them: unary operators quantify the receiver, binary operators quantify `LHS` and `RHS` at the method level, and shift or indexing operators take their concrete argument forms at the method level as well. This keeps capability requirements honest and stable in generic code, allows different `Referable` implementations to compose naturally, and makes the type API reflect semantic structure instead of encoding call-site accidents into the trait shape.
