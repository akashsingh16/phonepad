package com.phonepad.gamepad

/**
 * HID descriptor pieces for a standard boot-protocol Keyboard (Report ID 2)
 * and a relative Mouse (Report ID 3). These are concatenated onto
 * [HidGamepadDescriptor.DESCRIPTOR] (Report ID 1, untouched) at registration
 * time, so one Bluetooth HID pairing exposes all three device types and the
 * app can switch which report it sends without re-pairing.
 */
object HidKeyboardMouseDescriptor {

    const val REPORT_ID_KEYBOARD: Byte = 2
    const val REPORT_ID_MOUSE: Byte = 3

    val DESCRIPTOR: ByteArray = byteArrayOf(
        // ---- Keyboard (boot protocol layout) ----
        0x05, 0x01,                      // Usage Page (Generic Desktop)
        0x09, 0x06,                      // Usage (Keyboard)
        0xA1.toByte(), 0x01,             // Collection (Application)
        0x85.toByte(), REPORT_ID_KEYBOARD,

        0x05, 0x07,                      //   Usage Page (Key Codes)
        0x19, 0xE0.toByte(),             //   Usage Minimum (224) - Left Ctrl
        0x29, 0xE7.toByte(),             //   Usage Maximum (231) - Right GUI
        0x15, 0x00,                      //   Logical Minimum (0)
        0x25, 0x01,                      //   Logical Maximum (1)
        0x75, 0x01,                      //   Report Size (1)
        0x95.toByte(), 0x08,             //   Report Count (8)
        0x81.toByte(), 0x02,             //   Input (Data,Var,Abs) - modifier byte

        0x95.toByte(), 0x01,             //   Report Count (1)
        0x75, 0x08,                      //   Report Size (8)
        0x81.toByte(), 0x01,             //   Input (Const) - reserved byte

        0x95.toByte(), 0x06,             //   Report Count (6)
        0x75, 0x08,                      //   Report Size (8)
        0x15, 0x00,                      //   Logical Minimum (0)
        0x25, 0x65,                      //   Logical Maximum (101)
        0x05, 0x07,                      //   Usage Page (Key Codes)
        0x19, 0x00,                      //   Usage Minimum (0)
        0x29, 0x65,                      //   Usage Maximum (101)
        0x81.toByte(), 0x00,             //   Input (Data,Array) - 6 keycode slots
        0xC0.toByte(),                   // End Collection

        // ---- Mouse (relative) ----
        0x05, 0x01,                      // Usage Page (Generic Desktop)
        0x09, 0x02,                      // Usage (Mouse)
        0xA1.toByte(), 0x01,             // Collection (Application)
        0x09, 0x01,                      //   Usage (Pointer)
        0xA1.toByte(), 0x00,             //   Collection (Physical)
        0x85.toByte(), REPORT_ID_MOUSE,

        0x05, 0x09,                      //     Usage Page (Button)
        0x19, 0x01,                      //     Usage Minimum (Button 1)
        0x29, 0x03,                      //     Usage Maximum (Button 3)
        0x15, 0x00,
        0x25, 0x01,
        0x75, 0x01,
        0x95.toByte(), 0x03,
        0x81.toByte(), 0x02,             //     Input (Data,Var,Abs) - L/R/M buttons
        0x75, 0x05,
        0x95.toByte(), 0x01,
        0x81.toByte(), 0x03,             //     Input (Const) - padding to full byte

        0x05, 0x01,                      //     Usage Page (Generic Desktop)
        0x09, 0x30,                      //     Usage (X)
        0x09, 0x31,                      //     Usage (Y)
        0x09, 0x38,                      //     Usage (Wheel)
        0x15, 0x81.toByte(),             //     Logical Minimum (-127)
        0x25, 0x7F,                      //     Logical Maximum (127)
        0x75, 0x08,
        0x95.toByte(), 0x03,
        0x81.toByte(), 0x06,             //     Input (Data,Var,Rel) - dx, dy, wheel
        0xC0.toByte(),                   //   End Collection
        0xC0.toByte()                    // End Collection
    )

    // Modifier bitmask (byte 0 of the keyboard report)
    const val MOD_LEFT_CTRL: Int = 0x01
    const val MOD_LEFT_SHIFT: Int = 0x02
    const val MOD_LEFT_ALT: Int = 0x04
    const val MOD_LEFT_GUI: Int = 0x08
    const val MOD_RIGHT_CTRL: Int = 0x10
    const val MOD_RIGHT_SHIFT: Int = 0x20
    const val MOD_RIGHT_ALT: Int = 0x40
    const val MOD_RIGHT_GUI: Int = 0x80

    // Mouse button bits (byte 0 of the mouse report)
    const val MOUSE_LEFT: Int = 0x01
    const val MOUSE_RIGHT: Int = 0x02
    const val MOUSE_MIDDLE: Int = 0x04

    /** Builds the 8-byte keyboard report body: modifier, reserved, up to 6 keycodes. */
    fun buildKeyboardReport(modifiers: Int, keycodes: Set<Int>): ByteArray {
        val out = ByteArray(8)
        out[0] = (modifiers and 0xFF).toByte()
        out[1] = 0 // reserved
        keycodes.take(6).forEachIndexed { i, code -> out[2 + i] = (code and 0xFF).toByte() }
        return out
    }

    /** Builds the 4-byte relative mouse report body: buttons, dx, dy, wheel (each -127..127). */
    fun buildMouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int): ByteArray {
        return byteArrayOf(
            (buttons and 0xFF).toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte()
        )
    }
}
