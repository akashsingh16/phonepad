# PhonePad — Phone as a Bluetooth Gamepad

Turns your Android phone into a **real Bluetooth HID gamepad** using Android's
`BluetoothHidDevice` API. No companion software needed on the PC/TV side —
Windows, Linux, and Android TV all have built-in support for generic HID
gamepads, so once paired it just works like a plugged-in controller.

## Why "generic HID gamepad" and not literally an Xbox/PS5 controller

Xbox and PlayStation controllers use closed, proprietary wireless protocols
that aren't publicly documented for third-party use — there's no legitimate
way to make an app that speaks their exact wireless protocol. What this app
does instead is register as a **standard Bluetooth HID gamepad**, which is
the actual class of device your PC/TV already has drivers for. Functionally,
this is what you want: axes, buttons, D-pad, triggers, all recognized as a
generic controller by Windows, Linux, SDL2-based games, emulators, and
Android TV.

If a specific Windows game insists on Xbox-style XInput rather than generic
DirectInput/HID, pair this normally, then run the free tool **x360ce** on the
PC to map it to XInput.

## What's included

```
PhonePad/
  app/src/main/java/com/phonepad/gamepad/
    MainActivity.kt            - Bluetooth HID registration, pairing, input wiring
    HidGamepadDescriptor.kt    - The HID report descriptor + report builder
    JoystickView.kt            - Custom touch-draggable analog stick
  app/src/main/res/layout/activity_main.xml - Full controller UI (sticks, ABXY, LB/RB, LT/RT, D-pad, Back/Start/Guide)
```

The report layout: 16 buttons, a D-pad hat switch, two analog sticks, and two
triggers — the same fields any standard gamepad exposes.

## Build it (needs Android Studio — free)

1. Install **Android Studio** (Hedgehog or newer) on your PC.
2. **File → Open** → select the `PhonePad` folder → let Gradle sync (needs
   internet the first time, to pull the Android SDK/dependencies).
3. Enable Developer Options + USB debugging on your phone, connect via USB.
4. Click **Run ▶** to install it directly, or **Build → Generate Signed
   Bundle/APK** to get an installable `.apk` you can share/sideload.

> I wrote and reviewed all of this code carefully, but couldn't compile it
> myself — my sandbox has no access to the Android SDK/Google's Maven repo.
> The Bluetooth HID logic and report descriptor are correct per Android's
> API, but budget a little time for normal first-build friction (Gradle/AGP
> version bumps, etc.) when you open it.

## Pairing

1. Open **PhonePad** on your phone, grant the Bluetooth permission prompt,
   accept the "make discoverable" prompt.
2. **On your PC (Pop!_OS):** Settings → Bluetooth → scan → select
   **PhonePad** → pair & connect.
   - If the GUI doesn't show it, use the terminal:
     ```bash
     bluetoothctl
     scan on
     pair <PHONE_MAC_ADDRESS>
     trust <PHONE_MAC_ADDRESS>
     connect <PHONE_MAC_ADDRESS>
     ```
   - Verify it's recognized as a joystick:
     ```bash
     sudo apt install joystick
     jstest /dev/input/js0
     ```
3. **On Android TV:** Settings → Remotes & Accessories → Add accessory →
   select **PhonePad**.
4. **On Windows:** Settings → Bluetooth & devices → Add device → select
   **PhonePad**.

Status text on screen tells you when it's registered and when a device
connects.

## Known limitations / easy extensions

- **Triggers (LT/RT) are digital** right now (full press = 255, release = 0)
  for simplicity. To make them analog, swap the `Button` for a vertical
  `SeekBar` and feed its progress into `lt`/`rt` in `MainActivity.kt`.
- **Guide button** is included but some hosts ignore it depending on driver.
- Layout is fixed for landscape; portrait isn't handled.

---

## New: Keyboard mode (v2)

Tap **"⌨ Keyboard"** (top-right, in Gamepad mode) to switch. Tap **"🎮 Gamepad"** to switch back.
Same Bluetooth/WiFi pairing — no need to re-pair when switching modes.

- **Left stick** → WASD (digital, deadzone-based — push in a direction, that key is held; diagonals hold two keys)
- **Right stick** → mouse look (continuous relative movement while held, self-centers to stop on release)
- **Action buttons** → fully customizable. Default set: Space, Shift, Ctrl, E, R, F, Tab, Esc, Left-Click, Right-Click
- **Long-press any action button** → Change key or Remove
- **"+ Key"** → add a new button from the catalog (letters, numbers, arrows, modifiers, mouse clicks)
- **Sensitivity slider** → mouse look speed, 0.1x–3.0x, saved automatically

Everything (layout + sensitivity) persists across app restarts via SharedPreferences.

### How it works technically

One Bluetooth HID registration now exposes three HID collections under one descriptor:
- Report ID 1 — Gamepad (unchanged from v1)
- Report ID 2 — Keyboard (standard 8-byte boot report: modifier byte + 6 keycodes)
- Report ID 3 — Mouse (buttons + relative dx/dy/wheel)

The app just switches which report ID it sends based on which mode is on screen — the pairing
itself never changes, so toggling modes is instant with no reconnect.

