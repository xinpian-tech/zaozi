package me.jiuyang.stdlib.prcm

import scala.annotation.tailrec
import scala.collection.immutable.Queue

/** Explicit-state search over the PRCM models. States need structural equality. The algorithms are those of zaozi
  * PR #159's PRCMGraph; the search also returns the counterexample it finds.
  */
private[prcm] object ModelChecker:

  /** Every state reachable from `initial`, breadth first; `initial` comes first. */
  def reachable[S](initial: S)(successors: S => Seq[S]): Vector[S] =
    @tailrec
    def visit(pending: Queue[S], seen: Set[S], result: Vector[S]): Vector[S] =
      pending.dequeueOption match
        case None               => result
        case Some((state, rest)) =>
          val fresh = successors(state).distinct.filterNot(seen)
          visit(rest.enqueueAll(fresh), seen ++ fresh, result ++ fresh)
    visit(Queue(initial), Set(initial), Vector(initial))

  /** A run that never finishes. Over `states` (the first is the initial state), it looks for a cycle of states that
    * are not `finished` under `step`, on which every fairness condition holds somewhere, and returns a prefix that
    * reaches it under `reach` together with the cycle. Successors outside `states` are ignored.
    */
  def stall[S](
    states:   Vector[S],
    reach:    S => Seq[S],
    step:     S => Seq[S],
    finished: S => Boolean,
    fairness: Seq[S => Boolean]
  ): Option[(Vector[S], Vector[S])] =
    val index  = states.zipWithIndex.toMap
    val all    = states.indices.toSet
    def edges(next: S => Seq[S]): Vector[Vector[Int]] = states.map(s => next(s).flatMap(index.get).distinct.toVector)
    val active = all.filterNot(i => finished(states(i)))
    val fair   = fairness.toVector.map(holds => all.filter(i => holds(states(i))))
    fairCycle(edges(step), active, fair).flatMap { loop =>
      path(edges(reach), 0, loop.head, all).map(prefix => prefix.map(states) -> loop.map(states))
    }

  private def components(edges: Vector[Vector[Int]], active: Set[Int]): Vector[Set[Int]] =
    @tailrec
    def finish(pending: List[(Int, Boolean)], seen: Set[Int], order: List[Int]): List[Int] = pending match
      case Nil                                                  => order
      case (node, true) :: rest                                 => finish(rest, seen, node :: order)
      case (node, false) :: rest if seen(node) || !active(node) => finish(rest, seen, order)
      case (node, false) :: rest                                =>
        finish(edges(node).map(_ -> false).toList ::: (node -> true) :: rest, seen + node, order)
    val order   = finish(active.toList.sorted.map(_ -> false), Set.empty, Nil)
    val reverse = edges.zipWithIndex.foldLeft(Vector.fill(edges.size)(Vector.empty[Int])) { case (result, (next, node)) =>
      next.foldLeft(result)((current, to) => current.updated(to, current(to) :+ node))
    }
    @tailrec
    def collect(pending: List[Int], seen: Set[Int], group: Set[Int]): (Set[Int], Set[Int]) = pending match
      case Nil                                         => seen -> group
      case node :: rest if seen(node) || !active(node) => collect(rest, seen, group)
      case node :: rest                                => collect(reverse(node).toList ::: rest, seen + node, group + node)
    order
      .foldLeft((Set.empty[Int], Vector.empty[Set[Int]])) { case ((seen, groups), node) =>
        if seen(node) then seen -> groups
        else
          val (visited, group) = collect(List(node), seen, Set.empty)
          visited -> (groups :+ group)
      }
      ._2

  private def path(edges: Vector[Vector[Int]], from: Int, to: Int, allowed: Set[Int]): Option[Vector[Int]] =
    @tailrec
    def search(pending: Queue[Int], previous: Map[Int, Int]): Option[Vector[Int]] =
      pending.dequeueOption match
        case None               => None
        case Some((node, rest)) =>
          if node == to then
            @tailrec
            def trace(current: Int, result: List[Int]): Vector[Int] =
              if current == from then (from :: result).toVector else trace(previous(current), current :: result)
            Some(trace(node, Nil))
          else
            val fresh = edges(node).filter(next => allowed(next) && !previous.contains(next))
            search(rest.enqueueAll(fresh), previous ++ fresh.map(_ -> node))
    search(Queue(from), Map(from -> from))

  private def fairCycle(edges: Vector[Vector[Int]], active: Set[Int], fairness: Vector[Set[Int]]): Option[Vector[Int]] =
    components(edges, active).iterator.flatMap { group =>
      val cyclic  = group.size > 1 || edges(group.head).contains(group.head)
      val witness = fairness.map(_ intersect group)
      if !cyclic || witness.exists(_.isEmpty) then None
      else
        val start = group.min
        val loop  = (witness.map(_.min) :+ start).foldLeft(Vector(start)) { (walk, next) =>
          walk ++ path(edges, walk.last, next, group).get.tail
        }
        if loop.size > 1 then Some(loop)
        else
          val next = edges(start).find(group).get
          Some(Vector(start) ++ path(edges, next, start, group).get)
    }.nextOption()
