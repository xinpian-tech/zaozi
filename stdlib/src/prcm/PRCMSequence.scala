// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

private[prcm] enum PRCMPhase:
  case Init, Off, Power, Clock, Reset, Release, Connect, Resume, Run, Drain, Isolate, Stop
  case FaultRelease, Fault, FaultOff, FaultPower

private[prcm] case class PRCMFeedback(power: Boolean, reset: Boolean, isolation: Boolean, idle: Boolean):
  def bits: Int = (if power then 1 else 0) | (if reset then 2 else 0) |
    (if isolation then 4 else 0) | (if idle then 8 else 0)

private[prcm] object PRCMFeedback:
  def fromBits(value: Int): PRCMFeedback =
    PRCMFeedback((value & 1) != 0, (value & 2) != 0, (value & 4) != 0, (value & 8) != 0)

private[prcm] case class PRCMControl(
  power:     Boolean,
  clock:     Boolean,
  reset:     Boolean,
  isolation: Boolean,
  quiesce:   Boolean)

private[prcm] object PRCMSequence:
  import PRCMPhase.*

  def control(phase: PRCMPhase): PRCMControl = phase match
    case Init | Off | FaultOff             => PRCMControl(false, false, true, true, true)
    case Power | FaultPower | Stop | Fault => PRCMControl(true, false, true, true, true)
    case Clock | Reset                     => PRCMControl(true, true, true, true, true)
    case Release | Isolate                 => PRCMControl(true, true, false, true, true)
    case Connect | Drain                   => PRCMControl(true, true, false, false, true)
    case Resume | Run                      => PRCMControl(true, true, false, false, false)
    case FaultRelease                      => PRCMControl(true, false, true, true, false)

  def fault(phase: PRCMPhase): Boolean = phase match
    case FaultRelease | Fault | FaultOff | FaultPower => true
    case _                                            => false

  def powerLost(phase: PRCMPhase, power: Boolean): Boolean =
    val watching = phase match
      case Clock | Reset | Release | Connect | Resume | Run | Drain | Isolate | Stop => true
      case _                                                                         => false
    watching && !power

  def step(phase: PRCMPhase, target: PRCMTarget, feedback: PRCMFeedback, serviceFault: Boolean): PRCMPhase =
    val releasing = !control(phase).quiesce && feedback.idle
    if serviceFault && !fault(phase) then
      phase match
        case Init | Off => FaultOff
        case Power      => FaultPower
        case _          => if releasing then FaultRelease else Fault
    else if powerLost(phase, feedback.power) then if releasing then FaultRelease else Fault
    else
      val protectedDomain = feedback.reset && feedback.isolation
      val off             = target == PRCMTarget.Off
      val run             = target == PRCMTarget.Run
      phase match
        case Init                                                  => Off
        case Off if !off && !feedback.power && protectedDomain     => Power
        case Power if feedback.power && protectedDomain            => if off then Off else Clock
        case Clock | Reset if protectedDomain                      =>
          if off then Stop else if run then Release else Reset
        case Release if !feedback.reset && feedback.isolation      => if run then Connect else Reset
        case Connect if !feedback.isolation && feedback.idle       => if run then Resume else Isolate
        case Resume if !feedback.idle                              => if run then Run else Drain
        case Run if !run                                           => Drain
        case Drain if feedback.idle                                => if run then Resume else Isolate
        case Isolate if feedback.isolation                         => if run then Connect else Reset
        case Stop if protectedDomain                               => if off then Off else Clock
        case FaultRelease if !feedback.idle                        => Fault
        case Fault if protectedDomain && feedback.idle             => FaultOff
        case FaultPower if feedback.power                          => Fault
        case FaultOff if off && !feedback.power && protectedDomain => Off
        case _                                                     => phase

  def complete(phase: PRCMPhase, target: PRCMTarget, feedback: PRCMFeedback): Boolean = target match
    case PRCMTarget.Off   =>
      phase == Off && !feedback.power && feedback.reset && feedback.isolation && feedback.idle
    case PRCMTarget.Reset =>
      phase == Reset && feedback.power && feedback.reset && feedback.isolation && feedback.idle
    case PRCMTarget.Run   =>
      phase == Run && feedback.power && !feedback.reset && !feedback.isolation && !feedback.idle

private[prcm] enum PRCMServicePhase:
  case Idle, Wait, Hold, Return

private[prcm] object PRCMServiceSequence:
  import PRCMServicePhase.*

  def step(phase: PRCMServicePhase, need: Boolean, grant: Boolean, fault: Boolean, released: Boolean)
    : PRCMServicePhase =
    phase match
      case Idle if need                         => Wait
      case Wait if fault                        => Return
      case Wait if grant                        => Hold
      case Hold if (!need || fault) && released => Return
      case Return if !grant                     => Idle
      case _                                    => phase

  def request(phase: PRCMServicePhase): Boolean = phase == Wait || phase == Hold
