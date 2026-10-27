export {HardwareType, HardwareRequirementLevel} from "@mentra/miniapp/hardware"

export enum DeviceTypes {
  NONE = "None",
  SIMULATED = "Simulated Glasses",
  AR99 = "AR99",
  G1 = "Even Realities G1",
  G2 = "Even Realities G2",
  LIVE = "Mentra Live",
  MACH1 = "Mentra Mach1",
  Z100 = "Vuzix Z100",
  NEX = "Mentra Display",
  FRAME = "Brilliant Frame",
  NIMO = "NIMO",
  INMO_GO3 = "INMO GO3",
}

export enum ControllerTypes {
  R1 = "Even Realities R1",
  /** A ring paired with the phone as a Bluetooth keyboard (INMO ring; R08 and Aivela pending). Android only. */
  HID_RING = "Bluetooth Ring",
}
