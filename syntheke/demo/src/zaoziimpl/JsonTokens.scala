// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo.zaoziimpl

import upickle.default.ReadWriter

/** The `@generator` macro derives a mainargs CLI for every Parameter, so a parameter with nested fields needs a reader
  * for them: each such field reads as one JSON token. Every `given …Tokens` in this package is one of these.
  */
private[zaoziimpl] def jsonTokens[T: ReadWriter](name: String): mainargs.TokensReader.Simple[T] =
  new mainargs.TokensReader.Simple[T]:
    def shortName = name
    def read(strs: Seq[String]): Either[String, T] =
      try Right(upickle.default.read[T](strs.last))
      catch case e: Exception => Left(e.getMessage)
