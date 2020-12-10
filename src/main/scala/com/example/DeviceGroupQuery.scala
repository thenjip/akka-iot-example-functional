package com.example



import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.TimerScheduler
import akka.actor.typed.scaladsl.Behaviors

import com.example.Device.ReadTemperature
import com.example.DeviceManager.DeviceNotAvailable
import com.example.DeviceManager.DeviceTimedOut
import com.example.DeviceManager.Temperature
import com.example.DeviceManager.TemperatureNotAvailable
import com.example.DeviceManager.TemperatureReading
import com.example.types.DeviceIdentity
import com.example.types.Protocol._

import monocle.macros.GenLens
import monocle.macros.syntax.lens._

import scala.concurrent.duration.FiniteDuration



object DeviceGroupQuery
{
  trait Command

  final case class
    WrappedRespondTemperature(response: Device.RespondTemperature)
    extends Command

  private final case class
    DeviceTerminated(deviceId: DeviceIdentity.Id)
    extends Command

  private case object CollectionTimeout extends Command



  final case class PublicParams(
    devices: Map[DeviceIdentity.Id, ActorRef[Device.Command]],
    requestId: RequestId,
    requester: ActorRef[DeviceManager.RespondAllTemperatures],
    timeout: FiniteDuration
  )

  private final case class PrivateParams(
    timers: TimerScheduler[Command],
    repliesSoFar: Map[DeviceIdentity.Id, TemperatureReading],
    stillWaiting: Set[DeviceIdentity.Id]
  )

  private final case class Params(
    public: PublicParams,
    `private`: PrivateParams
  )



  def apply(params: PublicParams): Behavior[Command] =
    Behaviors.setup(
      context =>
        Behaviors.withTimers(
          timers => {
            timers.startSingleTimer(
              CollectionTimeout,
              CollectionTimeout,
              params.timeout
            )
            params.devices.foreachEntry(
              (deviceId, device) => {
                context.watchWith(device, DeviceTerminated(deviceId))
                device.tell(
                  Device.ReadTemperature(
                    0,
                    context.messageAdapter(WrappedRespondTemperature.apply)
                  )
                )
              }
            )

            DeviceGroupQuery(
              Params(
                params,
                PrivateParams(timers, Map.empty, params.devices.keySet)
              )
            )
          }
      )
    )


  private def apply(params: Params): Behavior[Command] =
    Behaviors.receiveMessage(onMessage(_, params))



  private def onMessage(msg: Command, params: Params): Behavior[Command] =
    msg match {
      case WrappedRespondTemperature(msg) => onRespondTemperature(msg, params)
      case DeviceTerminated(deviceId) => onDeviceTerminated(deviceId, params)
      case CollectionTimeout => onCollectionTimout(params)
    }


  private def onRespondTemperature(
    msg: Device.RespondTemperature,
    params: Params
  ): Behavior[Command] =
  {
    respondWhenAllCollected(
      params.lens(_.`private`).modify(updateReplies(msg, _))
    )
  }


  private def updateReplies(
    msg: Device.RespondTemperature,
    params: PrivateParams
  ): PrivateParams =
  {
    val reading =
      msg.value match {
        case Some(value) => DeviceManager.Temperature(value)
        case None        => TemperatureNotAvailable
      }
    val deviceId = msg.deviceId

    params
      .lens(_.repliesSoFar)
      .modify(_ + (deviceId -> reading))
      .lens(_.stillWaiting)
      .modify(_ - deviceId)
  }


  private def onDeviceTerminated(
    deviceId: DeviceIdentity.Id,
    params: Params
  ): Behavior[Command] =
  {
    respondWhenAllCollected(
      params.`private`.stillWaiting.contains(deviceId) match {
        case false => params

        case true =>
          params
            .lens(_.`private`)
            .modify(
              _
                .lens(_.repliesSoFar)
                .modify(_ + (deviceId -> DeviceNotAvailable))
                .lens(_.stillWaiting)
                .modify(_ - deviceId)
            )
      }
    )
  }


  private def onCollectionTimout(params: Params): Behavior[Command] =
  {
    respondWhenAllCollected(
      params
        .lens(_.`private`)
        .modify(
          priv =>
            priv
              .lens(_.repliesSoFar)
              .modify(
                _ ++ priv.stillWaiting.map(_ -> DeviceManager.DeviceTimedOut)
              ).lens(_.stillWaiting)
              .set(Set.empty)
        )
    )
  }


  private def respondWhenAllCollected(params: Params): Behavior[Command] =
    params match {
      case
        Params(
          PublicParams(_, requestId, requester, _),
          PrivateParams(_, repliesSoFar, stillWaiting)
        )
      =>
        stillWaiting.isEmpty match {
          case false => DeviceGroupQuery(params)

          case true => {
            requester.tell(
              DeviceManager.RespondAllTemperatures(requestId, repliesSoFar)
            )

            Behaviors.stopped
          }
        }
    }
}
