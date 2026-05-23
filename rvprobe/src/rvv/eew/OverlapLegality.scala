// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.eew

import me.jiuyang.rvprobe.rvv.{OperandRole, Schema}
import me.jiuyang.rvprobe.rvv.vtype.VTypeEnvelope

/** Static legality of vector-register-group assignment. Enforces the
 *  RVV-spec rules about source/destination overlap. Per-instruction
 *  layer supplies the `OverlapRule` set; this module evaluates whether
 *  a proposed (role -> register-index) assignment is legal under the
 *  rule set.
 */
object OverlapLegality:

  final case class Assignment(
    role:  OperandRole,
    index: Int,
    width: Int // EMUL.asWholeRegisters
  ):
    def occupies: Range = index until (index + width)

  /** Result of evaluating one or more `OverlapRule`s against an
   *  assignment list. `Right` means legal; `Left` carries an
   *  human-readable description.
   */
  type LegalityResult = Either[String, Unit]

  def check(
    assignments: List[Assignment],
    rules:       List[OverlapRule]
  ): LegalityResult =
    val errors = rules.flatMap(r => check1(assignments, r).left.toOption)
    if errors.isEmpty then Right(()) else Left(errors.mkString("; "))

  private def check1(
    assignments: List[Assignment],
    rule:        OverlapRule
  ): LegalityResult =
    def find(role: OperandRole): Option[Assignment] =
      assignments.find(_.role == role)

    def overlaps(a: Assignment, b: Assignment): Boolean =
      a.occupies.exists(b.occupies.contains)

    rule match
      case OverlapRule.None                          => Right(())
      case OverlapRule.DestNoVs1Overlap              =>
        (find(OperandRole.Vd), find(OperandRole.Vs1)) match
          case (Some(d), Some(s)) if overlaps(d, s) =>
            Left(s"vd@${d.index}..${d.index + d.width - 1} overlaps vs1@${s.index}..${s.index + s.width - 1}")
          case _                                    => Right(())
      case OverlapRule.DestNoVs2Overlap              =>
        (find(OperandRole.Vd), find(OperandRole.Vs2)) match
          case (Some(d), Some(s)) if overlaps(d, s) =>
            Left(s"vd@${d.index}..${d.index + d.width - 1} overlaps vs2@${s.index}..${s.index + s.width - 1}")
          case _                                    => Right(())
      case OverlapRule.DestNoMaskOverlap             =>
        // When a vector instruction has an active mask in v0, dest
        // cannot be v0 itself.
        find(OperandRole.Vd) match
          case Some(d) if d.occupies.contains(0) =>
            Left(s"vd@${d.index} overlaps mask register v0")
          case _                                 => Right(())
      case OverlapRule.WideningDestSourceOverlap     =>
        // For widening (dest EMUL = 2 * source EMUL), dest may overlap
        // sources only at the highest-numbered part of dest. The
        // simple form here disallows any overlap; a finer-grained
        // rule for the "highest-numbered" exception can be added when
        // the per-instruction layer needs it.
        val dOpt = find(OperandRole.Vd)
        val sOpt = find(OperandRole.Vs2).orElse(find(OperandRole.Vs1))
        (dOpt, sOpt) match
          case (Some(d), Some(s)) if overlaps(d, s) =>
            Left(s"widening dest@${d.index}..${d.index + d.width - 1} overlaps source@${s.index}..${s.index + s.width - 1}")
          case _                                    => Right(())

  /** Build an assignment list from a per-role footprint map and a
   *  candidate register-index assignment. Convenience for tests +
   *  driver call sites.
   */
  def assignments(
    footprints: Map[OperandRole, Int],
    indices:    Map[OperandRole, Int]
  ): List[Assignment] =
    footprints.toList.map { case (role, w) =>
      val idx = indices.getOrElse(role, 0)
      Assignment(role, idx, w)
    }
