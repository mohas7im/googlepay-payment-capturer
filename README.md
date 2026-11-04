# googlepay-payment-capturer

A Flutter plugin that captures UPI payments on Android from **Google Pay**, PhonePe, Paytm, BHIM, CRED and banking apps, with no bank SMS needed. It reads the payment apps' notifications and their payment screens. Your app gets every payment, with the amount, payee, UPI reference, time and the place where it was paid.

> Dart package: `google_pay_payment_capturer` · Android only · MIT

## Features

### Payments you make (screen reader)
GPay sends no notification when you pay someone, so an accessibility service reads its screens instead:

- **Success page.** It reads "Paid ₹370 to …" / "Payment successful" with the amount, the payee and the UPI reference (UTR), even when GPay reads the UTR out digit by digit.
- **Re-reads while the page animates.** GPay's tick page fills in the payee 1–2 s late, often without telling Android. The screen is read again for up to 4 s, and the most complete reading is the one sent.
- **Details page, opened later.** An older payment's own page (date, time and UTR, within 2 days) is caught up. This covers the phone being locked straight after paying.
- **Chat bubbles.** After you pay a contact, GPay opens your chat with them. Today's payment bubbles are captured, with the payee's full name from the chat header.
- **Payments not confirmed.** If GPay is closed during the tick animation, a payment seen on the "Paying ₹X securely to" page with its PIN entered is saved as *not confirmed* after 30 s. A failed payment ("Payment failed", "declined", …) is dropped.
- **Where it was paid.** A payment made just now is tagged with a fresh location fix (fused, then GPS, then network, up to 10 s), or a cached fix under 10 minutes old. Payments caught up later get no location, because they happened somewhere else.
- **Merchant fallbacks.** If there's no "Paid to" label, it uses the name in the UPI ID, then the screen's first meaningful text, then "Google Pay Merchant" / "PhonePe Merchant" / "Paytm Merchant".

### GPay group splits
- **Member picker.** It reads the total, the people who are ticked (unticked contacts are left out) and each share. Equal shares are filled in when none are shown. The split is sent when "Send request" is tapped.
- **A split's own page.** It reads "Total: ₹164" and each person's row: name, status ("Unpaid", "Sent this request", …) and amount. The person who sent the request is marked as the payer. The split's description ("Requested for '…'") is kept.
- **Who paid.** It reads "Paid by Ravi", "You paid" or "Paid by you".
- **Closed splits.** A split closed while its page is open is reported, and is forgotten so the same split made again is captured anew. Greyed-out "Closed" bubbles in a group chat are reported with the time from the chat's separator.
- **No false splits.** A group's Chat / Expenses tabs (old requests and balances) aren't taken for a new split. Dates, tabs and section headers aren't taken for people.

### Notifications (money in, bank alerts)
- Google Pay, Google Wallet, PhonePe, Paytm, BHIM, CRED and any UPI app, plus banking apps (`bank`, YONO, iMobile, FedMobile).
- **Money received:** "Rahul paid you ₹200", group payments ("New payment in 'Trip'" / "… paid you ₹203.33"), credits and refunds.
- **Filters out** offers, cashback, scratch cards, loans and ads, unless money actually moved. Money requests that weren't paid are filtered out too.
- Notifications already in the shade when the listener connects are read too.

### Never twice, never lost
- **Duplicates.** One payment seen on its success page, details page and chat bubble is captured once. It's matched by UTR, or by app + amount + payee + time shown, and remembered for a week. Two payments of the same amount a few minutes apart stay two.
- **Splits.** The same total and people within a week is the same split.
- **Offline buffer.** Every capture is stored natively (up to 500) until the app reads it. The buffer is read and cleared in one step, so nothing captured in between is lost.

### Safety
- UPI PIN screens are never read, logged or stored, and password fields aren't read at all.
- Autopay / mandate screens ("Up to ₹15,000", "1 payment completed") and offer banners aren't taken for payments.

### Extras
- **Capture notification.** "₹370 to Sharma Mobiles · tap to pick a category and add". Its link, intent action and icon are set from your manifest.
- **Keeps itself on.** With a one-time adb grant, the reader switches itself back on after an app update or force-stop (`flutter run` does one), but only if it was on before.
- **Screen reader log.** A debug log of the screens the reader saw and what it made of each one (off by default).
- **Raw text.** Every capture keeps the exact texts it was read from, so you can see where the amount and payee came from.

## Install

```yaml
dependencies:
  google_pay_payment_capturer:
    git:
      url: https://github.com/mohas7im/googlepay-payment-capturer.git
      ref: v0.1.0
```

The plugin declares its notification listener, accessibility service and post-update receiver itself. Add the permissions you want to your app's `AndroidManifest.xml`:

```xml
<!-- Tag outgoing payments with where they were made (ask at runtime; the
     payment happens in GPay, so it needs "Allow all the time") -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/>

<!-- The "payment captured" notification -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>

<!-- Optional: switch the reader back on after an app update (see below) -->
<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"
    xmlns:tools="http://schemas.android.com/tools" tools:ignore="ProtectedPermissions"/>
```

Minimum Android 7.0 (API 24). The capture notification needs Android 8.0+.

