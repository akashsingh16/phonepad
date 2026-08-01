package com.phonepad.gamepad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Standard USB HID Keyboard/Keypad usage IDs (page 0x07) used by this app. */
object HidKeyCodes {
    const val A=0x04; const val B=0x05; const val C=0x06; const val D=0x07
    const val E=0x08; const val F=0x09; const val G=0x0A; const val H=0x0B
    const val I=0x0C; const val J=0x0D; const val K=0x0E; const val L=0x0F
    const val M=0x10; const val N=0x11; const val O=0x12; const val P=0x13
    const val Q=0x14; const val R=0x15; const val S=0x16; const val T=0x17
    const val U=0x18; const val V=0x19; const val W=0x1A; const val X=0x1B
    const val Y=0x1C; const val Z=0x1D
    const val K1=0x1E; const val K2=0x1F; const val K3=0x20; const val K4=0x21
    const val K5=0x22; const val K6=0x23; const val K7=0x24; const val K8=0x25
    const val K9=0x26; const val K0=0x27
    const val ENTER=0x28; const val ESC=0x29; const val BACKSPACE=0x2A
    const val TAB=0x2B; const val SPACE=0x2C
    const val ARROW_RIGHT=0x4F; const val ARROW_LEFT=0x50
    const val ARROW_DOWN=0x51; const val ARROW_UP=0x52
}

enum class BindingType { KEY, MODIFIER, MOUSE }

/**
 * One button in the customizable keyboard-mode layout.
 * x/y/size are in dp, relative to the play canvas's top-left corner.
 * -1 means "never been positioned" — renderer falls back to a default
 * cascade position so newly-added buttons don't overlap at (0,0).
 */
data class KeyBinding(
    val id: String,
    val label: String,
    val type: BindingType,
    val code: Int,
    val x: Int = -1,
    val y: Int = -1,
    val size: Int = 60
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("label", label); put("type", type.name); put("code", code)
        put("x", x); put("y", y); put("size", size)
    }
    companion object {
        fun fromJson(o: JSONObject) = KeyBinding(
            o.getString("id"), o.getString("label"),
            BindingType.valueOf(o.getString("type")), o.getInt("code"),
            o.optInt("x", -1), o.optInt("y", -1), o.optInt("size", 60)
        )
    }
}

/** Catalog of bindings offered in the "Add key" / "Change key" pickers. */
object BindingCatalog {
    val ALL: List<KeyBinding> = listOf(
        KeyBinding("space", "Space", BindingType.KEY, HidKeyCodes.SPACE),
        KeyBinding("lshift", "Shift", BindingType.MODIFIER, HidKeyboardMouseDescriptor.MOD_LEFT_SHIFT),
        KeyBinding("lctrl", "Ctrl", BindingType.MODIFIER, HidKeyboardMouseDescriptor.MOD_LEFT_CTRL),
        KeyBinding("lalt", "Alt", BindingType.MODIFIER, HidKeyboardMouseDescriptor.MOD_LEFT_ALT),
        KeyBinding("e", "E", BindingType.KEY, HidKeyCodes.E),
        KeyBinding("q", "Q", BindingType.KEY, HidKeyCodes.Q),
        KeyBinding("r", "R", BindingType.KEY, HidKeyCodes.R),
        KeyBinding("f", "F", BindingType.KEY, HidKeyCodes.F),
        KeyBinding("g", "G", BindingType.KEY, HidKeyCodes.G),
        KeyBinding("c", "C", BindingType.KEY, HidKeyCodes.C),
        KeyBinding("v", "V", BindingType.KEY, HidKeyCodes.V),
        KeyBinding("tab", "Tab", BindingType.KEY, HidKeyCodes.TAB),
        KeyBinding("esc", "Esc", BindingType.KEY, HidKeyCodes.ESC),
        KeyBinding("enter", "Enter", BindingType.KEY, HidKeyCodes.ENTER),
        KeyBinding("k1", "1", BindingType.KEY, HidKeyCodes.K1),
        KeyBinding("k2", "2", BindingType.KEY, HidKeyCodes.K2),
        KeyBinding("k3", "3", BindingType.KEY, HidKeyCodes.K3),
        KeyBinding("k4", "4", BindingType.KEY, HidKeyCodes.K4),
        KeyBinding("k5", "5", BindingType.KEY, HidKeyCodes.K5),
        KeyBinding("up", "\u2191", BindingType.KEY, HidKeyCodes.ARROW_UP),
        KeyBinding("down", "\u2193", BindingType.KEY, HidKeyCodes.ARROW_DOWN),
        KeyBinding("left", "\u2190", BindingType.KEY, HidKeyCodes.ARROW_LEFT),
        KeyBinding("right", "\u2192", BindingType.KEY, HidKeyCodes.ARROW_RIGHT),
        KeyBinding("mleft", "L-Click", BindingType.MOUSE, HidKeyboardMouseDescriptor.MOUSE_LEFT),
        KeyBinding("mright", "R-Click", BindingType.MOUSE, HidKeyboardMouseDescriptor.MOUSE_RIGHT),
        KeyBinding("mmid", "M-Click", BindingType.MOUSE, HidKeyboardMouseDescriptor.MOUSE_MIDDLE),
    )

