## 0.2.0

- Classify each on-screen capture as paying (`DEBIT`) or receiving (`CREDIT`);
  a "Payment to you" / "Received from" / "paid you" receipt is now read as a
  credit (payer as counterparty) instead of being recorded as an expense.
- Read the merchant category label GPay shows under the merchant name / VPA
  (e.g. "Food & Beverage", "Groceries", "Fuel") and send it as
  `gpayCategoryLabel`. A category seen on the pre-PIN "Paying …" screen is
  cached for 60 s so it attaches to the receipt.
- `PaymentCapture` now exposes `direction` and `gpayCategoryLabel`.

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
