package com.example



import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt



class DeviceGroupQueryTest
  extends ScalaTestWithActorTestKit with AnyWordSpecLike
{
  import com.example.DeviceGroupQuery._



  "DeviceGroupQuery actor" must {
    "return temperature value for working devices" in {
      val requester = createTestProbe[DeviceManager.RespondAllTemperatures]()

      val device1 = createTestProbe[Device.Command]()
      val device2 = createTestProbe[Device.Command]()

      val deviceIdToActor =
        Map("device1" -> device1.ref, "device2" -> device2.ref)

      val queryActor =
        spawn(
          DeviceGroupQuery(
            PublicParams(deviceIdToActor, 1, requester.ref, 3.seconds)
          )
        )

      device1.expectMessageType[Device.ReadTemperature]
      device2.expectMessageType[Device.ReadTemperature]

      queryActor.tell(
        WrappedRespondTemperature(
          Device.RespondTemperature(requestId = 0, "device1", Some(1.0))
        )
      )
      queryActor.tell(
        WrappedRespondTemperature(
          Device.RespondTemperature(requestId = 0, "device2", Some(2.0))
        )
      )

      requester.expectMessage(
        DeviceManager.RespondAllTemperatures(
          requestId = 1,
          temperatures =
            Map(
              "device1" -> DeviceManager.Temperature(1.0),
              "device2" -> DeviceManager.Temperature(2.0)
            )
        )
      )
    }


    "return TemperatureNotAvailable for devices with no readings" in {
      val requester = createTestProbe[DeviceManager.RespondAllTemperatures]()

      val device1 = createTestProbe[Device.Command]()
      val device2 = createTestProbe[Device.Command]()

      val deviceIdToActor =
        Map("device1" -> device1.ref, "device2" -> device2.ref)

      val queryActor =
        spawn(
          DeviceGroupQuery(
            PublicParams(deviceIdToActor, 1, requester.ref, 3.seconds)
          )
        )

      device1.expectMessageType[Device.ReadTemperature]
      device2.expectMessageType[Device.ReadTemperature]

      queryActor.tell(
        WrappedRespondTemperature(
          Device.RespondTemperature(requestId = 0, "device1", None)
        )
      )
      queryActor.tell(
        WrappedRespondTemperature(
          Device.RespondTemperature(requestId = 0, "device2", Some(2.0))
        )
      )

      requester.expectMessage(
        DeviceManager.RespondAllTemperatures(
          requestId = 1,
          Map(
            "device1" -> DeviceManager.TemperatureNotAvailable,
            "device2" -> DeviceManager.Temperature(2.0)
          )
        )
      )
    }


    "return DeviceNotAvailable if device stops before answering" in {
      val requester = createTestProbe[DeviceManager.RespondAllTemperatures]()

      val device1 = createTestProbe[Device.Command]()
      val device2 = createTestProbe[Device.Command]()

      val deviceIdToActor =
        Map("device1" -> device1.ref, "device2" -> device2.ref)

      val queryActor =
        spawn(
          DeviceGroupQuery(
            PublicParams(deviceIdToActor, 1, requester.ref, 3.seconds)
          )
        )

      device1.expectMessageType[Device.ReadTemperature]
      device2.expectMessageType[Device.ReadTemperature]

      queryActor.tell(
        WrappedRespondTemperature(
          Device.RespondTemperature(requestId = 0, "device1", Some(2.0))
        )
      )

      device2.stop()

      requester.expectMessage(
        DeviceManager.RespondAllTemperatures(
          requestId = 1,
          Map(
            "device1" -> DeviceManager.Temperature(2.0),
            "device2" -> DeviceManager.DeviceNotAvailable
          )
        )
      )
    }
  }
}
