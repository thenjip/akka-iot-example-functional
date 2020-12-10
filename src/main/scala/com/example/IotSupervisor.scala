package com.example



import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.PostStop
import akka.actor.typed.Signal



object IotSupervisor
{
  def apply(): Behavior[Nothing] =
    Behaviors.setup[Nothing](
      context => {
        context.log.info("IoT Application started")

        Behaviors
          .receiveMessage[Nothing](onMessage)
          .receiveSignal(params => onSignal(params._1, params._2))
      }
    )



  private def onMessage(msg: Nothing): Behavior[Nothing] =
    Behaviors.unhandled


  private def onSignal(
    context: ActorContext[Nothing],
    signal: Signal
  ): Behavior[Nothing] =
  {
    signal match {
      case PostStop => {
        context.log.info("IoT Application stopped")
        Behaviors.same
      }
    }
  }
}
