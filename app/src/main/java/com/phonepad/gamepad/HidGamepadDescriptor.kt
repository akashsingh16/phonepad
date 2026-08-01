package com.phonepad.gamepad

/**
 * Defines a standard USB/Bluetooth HID "Game Pad" report descriptor and
 * builds the raw input reports sent over the Bluetooth HID Device profile.
 *
 * This is the real, industry-standard way a phone (or anything else) presents
 * itself as a generic gamepad to a Windows/Linux PC or Android TV — it is the
 * same class of device Windows/Linux/Android already have built-in drivers
 * for, so no companion software is needed on the host.
 *
 * Layout of one input report (9 bytes, report ID sent separately by the API):
 *   byte 0-1 : 16 buttons, 1 bit each
 *   byte 2   : bits 0-3 = D-pad hat switch (0=up,1=up-right,...,7=up-left,15=released)
 *              bits 4-7 = padding (unused)
 *   byte 3   : left stick X   (0-255, 128 = center)
 *   byte 4   : left stick Y   (0-255, 128 = center)
 *   byte 5   : right stick X  (0-255, 128 = center)
 *   byte 6   : right stick Y  (0-255, 128 = center)
 *   byte 7   : left trigger   (0-255)
 *   byte 8   : right trigger  (0-255)
 */
object HidGamepadDescriptor {

    const val REPORT_ID: Byte = 1

    val DESCRIPTOR: ByteArray = byteArrayOf(
        0x05, 0x01,                     // Usage Page (Generic Desktop)
        0x09, 0x05,                     // Usage (Game Pad)
        0xA1.toByte(), 0x01,            // Collection (Application)
        0x85.toByte(), REPORT_ID,       //   Report ID (1)

        0x05, 0x09,                     //   Usage Page (Button)
        0x19, 0x01,                     //   Usage Minimum (Button 1)
        0x29, 0x10,                     //   Usage Maximum (Button 16)
        0x15, 0x00,                     //   Logical Minimum (0)
        0x25, 0x01,                     //   Logical Maximum (1)
        0x75, 0x01,                     //   Report Size (1)
        0x95.toByte(), 0x10,            //   Report Count (16)
        0x81.toByte(), 0x02,            //   Input (Data,Var,Abs)        -> 2 bytes

        0x05, 0x01,                     //   Usage Page (Generic Desktop)
        0x09, 0x39,                     //   Usage (Hat Switch)
        0x15, 0x00,                     //   Logical Minimum (0)
        0x25, 0x07,                     //   Logical Maximum (7)
        0x35, 0x00,                     //   Physical Minimum (0)
        0x46, 0x3B, 0x01,               //   Physical Maximum (315)
        0x65, 0x14,                     //   Unit (Eng Rot:Degrees)
        0x75, 0x04,                     //   Report Size (4)
        0x95.toByte(), 0x01,            //   Report Count (1)
        0x81.toByte(), 0x42,            //   Input (Data,Var,Abs,Null)
        0x65, 0x00,                     //   Unit (None)
        0x75, 0x04,                     //   Report Size (4)
        0x95.toByte(), 0x01,            //   Report Count (1)
        0x81.toByte(), 0x03,            //   Input (Const,Var,Abs)       -> 1 byte total (hat + pad)

        0x05, 0x01,                     //   Usage Page (Generic Desktop)
        0x09, 0x30,                     //   Usage (X)   left stick X
        0x09, 0x31,                     //   Usage (Y)   left stick Y
        0x09, 0x33,                     //   Usage (Rx)  right stick X
        0x09, 0x34,                     //   Usage (Ry)  right stick Y
        0x09, 0x32,                     //   Usage (Z)   left trigger
        0x09, 0x35,                     //   Usage (Rz)  right trigger
        0x15, 0x00,                     //   Logical Minimum (0)
        0x26, 0xFF.toByte(), 0x00,      //   Logical Maximum (255)
        0x75, 0x08,                     //   Report Size (8)
        0x95.toByte(), 0x06,            //   Report Count (6)            -> 6 bytes
        0x81.toByte(), 0x02,            //   Input (Data,Var,Abs)

        0xC0.toByte()                   // End Collection
    )

    // Bit positions within the 16-bit button field
    const val BTN_A = 0
    const val BTN_B = 1
    const val BTN_X = 2
    const val BTN_Y = 3
    const val BTN_LB = 4
    const val BTN_RB = 5
    const val BTN_BACK = 6
    const val BTN_START = 7
    const val BTN_GUIDE = 8
    const val BTN_L3 = 9
    const val BTN_R3 = 10

    /** Builds the 9-byte input report body (report ID is passed separately to sendReport). */
    fun buildReport(
        buttons: Int,
        hat: Int,
        lx: Int, ly: Int,
        rx: Int, ry: Int,
        lt: Int, rt: Int
    ): ByteArray {
        val hatNibble = if (hat in 0..7) hat else 0x0F
        val b0 = (buttons and 0xFF).toByte()
        val b1 = ((buttons shr 8) and 0xFF).toByte()
        val b2 = (hatNibble and 0x0F).toByte()
        return byteArrayOf(
            b0, b1, b2,
            lx.coerceIn(0, 255).toByte(),
            ly.coerceIn(0, 255).toByte(),
            rx.coerceIn(0, 255).toByte(),
            ry.coerceIn(0, 255).toByte(),
            lt.coerceIn(0, 255).toByte(),
            rt.coerceIn(0, 255).toByte()
        )
    }
}
