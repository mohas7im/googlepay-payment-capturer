package io.github.mohas7im.google_pay_payment_capturer

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter side of the reader: permission checks and settings pages, the
 * offline capture buffer, the screen reader log, and a live stream of
 * captures from [GPayNotificationListener] and [PaymentAccessibilityService].
 */
class GooglePayPaymentCapturerPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler, ActivityAware {
    companion object {
        const val CONTROL_CHANNEL = "google_pay_payment_capturer/control"
        const val CAPTURE_CHANNEL = "google_pay_payment_capturer/captures"
    }

    private lateinit var context: Context
    private var activity: Activity? = null
    private var controlChannel: MethodChannel? = null
    private var captureChannel: EventChannel? = null
    private var captureCallback: ((Map<String, Any?>) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        // In case the post-update broadcast didn't run: bring the Payment
        // Reader back if the last update switched it off.
        ReaderRestore.restoreIfNeeded(context)
        controlChannel = MethodChannel(binding.binaryMessenger, CONTROL_CHANNEL).also { it.setMethodCallHandler(this) }
        captureChannel = EventChannel(binding.binaryMessenger, CAPTURE_CHANNEL).also { it.setStreamHandler(this) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        controlChannel?.setMethodCallHandler(null)
        captureChannel?.setStreamHandler(null)
        controlChannel = null
        captureChannel = null
        stopCaptures()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) { activity = binding.activity }
    override fun onDetachedFromActivityForConfigChanges() { activity = null }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) { activity = binding.activity }
    override fun onDetachedFromActivity() { activity = null }

    // ── Captures stream ─────────────────────────────────────────────────────
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        stopCaptures()
        val callback: (Map<String, Any?>) -> Unit = { data ->
            mainHandler.post {
                try {
                    events?.success(data)
                } catch (_: Exception) {}
            }
        }
        captureCallback = callback
        GPayNotificationListener.setNotificationListener(callback)
    }

    override fun onCancel(arguments: Any?) = stopCaptures()

    private fun stopCaptures() {
        captureCallback?.let { GPayNotificationListener.clearNotificationListener(it) }
        captureCallback = null
    }

    // ── Control channel ─────────────────────────────────────────────────────
    private fun startSettings(intent: Intent) {
        val from = activity
        if (from != null) from.startActivity(intent)
        else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "hasPermission" -> {
                try {
                    val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
                    result.success(enabledPackages.contains(context.packageName))
                } catch (e: Exception) {
                    result.error("PERMISSION_CHECK_ERROR", e.message, null)
                }
            }
            "openSettings" -> {
                try {
                    var intent: Intent? = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val detailIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                            val component = ComponentName(context, GPayNotificationListener::class.java)
                            putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
                        }
                        if (detailIntent.resolveActivity(context.packageManager) != null) {
                            intent = detailIntent
                        }
                    }
                    startSettings(intent ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    result.success(true)
                } catch (_: Exception) {
                    try {
                        startSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        result.success(true)
                    } catch (_: Exception) {
                        try {
                            startSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            })
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("SETTINGS_ERROR", e.message, null)
                        }
                    }
                }
            }
            "getBufferedNotifications" -> {
                try {
                    result.success(GPayNotificationListener.getBufferedNotifications(context))
                } catch (e: Exception) {
                    result.error("BUFFERED_NOTIFS_ERROR", e.message, null)
                }
            }
            "drainBufferedNotifications" -> {
                try {
                    result.success(GPayNotificationListener.drainBufferedNotifications(context))
                } catch (e: Exception) {
                    result.error("DRAIN_BUFFER_ERROR", e.message, null)
                }
            }
            "clearBufferedNotifications" -> {
                try {
                    GPayNotificationListener.clearBufferedNotifications(context)
                    result.success(true)
                } catch (e: Exception) {
                    result.error("CLEAR_BUFFER_ERROR", e.message, null)
                }
            }
            "getCurrentLocation" -> {
                try {
                    val loc = GPayNotificationListener.getLatestLocation(context)
                    result.success(loc?.let { mapOf("latitude" to it.first, "longitude" to it.second) })
                } catch (e: Exception) {
                    result.error("LOCATION_ERROR", e.message, null)
                }
            }
            "syncActiveNotifications" -> {
                try {
                    result.success(GPayNotificationListener.syncActiveNotifications(context))
                } catch (e: Exception) {
                    result.error("SYNC_ACTIVE_ERROR", e.message, null)
                }
            }
            "isListenerConnected" -> result.success(GPayNotificationListener.instance != null)
            "hasAccessibilityPermission" -> {
                try {
                    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                    val enabledServices = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
                    val isEnabled = enabledServices?.any {
                        it.resolveInfo.serviceInfo.packageName == context.packageName &&
                            it.resolveInfo.serviceInfo.name == PaymentAccessibilityService::class.java.name
                    } ?: false
                    result.success(isEnabled || PaymentAccessibilityService.isRunning)
                } catch (e: Exception) {
                    result.error("ACCESSIBILITY_CHECK_ERROR", e.message, null)
                }
            }
            "openAccessibilitySettings" -> {
                try {
                    startSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    result.success(true)
                } catch (e: Exception) {
                    result.error("ACCESSIBILITY_SETTINGS_ERROR", e.message, null)
                }
            }
            "canRestoreReader" -> result.success(ReaderRestore.canRestore(context))
            "getScreenDumps" -> result.success(PaymentAccessibilityService.getScreenDumps(context))
            "clearScreenDumps" -> {
                PaymentAccessibilityService.clearScreenDumps(context)
                result.success(true)
            }
            "isScreenLogEnabled" -> result.success(PaymentAccessibilityService.isScreenLogEnabled(context))
            "setScreenLogEnabled" -> {
                PaymentAccessibilityService.setScreenLogEnabled(context, call.arguments == true)
                result.success(true)
            }
            else -> result.notImplemented()
        }
    }
}
