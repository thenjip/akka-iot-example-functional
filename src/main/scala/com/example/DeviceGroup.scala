package com.example



import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.Signal
import akka.actor.typed.PostStop
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors

import com.example.DeviceManager.DeviceRegistered
import com.example.DeviceManager.RequestAllTemperatures
import com.example.DeviceManager.RequestTrackDevice
import com.example.types.DeviceIdentity
import com.example.types.Protocol._

import monocle.macros.syntax.lens._

import scala.concurrent.duration.DurationInt

import scalaz.Id._



object DeviceGroup
{
  trait Command

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

  private final case class
    DeviceTerminated(device: ActorRef[Device.Command], identity: DeviceIdentity)
    extends Command



  final case class Params(
    groupId: DeviceIdentity.GroupId,
    devices: Map[DeviceIdentity.Id, ActorRef[Device.Command]]
  )



  def apply(params: Params): Behavior[Command] =
  {
    Behaviors.setup(
      context =>
        Behaviors
          .receive(onMessage(_, _, params))
          .receiveSignal(
            signalParams =>
              onSignal(signalParams._1, signalParams._2, params.groupId)
          )
    )
  }


  def apply(groupId: DeviceIdentity.GroupId): Behavior[Command] =
    DeviceGroup(Params(groupId, Map.empty))



  private def onMessage(
    context: ActorContext[Command],
    msg: Command,
    params: Params
  ): Behavior[Command] =
  {
    val groupId = params.groupId

    msg match {
      case msg @ RequestTrackDevice(DeviceIdentity(`groupId`, _), replyTo) =>
        findOrRegisterDevice(context, msg, params)

      case RequestTrackDevice(DeviceIdentity(gId, _), _) =>
        warnWrongDeviceGroup(context, gId, groupId)

      case msg: RequestDeviceList => trySendDevicesForGroup(msg, params)

      case DeviceTerminated(_, DeviceIdentity(_, deviceId)) =>
        removeDevice(context, deviceId, params)

      case msg: RequestAllTemperatures =>
        spawnQueryIfSameGroup(context, msg, params)
    }
  }


  private def tellDeviceRegistered(
    replyTo: ActorRef[DeviceRegistered],
    device: ActorRef[Device.Command],
    returned: Behavior[Command]
  ): Behavior[Command] =
  {
    replyTo.tell(DeviceRegistered(device))

    returned
  }


  private def findOrRegisterDevice(
    context: ActorContext[Command],
    msg: RequestTrackDevice,
    params: Params
  ): Behavior[Command] =
  {
    params.devices.get(msg.identity.deviceId) match {
      case Some(device) =>
        tellDeviceRegistered(msg.replyTo, device, Behaviors.same)

      case None => registerDevice(context, msg, params)
    }
  }


  private def spawnDevice(
    identity: DeviceIdentity,
    context: ActorContext[Command]
  ): ActorRef[Device.Command] =
  {
    val deviceId = identity.deviceId

    context.log.info("Creating device actor for {}", deviceId)

    context.spawn(Device(identity), s"device-$deviceId")
  }


  private def registerDevice(
    context: ActorContext[Command],
    msg: RequestTrackDevice,
    params: Params
  ): Behavior[Command] =
  {
    val device = spawnDevice(msg.identity, context)

    context.watchWith(device, DeviceTerminated(device, msg.identity))

    tellDeviceRegistered(
      msg.replyTo,
      device,
      DeviceGroup(
        params.lens(_.devices).modify(_.updated(msg.identity.deviceId, device))
      )
    )
  }


  private def warnWrongDeviceGroup(
    context: ActorContext[Command],
    wrongGroupId: DeviceIdentity.GroupId,
    groupId: DeviceIdentity.GroupId
  ): Behavior[Command] =
  {
    context.log.warn(
      "Ignoring TrackDevice request for {}. This actor is responsible for {}.",
      wrongGroupId,
      groupId
    )

    Behaviors.same
  }


  private def sendDevices(
    replyTo: ActorRef[ReplyDeviceList],
    requestId: RequestId,
    devices: Map[DeviceIdentity.Id, ActorRef[Device.Command]]
  ): Behavior[Command] =
  {
    replyTo.tell(ReplyDeviceList(requestId, devices.keySet))

    Behaviors.same
  }


  private def trySendDevicesForGroup(
    msg: RequestDeviceList,
    params: Params
  ): Behavior[Command] =
  {
    msg.groupId match {
      case params.groupId =>
        sendDevices(msg.replyTo, msg.requestId, params.devices)

      case _ => Behaviors.unhandled
    }
  }


  private def removeDevice(
    context: ActorContext[Command],
    deviceId: DeviceIdentity.Id,
    params: Params
  ): Behavior[Command] =
  {
    context.log.info("Device actor for {} has been terminated", deviceId)

    DeviceGroup(params.lens(_.devices).modify(_.removed(deviceId)))
  }


  private def spawnQueryIfSameGroup(
    context: ActorContext[Command],
    msg: RequestAllTemperatures,
    params: Params
  ): Behavior[Command] =
    msg.groupId match {
      case params.groupId => {
        context.spawnAnonymous(
          DeviceGroupQuery(
            DeviceGroupQuery.PublicParams(
              params.devices,
              msg.requestId,
              msg.replyTo,
              3.seconds
            )
          )
        )

        Behaviors.same
      }

      case _ => Behaviors.unhandled
    }



  private def onSignal(
    context: ActorContext[Command],
    signal: Signal,
    groupId: DeviceIdentity.GroupId
  ): Behavior[Command] =
    signal match {
      case PostStop => onPostStopSignal(context, groupId)
    }


  private def onPostStopSignal(
    context: ActorContext[Command],
    groupId: DeviceIdentity.GroupId
  ): Behavior[Command] =
  {
    context.log.info("DeviceGroup {} stopped", groupId)

    Behaviors.same
  }
}
