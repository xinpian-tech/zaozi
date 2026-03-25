// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.privilege

/** Tracks Sv39 page table configuration for static verification of S-mode code/data permissions.
  *
  * Catches bugs where the code region lacks V+X+A, which causes INSN_PAGE_FAULT(12) on the first instruction fetch in
  * S-mode. The model is reset per test invocation by [[PrivilegeProbeLib.textStartWithTrap]] and verified automatically
  * by [[PrivilegeProbeLib.switchToSMode]].
  *
  * Only the initial page table configuration is tracked; runtime PTE modifications (e.g. in VMSfenceVmaRemap) are not
  * modeled. This is sufficient to catch the most common class of permission bugs.
  */
class PageTableModel:
  import PageTableModel.*

  private var _mapping: Option[Mapping] = None

  /** Register a single gigapage identity mapping (root[2] leaf). */
  def registerGigapage(pteFlags: Long): Unit =
    _mapping = Some(Mapping.Gigapage(pteFlags))

  /** Register a two-level mapping with separate code/data megapage permissions. */
  def registerTwoLevel(codeFlags: Long, dataFlags: Long): Unit =
    _mapping = Some(Mapping.TwoLevel(codeFlags, dataFlags))

  /** Verify that the code region supports S-mode instruction fetch.
    *
    * Leaf PTEs for code pages must have V=1, X=1, A=1. Missing any of these causes INSN_PAGE_FAULT(12) on the first
    * instruction fetch in S-mode.
    *
    * @param label
    *   the S-mode entry label (for error context)
    */
  def verifyCodeFetchable(label: String = ""): Unit =
    val codeFlags = _mapping match
      case Some(Mapping.Gigapage(flags))        => Some(flags)
      case Some(Mapping.TwoLevel(codeFlags, _)) => Some(codeFlags)
      case None                                 => None

    codeFlags.foreach { flags =>
      val missing = Seq(
        (PTE.V, "V (Valid)"),
        (PTE.X, "X (Execute)"),
        (PTE.A, "A (Accessed)")
      ).collect { case (bit, name) if (flags & bit) == 0 => name }

      if missing.nonEmpty then
        val ctx = if label.nonEmpty then s" for '$label'" else ""
        throw new IllegalStateException(
          f"Code region PTE 0x${flags}%02x missing: ${missing.mkString(", ")}$ctx. " +
            "S-mode code pages require V+X+A for instruction fetch. " +
            "Use setupCodeDataPageTable() to separate code and data permissions."
        )
    }

object PageTableModel:

  /** Sv39 PTE flag bit positions. */
  object PTE:
    val V: Long = 0x01L
    val R: Long = 0x02L
    val W: Long = 0x04L
    val X: Long = 0x08L
    val U: Long = 0x10L
    val G: Long = 0x20L
    val A: Long = 0x40L
    val D: Long = 0x80L

  enum Mapping:
    case Gigapage(pteFlags: Long)
    case TwoLevel(codeFlags: Long, dataFlags: Long)
