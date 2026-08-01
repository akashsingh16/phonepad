package com.phonepad.gamepad

import android.util.Log
import org.json.JSONObject
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Sends gamepad / keyboard / mouse reports to a PC over WiFi via a persistent
 * TCP connection. Each report is one JSON line so the Python server side is
 * trivial. A dedicated thread owns the socket; reports are queued and sent in
 * order, dropping old ones if the queue backs up (keeps latency low).
 */
class WifiTransport(
    private val host: String,
    private val port: Int,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "PhonePad.WiFi"
        private const val MAX_QUEUE = 8
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val queue = LinkedBlockingQueue<String>(MAX_QUEUE)
    @Volatile private var running = false
    private var socket: Socket? = null
    private var writer: PrintWriter? = null

    fun connect() {
        running = true
        executor.submit {
            while (running) {
                try {
                    onStatus("Connecting to $host:$port…")
                    socket = Socket(host, port).apply { tcpNoDelay = true; soTimeout = 5000 }
                    writer = PrintWriter(socket!!.getOutputStream(), true)
                    onStatus("WiFi connected to $host — controls active")
                    drainQueue()
                } catch (e: Exception) {
                    Log.w(TAG, "Connection error: $e")
                    onStatus("WiFi: retrying… ($e)")
                    Thread.sleep(2000)
                }
            }
        }
    }

    private fun drainQueue() {
        while (running) {
            try {
                val msg = queue.poll(1, TimeUnit.SECONDS) ?: continue
                writer?.println(msg) ?: break
                if (writer?.checkError() == true) break
            } catch (e: Exception) {
                Log.w(TAG, "Send error: $e")
                break
            }
        }
    }

    private fun enqueue(json: String) {
        if (!queue.offer(json)) {
            queue.poll() // drop oldest, keep latency low
            queue.offer(json)
        }
    }

    fun sendGamepad(report: ByteArray) {
        val json = JSONObject().apply {
            put("type", "gamepad")
            put("b", report[0].toInt() and 0xFF or ((report[1].toInt() and 0xFF) shl 8))
            put("hat", report[2].toInt() and 0x0F)
            put("lx", report[3].toInt() and 0xFF)
            put("ly", report[4].toInt() and 0xFF)
            put("rx", report[5].toInt() and 0xFF)
            put("ry", report[6].toInt() and 0xFF)
            put("lt", report[7].toInt() and 0xFF)
            put("rt", report[8].toInt() and 0xFF)
        }.toString()
        enqueue(json)
    }

    fun sendKeyboard(modifiers: Int, keycodes: Set<Int>) {
        val json = JSONObject().apply {
            put("type", "keyboard")
            put("mod", modifiers)
            put("keys", org.json.JSONArray(keycodes.toList()))
        }.toString()
        enqueue(json)
    }

    fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val json = JSONObject().apply {
            put("type", "mouse")
            put("buttons", buttons)
            put("dx", dx)
            put("dy", dy)
            put("wheel", wheel)
        }.toString()
        enqueue(json)
    }

    fun close() {
        running = false
        runCatching { socket?.close() }
        executor.shutdownNow()
    }
}
