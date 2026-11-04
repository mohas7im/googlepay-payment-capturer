import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_pay_payment_capturer/google_pay_payment_capturer.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('PaymentCapture.fromMap', () {
    test('screen capture keeps what the reader read', () {
      final c = PaymentCapture.fromMap({
        'packageName': 'com.google.android.apps.nbu.paisa.user',
        'title': 'Paid ₹370.00 to Sharma Mobiles',
        'body': 'On-screen captured payment • UPI Ref: 123456789012 • Completed',
        'subText': 'On-Screen Payment Reader · success page',
        'timestamp': 1760000000000,
        'postTime': 1760000000000,
        'amount': 370.0,
        'merchant': 'Sharma Mobiles',
        'isExpense': true,
        'channelId': 'accessibility_screen_reader',
        'tag': 'accessibility',
        'utrNumber': '123456789012',
        'live': true,
        'latitude': 11.25,
        'longitude': 75.78,
      });
      expect(c.source, CaptureSource.screen);
      expect(c.amount, 370.0);
      expect(c.merchant, 'Sharma Mobiles');
      expect(c.isExpense, isTrue);
      expect(c.utrNumber, '123456789012');
      expect(c.hasLocation, isTrue);
      expect(c.isUnconfirmed, isFalse);
      expect(c.timestamp.millisecondsSinceEpoch, 1760000000000);
    });

    test('notification: money received is read from the title', () {
      final c = PaymentCapture.fromMap({
        'title': 'PRIYA NAIR paid you ₹1.00',
        'body': 'Tap to view',
        'channelId': 'payments',
        'postTime': 1760000000000,
      });
      expect(c.source, CaptureSource.notification);
      expect(c.isExpense, isFalse);
      expect(c.merchant, 'PRIYA NAIR');
      expect(c.amount, 1.0);
    });

    test('notification: group payment names the payer in the body', () {
      final c = PaymentCapture.fromMap({
        'title': "New payment in 'Trip'",
        'body': 'Anil Menon paid you ₹203.33!',
      });
      expect(c.isExpense, isFalse);
      expect(c.merchant, 'Anil Menon');
      expect(c.amount, 203.33);
    });

    test('notification: outgoing payment names the payee', () {
      final c = PaymentCapture.fromMap({'title': 'Paid ₹1,250 to Swiggy', 'body': ''});
      expect(c.isExpense, isTrue);
      expect(c.merchant, 'Swiggy');
      expect(c.amount, 1250.0);
    });

    test('split participants arrive as a JSON string', () {
      final c = PaymentCapture.fromMap({
        'title': 'Split ₹164.00 with 2 people',
        'channelId': 'split_request',
        'amount': 164.0,
        'splitParticipants':
            '[{"name":"Ravi Kumar","amount":54.66,"status":"Unpaid"},{"name":"You","amount":54.67,"paid":true}]',
      });
      expect(c.source, CaptureSource.split);
      expect(c.isGroupSplit, isTrue);
      expect(c.splitParticipants, hasLength(2));
      expect(c.splitParticipants.first.status, 'Unpaid');
      expect(c.splitParticipants.last.paid, isTrue);
    });

    test('not confirmed and caught up captures', () {
      final c = PaymentCapture.fromMap({
        'title': 'Paid ₹1.00 to PRIYA NAIR',
        'body': 'On-screen captured payment • Not confirmed: GPay closed before its success page',
        'channelId': 'accessibility_screen_reader',
        'live': false,
      });
      expect(c.isUnconfirmed, isTrue);
      expect(c.live, isFalse);
    });
  });

  group('GooglePayPaymentCapturer', () {
    const channel = MethodChannel('google_pay_payment_capturer/control');
    final calls = <MethodCall>[];

    setUp(() {
      calls.clear();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        switch (call.method) {
          case 'hasPermission':
            return true;
          case 'drainBufferedNotifications':
            return [
              {'title': 'Paid ₹50 to Swiggy', 'channelId': 'accessibility_screen_reader', 'amount': 50.0, 'merchant': 'Swiggy'},
            ];
          case 'getCurrentLocation':
            return {'latitude': 1.5, 'longitude': 2.5};
          case 'getScreenDumps':
            return [
              {'time': 1, 'packageName': 'p', 'result': 'payment', 'texts': 'a | b'},
            ];
        }
        return null;
      });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    });

    test('permission check', () async {
      expect(await GooglePayPaymentCapturer.instance.hasNotificationAccess(), isTrue);
      expect(await GooglePayPaymentCapturer.instance.hasAccessibilityAccess(), isFalse);
    });

    test('drain buffered captures', () async {
      final list = await GooglePayPaymentCapturer.instance.drainBufferedCaptures();
      expect(list.single.merchant, 'Swiggy');
      expect(list.single.source, CaptureSource.screen);
    });

    test('location and screen log', () async {
      final loc = await GooglePayPaymentCapturer.instance.getCurrentLocation();
      expect(loc?.latitude, 1.5);
      final dumps = await GooglePayPaymentCapturer.instance.getScreenDumps();
      expect(dumps.single.toMap()['texts'], 'a | b');
      await GooglePayPaymentCapturer.instance.setScreenLogEnabled(true);
      expect(calls.last.arguments, true);
    });

    test('missing native side gives safe defaults', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
      expect(await GooglePayPaymentCapturer.instance.hasNotificationAccess(), isFalse);
      expect(await GooglePayPaymentCapturer.instance.drainBufferedCaptures(), isEmpty);
    });
  });
}
