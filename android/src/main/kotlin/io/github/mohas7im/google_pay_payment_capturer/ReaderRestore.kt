package io.github.mohas7im.google_pay_payment_capturer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Keeps the Payment Reader on across app updates.
 *
 * Force-stopping the app (flutter run does it before installing) switches
 * its accessibility service off, and captures stop until someone notices. An
 * app can't switch an accessibility service on by itself, except with
 * WRITE_SECURE_SETTINGS, which only adb can grant (once; it survives updates,
 * not an uninstall):
 *
 *   adb shell pm grant <your.application.id> android.permission.WRITE_SECURE_SETTINGS
 *
 * (the app declares WRITE_SECURE_SETTINGS in its own manifest for this).
 *
 * With it, the reader is switched back on after an update or a force-stop,
 * when the app next starts — only if it was on before. Turning it off in
 * Settings is respected.
 */
object ReaderRestore {
    private const val TAG = "ReaderRestore"
    private const val PREFS = "payment_reader_state"
    private const val KEY_WANTED = "reader_wanted"
    private const val KEY_WANTED_AT_UPDATE = "reader_wanted_at_update"
    private const val KEY_UNBOUND = "reader_unbound"

    private fun component(context: Context) =
        ComponentName(context, PaymentAccessibilityService::class.java)

    private fun lastUpdateTime(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    } catch (_: Exception) {
        0L
    }

    fun canRestore(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(context: Context): Boolean {
        val me = component(context)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** The reader is on (it just connected): remember that, for this install. */
    fun markWanted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WANTED, true)
            .putBoolean(KEY_UNBOUND, false)
            .putLong(KEY_WANTED_AT_UPDATE, lastUpdateTime(context))
            .apply()
    }

    /**
     * Android unbound the reader while the app was alive: someone switched it
     * off in Settings. A force-stop (flutter run does one) or an update kills
     * the app without this, and those are the cases to undo.
     */
    fun markUnbound(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_UNBOUND, true)
            .commit() // before the process can go
    }

    /**
     * Switches the reader back on when an update or a force-stop turned it
     * off. Switched off in Settings (with no update since): leave it off.
     */
    fun restoreIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_WANTED, false) || isEnabled(context)) return
        val updatedSince = prefs.getLong(KEY_WANTED_AT_UPDATE, 0L) != lastUpdateTime(context)
        if (!updatedSince && prefs.getBoolean(KEY_UNBOUND, false)) {
            prefs.edit().putBoolean(KEY_WANTED, false).apply()
            return
        }
        if (!canRestore(context)) {
            Log.w(TAG, "Reader was switched off by the update; grant WRITE_SECURE_SETTINGS over adb to restore it")
            return
        }
        try {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val services = current.split(':').filter { it.isNotBlank() } + component(context).flattenToString()
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "Payment Reader switched back on (it was turned off by an update or force-stop)")
        } catch (e: Exception) {
            Log.e(TAG, "Could not switch the Payment Reader back on", e)
        }
    }
}

/** Runs right after the host app is updated. */
class ReaderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) ReaderRestore.restoreIfNeeded(context)
    }
}
