package com.example



import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

import com.example.Device.Passivate
import com.example.Device.RecordTemperature
import com.example.Device.TemperatureRecorded
import com.example.DeviceManager.DeviceRegistered
import com.example.DeviceManager.RequestTrackDevice
import com.example.types.DeviceIdentity

import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.DurationInt
import com.example.DeviceManager.RespondAllTemperatures
import com.example.DeviceManager.RequestAllTemperatures
import com.example.DeviceManager.TemperatureNotAvailable



class DeviceGroupTest extends ScalaTestWithActorTestKit with AnyWordSpecLike
{
  import DeviceGroup._



  "DeviceGroup actor" must {
    "be able to register a device actor" in {
      val probe = createTestProbe[DeviceRegistered]()
      val groupActor = spawn(DeviceGroup("group"))

      groupActor.tell(
        RequestTrackDevice(DeviceIdentity("group", "device1"), probe.ref)
      )
      val registered1 = probe.receiveMessage()
      val deviceActor1 = registered1.device

      // another deviceId
      groupActor.tell(
        RequestTrackDevice(DeviceIdentity("group", "device2"), probe.ref)
      )
      val registered2 = probe.receiveMessage()
      val deviceActor2 = registered2.device
      deviceActor1 should !==(deviceActor2)

      // Check that the device actors are working
      val recordProbe = createTestProbe[TemperatureRecorded]()
      deviceActor1.tell(RecordTemperature(requestId = 0, 1.0, recordProbe.ref))
      recordProbe.expectMessage(TemperatureRecorded(requestId = 0))
      deviceActor2.tell(Device.RecordTemperature(requestId = 1, 2.0, recordProbe.ref))
      recordProbe.expectMessage(Device.TemperatureRecorded(requestId = 1))
    }


    "ignore requests for wrong groupId" in {
      val probe = createTestProbe[DeviceRegistered]()
      val groupActor = spawn(DeviceGroup("group"))

      groupActor.tell(
        RequestTrackDevice(DeviceIdentity("wrongGroup", "device1"), probe.ref)
      )
      probe.expectNoMessage(500.milliseconds)
    }


    "return same actor for same deviceId" in {
      val probe = createTestProbe[DeviceRegistered]()
      val groupActor = spawn(DeviceGroup("group"))

      groupActor.tell(
        RequestTrackDevice(DeviceIdentity("group", "device1"), probe.ref)
      )
      val registered1 = probe.receiveMessage()

      // registering same again should be idempotent
      groupActor.tell(
        RequestTrackDevice(DeviceIdentity("group", "device1"), probe.ref)
      )
      val registered2 = probe.receiveMessage()

      registered1.device should ===(registered2.device)
    }
  }


  "be able to list active devices" in {
    val registeredProbe = createTestProbe[DeviceRegistered]()
    val groupActor = spawn(DeviceGroup("group"))

    groupActor.tell(
      RequestTrackDevice(
        DeviceIdentity("group", "device1"),
        registeredProbe.ref
      )
    )
    registeredProbe.receiveMessage()

    groupActor.tell(
      RequestTrackDevice(
        DeviceIdentity("group", "device2"),
        registeredProbe.ref
      )
    )
    registeredProbe.receiveMessage()

    val deviceListProbe = createTestProbe[ReplyDeviceList]()
    groupActor.tell(RequestDeviceList(0, "group", deviceListProbe.ref))
    deviceListProbe.expectMessage(
      ReplyDeviceList(0, Set("device1", "device2"))
    )
  }


  "be able to list active devices after one shuts down" in {
    val registeredProbe = createTestProbe[DeviceRegistered]()
    val groupActor = spawn(DeviceGroup("group"))

    groupActor.tell(
      RequestTrackDevice(
        DeviceIdentity("group", "device1"),
        registeredProbe.ref
      )
    )
    val registered1 = registeredProbe.receiveMessage()
    val toShutDown = registered1.device

    groupActor.tell(
      RequestTrackDevice(
        DeviceIdentity("group", "device2"),
        registeredProbe.ref
      )
    )
    registeredProbe.receiveMessage()

    val deviceListProbe = createTestProbe[ReplyDeviceList]()
    groupActor.tell(RequestDeviceList(0, "group", deviceListProbe.ref))
    deviceListProbe.expectMessage(ReplyDeviceList(0, Set("device1", "device2")))

    toShutDown.tell(Passivate)
    registeredProbe.expectTerminated(
      toShutDown,
      registeredProbe.remainingOrDefault
    )

    // using awaitAssert to retry because it might take longer for the groupActor
    // to see the Terminated, that order is undefined
    registeredProbe.awaitAssert {
      groupActor.tell(RequestDeviceList(1, "group", deviceListProbe.ref))
      deviceListProbe.expectMessage(ReplyDeviceList(1, Set("device2")))
    }
  }


  "be able to collect temperatures from all active devices" in {
    val registeredProbe = createTestProbe[DeviceRegistered]()
    val groupActor = spawn(DeviceGroup("group"))

    groupActor.tell(
      RequestTrackDevice(
        DeviceIdentity("group", "device1"),
        registeredProbe.ref
      )
    )
    val deviceActor1 = registeredProbe.receiveMessage().device

    groupActor.tell(
      RequestTrackDevice(
        DeviceIdentity("group", "device2"),
        registeredProbe.ref
      )
    )
    val deviceActor2 = registeredProbe.receiveMessage().device

    groupActor.tell(
      RequestTrackDevice(
        DeviceIdentity("group", "device3"), registeredProbe.ref
      )
    )
    registeredProbe.receiveMessage()

    // Check that the device actors are working
    val recordProbe = createTestProbe[TemperatureRecorded]()
    deviceActor1.tell(RecordTemperature(requestId = 0, 1.0, recordProbe.ref))
    recordProbe.expectMessage(TemperatureRecorded(requestId = 0))
    deviceActor2.tell(RecordTemperature(requestId = 1, 2.0, recordProbe.ref))
    recordProbe.expectMessage(TemperatureRecorded(requestId = 1))
    // No temperature for device3

    val allTempProbe = createTestProbe[RespondAllTemperatures]()
    groupActor.tell(
      RequestAllTemperatures(requestId = 0, groupId = "group", allTempProbe.ref)
    )
    allTempProbe.expectMessage(
      RespondAllTemperatures(
        requestId = 0,
        Map(
          "device1" -> DeviceManager.Temperature(1.0),
          "device2" -> DeviceManager.Temperature(2.0),
          "device3" -> TemperatureNotAvailable
        )
      )
    )
  }
}
