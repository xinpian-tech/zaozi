package me.jiuyang.syntheke

import utest.*

import Fixtures.{*, given}

object NegotiationSpec extends TestSuite:
  private val here = "NegotiationSpec.scala:"

  val tests: Tests = Tests:
    test("a port parameter function reads opposite nodes and a domain as a value"):
      val resolved = Negotiator.negotiate(Design("Reads") {
        val clock = Clock.declare(7)
        val adder = generator[Int] {
          val in   = named("in")(inward(Bus)())
          val out  = named("out")(outward(Bus)())
          val up   = in.fixed(())
          val down = out.derive(in)(width => Right(width + clock.definition))
          parameters(_ => Right(0))
          (up, down)
        }
        val feed  = source(1)
        val drain = sink
        adder._1 <-- feed
        drain <-- adder._2
        EmptyTuple
      })
      val widths   = resolved.edges.map(_.edgeAs(Bus))
      assert(widths.sorted == Vector(1, 8))

    test("a node's domain of a kind it is not in is a located error"):
      val message = failure(Design("NoClock") {
        generator[Int] {
          outward(Bus)().domain(Clock)
          parameters(_ => Right(0))
        }
        EmptyTuple
      })
      assert(message.contains("is in no clock domain"), message.contains(here))

    test("a full parameter reading another module's node is a located error"):
      val message = failure(Design("Foreign") {
        val other  = source(1)
        val drain  = sink
        val reader = generator[Int](parameters(view => Right(view.edgeOf(other))))
        drain <-- other
        EmptyTuple
      })
      assert(message.contains("is not a node of"), message.contains(here))

    test("domains are declared at the design root"):
      val message = failure(Design("Inner") {
        generator[Int] {
          Clock.declare(1)
          parameters(_ => Right(0))
        }
        EmptyTuple
      })
      assert(message.contains("domains are declared at the design root"), message.contains(here))

    test("a design hands out only its own boundaries"):
      val inner   = Design[OutwardBoundary[Bus.type]]("Inner")(source(1).boundary(())())
      val message = failure(Design("Middle")(inner.instantiate.ports))
      assert(message.contains("which is not one of its boundaries"), message.contains(here))

    test("a design does not instantiate itself"):
      lazy val looping: Design[EmptyTuple] = Design("Loop") {
        looping.instantiate
        EmptyTuple
      }
      assert(failure(looping).contains("instantiates itself"))

    test("an interface carrying a probe is reported at every bind"):
      def peek(
        using WrapperScope,
        sourcecode.Name,
        sourcecode.File,
        sourcecode.Line
      ) = generator[Int] {
        val out = named("out")(outward(Peek)().fixed(()))
        val in  = named("in")(inward(Peek)().fixed(()))
        parameters(_ => Right(0))
        (out, in)
      }
      val message = failure(Design("Peeks") {
        val a = peek
        val b = peek
        b._2 <-- a._1
        a._2 <-- b._1
        EmptyTuple
      })
      assert(message.startsWith("interfaces failed"), message.split("contains a probe").length == 3)

    test("each instance holds its own copies of the frozen domains its boundaries reach"):
      val frozen   = Design[OutwardBoundary[ResetWire.type]]("Frozen") {
        val clock = Clock.declare(1)
        val reset = Reset.declare(clock)
        val out   = generator[Int] {
          val out = named("reset")(outward(ResetWire)(reset).fixed(()))
          parameters(_ => Right(0))
          out
        }
        named("reset")(out.boundary(())(reset))
      }
      var released = Vector.empty[Domain[Clock.type]]
      Negotiator.negotiate(Design("User") {
        Vector("a", "b").foreach { name =>
          val instance = named(name)(frozen.instantiate)
          val reset    = instance.ports.domain(Reset)
          val in       = named(s"${name}Sink")(generator[Int] {
            val in = named("in")(inward(ResetWire)(reset).fixed(()))
            parameters { _ =>
              released :+= reset.definition
              Right(0)
            }
            in
          })
          in <-- instance.ports
        }
        EmptyTuple
      })
      assert(released.map(_.id.show) == Vector("a@clock", "b@clock"), released.map(_.definition) == Vector(1, 1))

    test("a carrier bind joins nodes of one domain"):
      val message = failure(Design("Apart") {
        val clock = Clock.declare(1)
        val a     = Reset.declare(clock)
        val b     = Reset.declare(clock)
        val out   = generator[Int] {
          val out = named("out")(outward(ResetWire)(a).fixed(()))
          parameters(_ => Right(0))
          out
        }
        val sink  = generator[Int] {
          val in = named("in")(inward(ResetWire)(b).fixed(()))
          parameters(_ => Right(0))
          in
        }
        sink <-- out
        EmptyTuple
      })
      assert(message.contains("carries reset domains, yet joins"))

    test("a fed boundary stands for the frozen domain"):
      val frozen   = Design[InwardBoundary[ResetWire.type]]("Frozen") {
        val reset = Reset.declare(named("clock")(Clock.declare(1)))
        val in    = generator[Int] {
          val in = named("in")(inward(ResetWire)(reset).fixed(()))
          parameters(_ => Right(0))
          in
        }
        named("reset")(in.boundary(())(reset))
      }
      val resolved = Negotiator.negotiate(Design("Feeder") {
        val reset    = Reset.declare(named("clock")(Clock.declare(1)))
        val out      = generator[Int] {
          val out = named("out")(outward(ResetWire)(reset).fixed(()))
          parameters(_ => Right(0))
          out
        }
        val instance = frozen.instantiate(ports => Seq(ports -> Seq(reset)))
        instance.ports <-- out
        EmptyTuple
      })
      assert(resolved.spec.domains.count(_.kind eq Reset) == 1)

    test("a fed domain has the properties of the frozen domain it takes the place of"):
      val frozen  = Design[InwardBoundary[Bus.type]]("Frozen") {
        val clock = Clock.declare(1)
        val in    = generator[Int] {
          val in = named("in")(inward(Bus)(clock).fixed(()))
          parameters(_ => Right(0))
          in
        }
        named("bus")(in.boundary(1)(clock))
      }
      val message = failure(Design("Feeder") {
        val clock = Clock.declare(2)
        frozen.instantiate(ports => Seq(ports -> Seq(clock)))
        EmptyTuple
      })
      assert(message.contains("whose clock properties differ"), message.contains(here))

    test("a boundary of another design is a located error"):
      val frozen  = Design[OutwardBoundary[Bus.type]]("Frozen")(source(1).boundary(())())
      val other   = Design[OutwardBoundary[Bus.type]]("Other")(source(2).boundary(())())
      val message = failure(Design("Reader") {
        frozen.edgeOf(other.ports)
        EmptyTuple
      })
      assert(message.contains("is not a boundary of Frozen"), message.contains(here))
