## 0.1.0

First release.

- Notification listener for GPay, PhonePe, Paytm, BHIM, CRED and bank apps, with offer / ad filtering.
- Accessibility reader for outgoing payments: success page, details page, chat bubbles, and "not confirmed" saves when GPay closes after the PIN.
- GPay group splits: member picker, split page, closed splits.
- Duplicate detection across screens and re-reads.
- Fresh-location tagging for payments made just now.
- Offline buffer drained atomically from Dart.
- Capture notification, configurable from the manifest.
- Reader restore after app updates (with an adb grant).
- Screen reader debug log.
