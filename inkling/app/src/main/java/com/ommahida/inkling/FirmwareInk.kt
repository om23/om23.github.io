package com.ommahida.inkling

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Claims the Supernote firmware's pen-ink overlay so wet ink is painted by the EPD controller at
 * sub-frame latency — the same path the native Notes app uses — instead of being rendered app-side.
 * When this is active, [InkView] stops drawing strokes to the screen (it still records the geometry
 * for the Claude snapshot); the firmware shows the live ink.
 *
 * The mechanism is a Binder service (`service_myservice`, interface `android.demo.IMyService`).
 * Every method is best-effort and never throws across the boundary: if the service is missing or a
 * transaction fails, callers fall back to the app-side renderer.
 *
 * The Binder contract used here (service name, interface token, transaction codes, nib constants)
 * is an interface fact reproduced from the publicly documented Supernote "HandWriteClient" — as
 * charted by the inkread project (github.com/j-raghavan/inkread) and KOReader's Supernote plugin.
 * This is our own implementation of that documented contract.
 */
class FirmwareInk(private val context: Context) {

    private var cached: IBinder? = null
    private var active = false

    /** Resolve (and cache) the firmware binder via the hidden ServiceManager.getService. */
    private fun binder(): IBinder? {
        cached?.let { if (it.isBinderAlive) return it }
        cached = try {
            val sm = Class.forName("android.os.ServiceManager")
            val get = sm.getMethod("getService", String::class.java)
            SERVICE_NAMES.asSequence()
                .mapNotNull { name -> get.invoke(null, name) as? IBinder }
                .firstOrNull()
        } catch (t: Throwable) {
            Log.w(TAG, "ServiceManager.getService failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        return cached
    }

    fun isAvailable(): Boolean = binder() != null

    /** interface-token + app-name preamble, then the per-call ints. Returns true if it transacted. */
    private fun send(code: Int, write: (Parcel) -> Unit): Boolean {
        val b = binder() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE_TOKEN)
            data.writeString(APP_NAME)
            write(data)
            b.transact(code, data, reply, 0)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "transact(code=$code) failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Best-effort: getSystemService("eink").enableFullUiAuto(boolean) — paint ink for our window. */
    private fun enableFullUiAuto(enable: Boolean) {
        try {
            val eink = context.getSystemService("eink") ?: run {
                Log.i(TAG, "no 'eink' system service")
                return
            }
            eink.javaClass.getMethod("enableFullUiAuto", Boolean::class.javaPrimitiveType)
                .invoke(eink, enable)
            Log.i(TAG, "enableFullUiAuto($enable) ok")
        } catch (t: Throwable) {
            Log.w(TAG, "enableFullUiAuto($enable): ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Claim the pen and turn on firmware ink for our window. Idempotent — safe to call on every
     * focus gain (the firmware resets ownership when another window takes focus).
     * @return true if the firmware binder was reachable and the claim transacted.
     */
    fun setup(): Boolean {
        if (binder() == null) {
            Log.i(TAG, "firmware ink unavailable (no binder)")
            return false
        }
        val a = send(TX_WRITE_APP_INFO) { it.writeInt(0); it.writeInt(0) }
        enableFullUiAuto(true)
        send(TX_DISABLE_AREA) { it.writeInt(0) } // no disabled areas
        val b = send(TX_PEN) { it.writeInt(PEN_NEEDLE); it.writeInt(SIZE_EMR); it.writeInt(COLOR_BLACK) }
        active = a && b
        Log.i(TAG, "firmware ink setup: appInfo=$a pen=$b -> active=$active")
        return active
    }

    /** Clear the firmware ink overlay (the "page drinks your ink" moment). */
    fun clearAll() {
        if (!active) return
        send(TX_DRAW_BUFFER) { it.writeInt(255); it.writeInt(0) }
    }

    /** Release the firmware ink claim and clear the overlay. */
    fun teardown() {
        if (!active) return
        clearAll()
        enableFullUiAuto(false)
        active = false
        Log.i(TAG, "firmware ink released")
    }

    private companion object {
        const val TAG = "InklingFwInk"
        val SERVICE_NAMES = arrayOf("service_myservice", "service.myservice")
        const val IFACE_TOKEN = "android.demo.IMyService"
        const val APP_NAME = "inkling"

        const val TX_WRITE_APP_INFO = 0
        const val TX_DISABLE_AREA = 1
        const val TX_PEN = 2
        const val TX_DRAW_BUFFER = 6

        const val PEN_NEEDLE = 10
        const val COLOR_BLACK = 0
        const val SIZE_EMR = 1000
    }
}
