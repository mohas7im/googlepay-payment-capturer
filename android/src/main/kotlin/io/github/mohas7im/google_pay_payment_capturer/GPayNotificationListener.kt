package io.github.mohas7im.google_pay_payment_capturer

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android NotificationListenerService that captures incoming notifications
 * specifically from Google Pay India (com.google.android.apps.nbu.paisa.user).
 */
class GPayNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "GPayNotifListener"
        /** Oldest cached location fix still taken as "where the payment happened". */
        private const val MAX_LOCATION_AGE_MS = 10 * 60 * 1000L
        /** How long a payment waits for a fresh fix before a cached one is used. */
        private const val FRESH_LOCATION_TIMEOUT_MS = 10_000L
        const val GPAY_PACKAGE_NAME = "com.google.android.apps.nbu.paisa.user"
        private const val PREFS_NAME = "gpay_notification_prefs"
        private const val KEY_BUFFERED_NOTIFS = "buffered_notifications"
        // Holds everything captured while the app is closed; the oldest is
        // dropped past this, so it's kept well above a few days of payments.
        private const val MAX_BUFFER_SIZE = 500

        // Active listener callback registered by GooglePayPaymentCapturerPlugin's EventChannel
        @Volatile
        private var notificationCallback: ((Map<String, Any?>) -> Unit)? = null

        // Static reference to the active NotificationListenerService instance
        @Volatile
        var instance: GPayNotificationListener? = null
            private set

        fun setNotificationListener(callback: ((Map<String, Any?>) -> Unit)?) {
            notificationCallback = callback
        }

        /**
         * Removes [callback] only if it's still the active one. The plugin is
         * attached to every Flutter engine (background isolates too); one
         * engine going away mustn't cut off the stream another is listening to.
         */
        fun clearNotificationListener(callback: (Map<String, Any?>) -> Unit) {
            if (notificationCallback === callback) notificationCallback = null
        }

        fun postDirectMessage(data: Map<String, Any?>) {
            notificationCallback?.invoke(data)
        }

        /**
         * Checks if the notification is from Google Pay, other major UPI apps,
         * or SMS messaging apps (which receive bank debit/credit alerts for UPI transactions).
         */
        fun isTargetPackage(pkgName: String): Boolean {
            val lower = pkgName.lowercase()
            // Google Pay India & Google Wallet
            if (lower == GPAY_PACKAGE_NAME || lower == "com.google.android.apps.walletnfcrel" || lower.contains("paisa")) {
                return true
            }
            // Other popular UPI & payment apps in India
            if (lower == "com.phonepe.app" || lower == "net.one97.paytm" ||
                lower == "in.org.npci.upiapp" || lower == "com.dreamplug.androidapp" ||
                lower.contains("bhim") || lower.contains("upi")) {
                return true
            }
            // NOTE: SMS / messaging apps are intentionally NOT captured here.
            // Bank SMS alerts are handled by SmsParserService; this listener is
            // only for UPI / payment app notifications and on-screen captures.
            // Banking apps
            if (lower.contains("bank") || lower.contains("yono") || lower.contains("imobile") || lower.contains("fedmobile")) {
                return true
            }
            return false
        }

        /**
         * Filter out promotional noise, ad banners, scratch cards, and unpaid requests.
         * Accepts actual debit/credit payments that mention an amount or financial movement.
         */
        fun isRealTransaction(title: String, content: String): Boolean {
            val combined = "$title $content".lowercase()
            if (combined.isBlank()) return false

            // 1. Skip unpaid request notifications (requests without money movement)
            if (combined.contains("requested money") || 
                combined.contains("payment request") ||
                combined.contains("new split request") ||
                combined.contains("request in")) {
                // If it indicates money was actually sent/received, allow it
                if (!combined.contains("paid you") && !combined.contains("you paid") && !combined.contains("successful")) {
                    return false
                }
            }

            // 2. Needs an amount...
            if (!AMOUNT.containsMatchIn(combined)) return false

            // 3. ...and proof that money moved. A ₹ figure alone is how every
            // offer looks ("Get ₹50 back", "win up to ₹500").
            val personToPerson = PERSON_MOVEMENT.containsMatchIn(combined)
            val confirmed = personToPerson || CONFIRMED_MOVEMENT.containsMatchIn(combined)
            if (!confirmed) return false

            // 4. Offer wording sinks it unless someone actually paid / was paid
            // ("You paid Rahul ₹200 · you won a scratch card" is still a payment).
            if (PROMO.containsMatchIn(combined) && !personToPerson) return false
            return true
        }

        private val AMOUNT = Regex("""(?:₹|\brs\.?|\binr\.?)\s*[\d,]*\d""")

        /**
         * Money that came in ("Arjun paid you ₹500"). Only a payment you make
         * gets the phone's location, so these skip the GPS fix. A debit that
         * names where it was "credited to" is still outgoing.
         */
        private val RECEIVED = Regex("""\b(paid you|sent you|received from|received ₹|credited)""")
        private val OUTGOING = Regex("""\b(you paid|you sent|paid to|sent to|debited)\b""")

        fun isReceived(data: Map<String, Any?>): Boolean {
            val text = "${data["title"] ?: ""} ${data["body"] ?: ""}".lowercase()
            return RECEIVED.containsMatchIn(text) && !OUTGOING.containsMatchIn(text)
        }

        /** Money moved between you and someone: the strongest signal. */
        private val PERSON_MOVEMENT = Regex(
            """\b(paid you|sent you|you paid|you sent|received from|received ₹)"""
        )

        /** Bank / UPI confirmation of a completed debit or credit. */
        private val CONFIRMED_MOVEMENT = Regex(
            """\b(debited|credited|payment (was )?successful|paid successfully|sent successfully|transaction successful|transfer successful|money sent|refund(ed)? of|upi ref|utr)\b|\b(payment|paid|sent) to .{1,40}?\b(was )?(successful|successfully|completed)\b"""
        )

        private val PROMO = Regex(
            """\b(cashback|offers?|rewards?|scratch|voucher|coupon|promo|win|won|earn|bonus|free|discount|deal|sale|claim|unlock|invite|refer|pre-?approved|loan|credit line|insurance|gold|recharge now|pay now|pay bills?|bill due|due on|get up to|up ?to ₹|get ₹|t&c|valid (till|until)|limited|hurry|exclusive)\b|\d+\s?%"""
        )

        /**
         * Obtains device GPS latitude & longitude at the moment of payment interception.
         */
        /**
         * Where the phone is right now, for a payment made just now: asks
         * Android for a fresh fix (fused, else GPS, else network) and falls
         * back to a recent cached one if none arrives within [timeoutMs].
         * [onResult] runs once, on the main thread; null when location is off
         * or not allowed. Needs "Allow all the time": the payment happens in
         * GPay, so the host app is in the background.
         */
        fun requestFreshLocation(
            context: Context,
            timeoutMs: Long = FRESH_LOCATION_TIMEOUT_MS,
            onResult: (Pair<Double, Double>?) -> Unit,
        ) {
            var done = false
            fun finish(result: Pair<Double, Double>?) {
                if (done) return
                done = true
                onResult(result)
            }

            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                finish(getLatestLocation(context))
                return
            }
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                val provider = when {
                    lm == null -> null
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.allProviders.contains(LocationManager.FUSED_PROVIDER) ->
                        LocationManager.FUSED_PROVIDER
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }
                if (lm == null || provider == null) {
                    finish(getLatestLocation(context))
                    return
                }
                val signal = CancellationSignal()
                lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { loc ->
                    if (loc != null) {
                        Log.i(TAG, "Fresh location for payment ($provider, ±${loc.accuracy.toInt()} m)")
                        finish(Pair(loc.latitude, loc.longitude))
                    } else {
                        finish(getLatestLocation(context))
                    }
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!done) {
                        signal.cancel()
                        Log.w(TAG, "No fresh location within ${timeoutMs} ms; using a recent cached one if any")
                        finish(getLatestLocation(context))
                    }
                }, timeoutMs)
            } catch (e: SecurityException) {
                Log.w(TAG, "Location not allowed in the background: ${e.message}")
                finish(getLatestLocation(context))
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting a fresh location", e)
                finish(getLatestLocation(context))
            }
        }

        fun getLatestLocation(context: Context): Pair<Double, Double>? {
            val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fineGranted && !coarseGranted) {
                Log.w(TAG, "Location permission not granted: fine=$fineGranted, coarse=$coarseGranted")
                return null
            }

            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
                val candidateProviders = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    candidateProviders.add(LocationManager.FUSED_PROVIDER)
                }
                candidateProviders.add(LocationManager.GPS_PROVIDER)
                candidateProviders.add(LocationManager.NETWORK_PROVIDER)
                candidateProviders.add(LocationManager.PASSIVE_PROVIDER)

                for (p in lm.allProviders) {
                    if (!candidateProviders.contains(p)) {
                        candidateProviders.add(p)
                    }
                }

                var bestLocation: Location? = null
                for (provider in candidateProviders) {
                    try {
                        val l = lm.getLastKnownLocation(provider) ?: continue
                        // A cached fix can be hours old and from somewhere
                        // else entirely; tagging the payment with it is
                        // worse than leaving the location empty.
                        val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
                        if (ageMs > MAX_LOCATION_AGE_MS) continue
                        if (bestLocation == null || l.time > bestLocation.time) {
                            bestLocation = l
                        }
                    } catch (pe: SecurityException) {
                        Log.w(TAG, "SecurityException fetching location for $provider: ${pe.message}")
                    } catch (_: Exception) {}
                }

                if (bestLocation != null) {
                    Log.i(TAG, "Obtained GPS location: lat=${bestLocation.latitude}, lng=${bestLocation.longitude} (provider=${bestLocation.provider})")
                    return Pair(bestLocation.latitude, bestLocation.longitude)
                } else {
                    Log.w(TAG, "No cached location available across any provider: $candidateProviders")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching location for GPay notification", e)
            }
            return null
        }

        /**
         * Persist notification to native SharedPreferences buffer so background or
         * closed-app notifications are never dropped before Flutter reads them.
         */
        private val bufferLock = Any()

        fun bufferNotification(context: Context, data: Map<String, Any?>) {
            synchronized(bufferLock) { bufferNotificationLocked(context, data) }
        }

        private fun bufferNotificationLocked(context: Context, data: Map<String, Any?>) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val existingStr = prefs.getString(KEY_BUFFERED_NOTIFS, null)
                val jsonArray = if (existingStr != null) JSONArray(existingStr) else JSONArray()

                val obj = JSONObject()
                for ((k, v) in data) {
                    obj.put(k, v)
                }

                // Check for duplicates in buffer before adding
                val newTitle = data["title"]?.toString() ?: ""
                val newBody = data["body"]?.toString() ?: ""
                val newTs = data["timestamp"]?.toString() ?: ""

                for (i in 0 until jsonArray.length()) {
                    val existing = jsonArray.getJSONObject(i)
                    if (existing.optString("title") == newTitle &&
                        existing.optString("body") == newBody &&
                        existing.optString("timestamp") == newTs) {
                        return // Already in buffer
                    }
                }

                if (jsonArray.length() >= MAX_BUFFER_SIZE) {
                    jsonArray.remove(0)
                }
                jsonArray.put(obj)
                prefs.edit().putString(KEY_BUFFERED_NOTIFS, jsonArray.toString()).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Error buffering notification", e)
            }
        }

        /**
         * Returns all offline buffered notifications as a List of Maps.
         */
        fun getBufferedNotifications(context: Context): List<Map<String, Any?>> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_BUFFERED_NOTIFS, null) ?: return emptyList()
            val list = mutableListOf<Map<String, Any?>>()
            try {
                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val map = mutableMapOf<String, Any?>()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        map[key] = obj.get(key)
                    }
                    list.add(map)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading buffered notifications", e)
            }
            return list
        }

        /**
         * Clears the native SharedPreferences buffer after Flutter has synced.
         */
        fun clearBufferedNotifications(context: Context) {
            synchronized(bufferLock) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().remove(KEY_BUFFERED_NOTIFS).apply()
            }
        }

        /**
         * Atomically reads AND clears the buffer in one step, so a capture written
         * by the accessibility service between a separate "get" and "clear" call
         * from Flutter can never be silently wiped before Flutter has seen it.
         */
        fun drainBufferedNotifications(context: Context): List<Map<String, Any?>> {
            synchronized(bufferLock) {
                val list = getBufferedNotifications(context)
                if (list.isNotEmpty()) {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().remove(KEY_BUFFERED_NOTIFS).apply()
                }
                return list
            }
        }

        /**
         * Extracts and validates structured transaction data from a StatusBarNotification.
         */
        fun extractNotificationData(context: Context, sbn: StatusBarNotification): Map<String, Any?>? {
            val pkgName = sbn.packageName ?: return null
            if (!isTargetPackage(pkgName)) return null

            try {
                val notification = sbn.notification ?: return null
                val extras = notification.extras ?: return null

                // Extract all title variations
                val title = extras.getString(Notification.EXTRA_TITLE)
                    ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                    ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
                    ?: ""

                // Extract all body / text variations
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
                val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
                val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString() ?: ""
                val ticker = notification.tickerText?.toString() ?: ""

                // Multi-line messages (InboxStyle)
                val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                    ?.joinToString("\n") ?: ""

                // Construct full content for matching
                val fullContent = listOf(title, bigText, text, textLines, subText, ticker, infoText)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" ")

                // Pick best resolved body
                val resolvedBody = listOf(bigText, text, textLines, subText, ticker, infoText)
                    .firstOrNull { it.isNotBlank() } ?: ""

                // Check transaction validity
                if (!isRealTransaction(title, fullContent)) {
                    Log.d(TAG, "Filtered out non-transaction alert: title='$title', body='$resolvedBody' (pkg=$pkgName)")
                    return null
                }

                val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) notification.channelId ?: "" else ""
                val tag = sbn.tag ?: ""
                val category = notification.category ?: ""

                // Phone location, only for a notification from the last few
                // minutes: one read later from the shade happened elsewhere.
                // onNotificationPosted replaces this with a fresh fix.
                val isRecent = System.currentTimeMillis() - sbn.postTime <= MAX_LOCATION_AGE_MS
                val isIncoming = isReceived(mapOf("title" to title, "body" to fullContent))
                val locationPair = if (isRecent && !isIncoming) getLatestLocation(context) else null
                val latitude = locationPair?.first
                val longitude = locationPair?.second

                val data = mutableMapOf<String, Any?>(
                    "packageName" to pkgName,
                    "title" to title,
                    "body" to resolvedBody,
                    "subText" to subText,
                    "timestamp" to sbn.postTime,
                    "postTime" to sbn.postTime,
                    "isTransaction" to true,
                    "channelId" to channelId,
                    "tag" to tag,
                    "category" to category,
                    "latitude" to latitude,
                    "longitude" to longitude,
                    // Every field the notification carried, as read.
                    "rawText" to listOf(
                        "title" to title, "text" to text, "bigText" to bigText, "lines" to textLines,
                        "subText" to subText, "infoText" to infoText, "ticker" to ticker,
                    ).filter { it.second.isNotBlank() }.joinToString("\n") { "${it.first}: ${it.second}" }
                )

                return data
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting notification data from $pkgName", e)
                return null
            }
        }

        /**
         * Scans all currently active notifications in the Android notification shade,
         * extracts any GPay / UPI / Bank transaction notifications, buffers them,
         * and returns them to Flutter.
         */
        fun syncActiveNotifications(context: Context): List<Map<String, Any?>> {
            val listener = instance
            if (listener == null) {
                Log.w(TAG, "Cannot sync active notifications: listener instance is null (waiting for onListenerConnected)")
                return emptyList()
            }

            val activeSbn = try {
                listener.activeNotifications
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching activeNotifications from system", e)
                null
            } ?: return emptyList()

            val resultList = mutableListOf<Map<String, Any?>>()
            for (sbn in activeSbn) {
                val data = extractNotificationData(context, sbn)
                if (data != null) {
                    bufferNotification(context, data)
                    resultList.add(data)
                    Log.d(TAG, "Synced active notification: ${data["title"]} - ${data["body"]}")
                }
            }
            return resultList
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val data = extractNotificationData(applicationContext, sbn) ?: return
        // Money received needs no place: send it on without a GPS fix.
        if (isReceived(data)) {
            bufferNotification(applicationContext, data)
            notificationCallback?.invoke(data)
            Log.d(TAG, "Dispatched incoming GPay/UPI notification: ${data["title"]} - ${data["body"]}")
            return
        }
        // Tag it with where the phone is now, as the payment just happened.
        requestFreshLocation(applicationContext) { loc ->
            val tagged = data.toMutableMap()
            if (loc != null) {
                tagged["latitude"] = loc.first
                tagged["longitude"] = loc.second
            }
            bufferNotification(applicationContext, tagged)
            notificationCallback?.invoke(tagged)
            Log.d(TAG, "Dispatched GPay/UPI transaction notification: ${tagged["title"]} - ${tagged["body"]}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "GPayNotificationListener connected and active")
        // Automatically scan active notifications as soon as connected
        syncActiveNotifications(applicationContext)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) instance = null
        Log.i(TAG, "GPayNotificationListener disconnected")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }
}

