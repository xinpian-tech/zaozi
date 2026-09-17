package me.jiuyang.syntheke.demo.zaoziimpl

import upickle.default.ReadWriter

private[demo] def jsonTokens[T: ReadWriter](name: String): mainargs.TokensReader.Simple[T] =
  new mainargs.TokensReader.Simple[T]:
    def shortName = name
    def read(strs: Seq[String]): Either[String, T] =
      try Right(upickle.default.read[T](strs.last))
      catch case e: Exception => Left(e.getMessage)
