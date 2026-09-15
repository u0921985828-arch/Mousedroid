package com.eddie.usbmouse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Abre la app sola al enchufar el cable USB.
 *
 * ACTION_POWER_CONNECTED sigue permitido en el manifest (esta en la lista de
 * excepciones de Android 8+). Para poder lanzar la Activity desde segundo plano
 * en Android 10+ hace falta el permiso "Mostrar sobre otras apps"; la propia app
 * lo pide al activar la casilla.
 *
 * Si usas el modo adb, esto ni siquiera hace falta: el PC abre la app con
 * `am start` en cuanto detecta el movil.
 */
class PowerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return

        val prefs = context.getSharedPreferences("usbmouse", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autolaunch", false)) return

        // solo si es USB, no cargador de pared ni inalambrico
        if (!isUsb(context)) return

        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (_: Exception) {
            // sin permiso de superposicion el sistema lo bloquea; se ignora en silencio
        }
    }

    private fun isUsb(context: Context): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return plugged == BatteryManager.BATTERY_PLUGGED_USB
    }
}
