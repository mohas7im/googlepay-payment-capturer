package io.github.mohas7im.google_pay_payment_capturer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Tells the user a payment was captured, so it can be added without first
 * opening the app. Tapping opens the app's launcher activity (no new task:
 * a singleTop activity gets it in onNewIntent).
 *
 * Configured from the host app's manifest, inside <application>:
 *
 *   <!-- Opened with this data URI (e.g. a deep link to the capture page) -->
 *   <meta-data android:name="google_pay_payment_capturer.capture_link" android:value="myapp://auto-capture"/>
 *   <!-- The intent's action; default android.intent.action.VIEW -->
 *   <meta-data android:name="google_pay_payment_capturer.capture_link_action" android:value="..."/>
 *   <!-- Small icon; default the app's icon -->
 *   <meta-data android:name="google_pay_payment_capturer.notification_icon" android:resource="@mipmap/ic_launcher"/>
 *   <!-- false turns the notification off -->
 *   <meta-data android:name="google_pay_payment_capturer.capture_notifications" android:value="false"/>
 */
object CaptureNotifier {
    private const val TAG = "CaptureNotifier"
    private const val CHANNEL_ID = "payment_captures"

    private const val META_LINK = "google_pay_payment_capturer.capture_link"
    private const val META_LINK_ACTION = "google_pay_payment_capturer.capture_link_action"
    private const val META_ICON = "google_pay_payment_capturer.notification_icon"
    private const val META_ENABLED = "google_pay_payment_capturer.capture_notifications"

    private fun metaData(context: Context): Bundle? = try {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    } catch (_: Exception) {
        null
    }

    fun notifyPayment(context: Context, data: Map<String, Any?>) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val meta = metaData(context)
            if (meta?.getBoolean(META_ENABLED, true) == false) return

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Captured payments", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "A payment the reader caught, ready to add"
                    }
                )
            }

            val amount = (data["amount"] as? Number)?.toDouble() ?: return
            val merchant = (data["merchant"] as? String).orEmpty().ifEmpty { "a payment" }
            val shown = if (amount % 1.0 == 0.0) amount.toLong().toString() else String.format("%.2f", amount)
            val caughtUp = data["live"] == false
            val icon = meta?.getInt(META_ICON, 0)?.takeIf { it != 0 } ?: context.applicationInfo.icon

            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle("₹$shown to $merchant")
                .setContentText(if (caughtUp) "Caught up from GPay · tap to add" else "Captured · tap to pick a category and add")
                .setAutoCancel(true)
            openAppIntent(context, meta)?.let { builder.setContentIntent(it) }
            // One notification per payment; the same one read twice replaces itself.
            manager.notify(("$amount|$merchant|${data["timestamp"]}").hashCode(), builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Could not post the capture notification", e)
        }
    }

    /** The app's launcher activity, with the configured action and link. */
    private fun openAppIntent(context: Context, meta: Bundle?): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        val link = meta?.getString(META_LINK)
        val intent = Intent().apply {
            component = launch.component
            action = meta?.getString(META_LINK_ACTION) ?: if (link != null) Intent.ACTION_VIEW else launch.action
            link?.let { data = Uri.parse(it) }
        }
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
