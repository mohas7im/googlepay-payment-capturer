import 'dart:async';

import 'package:flutter/material.dart';
import 'package:google_pay_payment_capturer/google_pay_payment_capturer.dart';

void main() => runApp(const MaterialApp(home: CapturesPage()));

class CapturesPage extends StatefulWidget {
  const CapturesPage({super.key});

  @override
  State<CapturesPage> createState() => _CapturesPageState();
}

class _CapturesPageState extends State<CapturesPage> with WidgetsBindingObserver {
  final _reader = GooglePayPaymentCapturer.instance;
  final _captures = <PaymentCapture>[];
  StreamSubscription<PaymentCapture>? _sub;
  bool _notificationAccess = false;
  bool _accessibility = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _sub = _reader.captures.listen((c) => setState(() => _captures.insert(0, c)));
    _refresh();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Back from the settings page, or captured while in the background.
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    final buffered = await _reader.drainBufferedCaptures();
    final notificationAccess = await _reader.hasNotificationAccess();
    final accessibility = await _reader.hasAccessibilityAccess();
    if (!mounted) return;
    setState(() {
      _notificationAccess = notificationAccess;
      _accessibility = accessibility;
      // The buffer also holds what the live stream already delivered.
      for (final c in buffered.reversed) {
        final seen = _captures.any((o) => o.title == c.title && o.timestamp == c.timestamp);
        if (!seen) _captures.insert(0, c);
      }
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Payment Reader')),
      body: ListView(
        children: [
          SwitchListTile(
            title: const Text('Notification access'),
            subtitle: const Text('Money received, bank alerts'),
            value: _notificationAccess,
            onChanged: (_) => _reader.openNotificationAccessSettings(),
          ),
          SwitchListTile(
            title: const Text('Payment Reader (accessibility)'),
            subtitle: const Text('Payments you make, group splits'),
            value: _accessibility,
            onChanged: (_) => _reader.openAccessibilitySettings(),
          ),
          const Divider(),
          if (_captures.isEmpty)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Text('Make a UPI payment in GPay and it shows up here.'),
            ),
          for (final c in _captures)
            ListTile(
              leading: Icon(c.isExpense == false ? Icons.south_west : Icons.north_east),
              title: Text(c.merchant.isNotEmpty ? c.merchant : c.title),
              subtitle: Text([
                c.source.name,
                if (c.utrNumber.isNotEmpty) 'UTR ${c.utrNumber}',
                if (c.hasLocation) '${c.latitude!.toStringAsFixed(4)}, ${c.longitude!.toStringAsFixed(4)}',
                if (c.isUnconfirmed) 'not confirmed',
              ].join(' · ')),
              trailing: Text(c.amount != null ? '₹${c.amount!.toStringAsFixed(2)}' : ''),
            ),
        ],
      ),
    );
  }
}