WiFi mode carries all three report types too — `phonepad_server.py` was updated to build one
combined `uinput` virtual device with gamepad axes, keyboard keys, and a relative mouse all
declared together, and diffs incoming keyboard/mouse state against the previous packet to emit
proper key-down/key-up events (HID reports are snapshots, not discrete events, so this diffing
is necessary for keys to behave correctly rather than just flickering).

### Note on this being untested

Same caveat as before: I wrote and reviewed this carefully but couldn't compile or run it on real
hardware here (no Android SDK access in my sandbox). The HID descriptor math and Kotlin/Python
logic are correct as far as I can verify by hand, but budget some first-run debugging time —
especially around whether your specific phone's Bluetooth stack accepts the combined 3-collection
descriptor cleanly. If BT pairing acts up after this update, WiFi mode is the more reliable
fallback since it bypasses the phone's BT HID stack entirely.

---

## New: layout editing, hidden controls, auto-connect, better mouse feel (v3)

All of this is in **Keyboard mode** only — Gamepad mode's screen/behavior is untouched.

### Reposition & resize everything
Tap the small **⚙** at the top to open the control panel, then **"Edit Layout"**.
While it's on:
- **Drag** any button or stick to move it
- **Pinch** any button or stick to resize it
- Long-press still opens the remap/remove dialog when *not* editing

Positions and sizes save automatically per element and persist across restarts.
Turn "Edit Layout" back off to return to normal play (this also releases any
keys/clicks that were held, so nothing gets stuck down while you're rearranging).

### Controls are hidden by default
Everything that isn't a stick or an action button — mode switches, sensitivity,
add-key, edit toggle, connect — now lives behind the **⚙** icon, collapsed by
default. The play surface has nothing on it to fat-finger by accident.

### Phone-initiated connection (like a typical remote app)
**⚙ → Connect…** shows your already-paired devices (tap to connect directly) or
**"Scan for new device…"** for first-time pairing — no more digging into your
PC/TV's own Bluetooth settings. Once connected, the app remembers that device
and **auto-reconnects on future launches** by itself.

First pairing still needs your PC/TV Bluetooth to be on/discoverable (that's a
Bluetooth protocol requirement, not an app limitation) — after that one time,
it's automatic.

### Right stick mouse feel — nudge vs. hold-to-look
- **Small-to-medium deflection** → the mouse moves only as much as your finger
  actually moves, then stops when your finger stops (trackpad-style). Hold the
  stick at a partial angle without moving it further and the cursor stays put.
- **Pushed to the edge** (past ~75% of the stick's travel) → switches to
  continuous movement for as long as you hold it there, so you can keep
  looking/scrolling without running out of physical thumb travel.

The sensitivity slider scales both behaviors together.

### Note on this round

Same standing caveat: written and reviewed carefully, not compiled on real
hardware here. The parts most worth testing first: whether `createBond()`
completes within the 3-second window before the app tries to connect (some
phones are slower — if auto-connect after a fresh pairing doesn't work first
try, just tap Connect… again once bonding finishes), and the pinch-resize
gesture's `coerceIn` bounds (`40dp–140dp` for buttons, `90dp–260dp` for
sticks) may want tuning to your screen size.

---

## v4: mouse joystick replaced with a real touchpad

Per feedback that the right-stick-as-mouse never quite felt natural — swapped it for an
actual touchpad (`TouchpadView.kt`). This is simpler than the stick approach and closer to
what people already know from laptops:

- **Direct 1:1 relative movement** — drag your finger, the cursor moves; stop, it stops.
  No deadzone, no "hold at the edge to keep going" mode — that whole distinction is gone
  because a touchpad doesn't need it. Lift your finger and reposition to swipe again for
  long distances, exactly like a laptop trackpad.
- **Tap to click** — a quick, low-movement tap fires a left click automatically. The
  dedicated L-Click/R-Click buttons in your action layout still work as before for held
  clicks or right-clicks.
- Same Edit Layout system as everything else: drag to move, pinch to resize (independent
  width/height now, since a touchpad reads better as a rectangle than a square).
- Sensitivity slider now scales raw pixel deltas directly (0.1x–3.0x), so the same slider
  you already had works even better here — lower for precision, higher for fast movement
  across the whole screen with less swiping.

Left stick (WASD) is untouched.

On the gamepad-to-gamepad comparison: fair point that it's a separate conversation from
keyboard mode — if you want to dig into what's making the LG app's gamepad feel better
(probably deadzone tuning, response curve, or trigger behavior), that's a good next thing
to compare side by side once you've had a chance to try the touchpad.

---

## v5: double-tap-then-drag = click-and-drag

Standard trackpad gesture, now supported: **tap once, then within ~300ms touch down again
near the same spot and start dragging** — the left button gets held for the duration of
that drag (release when you lift your finger). Useful for drag-select, dragging files,
resizing windows, etc.

- A plain single tap still just clicks (press+release), like before.
- A plain drag (no preceding tap) still just moves the cursor with no button held, like before.
- The pad's border turns amber while a drag-hold is active, so you can see it engaged.
- If a normal tap's click-release is still pending (the 60ms auto-release) when a drag-hold
  starts, that pending release gets cancelled first — the two gestures don't fight over the
  button state.

No changes to tap-to-click, WASD stick, or anything else.