    /** Sensible default layout for a shooter/action game — fully editable afterward. */
    fun defaultLayout(): List<KeyBinding> = listOf(
        ALL.first { it.id == "space" },
        ALL.first { it.id == "lshift" },
        ALL.first { it.id == "lctrl" },
        ALL.first { it.id == "e" },
        ALL.first { it.id == "r" },
        ALL.first { it.id == "f" },
        ALL.first { it.id == "tab" },
        ALL.first { it.id == "esc" },
        ALL.first { it.id == "mleft" },
        ALL.first { it.id == "mright" },
    )
}

/** Position + size for a joystick, persisted independently of the button layout. */
data class StickConfig(val x: Int, val y: Int, val size: Int)

/** Position + width/height for a non-square element like the touchpad. */
data class PadConfig(val x: Int, val y: Int, val width: Int, val height: Int)

/** Persists the user's customized layout, stick placement, sensitivity, and last device. */
object LayoutStore {
    private const val PREFS = "phonepad_prefs"
    private const val KEY_LAYOUT = "keyboard_layout"
    private const val KEY_SENSITIVITY = "mouse_sensitivity"
    private const val KEY_STICK_PREFIX = "stick_"
    private const val KEY_PAD_PREFIX = "pad_"
    private const val KEY_LAST_DEVICE = "last_device_mac"

    fun loadLayout(context: Context): MutableList<KeyBinding> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LAYOUT, null) ?: return BindingCatalog.defaultLayout().toMutableList()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i -> KeyBinding.fromJson(arr.getJSONObject(i)) }
        } catch (ex: Exception) {
            BindingCatalog.defaultLayout().toMutableList()
        }
    }

    fun saveLayout(context: Context, layout: List<KeyBinding>) {
        val arr = JSONArray()
        layout.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAYOUT, arr.toString()).apply()
    }

    /** Sensitivity is stored as an int 1..30; callers scale it (e.g. /10f) into a movement multiplier. */
    fun loadSensitivity(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SENSITIVITY, 10)

    fun saveSensitivity(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SENSITIVITY, value).apply()
    }

    /** key is "left" or "right". Returns null if the user has never dragged that stick. */
    fun loadStickConfig(context: Context, key: String): StickConfig? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val x = prefs.getInt(KEY_STICK_PREFIX + key + "_x", -1)
        if (x < 0) return null
        val y = prefs.getInt(KEY_STICK_PREFIX + key + "_y", -1)
        val size = prefs.getInt(KEY_STICK_PREFIX + key + "_size", 150)
        return StickConfig(x, y, size)
    }

    fun saveStickConfig(context: Context, key: String, config: StickConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_STICK_PREFIX + key + "_x", config.x)
            .putInt(KEY_STICK_PREFIX + key + "_y", config.y)
            .putInt(KEY_STICK_PREFIX + key + "_size", config.size)
            .apply()
    }

    /** key is a stable identifier for the element, e.g. "mouse" for the touchpad. */
    fun loadPadConfig(context: Context, key: String): PadConfig? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val x = prefs.getInt(KEY_PAD_PREFIX + key + "_x", -1)
        if (x < 0) return null
        val y = prefs.getInt(KEY_PAD_PREFIX + key + "_y", -1)
        val w = prefs.getInt(KEY_PAD_PREFIX + key + "_w", 220)
        val h = prefs.getInt(KEY_PAD_PREFIX + key + "_h", 140)
        return PadConfig(x, y, w, h)
    }

    fun savePadConfig(context: Context, key: String, config: PadConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_PAD_PREFIX + key + "_x", config.x)
            .putInt(KEY_PAD_PREFIX + key + "_y", config.y)
            .putInt(KEY_PAD_PREFIX + key + "_w", config.width)
            .putInt(KEY_PAD_PREFIX + key + "_h", config.height)
            .apply()
    }

    fun loadLastDeviceMac(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_DEVICE, null)

    fun saveLastDeviceMac(context: Context, mac: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_DEVICE, mac).apply()
    }
}
