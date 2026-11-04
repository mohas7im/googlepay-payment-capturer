import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'payment_capture.dart';

/// Reads UPI payments on Android: payment-app notifications (money in) and
/// the payment apps' own screens (money out, group splits).
///
/// Captures made while the app isn't listening are kept natively; read them
/// with [drainBufferedCaptures] when the app starts.
class GooglePayPaymentCapturer {
  static const MethodChannel _control = MethodChannel('google_pay_payment_capturer/control');
  static const EventChannel _captures = EventChannel('google_pay_payment_capturer/captures');

  static final GooglePayPaymentCapturer instance = GooglePayPaymentCapturer._();
  factory GooglePayPaymentCapturer() => instance;
  GooglePayPaymentCapturer._();

  Stream<Map<String, dynamic>>? _rawCaptures;

  /// Captures as they happen, as the native payload. Only one Flutter engine
  /// receives them at a time: the one that listened last.
  Stream<Map<String, dynamic>> get rawCaptures => _rawCaptures ??= _captures
      .receiveBroadcastStream()
      .where((e) => e is Map)
      .map((e) => (e as Map).map((k, v) => MapEntry(k.toString(), v)));

  /// Captures as they happen. Each one is also kept in the native buffer
  /// until [drainBufferedCaptures], so nothing is lost if the app is killed
  /// before it's handled.
  Stream<PaymentCapture> get captures => rawCaptures.map(PaymentCapture.fromMap);

  Future<T?> _invoke<T>(String method, [Object? arguments]) async {
    try {
      return await _control.invokeMethod<T>(method, arguments);
    } on MissingPluginException catch (e) {
      debugPrint('GooglePayPaymentCapturer: native side missing (Android only; full rebuild needed?): $e');
    } on PlatformException catch (e) {
      debugPrint('GooglePayPaymentCapturer $method: $e');
    }
    return null;
  }

  List<Map<String, dynamic>> _maps(List<dynamic>? list) =>
      [for (final e in list ?? const []) if (e is Map) e.map((k, v) => MapEntry(k.toString(), v))];

  // ── Permissions ──────────────────────────────────────────────────────────

  /// Notification access is on for this app (needed for notification captures).
  Future<bool> hasNotificationAccess() async => await _invoke<bool>('hasPermission') ?? false;

  /// Opens the notification access page (this app's entry, where Android allows).
  Future<bool> openNotificationAccessSettings() async => await _invoke<bool>('openSettings') ?? false;

  /// The notification listener is bound right now (access alone doesn't mean it is).
  Future<bool> isListenerConnected() async => await _invoke<bool>('isListenerConnected') ?? false;

  /// The accessibility "Payment Reader" is on (needed for outgoing payments and splits).
  Future<bool> hasAccessibilityAccess() async => await _invoke<bool>('hasAccessibilityPermission') ?? false;

  Future<bool> openAccessibilitySettings() async => await _invoke<bool>('openAccessibilitySettings') ?? false;

  /// WRITE_SECURE_SETTINGS was granted over adb, so the reader is switched
  /// back on by itself after an app update or force-stop.
  Future<bool> canRestoreReader() async => await _invoke<bool>('canRestoreReader') ?? false;

  // ── Captures kept while the app wasn't listening ──────────────────────────

  /// Every buffered capture, removed from the buffer in the same step, so one
  /// captured in between can't be lost. Oldest first. Captures already
  /// delivered on [captures] are here too: de-duplicate on your side.
  Future<List<PaymentCapture>> drainBufferedCaptures() async =>
      (await drainBufferedCaptureMaps()).map(PaymentCapture.fromMap).toList();

  /// [drainBufferedCaptures] as native payloads.
  Future<List<Map<String, dynamic>>> drainBufferedCaptureMaps() async =>
      _maps(await _invoke<List<dynamic>>('drainBufferedNotifications'));

  /// The buffer without clearing it.
  Future<List<Map<String, dynamic>>> getBufferedCaptureMaps() async =>
      _maps(await _invoke<List<dynamic>>('getBufferedNotifications'));

  Future<void> clearBufferedCaptures() => _invoke('clearBufferedNotifications');

  /// Reads payment notifications still in the shade (also buffered).
  Future<List<Map<String, dynamic>>> syncActiveNotificationMaps() async =>
      _maps(await _invoke<List<dynamic>>('syncActiveNotifications'));

  // ── Location ──────────────────────────────────────────────────────────────

  /// The phone's last known location, if under 10 minutes old.
  Future<({double latitude, double longitude})?> getCurrentLocation() async {
    final res = await _invoke<Map<dynamic, dynamic>>('getCurrentLocation');
    if (res == null) return null;
    return (latitude: (res['latitude'] as num).toDouble(), longitude: (res['longitude'] as num).toDouble());
  }

  // ── Screen reader log (debugging) ─────────────────────────────────────────

  /// Off unless switched on. It records the text of payment screens (names,
  /// UPI IDs) and keeps it until [clearScreenDumps]; turning it off only stops recording.
  Future<bool> isScreenLogEnabled() async => await _invoke<bool>('isScreenLogEnabled') ?? false;

  Future<void> setScreenLogEnabled(bool enabled) => _invoke('setScreenLogEnabled', enabled);

  /// Screens with an amount on them that the reader saw, newest first.
  Future<List<ScreenDump>> getScreenDumps() async =>
      _maps(await _invoke<List<dynamic>>('getScreenDumps')).map(ScreenDump.fromMap).toList();

  Future<void> clearScreenDumps() => _invoke('clearScreenDumps');
}
