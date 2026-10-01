// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.cell

/** One entry of a `function` row: a pin level or a text column. */
enum CellValue:
  case Level(value: Option[Boolean])
  case Text(value: String)

/** A `function` row with its columns in written order, so pins keep their first-appearance order. */
case class CellRow(entries: Seq[(String, CellValue)]):
  require(entries.map(_._1).distinct.size == entries.size, "a function row names a column twice")

  def levels: Seq[(String, Option[Boolean])] = entries.collect { case (name, CellValue.Level(value)) => name -> value }
  def texts:  Seq[(String, String)]          = entries.collect { case (name, CellValue.Text(value)) => name -> value }

object CellRow:
  /** Pins in the order they first appear across `rows`. */
  def pins(rows: Seq[CellRow]): Seq[String] = rows.flatMap(_.levels.map(_._1)).distinct

given upickle.default.ReadWriter[CellRow] =
  upickle.default
    .readwriter[ujson.Value]
    .bimap[CellRow](
      row =>
        ujson.Obj.from(row.entries.map:
          case (name, CellValue.Level(Some(value))) => name -> ujson.Num(if value then 1 else 0)
          case (name, CellValue.Level(None))        => name -> ujson.Str("x")
          case (name, CellValue.Text(value))        => name -> ujson.Str(value)),
      json =>
        CellRow(json.obj.toSeq.map:
          case (name, ujson.Num(0))                     => name -> CellValue.Level(Some(false))
          case (name, ujson.Num(1))                     => name -> CellValue.Level(Some(true))
          case (name, ujson.Str("x"))                   => name -> CellValue.Level(None)
          case (name, ujson.Str(text)) if text.nonEmpty => name -> CellValue.Text(text)
          case (name, value)                            =>
            throw new IllegalArgumentException(s"function column $name: $value is not 0, 1, x or text"))
    )
