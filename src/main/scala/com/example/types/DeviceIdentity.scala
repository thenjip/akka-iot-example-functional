package com.example.types



final case class DeviceIdentity(
  groupId: DeviceIdentity.GroupId,
  deviceId: DeviceIdentity.Id
)



object DeviceIdentity
{
  type Id = String
  type GroupId = String
}
