package com.example



import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors

import com.example.types.DeviceIdentity
import com.example.types.Protocol._

import monocle.macros.syntax.lens._




object DeviceManager
{
  trait Command

  final case class
    RequestTrackDevice(
      identity: DeviceIdentity,
      replyTo: ActorRef[DeviceRegistered]
    ) extends DeviceManager.Command with DeviceGroup.Command

  final case class DeviceRegistered(device: ActorRef[Device.Command])

  final case class
    RequestDeviceList(
      requestId: RequestId,
      groupId: DeviceIdentity.GroupId,
      replyTo: ActorRef[ReplyDeviceList]
    ) extends DeviceManager.Command with DeviceGroup.Command
  final case class ReplyDeviceList(
    requestId: RequestId,
    ids: Set[DeviceIdentity.GroupId]
  )

  final case class
    RequestAllTemperatures(
      requestId: RequestId,
      groupId: DeviceIdentity.GroupId,
      replyTo: ActorRef[RespondAllTemperatures]
    ) extends DeviceGroupQuery.Command
      with DeviceGroup.Command
      with DeviceManager.Command

  final case class RespondAllTemperatures(
    requestId: RequestId,
    temperatures: Map[DeviceIdentity.Id, TemperatureReading]
  )

  private final case class
    DeviceGroupTerminated(groupId: DeviceIdentity.GroupId)
    extends DeviceManager.Command



  sealed trait TemperatureReading

  final case class Temperature(value: Device.Temperature)
    extends TemperatureReading
  case object TemperatureNotAvailable extends TemperatureReading
  case object DeviceNotAvailable extends TemperatureReading
  case object DeviceTimedOut extends TemperatureReading



  final case class Params(
    groups: Map[DeviceIdentity.GroupId, ActorRef[DeviceGroup.Command]]
  )



  def apply(params: Params): Behavior[Command] =
    Behaviors.setup(
      context =>
        Behaviors
          .receive(onMessage(_, _, params))
          .receiveSignal(
            signalParams => onSignal(signalParams._1, signalParams._2)
          )
    )


  def apply(): Behavior[Command] =
    DeviceManager(Params(Map.empty))



  private def onMessage(
    context: ActorContext[Command],
    msg: Command,
    params: Params
  ): Behavior[Command] =
    msg match {
      case msg @ RequestTrackDevice(DeviceIdentity(groupId, _), replyTo) =>
        findOrRegisterGroup(context, msg, params)

      case msg: RequestDeviceList =>
        tryPassDeviceListRequest(msg, params.groups)

      case DeviceGroupTerminated(groupId) =>
        removeGroup(context, groupId, params)
    }


  private def passTrackDeviceMsg(
    group: ActorRef[DeviceGroup.Command],
    msg: RequestTrackDevice,
    returned: Behavior[Command]
  ): Behavior[Command] =
  {
    group.tell(msg)

    returned
  }


  private def findOrRegisterGroup(
    context: ActorContext[Command],
    msg: RequestTrackDevice,
    params: Params
  ): Behavior[Command] =
    params.groups.get(msg.identity.groupId) match {
      case Some(group) => passTrackDeviceMsg(group, msg, Behaviors.same)

      case None => registerGroup(context, msg, params)
    }


  private def spawnGroup(
    groupId: DeviceIdentity.GroupId,
    context: ActorContext[Command]
  ): ActorRef[DeviceGroup.Command] =
  {
    context.log.info("Creating device group actor for {}", groupId)

    context.spawn(DeviceGroup(groupId), s"group-$groupId")
  }


  private def registerGroup(
    context: ActorContext[Command],
    msg: RequestTrackDevice,
    params: Params
  ): Behavior[Command] =
  {
    val groupId = msg.identity.groupId
    val group = spawnGroup(groupId, context)

    context.watchWith(group, DeviceGroupTerminated(groupId))

    passTrackDeviceMsg(
      group,
      msg,
      DeviceManager(params.lens(_.groups).modify(_.updated(groupId, group)))
    )
  }


  private def tryPassDeviceListRequest(
    msg: RequestDeviceList,
    groups: Map[DeviceIdentity.GroupId, ActorRef[DeviceGroup.Command]]
  ): Behavior[Command] =
  {
    groups.get(msg.groupId) match {
      case Some(group) => group.tell(msg)
      case None => msg.replyTo.tell(ReplyDeviceList(msg.requestId, Set.empty))
    }

    Behaviors.same
  }


  private def removeGroup(
    context: ActorContext[Command],
    groupId: DeviceIdentity.GroupId,
    params: Params
  ): Behavior[Command] =
  {
    context.log.info("Device group actor for {} has been terminated", groupId)

    DeviceManager(params.lens(_.groups).modify(_.removed(groupId)))
  }



  private def onSignal(
    context: ActorContext[Command],
    signal: Signal
  ): Behavior[Command] =
    signal match {
      case PostStop => onPostStopSignal(context)
    }


  private def onPostStopSignal(
    context: ActorContext[Command]
  ): Behavior[Command] =
  {
    context.log.info("DeviceManager stopped")

    Behaviors.same
  }
}