## Use

```dart
import 'package:google_pay_payment_capturer/google_pay_payment_capturer.dart';

final capturer = GooglePayPaymentCapturer.instance;

// 1. Ask the user to switch both on.
if (!await capturer.hasNotificationAccess()) await capturer.openNotificationAccessSettings();
if (!await capturer.hasAccessibilityAccess()) await capturer.openAccessibilitySettings();

// 2. Live captures.
capturer.captures.listen((c) {
  print('${c.source.name}: ₹${c.amount} ${c.isExpense == false ? 'from' : 'to'} ${c.merchant}'
      ' utr=${c.utrNumber} at=${c.timestamp} loc=${c.latitude},${c.longitude}');
});

// 3. On start / resume: what was captured while the app was closed.
for (final c in await capturer.drainBufferedCaptures()) {
  // also holds what the live stream already delivered — de-duplicate on your side
}
```

The [example app](example/lib/main.dart) does exactly this.

### API

| Method | |
|---|---|
| `captures` / `rawCaptures` | Live captures, typed or as the native payload |
| `drainBufferedCaptures()` / `drainBufferedCaptureMaps()` | Read and clear the offline buffer in one step |
| `getBufferedCaptureMaps()` / `clearBufferedCaptures()` | Peek at / clear the buffer |
| `syncActiveNotificationMaps()` | Read payment notifications still in the shade |
| `hasNotificationAccess()` / `openNotificationAccessSettings()` | Notification access |
| `isListenerConnected()` | The listener is bound right now |
| `hasAccessibilityAccess()` / `openAccessibilitySettings()` | The screen reader |
| `canRestoreReader()` | The adb grant for keeping the reader on is there |
| `getCurrentLocation()` | Last known location under 10 minutes old |
| `isScreenLogEnabled()` / `setScreenLogEnabled()` / `getScreenDumps()` / `clearScreenDumps()` | Screen reader log |

### `PaymentCapture`

| Field | |
|---|---|
| `source` | `notification`, `screen`, `split`, `splitClosed` |
| `amount`, `merchant`, `isExpense` | For notifications, these are worked out from the text ("Paid ₹50 to Swiggy", "Rahul paid you ₹200") |
| `utrNumber` | Only when read from its label |
| `timestamp` | The time shown on screen, or when the notification was posted |
| `latitude`, `longitude`, `hasLocation` | Outgoing payments made just now |
| `live` | `false` for a payment caught up later, which has no location |
| `isUnconfirmed` | PIN entered, but no success page seen |
| `splitParticipants` | `SplitShare(name, amount, paid, status)` |
| `subText` | The screen it was read from: `On-Screen Payment Reader · success page`, `· details page`, `· chat`, `· paying page, not confirmed` |
| `rawText` | Every text the reader read, one per line |
| `raw` | The native payload as sent |

## Capture notification

By default, tapping the notification opens your launcher activity. To change it, add meta-data inside `<application>`:

```xml
<!-- Open with this link (e.g. a deep link to your capture page) -->
<meta-data android:name="google_pay_payment_capturer.capture_link" android:value="myapp://auto-capture"/>
<!-- Intent action; default VIEW (with a link). For home_widget's link routing:
     es.antonborri.home_widget.action.LAUNCH -->
<meta-data android:name="google_pay_payment_capturer.capture_link_action" android:value="android.intent.action.VIEW"/>
<!-- Small icon; default the app icon -->
<meta-data android:name="google_pay_payment_capturer.notification_icon" android:resource="@mipmap/ic_launcher"/>
<!-- Turn it off -->
<meta-data android:name="google_pay_payment_capturer.capture_notifications" android:value="false"/>
```

## Names in Settings

Override these strings in your app's `res/values/strings.xml`:

```xml
<string name="google_pay_payment_capturer_accessibility_label">MyApp Payment Reader</string>
<string name="google_pay_payment_capturer_accessibility_description">Reads payment screens in GPay, PhonePe and Paytm to log your expenses.</string>
<string name="google_pay_payment_capturer_notification_listener_label">MyApp Payment Notifications</string>
```

## Keeping the reader on after updates

Force-stopping an app switches its accessibility service off, and Android won't let an app switch it back on by itself. The exception is this permission, granted once over adb (it survives updates, not an uninstall):

```sh
adb shell pm grant <your.application.id> android.permission.WRITE_SECURE_SETTINGS
```

With the grant, the reader is switched back on after an update or force-stop if it was on before. If the user switches it off in Settings, it stays off.

## Screen reader log

When a payment screen isn't recognised, switch the log on, open that screen, then read what the reader saw:

```dart
await capturer.setScreenLogEnabled(true);
final dumps = await capturer.getScreenDumps(); // newest first: time, packageName, result, texts
await capturer.clearScreenDumps();
```

It records names and UPI IDs and keeps them until cleared, so leave it off normally.

## Notes

- Notification access and the accessibility service are switched on by the user in Android Settings.
- Google Play restricts apps that use the accessibility API, so this suits personal and sideloaded apps.
- The screen reader is tuned to GPay India's screens. Other apps' success pages are read by the same rules.

## License

MIT
