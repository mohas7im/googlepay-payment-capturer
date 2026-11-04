import 'dart:convert';

/// Where a capture came from.
enum CaptureSource {
  /// A payment app's or bank's notification (money in, and some money out).
  notification,

  /// An outgoing payment read off the payment app's screen.
  screen,

  /// A GPay group split read off the screen.
  split,

  /// A GPay group split that was closed.
  splitClosed,
}

/// One person's share of a group split.
class SplitShare {
  final String name;
  final double amount;

  /// The one who paid the bill (sent the split request).
  final bool paid;

  /// GPay's word for the share, when shown: "Unpaid", "Sent this request"…
  final String? status;

  const SplitShare({required this.name, required this.amount, this.paid = false, this.status});

  factory SplitShare.fromMap(Map<dynamic, dynamic> map) => SplitShare(
        name: map['name']?.toString() ?? '',
        amount: (map['amount'] as num?)?.toDouble() ?? 0,
        paid: map['paid'] == true,
        status: map['status']?.toString(),
      );

  @override
  String toString() => 'SplitShare($name, $amount${paid ? ', paid' : ''})';
}

/// A payment, split or notification the reader captured.
///
/// [raw] is the payload exactly as the native side sent it; the other fields
/// are read from it. For a [CaptureSource.notification], which carries only
/// the notification's text, [amount], [merchant] and [isExpense] are worked
/// out from that text when it says them plainly ("Paid ₹50 to Swiggy",
/// "Rahul paid you ₹200").
class PaymentCapture {
  final Map<String, dynamic> raw;

  /// Set only for closed splits; otherwise use your own key (e.g. [timestamp] + [title]).
  final String? id;
  final String packageName;
  final String title;
  final String body;

  /// Says which screen a screen capture was read from: "On-Screen Payment Reader · success page".
  final String subText;

  /// When the payment was made (as shown on screen), or the notification was posted.
  final DateTime timestamp;
  final double? amount;
  final String merchant;

  /// true: money went out; false: money came in; null: unknown.
  final bool? isExpense;
  final String utrNumber;
  final double? latitude;
  final double? longitude;

  /// false when the payment was caught up later from its details page or a chat
  /// (the phone was locked straight after paying); such captures have no location.
  final bool live;
  final String channelId;
  final String tag;
  final String category;
  final List<SplitShare> splitParticipants;

  /// Every text the reader read, one per line, or the notification's fields.
  final String rawText;

  const PaymentCapture({
    required this.raw,
    this.id,
    required this.packageName,
    required this.title,
    required this.body,
    required this.subText,
    required this.timestamp,
    this.amount,
    this.merchant = '',
    this.isExpense,
    this.utrNumber = '',
    this.latitude,
    this.longitude,
    this.live = true,
    this.channelId = '',
    this.tag = '',
    this.category = '',
    this.splitParticipants = const [],
    this.rawText = '',
  });

  CaptureSource get source => switch (channelId) {
        'accessibility_screen_reader' => CaptureSource.screen,
        'split_request' => CaptureSource.split,
        'split_closed' => CaptureSource.splitClosed,
        _ => CaptureSource.notification,
      };

  bool get isGroupSplit => source == CaptureSource.split || source == CaptureSource.splitClosed;

  /// Saved from GPay's "Paying ₹X" page after the PIN, with no success page
  /// read: GPay was closed during the tick animation. The payment may still have failed.
  bool get isUnconfirmed => body.contains('Not confirmed');

  bool get hasLocation => latitude != null && longitude != null;

  static final _amount = RegExp(r'(?:₹|\brs\.?|\binr\.?)\s*([\d,]+(?:\.\d{1,2})?)', caseSensitive: false);

  /// "Paid ₹50 to Swiggy", "Sent Rs 200 to Rahul".
  static final _paidAmountTo = RegExp(r'\b(?:paid|sent)\s*(?:₹|rs\.?|inr\.?)\s*[\d,]+(?:\.\d{1,2})?\s+to\b');

