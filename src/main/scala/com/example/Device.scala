package com.example



import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.Signal
import akka.actor.typed.PostStop

import com.example.types.DeviceIdentity
import com.example.types.Protocol._

import monocle.macros.syntax.lens._



object Device
{
  type Temperature = Double



  sealed trait Command

  final case class
    ReadTemperature(
      requestId: RequestId,
      replyTo: ActorRef[RespondTemperature]
    ) extends Command
  final case class RespondTemperature(
    requestId: RequestId,
    deviceId: DeviceIdentity.Id,
    value: Option[Temperature]
  )

  final case class
    RecordTemperature(
      requestId: RequestId,
      value: Temperature,
      replyTo: ActorRef[TemperatureRecorded]
    ) extends Command
  final case class TemperatureRecorded(requestId: RequestId)

  case object Passivate extends Command



  final case class Params(
    identity: DeviceIdentity,
    lastReadTemperature: Option[Temperature]
  )



  def apply(params: Params): Behavior[Command] =
    Behaviors.setup(
      context =>
        Behaviors
          .receive(onMessage(_, _, params))
          .receiveSignal(
            signalParams =>
              onSignal(signalParams._1, signalParams._2, params.identity)
          )
    )


  def apply(identity: DeviceIdentity): Behavior[Command] =
    Device(Params(identity, None))



  private def onMessage(
    context: ActorContext[Command],
    msg: Command,
    params: Params
  ): Behavior[Command] =
    msg match {
      case msg: ReadTemperature =>
        readTemperature(
          msg,
          params.lastReadTemperature,
          params.identity.deviceId
        )

      case msg: RecordTemperature => recordTemperature(context, msg, params)

      case Passivate => Behaviors.stopped
    }


  private def onSignal(
    context: ActorContext[Command],
    signal: Signal,
    identity: DeviceIdentity
  ): Behavior[Command] =
    signal match {
      case PostStop => onPostStopSignal(context, identity)
    }


  private def onPostStopSignal(
    context: ActorContext[Command],
    identity: DeviceIdentity
  ): Behavior[Command] =
  {
    context.log.info(
      "Device actor {}-{} stopped",
      identity.groupId,
      identity.deviceId
    )

    Behaviors.same
  }



  private def readTemperature(
    msg: ReadTemperature,
    lastReadTemperature: Option[Temperature],
    deviceId: DeviceIdentity.Id
  ): Behavior[Command] =
  {
    msg
      .replyTo
      .tell(RespondTemperature(msg.requestId, deviceId, lastReadTemperature))

    Behaviors.same
  }


  private def recordTemperature(
    context: ActorContext[Command],
    msg: RecordTemperature,
    params: Params
  ): Behavior[Command] =
  {
    context.log.info(
      "Recorded temperature reading {} with {}",
      msg.value,
      msg.requestId
    )
    msg.replyTo.tell(TemperatureRecorded(msg.requestId))

    Device(params.lens(_.lastReadTemperature).set(Some(msg.value)))
  }
}