  factory PaymentCapture.fromMap(Map<dynamic, dynamic> map) {
    final raw = map.map((k, v) => MapEntry(k.toString(), v));
    final rawTime = raw['postTime'] ?? raw['timestamp'];
    final title = (raw['title'] as String?)?.trim() ?? '';
    final body = (raw['body'] as String?)?.trim() ?? '';
    final channelId = (raw['channelId'] as String?) ?? '';

    var amount = (raw['amount'] as num?)?.toDouble();
    var merchant = (raw['merchant'] as String?) ?? '';
    var isExpense = raw['isExpense'] is bool ? raw['isExpense'] as bool : null;

    final fromNotification = !const {'accessibility_screen_reader', 'split_request', 'split_closed'}.contains(channelId);
    if (fromNotification) {
      amount ??= double.tryParse(_amount.firstMatch('$title $body')?.group(1)?.replaceAll(',', '') ?? '');
      final lowerTitle = title.toLowerCase();
      if (lowerTitle.contains('paid you') || lowerTitle.contains('received from') || lowerTitle.contains('credited')) {
        // "PRIYA NAIR paid you ₹1.00" → "PRIYA NAIR"
        isExpense = false;
        final payer = title.split(RegExp(r'\s+paid\s+you\b', caseSensitive: false)).first.trim();
        if (merchant.isEmpty && payer.isNotEmpty && payer != title) merchant = payer;
      } else if (lowerTitle.contains('paid to') ||
          lowerTitle.contains('you paid') ||
          lowerTitle.contains('sent to') ||
          _paidAmountTo.hasMatch(lowerTitle)) {
        // "Paid ₹50 to Swiggy" → "Swiggy"
        isExpense = true;
        final parts = title.split(RegExp(r'\s+(?:to|at)\s+', caseSensitive: false));
        if (merchant.isEmpty && parts.length > 1 && parts.last.trim().isNotEmpty) merchant = parts.last.trim();
      } else if (RegExp(r'\bpaid\s+you\b', caseSensitive: false).hasMatch(body)) {
        // A GPay group's title is only "New payment in 'Trip'"; the body
        // says who paid: "Anil Menon paid you ₹203.33!".
        isExpense = false;
        final payer = body.split(RegExp(r'\s+paid\s+you\b', caseSensitive: false)).first.trim();
        if (merchant.isEmpty && payer.isNotEmpty) merchant = payer;
      }
    }

    return PaymentCapture(
      raw: raw,
      id: raw['id']?.toString(),
      packageName: (raw['packageName'] as String?) ?? '',
      title: title,
      body: body,
      subText: (raw['subText'] as String?)?.trim() ?? '',
      timestamp: rawTime is num ? DateTime.fromMillisecondsSinceEpoch(rawTime.toInt()) : DateTime.now(),
      amount: amount,
      merchant: merchant,
      isExpense: isExpense,
      utrNumber: (raw['utrNumber'] as String?) ?? '',
      latitude: (raw['latitude'] as num?)?.toDouble(),
      longitude: (raw['longitude'] as num?)?.toDouble(),
      live: raw['live'] != false,
      channelId: channelId,
      tag: (raw['tag'] as String?) ?? '',
      category: (raw['category'] as String?) ?? '',
      splitParticipants: _shares(raw['splitParticipants']),
      rawText: (raw['rawText'] as String?) ?? '',
    );
  }

  static List<SplitShare> _shares(dynamic value) {
    try {
      final list = value is String && value.isNotEmpty ? jsonDecode(value) : value;
      if (list is List) return [for (final e in list) if (e is Map) SplitShare.fromMap(e)];
    } catch (_) {}
    return const [];
  }

  @override
  String toString() => 'PaymentCapture(${source.name}, $title, amount: $amount, merchant: $merchant)';
}

/// A screen with an amount on it, as the reader saw it (see
/// `GooglePayPaymentCapturer.setScreenLogEnabled`).
class ScreenDump {
  final DateTime time;
  final String packageName;

  /// What the reader made of it: "payment", "chat", "split", "not recognised"…
  final String result;

  /// The screen's texts joined with " | ".
  final String texts;

  const ScreenDump({required this.time, required this.packageName, required this.result, required this.texts});

  factory ScreenDump.fromMap(Map<dynamic, dynamic> map) => ScreenDump(
        time: DateTime.fromMillisecondsSinceEpoch((map['time'] as num?)?.toInt() ?? 0),
        packageName: map['packageName']?.toString() ?? '',
        result: map['result']?.toString() ?? '',
        texts: map['texts']?.toString() ?? '',
      );

  Map<String, dynamic> toMap() => {
        'time': time.millisecondsSinceEpoch,
        'packageName': packageName,
        'result': result,
        'texts': texts,
      };
}
