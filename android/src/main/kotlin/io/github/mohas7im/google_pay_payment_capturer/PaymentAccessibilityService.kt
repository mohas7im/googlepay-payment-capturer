package io.github.mohas7im.google_pay_payment_capturer

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.regex.Pattern

/**
 * Android Accessibility Service that intercepts:
 *  1. Outgoing UPI payment confirmation screens from Google Pay, PhonePe, Paytm, BHIM
 *  2. Google Pay Group Split creation/confirmation screens
 *
 * Design principles:
 *  - Multiple extraction strategies with fallback chains — never silently drop data
 *  - Tree-based (structural) participant extraction for splits, not just flat text
 *  - Flat-text fallback chain so one strategy failing doesn't lose the capture
 *  - Aggressive but deduplicated — captures on meaningful change, not every event
 */
class PaymentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PaymentAccessService"

        @Volatile
        var isRunning: Boolean = false
            private set

        // ── Debouncing ────────────────────────────────────────────────────────
        private var lastSplitSignature: String = ""
        private var lastSplitTimestamp: Long = 0L
        private const val SPLIT_DEBOUNCE_MS   =  5_000L  // 5s — allow captures during editing

        // ── Captured payments (no double entries) ─────────────────────────────
        // Every payment sent to the app is remembered for a week. Seeing the
        // same one again — the tick page re-read, a quick look at history —
        // is recognised by its UPI reference, or by app + amount + a payment
        // time within this window (screens show minutes, not seconds).
        private const val SAME_PAYMENT_WINDOW_MS = 90_000L
        private const val STATE_PREFS = "payment_reader_state"
        private const val KEY_RECENT = "recent_captures"
        private const val KEY_RECENT_SPLITS = "recent_splits"
        private const val RECENT_KEEP_MS = 7 * 24 * 60 * 60 * 1000L
        private const val MAX_RECENT = 300

        // ── Regex ─────────────────────────────────────────────────────────────
        private val AMOUNT_PATTERN = Pattern.compile(
            """(?:₹|INR\.?|Rs\.?)\s*([\d,]+(?:\.\d{1,2})?)""",
            Pattern.CASE_INSENSITIVE
        )
        /** Words only a completed payment's screen shows. */
        private val SUCCESS_MARKERS = listOf(
            "paid successfully", "payment successful", "payment done", "money sent",
            "transfer successful", "bill paid", "recharge successful", "payment complete",
            "transaction complete", "transaction successful", "payment sent", "sent successfully",
        )

        /** Payment wording that offers can use too; needs no offer text beside it. */
        private val PAID_TO_MARKERS = listOf("paid to", "sent to", "transferred to", "you paid", "payment to")

        /** GPay's tick page can put the amount inside: "Paid ₹370 to" / "Sharma Mobiles". */
        private val PAID_AMOUNT_TO = Regex(
            """\b(?:paid|sent)\s*(?:₹|rs\.?|inr\.?)\s*[\d,]+(?:\.\d{1,2})?\s+to\b""",
            RegexOption.IGNORE_CASE
        )

        /**
         * CREDIT wording on a receipt — money came in. "Payment to you" shares
         * the "payment to" marker that also flags a payment screen, so a
         * received-money receipt still reaches [handlePaymentSuccess]; the
         * direction check below then classifies it correctly instead of
         * recording it as an expense.
         */
        private val RECEIVED_MARKERS = listOf(
            "payment to you", "paid you", "received from", "money received",
            "received payment", "you received", "credited to you",
        )
        /** "Received from Priya Nair" / "Priya Nair paid you ₹200" — the payer. */
        private val RECEIVED_FROM = Regex("""received from\s+(.+)""", RegexOption.IGNORE_CASE)
        private val PAID_YOU_BY = Regex("""^(.+?)\s+paid\s+you\b""", RegexOption.IGNORE_CASE)

        /**
         * Google Pay / UPI merchant category labels, lowercased → the canonical
         * label passed to Flutter. GPay shows one as a subtitle or pill under a
         * verified merchant's name / VPA on the payment and receipt screens.
         */
        private val GPAY_CATEGORY_LABELS: Map<String, String> = mapOf(
            "food & beverage" to "Food & Beverage",
            "food and beverage" to "Food & Beverage",
            "food & drink" to "Food & Beverage",
            "food & drinks" to "Food & Beverage",
            "restaurants" to "Food & Beverage",
            "restaurant" to "Food & Beverage",
            "dining" to "Food & Beverage",
            "groceries" to "Groceries",
            "grocery" to "Groceries",
            "supermarket" to "Groceries",
            "retail & shopping" to "Retail & Shopping",
            "retail and shopping" to "Retail & Shopping",
            "shopping" to "Retail & Shopping",
            "retail" to "Retail & Shopping",
            "fuel" to "Fuel",
            "petrol" to "Fuel",
            "fuel & gas" to "Fuel",
            "travel" to "Travel",
            "transport" to "Travel",
            "transportation" to "Travel",
            "utilities" to "Utilities",
            "bills & utilities" to "Utilities",
            "healthcare" to "Healthcare",
            "health" to "Healthcare",
            "pharmacy" to "Healthcare",
            "medical" to "Healthcare",
            "entertainment" to "Entertainment",
            "education" to "Education",
            "electronics" to "Electronics",
            "mobile & recharge" to "Mobile & Recharge",
            "recharge" to "Mobile & Recharge",
            "insurance" to "Insurance",
        )

        /**
         * How long a merchant's category read on the pre-PIN "Paying …" screen
         * is kept so it can attach to the receipt once the PIN is entered.
         */
        private const val CATEGORY_CACHE_MS = 60_000L

        /** A word next to a merchant that is never a category label. */
        private val NON_CATEGORY = setOf(
            "paid", "sent", "paid to", "sent to", "payment", "completed", "success",
            "successful", "done", "share", "view details", "see details", "details",
            "bank", "upi", "upi id", "reference", "txn id", "transaction id",
            "google pay", "phonepe", "paytm", "verified", "secured", "powered by",
            "to", "from", "help", "home", "split",
        )

        /**
         * GPay's page while a payment goes through: "Paying ₹1 securely to"
         * then the payee ("PRIYA NAIR") as its own text, or on one line.
         */
        private val PAYING_TO = Regex(
            """^paying\s*(?:₹|rs\.?|inr\.?)\s*([\d,]+(?:\.\d{1,2})?)\s+(?:securely\s+)?to\b\s*(.*)$""",
            RegexOption.IGNORE_CASE
        )

        /** A payment that didn't go through; its "Paying" page is forgotten. */
        private val FAILED_MARKERS = listOf(
            "payment failed", "transaction failed", "payment declined", "payment unsuccessful",
            "payment cancelled", "payment canceled", "couldn't complete", "could not complete",
        )

        /**
         * How long after its "Paying" page and PIN a payment waits for its
         * success page (or chat bubble) before it's saved as not confirmed.
         * People close GPay or lock the phone as soon as the tick shows.
         */
        private const val UNCONFIRMED_AFTER_MS = 30_000L

        private val OFFER_SCREEN = Regex(
            """\b(get ₹|get up to|win up to|up ?to ₹|cashback on|flat ₹|use code|apply code|promo ?code|coupon|voucher|valid (till|until)|t&c|offer ends|limited period)|\d+\s?% ?(off|cashback)""",
            RegexOption.IGNORE_CASE
        )

        private val PLAIN_AMOUNT_PATTERN = Pattern.compile("""^([\d,]+(?:\.\d{1,2})?)$""")

        // After paying a contact GPay opens your chat with them, one bubble
        // per payment: "Payment to Priya\n₹1\nPaid • 9:56 am" (today) or
        // "…\nPaid • 1 Oct" (older). Each bubble carries its own time.
        private val CHAT_PAYMENT = Regex(
            """^(?:payment to|paid to|sent to)\s+(.+?)\n\s*(?:₹|rs\.?|inr\.?)\s*([\d,]+(?:\.\d{1,2})?)\n\s*(?:paid|sent|completed)\s*•\s*(.+)$""",
            RegexOption.IGNORE_CASE
        )
        private val BUBBLE_TIME = Regex("""^(\d{1,2}):(\d{2})\s*([ap])\.?\s*m$""", RegexOption.IGNORE_CASE)

        /**
         * One payee under two readings: "Priya" (chat bubble) and "Priya Nair"
         * (details page) — the same words, one a start of the other.
         */
        private fun sameName(a: String, b: String): Boolean {
            fun words(s: String) = s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
            val x = words(a)
            val y = words(b)
            if (x.isEmpty() || y.isEmpty()) return false
            val (short, long) = if (x.size <= y.size) x to y else y to x
            return long.subList(0, short.size) == short
        }

        /** The split member picker's button; the split is made when it's tapped. */
        private const val SEND_REQUEST = "send request"
        /** How long a split read off the member picker waits to be sent. */
        private const val PENDING_SPLIT_MS = 60_000L

        /** GPay reads references out digit by digit: "1 2 3 4 5 6 7 8 9 0 1 2". */
        private val SPACED_DIGITS = Regex("""(?<=\d) (?=\d)""")

        // A GPay group page: "Chat / Tab 1 of 2", "Expenses / Tab 2 of 2".
        private val GROUP_CHAT_TAB = Regex("""\bchat\s+tab 1 of 2\b""")
        private val GROUP_EXPENSES_TAB = Regex("""\bexpenses\s+tab 2 of 2\b""")

        // One split's details page: "Total: ₹164", then a row per person,
        // "Ravi Kumar / Unpaid / ₹54.66" … "You / Sent this request / ₹54.67".
        private val SPLIT_TOTAL = Regex("""^total:?\s*₹\s*([\d,]+(?:\.\d{1,2})?)$""", RegexOption.IGNORE_CASE)
        private val SPLIT_ROW_STATUS = Regex(
            """^(unpaid|paid|sent this request|declined|pending|cancelled|settled|marked as paid|paid (by|via|in|on) .+)$""",
            RegexOption.IGNORE_CASE
        )
        private const val REQUESTER_STATUS = "sent this request"
        private val SHARE_AMOUNT = Regex("""^₹\s*([\d,]+(?:\.\d{1,2})?)$""")
        private val REQUESTED_FOR = Regex("""^requested for\s+['‘’"](.+?)['‘’"]$""", RegexOption.IGNORE_CASE)

        // A closed split: its page says "Total: ₹61.96 / Closed", and its
        // bubble in the group chat stays, greyed out, as one text:
        // "Split request\n₹61.96\nClosed" or "Requested for 'dinner'\n₹106.50\nClosed".
        private val CLOSED_STATUS = Regex("""^(closed|cancell?ed)$""", RegexOption.IGNORE_CASE)
        private val SPLIT_BUBBLE_TITLE = Regex("""^(?:split request|requested for\s+['‘’"](.*?)['‘’"])$""", RegexOption.IGNORE_CASE)

        // The chat's time above a run of bubbles: "21 Sept, 11:18 pm",
        // "Saturday, 9:21 am", "Yesterday, 8:02 pm", or a time alone.
        private val CHAT_SEPARATOR = Regex(
            """^(?:(?:(\d{1,2})\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?(?:\s+(\d{4}))?|(today|yesterday|(?:mon|tues|wednes|thurs|fri|satur|sun)day)),?\s+)?(\d{1,2}):(\d{2})\s*([ap])\.?\s*m$""",
            RegexOption.IGNORE_CASE)

        /** A split's page read open this long before it reads "Closed" was closed just now. */
        private const val CLOSE_WATCH_MS = 10 * 60 * 1000L

        /** Closed bubbles already reported since the reader started; the app keeps one of each anyway. */
        private val reportedClosedSplits = mutableSetOf<String>()
        private val UTR_PATTERN = Pattern.compile(
            """(?:upi\s*(?:ref(?:erence)?|txn|transaction)?\s*(?:id|no)?|rrn|transaction\s*id)[:\s]*(\d{10,12})""",
            Pattern.CASE_INSENSITIVE
        )

        // Words that cannot be participant names
        private val NAME_BLOCKLIST = setOf(
            "split", "new split", "split bill", "split expense", "split amount",
            "done", "create", "send", "request", "cancel", "back", "close",
            "add", "remove", "confirm", "continue", "pay", "next", "okay", "ok",
            "members", "people", "contacts", "amount", "total", "share", "equal",
            "custom", "unequal", "percentage", "adjust", "set", "help", "note",
            "description", "group", "bill", "expense", "divide", "per person",
            "google", "gpay", "phonepe", "paytm", "upi", "inr",
            "payment", "transaction", "transfer", "bank", "account",
            "edit", "view", "details", "receipt", "reference",
            "paid successfully", "success", "completed", "paid",
            "chat", "expenses", "show menu", "settle up", "owed by you", "you owe",
            "today", "yesterday",
        )

        /** How GPay names the phone's owner in a split: "You", "You (me)", "Me". */
        private val SELF_NAME = Regex("""^(you|me|myself)\b.*""")

        // GPay reads a member as "Ravi Kumar, is selected".
        private val SELECTED_SUFFIX = Regex(""",?\s+is (not )?selected$""", RegexOption.IGNORE_CASE)

        /** Screen labels that sit next to amounts but aren't people. */
        private val NON_NAME_PATTERNS = listOf(
            // Times and dates: "10:33 pm", "Friday, 10:33 pm", "26 Sept 2026"
            Regex("""\d{1,2}:\d{2}"""),
            Regex("""^(mon|tues|wednes|thurs|fri|satur|sun)day\b""", RegexOption.IGNORE_CASE),
            Regex("""\b\d{1,2}\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)""", RegexOption.IGNORE_CASE),
            // Expense rows, section headers and tab labels
            Regex("""^requested for\b""", RegexOption.IGNORE_CASE),
            Regex("""^enter\b""", RegexOption.IGNORE_CASE), // "Enter amount to split"
            Regex("""^section title\b""", RegexOption.IGNORE_CASE),
            Regex("""\btab \d+ of \d+""", RegexOption.IGNORE_CASE),
            Regex("""\b(people|person) owe""", RegexOption.IGNORE_CASE),
            Regex("""\bunpaid expenses?\b""", RegexOption.IGNORE_CASE),
        )

        /**
         * Re-reads after a payment app's screen changes. GPay's QR "tick page"
         * plays an animation first and adds "Paid to …" / the amount 1–2 s
         * later, often without another accessibility event (Compose), and
         * people press back soon after, so the screen is read again on a timer.
         */
        // Ends inside SPLIT_DEBOUNCE_MS so a re-read can't record a split twice.
        private val RESCAN_DELAYS_MS = longArrayOf(500, 1_000, 1_800, 2_800, 4_000)

        /** How long to wait for the merchant / UTR to render before sending a capture anyway. */
        private const val PENDING_FLUSH_MS = 1_500L

        // ── Screen reader log (debug) ─────────────────────────────────────────
        // Off unless switched on from the Auto-Capture Test page. It holds the
        // text of payment screens (names, UPI IDs) and keeps every entry until
        // it's cleared in the app; switching it off only stops recording.
        // One JSON object per line in a file, so recording a screen appends a
        // line instead of rewriting the whole log.
        private const val DEBUG_PREFS = "payment_reader_debug"
        private const val KEY_DUMPS = "screen_dumps" // where older builds kept the log
        private const val KEY_LOG_ENABLED = "enabled"
        private const val DUMP_FILE = "screen_reader_log.jsonl"
        private const val MAX_DUMP_TEXTS = 60
        private val dumpLock = Any()

        fun isScreenLogEnabled(context: Context): Boolean =
            context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOG_ENABLED, false)

        /** Starts or stops recording; what's recorded stays until it's cleared. */
        fun setScreenLogEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_LOG_ENABLED, enabled).apply()
        }

        private fun dumpFile(context: Context) = File(context.filesDir, DUMP_FILE)

        private fun appendScreenDump(context: Context, entry: JSONObject) = synchronized(dumpLock) {
            moveOldDumpsToFile(context)
            dumpFile(context).appendText(entry.toString() + "\n")
        }

        /** Older builds kept the log in SharedPreferences; keep those entries. */
        private fun moveOldDumpsToFile(context: Context) {
            val prefs = context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_DUMPS, null) ?: return
            try {
                val arr = JSONArray(raw)
                val lines = (0 until arr.length()).joinToString("") { arr.getJSONObject(it).toString() + "\n" }
                if (lines.isNotEmpty()) dumpFile(context).appendText(lines)
            } catch (e: Exception) {
                Log.e(TAG, "Could not move the old screen log", e)
            }
            prefs.edit().remove(KEY_DUMPS).apply()
        }

        // Raw screen text kept with each capture (see rawScreenText).
        private const val MAX_RAW_TEXTS = 150
        private const val MAX_RAW_CHARS = 8_000

        private val PIN_SCREEN_MARKERS = listOf(
            "enter your pin", "enter upi pin", "enter your upi pin", "upi pin", "enter pin", "enter mpin",
        )

        private val MANDATE_MARKERS = listOf(
            "automatic payments", "autopay on", "autopay approved", "autopay cancelled", "autopay paused",
            "autopay limit", "cancelling your autopay", "as presented",
        )

        /** Screens with an amount on them that the reader saw, newest first. */
        fun getScreenDumps(context: Context): List<Map<String, Any?>> = synchronized(dumpLock) {
            moveOldDumpsToFile(context)
            val file = dumpFile(context)
            if (!file.exists()) return emptyList()
            file.readLines()
                .mapNotNull { line -> try { JSONObject(line) } catch (_: Exception) { null } }
                .asReversed()
                .map { o ->
                    mapOf(
                        "time" to o.optLong("time"),
                        "packageName" to o.optString("pkg"),
                        "result" to o.optString("result"),
                        "texts" to o.optString("texts"),
                    )
                }
        }

        /** The only way the log is deleted. */
        fun clearScreenDumps(context: Context) = synchronized(dumpLock) {
            dumpFile(context).delete()
            context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).edit().remove(KEY_DUMPS).apply()
        }

        /** How old a location fix or an on-screen payment time can be to count as "now". */
        const val LIVE_WINDOW_MS = 10 * 60 * 1000L

        /** How far back a payment's details page opened later is still caught up. */
        private const val CATCH_UP_MS = 2 * 24 * 60 * 60 * 1000L

        /** Calendar.SUNDAY first, matching Calendar.DAY_OF_WEEK order. */
        private val WEEKDAYS = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private val SCREEN_TIME = Regex("""\b(\d{1,2}):(\d{2})\s*([ap])\.?\s*m\b""", RegexOption.IGNORE_CASE)
        private val SCREEN_OTHER_DAY = Regex(
            """\b(yesterday|(mon|tues|wednes|thurs|fri|satur|sun)day)\b""", RegexOption.IGNORE_CASE)
        private val SCREEN_DATE = Regex(
            """\b(\d{1,2})\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?,?\s+(\d{4})\b""",
            RegexOption.IGNORE_CASE)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val rescans = mutableListOf<Runnable>()

    /** Best reading of a payment still waiting for its merchant / UTR to render. */
    private var pendingPayment: Map<String, Any?>? = null
    private var pendingRecord: CaptureRecord? = null
    private var pendingKey = ""
    private var pendingScore = -1
    private val flushPending = Runnable { dispatchPendingPayment() }

    /**
     * A payment seen on GPay's "Paying ₹X securely to" page. Once its PIN is
     * entered ([pinSeen]) it's saved as not confirmed unless its success
     * page, chat bubble or a failure is read within [UNCONFIRMED_AFTER_MS].
     */
    private data class PayingIntent(
        val pkg: String,
        val amount: Double,
        val payee: String,
        val texts: List<String>,
        var pinSeen: Boolean = false,
        var lastSeen: Long = System.currentTimeMillis(),
    )
    private var payingIntent: PayingIntent? = null
    private val flushPaying = Runnable { dispatchUnconfirmedPayment() }

    /**
     * Merchant (lowercased) → (category label, expiry). A category read on the
     * pre-PIN "Paying …" screen is held here for [CATEGORY_CACHE_MS] so it can
     * attach to the receipt once the PIN is entered and the success page renders.
     */
    private val categoryCache = mutableMapOf<String, Pair<String, Long>>()

    private fun cacheCategory(merchant: String, label: String) {
        if (merchant.isBlank()) return
        categoryCache[merchant.lowercase().trim()] = label to (System.currentTimeMillis() + CATEGORY_CACHE_MS)
    }

    private fun cachedCategory(merchant: String): String? {
        val key = merchant.lowercase().trim()
        val entry = categoryCache[key] ?: return null
        if (System.currentTimeMillis() > entry.second) {
            categoryCache.remove(key)
            return null
        }
        return entry.first
    }

    /** CREDIT when the screen says money came in, else DEBIT. */
    private fun classifyDirection(lower: String): String =
        if (RECEIVED_MARKERS.any { lower.contains(it) }) "CREDIT" else "DEBIT"

    /**
     * The merchant category label GPay shows under the verified merchant name /
     * VPA, when present. Looks first at the few nodes right after the merchant
     * (a subtitle or pill sits there), then anywhere on screen for a known
     * label, then — only directly under the merchant header — accepts a short
     * descriptor that isn't a VPA, amount or known non-category word.
     */
    private fun findCategoryLabel(texts: List<String>, merchant: String): String? {
        val idx = if (merchant.isNotEmpty()) {
            texts.indexOfFirst {
                val t = it.trim()
                t.equals(merchant, ignoreCase = true) || t.startsWith(merchant, ignoreCase = true)
            }
        } else -1
        val window = if (idx >= 0) texts.subList(idx + 1, minOf(idx + 5, texts.size)) else emptyList()

        // 1. Known category label just below the merchant header.
        for (t in window) GPAY_CATEGORY_LABELS[t.trim().lowercase()]?.let { return it }
        // 2. Known category label anywhere on the screen.
        for (t in texts) GPAY_CATEGORY_LABELS[t.trim().lowercase()]?.let { return it }
        // 3. A short descriptor directly under the merchant header.
        for (t in window) {
            val c = t.trim()
            if (isLikelyCategoryDescriptor(c)) return c
        }
        return null
    }

    private fun isLikelyCategoryDescriptor(text: String): Boolean {
        val t = text.trim()
        if (t.length < 3 || t.length > 28) return false
        if (t.contains('\n')) return false
        if (t.contains("₹") || t.contains('@')) return false            // amount / VPA
        if (!t[0].isLetter()) return false
        if (t.matches(Regex("""[\d.,\s]+"""))) return false
        val lower = t.lowercase()
        if (NON_CATEGORY.any { lower == it }) return false
        if (AMOUNT_PATTERN.matcher(t).find()) return false
        if (UTR_PATTERN.matcher(t).find()) return false
        // A category reads as a word or two of letters, maybe joined by "&".
        return t.matches(Regex("""[\p{L}][\p{L} &/]{1,26}[\p{L}]""")) && t.count { it == ' ' } <= 2
    }

    private var lastDumpHash = 0

    // The split page last read open, so its turning to "Closed" is told
    // apart from an old closed split opened later.
    private var lastOpenSplitKey = ""
    private var lastOpenSplitAt = 0L

    /**
     * The member picker's latest reading. Every tick / untick changes it, so
     * it's sent only once the split is made: "Send request" tapped, or the
     * next screen shows the split's total.
     */
    private data class PendingSplit(
        val pkg: String,
        val texts: List<String>,
        val total: Double,
        val shares: List<SplitShare>,
        val readAt: Long,
    )
    private var pendingSplit: PendingSplit? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        ReaderRestore.markWanted(applicationContext)
        Log.i(TAG, "PaymentAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventType = event.eventType

        // A window was added or removed (success sheet, dialog). No package on it.
        if (eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            paymentWindowPackage()?.let { scheduleRescans(it, restart = true) }
            return
        }

        val pkgName = event.packageName?.toString() ?: return
        if (!isPaymentApp(pkgName)) return
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val label = (event.text.joinToString(" ") + " " + (event.contentDescription ?: "")).lowercase()
            if (label.contains(SEND_REQUEST)) sendPendingSplit()
            return
        }
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        try {
            val rootNode = resolveRootNode(event, pkgName)
            if (rootNode == null) {
                Log.d(TAG, "No root node available for $pkgName (event=$eventType)")
            } else {
                inspectScreen(pkgName, rootNode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting screen for $pkgName", e)
        }
        // A new screen restarts the timed re-reads. Content changes only start
        // them when none are queued, so a busy screen can't postpone them forever.
        scheduleRescans(pkgName, restart = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    private fun scheduleRescans(pkgName: String, restart: Boolean) {
        if (!restart && rescans.isNotEmpty()) return
        rescans.forEach { handler.removeCallbacks(it) }
        rescans.clear()
        for ((i, delay) in RESCAN_DELAYS_MS.withIndex()) {
            val isLast = i == RESCAN_DELAYS_MS.lastIndex
            val r = Runnable {
                rescanPackage(pkgName)
                if (isLast) rescans.clear()
            }
            rescans.add(r)
            handler.postDelayed(r, delay)
        }
    }

    /** Reads every window the payment app has on screen, freshly fetched. */
    private fun rescanPackage(pkgName: String) {
        try {
            val roots = mutableListOf<AccessibilityNodeInfo>()
            try {
                for (w in windows) {
                    val root = w.root ?: continue
                    if (root.packageName?.toString().equals(pkgName, ignoreCase = true)) roots.add(root)
                }
            } catch (_: Exception) {}
            if (roots.isEmpty()) {
                val active = try { rootInActiveWindow } catch (_: Exception) { null }
                if (active != null && active.packageName?.toString().equals(pkgName, ignoreCase = true)) {
                    roots.add(active)
                }
            }
            roots.forEach { inspectScreen(pkgName, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Rescan failed for $pkgName", e)
        }
    }

    private fun paymentWindowPackage(): String? = try {
        windows.firstNotNullOfOrNull { w ->
            w.root?.packageName?.toString()?.takeIf { isPaymentApp(it) }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * rootInActiveWindow is frequently null (or belongs to a system overlay /
     * keyboard) right when GPay swaps to its success screen. Fall back to the
     * event's own source tree and then to any visible window owned by the
     * payment app so we never drop the capture.
     */
    private fun resolveRootNode(event: AccessibilityEvent, pkgName: String): AccessibilityNodeInfo? {
        val active = try { rootInActiveWindow } catch (_: Exception) { null }
        if (active != null && active.packageName?.toString().equals(pkgName, ignoreCase = true)) {
            return active
        }

        // Walk up from the event source to its root
        try {
            var node = event.source
            var guard = 0
            while (node != null && node.parent != null && guard++ < 64) {
                node = node.parent
            }
            if (node != null && node.packageName?.toString().equals(pkgName, ignoreCase = true)) {
                return node
            }
        } catch (_: Exception) {}

        // Scan all windows for one owned by the payment app
        try {
            for (w in windows) {
                val root = w.root ?: continue
                if (root.packageName?.toString().equals(pkgName, ignoreCase = true)) return root
            }
        } catch (_: Exception) {}

        // Nothing of the payment app's own. The active window is then the
        // notification shade or another app (it was read as a GPay split).
        return null
    }

    override fun onInterrupt() {}

    /** Switched off in Settings (a force-stop never gets here). */
    override fun onUnbind(intent: Intent?): Boolean {
        ReaderRestore.markUnbound(applicationContext)
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        dispatchPendingPayment()
        handler.removeCallbacksAndMessages(null)
        isRunning = false
        super.onDestroy()
    }

    private fun isPaymentApp(pkg: String): Boolean {
        val l = pkg.lowercase()
        return l == GPayNotificationListener.GPAY_PACKAGE_NAME ||
            l == "com.google.android.apps.walletnfcrel" ||
            l.contains("paisa") ||
            l == "com.phonepe.app" ||
            l == "net.one97.paytm" ||
            l == "in.org.npci.upiapp" ||
            l == "com.dreamplug.androidapp"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Top-level dispatcher
    // ─────────────────────────────────────────────────────────────────────────
    private fun inspectScreen(pkgName: String, rootNode: AccessibilityNodeInfo) {
        val rootPkg = rootNode.packageName?.toString()
        if (rootPkg != null && !rootPkg.equals(pkgName, ignoreCase = true)) return

        val texts = mutableListOf<String>()
        collectAllTexts(rootNode, texts)
        if (texts.isEmpty()) return

        val combined   = texts.joinToString(" ")
        val lower      = combined.lowercase()

        // The UPI PIN pad exposes the digits typed so far as plain text.
        // Never read, log or store anything from it.
        PIN_SCREEN_MARKERS.firstOrNull { lower.contains(it) }?.let {
            logSkipped(pkgName, "PIN screen", it, texts)
            payingIntent?.takeIf { p -> p.pkg == pkgName }?.let { p ->
                p.pinSeen = true
                touchPaying(p)
            }
            return
        }

        // Autopay / mandate screens list limits ("Up to ₹15,000") and say
        // "1 payment completed"; none of it is a payment made now.
        MANDATE_MARKERS.firstOrNull { lower.contains(it) }?.let { logSkipped(pkgName, "autopay screen", it, texts); return }

        if (payingIntent != null && FAILED_MARKERS.any { lower.contains(it) }) {
            Log.d(TAG, "Payment failed on screen; its Paying page is dropped")
            clearPaying()
        }
        findPaying(texts)?.let { (amount, payee) -> notePaying(pkgName, amount, payee, texts) }

        pendingSplit?.let { p ->
            val onPicker = texts.any { it.trim().equals(SEND_REQUEST, ignoreCase = true) }
            when {
                SystemClock.elapsedRealtime() - p.readAt > PENDING_SPLIT_MS -> pendingSplit = null
                // Left the picker for a screen with the split on it (its
                // bubble, its page): it was sent.
                !onPicker && (lower.contains("split") || lower.contains("request")) &&
                    extractAllAmounts(combined).any { kotlin.math.abs(it - p.total) < 0.01 } -> sendPendingSplit()
            }
        }

        // ── A chat with a contact: one bubble per payment ────────────────────
        val chatPayments = findChatPayments(texts)
        if (chatPayments.isNotEmpty()) {
            recordScreenDump(pkgName, texts, "chat")
            handleChatPayments(pkgName, texts, chatPayments)
            return
        }

        // ── One split's details: "Total: ₹164", then each person's share ─────
        parseSplitDetails(texts)?.let { details ->
            val key = splitKey(pkgName, details.total, details.shares)
            val now = SystemClock.elapsedRealtime()
            if (details.closed) {
                // Not a new split. Closed while its page was open: tell the app.
                recordScreenDump(pkgName, texts, "split details (closed)")
                if (key == lastOpenSplitKey && now - lastOpenSplitAt < CLOSE_WATCH_MS) {
                    lastOpenSplitKey = ""
                    dispatchClosedSplitPage(pkgName, key, details)
                }
                return
            }
            lastOpenSplitKey = key
            lastOpenSplitAt = now
            recordScreenDump(pkgName, texts, "split details")
            dispatchSplit(pkgName, texts, details.total, details.shares, details.description)
            return
        }

        // A group's Chat and Expenses tabs list old split requests and
        // balances, not a split being made. Reading them paired the amount
        // of one bubble with the name and amount of another. Only its
        // closed splits are taken from it.
        if (GROUP_CHAT_TAB.containsMatchIn(lower) && GROUP_EXPENSES_TAB.containsMatchIn(lower)) {
            recordScreenDump(pkgName, texts, "group page (not captured)")
            findClosedBubbles(texts).forEach { dispatchClosedBubble(pkgName, it) }
            return
        }

        // ── Split screen detection (check FIRST — also has ₹) ────────────────
        if (isSplitScreen(lower)) {
            recordScreenDump(pkgName, texts, "split")
            handleSplitScreen(pkgName, rootNode, texts, combined, lower)
            return  // Don't also run payment check on split screen
        }

        // ── Payment success screen ─────────────────────────────────────────
        if (isPaymentSuccessScreen(lower)) {
            recordScreenDump(pkgName, texts, "payment")
            handlePaymentSuccess(pkgName, texts, combined, lower)
            return
        }

        // Diagnostic: shown in the app's Screen reader log (and logcat) so a
        // screen that isn't being recognised can have its texts inspected.
        recordScreenDump(pkgName, texts, "not recognised")
    }

    private var lastSkipped = ""

    /**
     * Says in logcat why a screen was left unread: which [marker] matched, in
     * which text, digits masked so a PIN typed so far never shows. A real
     * payment screen dropped by a marker that only looks like one shows here.
     */
    private fun logSkipped(pkgName: String, why: String, marker: String, texts: List<String>) {
        val where = texts.firstOrNull { it.lowercase().contains(marker) }
            ?.replace(Regex("""\d"""), "#")?.replace('\n', ' ')?.take(80).orEmpty()
        val line = "Skipped $why ($pkgName): '$marker' in \"$where\" of ${texts.size} texts"
        if (line == lastSkipped) return
        lastSkipped = line
        Log.d(TAG, line)
    }

    /**
     * Keeps the texts of screens that show an amount for the Screen reader log
     * on the Auto-Capture Test page. An unchanged screen is stored once.
     */
    private fun recordScreenDump(pkgName: String, texts: List<String>, result: String) {
        val combined = texts.joinToString(" ")
        if (!combined.contains("₹") && !AMOUNT_PATTERN.matcher(combined).find()) return
        val hash = (pkgName + result + combined).hashCode()
        if (hash == lastDumpHash) return
        lastDumpHash = hash
        // The screen's text stays out of logcat, which other tools can read.
        Log.d(TAG, "Screen [$result] ($pkgName): ${texts.size} texts")
        if (!isScreenLogEnabled(applicationContext)) return
        val joined = texts.take(MAX_DUMP_TEXTS).joinToString(" | ")
        try {
            appendScreenDump(applicationContext, JSONObject().apply {
                put("time", System.currentTimeMillis())
                put("pkg", pkgName)
                put("result", result)
                put("texts", joined)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Could not store screen dump", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DETECTION GUARDS
    // ─────────────────────────────────────────────────────────────────────────

    private fun isSplitScreen(lower: String): Boolean {
        // Must have a split-specific marker
        val splitKeywords = listOf(
            "new split", "split bill", "split expense", "split with",
            "create split", "send requests", "request money from",
            "split amount", "divide bill", "split equally",
            "split unequally", "members in this split",
            "who's paying", "how much does", "add people",
            "split request", "expense split",
        )
        val hasSplit = splitKeywords.any { lower.contains(it) }

        // Lighter check: just "split" + ₹ + at least one more signal
        val lightSplit = lower.contains("split") &&
            lower.contains("₹") &&
            (lower.contains("member") || lower.contains("people") ||
             lower.contains("contact") || lower.contains("request") ||
             lower.contains("per person") || lower.contains("each"))

        return hasSplit || lightSplit
    }

    private fun isPaymentSuccessScreen(lower: String): Boolean {
        // GPay sometimes renders "Rs" / "INR" instead of the ₹ glyph, so accept
        // any currency-prefixed amount, not only the ₹ symbol.
        val hasAmount = lower.contains("₹") || AMOUNT_PATTERN.matcher(lower).find()
        if (!hasAmount) return false

        val saysSuccess = SUCCESS_MARKERS.any { lower.contains(it) }
        val hasUtr = UTR_PATTERN.matcher(lower).find()
        val saysPaidTo = PAID_TO_MARKERS.any { lower.contains(it) } || PAID_AMOUNT_TO.containsMatchIn(lower)
        if (!saysSuccess && !hasUtr && !saysPaidTo) return false

        // Offer banners mention payments and amounts too ("Get ₹50 back on
        // your next payment to…"). Without a success message or a UPI
        // reference, such a screen is an ad, not a receipt. Rewards words
        // ("you won a scratch card") aren't here: real tick pages show them.
        if (!saysSuccess && !hasUtr && OFFER_SCREEN.containsMatchIn(lower)) return false
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SPLIT SCREEN HANDLER
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleSplitScreen(
        pkgName: String,
        rootNode: AccessibilityNodeInfo,
        texts: List<String>,
        combined: String,
        lower: String,
    ) {
        // ── Step 1: Extract total amount (3 strategies) ───────────────────────

        // Strategy A: amount near "total" label
        var totalAmount = extractLabeledAmount(texts, listOf("total", "split amount", "total amount", "bill amount"))

        // Strategy B: first ₹ amount in combined text
        if (totalAmount == null) {
            val m = AMOUNT_PATTERN.matcher(combined)
            if (m.find()) totalAmount = m.group(1)?.replace(",", "")?.toDoubleOrNull()
        }

        // Strategy C: largest ₹ amount found (total is usually the biggest)
        if (totalAmount == null) {
            val allAmounts = extractAllAmounts(combined)
            totalAmount = allAmounts.maxOrNull()
        }

        if (totalAmount == null || totalAmount <= 0.0) {
            Log.d(TAG, "Split screen: no amount found, skipping")
            return
        }

        // ── Step 2: Extract participants (3 strategies, merge results) ────────

        // Strategy A: Tree-based — look at node siblings / parent-child pairs
        val treeParticipants = extractParticipantsFromTree(rootNode)

        // Strategy B: Flat text — name node followed immediately by ₹ node
        val flatParticipants = extractParticipantsFromFlatText(texts)

        // Strategy C: Inline — "Name ₹200" or "Name - ₹200" in single node
        val inlineParticipants = extractInlineParticipants(texts)

        // Merge: prefer tree > flat > inline, deduplicate by name
        val found = mergeParticipants(treeParticipants, flatParticipants, inlineParticipants)

        // Only the friends ticked in GPay's member picker. Contacts that are
        // listed but not selected ("Rahul is not selected", an unticked box)
        // are dropped; selected ones with no share shown yet are added.
        val selected = linkedMapOf<String, String>() // lowercase name → name
        val unselected = mutableSetOf<String>()
        collectSelection(rootNode, selected, unselected, depth = 0)
        val merged = found
            .filter { p -> p["name"].toString().lowercase().trim().let { it !in unselected || it in selected } }
            .toMutableList()
        for ((key, name) in selected) {
            if (merged.none { it["name"].toString().lowercase().trim() == key }) {
                merged.add(mapOf("name" to name, "amount" to 0.0))
            }
        }

        Log.d(TAG, "Split participants found — tree=${treeParticipants.size}, " +
                   "flat=${flatParticipants.size}, inline=${inlineParticipants.size}, " +
                   "selected=${selected.size}, unselected=${unselected.size}, kept=${merged.size}")

        // ── Step 3: Fill missing share amounts ────────────────────────────────
        // If we found names but amounts are 0 (equal split), compute per-person share
        val participants = if (merged.isNotEmpty()) {
            val hasAmounts = merged.any { (it["amount"] as? Double ?: 0.0) > 0.0 }
            if (!hasAmounts && merged.size > 1) {
                val perPerson = totalAmount / merged.size
                merged.map { mapOf("name" to it["name"]!!, "amount" to perPerson) }
            } else merged
        } else emptyList()

        // No real people on screen: this is the group chat / an expense row,
        // not a split. Capturing it produced a flood of "splits" whose
        // "participants" were dates, tabs and section headers.
        if (participants.isEmpty()) {
            Log.d(TAG, "Split screen: no participants found, skipping")
            return
        }

        // ── Step 4: Debounce ──────────────────────────────────────────────────
        val participantHash = participants.joinToString("|") { "${it["name"]}:${it["amount"]}" }
        val signature = "${pkgName}_split_${totalAmount}_$participantHash"
        val now = SystemClock.elapsedRealtime()
        if (signature == lastSplitSignature && (now - lastSplitTimestamp) < SPLIT_DEBOUNCE_MS) return
        lastSplitSignature = signature
        lastSplitTimestamp = now

        // Who paid, flagged on that person ("You" counts: it's how GPay lists
        // the phone's owner). Not found → no flag, and the app assumes you.
        val payer = findSplitPayer(texts)?.lowercase()
        val shares = participants.map { p ->
            val name = p["name"].toString()
            val key = name.lowercase().trim()
            SplitShare(
                name = name,
                amount = (p["amount"] as? Number)?.toDouble() ?: 0.0,
                paid = payer != null && (key == payer || (payer == "you" && SELF_NAME.matches(key))),
            )
        }
        // Still being edited on the member picker: keep only the latest reading.
        if (texts.any { it.trim().equals(SEND_REQUEST, ignoreCase = true) }) {
            pendingSplit = PendingSplit(pkgName, texts, totalAmount, shares, SystemClock.elapsedRealtime())
            return
        }
        dispatchSplit(pkgName, texts, totalAmount, shares, description = null)
    }

    private fun sendPendingSplit() {
        val p = pendingSplit ?: return
        pendingSplit = null
        dispatchSplit(p.pkg, p.texts, p.total, p.shares, description = null)
    }

    /** One person's share of a split. [status] is GPay's word for it ("Unpaid"), when shown. */
    private data class SplitShare(
        val name: String,
        val amount: Double,
        val paid: Boolean,
        val status: String? = null,
    )

    private data class SplitDetails(
        val total: Double,
        val shares: List<SplitShare>,
        val description: String?,
        val closed: Boolean,
    )

    /**
     * A split's own page, opened from its bubble in a group:
     *   Total: ₹164 / ₹54.67 paid / ₹109.33 left · 1 of 3 paid
     *   Ravi Kumar / Unpaid / ₹54.66
     *   You / Sent this request / ₹54.67
     * Each person's lines may be one text or several. The one who sent the
     * request paid the bill. A closed split says "Closed" under its total.
     * Null when the screen isn't one.
     */
    private fun parseSplitDetails(texts: List<String>): SplitDetails? {
        val lines = texts.flatMap { it.lines() }.map { it.trim() }.filter { it.isNotEmpty() }
        val totalAt = lines.indexOfFirst { SPLIT_TOTAL.matches(it) }
        if (totalAt < 0) return null
        val total = SPLIT_TOTAL.find(lines[totalAt])
            ?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            ?: return null
        if (total <= 0.0) return null
        val closed = lines.getOrNull(totalAt + 1)?.let { CLOSED_STATUS.matches(it) } == true

        val shares = mutableListOf<SplitShare>()
        var i = 0
        while (i + 2 < lines.size) {
            val status = lines[i + 1]
            val amount = SHARE_AMOUNT.find(lines[i + 2])?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            val name = if (SELF_NAME.matches(lines[i].lowercase())) lines[i] else cleanParticipantName(lines[i])
            if (name != null && amount != null && SPLIT_ROW_STATUS.matches(status)) {
                shares.add(SplitShare(name, amount, paid = status.equals(REQUESTER_STATUS, ignoreCase = true), status = status))
                i += 3
            } else {
                i++
            }
        }
        if (shares.isEmpty()) return null

        val description = lines.firstNotNullOfOrNull { REQUESTED_FOR.find(it)?.groupValues?.get(1) }
        return SplitDetails(total, shares.distinctBy { it.name.lowercase() }, description, closed)
    }

    /** What tells one split from another: the app, the total and the people in it. */
    private fun splitKey(pkgName: String, total: Double, shares: List<SplitShare>): String =
        "${pkgName}_${total}_" + shares.map { it.name.lowercase().trim() }.sorted().joinToString(",")

    /** Sends a split to the app, once: the same total and people again is the same split. */
    private fun dispatchSplit(
        pkgName: String,
        texts: List<String>,
        total: Double,
        shares: List<SplitShare>,
        description: String?,
    ) {
        // Seen again later (the group reopened, its details page opened
        // after making it) — not a new split.
        if (!rememberSplit(splitKey(pkgName, total, shares))) {
            Log.d(TAG, "Split already captured, skipping")
            return
        }

        val participantsJson = sharesJson(shares)

        // Only a payment made just now happened where the phone is now; an old
        // payment opened later would get today's location instead of its own.
        val locationPair = if (isLivePaymentScreen(texts)) GPayNotificationListener.getLatestLocation(applicationContext) else null
        val title = "Split ₹${String.format("%.2f", total)} with ${shares.size} people"
        val payer = shares.firstOrNull { it.paid }?.name
        val body = listOfNotNull(
            description?.let { "For '$it'" },
            "Participants: " + shares.joinToString(", ") { it.name },
            payer?.let { "Paid by $it" },
        ).joinToString(" • ")

        val data = mutableMapOf<String, Any?>(
            "packageName"       to pkgName,
            "title"             to title,
            "body"              to body,
            "subText"           to "GPay Group Split — Screen Reader",
            "timestamp"         to System.currentTimeMillis(),
            "postTime"          to System.currentTimeMillis(),
            "amount"            to total,
            "merchant"          to (description ?: ""),
            "isExpense"         to false,
            "isTransaction"     to false,
            "channelId"         to "split_request",
            "tag"               to "split",
            "category"          to "group_split",
            "utrNumber"         to "",
            "latitude"          to locationPair?.first,
            "longitude"         to locationPair?.second,
            "splitParticipants" to participantsJson,
            "rawText"           to rawScreenText(texts),
        )

        GPayNotificationListener.bufferNotification(applicationContext, data)
        GPayNotificationListener.postDirectMessage(data)
        Log.i(TAG, "Captured split: $title | participants=$participantsJson")
    }

    private fun sharesJson(shares: List<SplitShare>): String = JSONArray().apply {
        shares.forEach { s ->
            put(JSONObject().apply {
                put("name", s.name)
                put("amount", s.amount)
                if (s.paid) put("paid", true)
                s.status?.let { put("status", it) }
            })
        }
    }.toString()

    /** A closed split's bubble in a group chat, with the time of the separator above it. */
    private data class ClosedBubble(val total: Double, val description: String?, val madeAt: Long)

    /**
     * The closed splits on a group's chat page. A bubble whose time
     * separator is scrolled off is left out until it shows.
     */
    private fun findClosedBubbles(texts: List<String>): List<ClosedBubble> {
        val found = mutableListOf<ClosedBubble>()
        var separatorAt: Long? = null
        for (text in texts) {
            val time = chatSeparatorTime(text)
            if (time != null) {
                separatorAt = time
                continue
            }
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size !in 3..4 || !CLOSED_STATUS.matches(lines.last())) continue
            val title = SPLIT_BUBBLE_TITLE.matchEntire(lines.first()) ?: continue
            val total = lines.firstNotNullOfOrNull { SHARE_AMOUNT.find(it) }
                ?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: continue
            val madeAt = separatorAt ?: continue
            found.add(ClosedBubble(total, title.groupValues[1].trim().ifEmpty { null }, madeAt))
        }
        return found
    }

    /**
     * When a chat separator says: "21 Sept, 11:18 pm" (this year, or last
     * year when that's still ahead), "Saturday, 9:21 am" (the last one before
     * today), "Yesterday, …", or a time alone for today. Null when it isn't one.
     */
    private fun chatSeparatorTime(text: String): Long? {
        val m = CHAT_SEPARATOR.matchEntire(text.replace(' ', ' ').replace(' ', ' ').trim()) ?: return null
        val g = m.groupValues
        val now = Calendar.getInstance()
        val at = now.clone() as Calendar
        val word = g[4].lowercase()
        when {
            g[1].isNotEmpty() -> {
                val year = g[3].toIntOrNull() ?: now.get(Calendar.YEAR)
                at.set(year, MONTHS.indexOf(g[2].lowercase().take(3)), g[1].toInt())
            }
            word == "yesterday" -> at.add(Calendar.DAY_OF_MONTH, -1)
            word.isNotEmpty() && word != "today" -> {
                val target = Calendar.SUNDAY + WEEKDAYS.indexOfFirst { word.startsWith(it) }
                do { at.add(Calendar.DAY_OF_MONTH, -1) } while (at.get(Calendar.DAY_OF_WEEK) != target)
            }
        }
        val hour12 = g[5].toInt() % 12
        at.set(Calendar.HOUR_OF_DAY, if (g[7].lowercase() == "p") hour12 + 12 else hour12)
        at.set(Calendar.MINUTE, g[6].toInt())
        at.set(Calendar.SECOND, 0)
        at.set(Calendar.MILLISECOND, 0)
        if (g[1].isNotEmpty() && g[3].isEmpty() && at.timeInMillis - now.timeInMillis > 24 * 60 * 60 * 1000L) {
            at.add(Calendar.YEAR, -1)
        }
        return at.timeInMillis
    }

    /**
     * Tells the app about a closed bubble in a group chat. Its time is when
     * the split was made; the app matches it to its captures by amount and
     * that time.
     */
    private fun dispatchClosedBubble(pkgName: String, bubble: ClosedBubble) {
        val id = "split_closed_${pkgName}_${bubble.total}_${bubble.madeAt}_${bubble.description.orEmpty().lowercase()}"
        if (!synchronized(reportedClosedSplits) { reportedClosedSplits.add(id) }) return
        dispatchClosed(pkgName, id, bubble.total, bubble.description, shares = emptyList(), at = bubble.madeAt)
    }

    /**
     * Tells the app a split was closed just now, on its own page. That split
     * is forgotten, so making the same split again (close it, make it anew)
     * is captured instead of taken for the closed one.
     */
    private fun dispatchClosedSplitPage(pkgName: String, key: String, details: SplitDetails) {
        forgetSplit(key)
        val now = System.currentTimeMillis()
        dispatchClosed(pkgName, "split_closed_${pkgName}_${details.total}_$now", details.total,
            details.description, details.shares, at = now)
    }

    private fun dispatchClosed(
        pkgName: String,
        id: String,
        total: Double,
        description: String?,
        shares: List<SplitShare>,
        at: Long,
    ) {
        val title = "Split ₹${String.format("%.2f", total)} closed"
        val body = listOfNotNull(
            description?.let { "For '$it'" },
            if (shares.isNotEmpty()) "Participants: " + shares.joinToString(", ") { it.name } else null,
            "Closed in GPay",
        ).joinToString(" • ")
        val data = mutableMapOf<String, Any?>(
            "id"                to id,
            "packageName"       to pkgName,
            "title"             to title,
            "body"              to body,
            "subText"           to "GPay Group Split — Screen Reader",
            "timestamp"         to at,
            "postTime"          to at,
            "amount"            to total,
            "merchant"          to (description ?: ""),
            "isExpense"         to false,
            "isTransaction"     to false,
            "channelId"         to "split_closed",
            "tag"               to "split",
            "category"          to "group_split_closed",
            "utrNumber"         to "",
            "splitParticipants" to if (shares.isNotEmpty()) sharesJson(shares) else null,
        )
        GPayNotificationListener.bufferNotification(applicationContext, data)
        GPayNotificationListener.postDirectMessage(data)
        Log.i(TAG, "Split closed: $title")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PARTICIPANT EXTRACTION — Strategy A: Tree-based (most accurate)
    // Walks the view hierarchy looking for container nodes whose direct children
    // are exactly a name + an amount. Matches GPay's RecyclerView list items.
    // ─────────────────────────────────────────────────────────────────────────
    private fun extractParticipantsFromTree(root: AccessibilityNodeInfo): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        scanNodeForParticipant(root, result, depth = 0)
        return result.distinctBy { it["name"].toString().lowercase() }
    }

    private fun scanNodeForParticipant(
        node: AccessibilityNodeInfo,
        result: MutableList<Map<String, Any>>,
        depth: Int,
    ) {
        if (depth > 12) return  // Don't go infinitely deep

        // Collect immediate children's texts
        val childTexts = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val t = getNodeText(child)
            if (t.isNotEmpty()) childTexts.add(Pair(t, child))
        }

        // Pattern: exactly 2 meaningful children — one name, one amount
        if (childTexts.size == 2) {
            val (t1, _) = childTexts[0]
            val (t2, _) = childTexts[1]
            tryExtractParticipant(t1, t2)?.let { result.add(it) }
                ?: tryExtractParticipant(t2, t1)?.let { result.add(it) }
        }

        // Pattern: 3 children where one is an avatar/icon (empty text), one name, one amount
        if (childTexts.size == 3) {
            val texts3 = childTexts.map { it.first }
            for (i in 0 until 3) {
                for (j in 0 until 3) {
                    if (i == j) continue
                    val p = tryExtractParticipant(texts3[i], texts3[j])
                    if (p != null) { result.add(p); break }
                }
            }
        }

        // Recurse into all children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            scanNodeForParticipant(child, result, depth + 1)
        }
    }

    private fun tryExtractParticipant(nameCandidate: String, amountCandidate: String): Map<String, Any>? {
        val name = cleanParticipantName(nameCandidate) ?: return null
        val m = AMOUNT_PATTERN.matcher(amountCandidate)
        if (!m.find()) return null
        val amount = m.group(1)?.replace(",", "")?.toDoubleOrNull() ?: return null
        if (amount <= 0.0) return null
        return mapOf("name" to name, "amount" to amount)
    }

    /**
     * The payer on a split screen: "Paid by Rahul" (one text, or "Paid by"
     * then the name), or "You paid" / "Paid by you" → "you". Null if not shown.
     */
    private fun findSplitPayer(texts: List<String>): String? {
        for ((i, raw) in texts.withIndex()) {
            val t = raw.lineSequence().firstOrNull()?.trim() ?: continue
            val lower = t.lowercase()
            if (lower == "you paid" || lower.startsWith("you paid ")) return "you"
            val name = when {
                lower.startsWith("paid by ") -> t.substring("paid by ".length).trim()
                lower == "paid by" -> texts.getOrNull(i + 1)?.lineSequence()?.firstOrNull()?.trim()
                else -> null
            } ?: continue
            if (SELF_NAME.matches(name.lowercase())) return "you"
            cleanParticipantName(name)?.let { return it }
        }
        return null
    }

    /**
     * Reads which people are ticked in a member picker. A row says so in
     * its text ("Rahul is selected" / "is not selected"), in its state
     * description ("Selected"), or with a checkbox next to the name.
     */
    private fun collectSelection(
        node: AccessibilityNodeInfo?,
        selected: MutableMap<String, String>,
        unselected: MutableSet<String>,
        depth: Int,
    ) {
        if (node == null || depth > 25) return
        val firstLine = getNodeText(node).lineSequence().firstOrNull()?.trim() ?: ""
        val textState = SELECTED_SUFFIX.find(firstLine)
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            node.stateDescription?.toString()?.trim()?.lowercase() else null

        val isSelected: Boolean? = when {
            textState != null -> textState.groupValues[1].isEmpty()
            state == "selected" || state == "checked" -> true
            state == "not selected" || state == "not checked" || state == "unchecked" -> false
            node.isCheckable -> node.isChecked
            else -> null
        }
        if (isSelected != null) {
            val name = cleanParticipantName(firstLine) ?: nameNear(node)
            if (name != null) {
                val key = name.lowercase()
                if (isSelected) selected.putIfAbsent(key, name) else unselected.add(key)
            }
        }
        for (i in 0 until node.childCount) collectSelection(node.getChild(i), selected, unselected, depth + 1)
    }

    /** The person's name in a checkbox's row: in the node, its children, or its siblings. */
    private fun nameNear(node: AccessibilityNodeInfo): String? {
        fun firstName(n: AccessibilityNodeInfo?, depth: Int): String? {
            if (n == null || depth > 3) return null
            cleanParticipantName(getNodeText(n))?.let { return it }
            for (i in 0 until n.childCount) firstName(n.getChild(i), depth + 1)?.let { return it }
            return null
        }
        return firstName(node, 0) ?: firstName(node.parent, 0)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PARTICIPANT EXTRACTION — Strategy B: Flat text pairs
    // Looks for [name_text, ₹amount_text] consecutive elements in the flat list
    // ─────────────────────────────────────────────────────────────────────────
    private fun extractParticipantsFromFlatText(texts: List<String>): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        var i = 0
        while (i < texts.size) {
            val cur = texts[i].trim()
            // Look 1 and 2 positions ahead for amount (some UIs insert role text between)
            for (lookahead in 1..2) {
                if (i + lookahead >= texts.size) break
                val next = texts[i + lookahead].trim()
                val p = tryExtractParticipant(cur, next)
                if (p != null) {
                    result.add(p)
                    i += lookahead + 1
                    break
                }
                if (lookahead == 2) i++
            }
            if (i < texts.size && texts[i].trim() == cur) i++ // safety advance
        }
        return result.distinctBy { it["name"].toString().lowercase() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PARTICIPANT EXTRACTION — Strategy C: Inline single-node format
    // "Rahul ₹200" or "Rahul — ₹200" or "Rahul: ₹200" in one text node
    // ─────────────────────────────────────────────────────────────────────────
    private fun extractInlineParticipants(texts: List<String>): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        for (t in texts) {
            if (!t.contains("₹")) continue
            val m = AMOUNT_PATTERN.matcher(t)
            if (!m.find()) continue
            val amount = m.group(1)?.replace(",", "")?.toDoubleOrNull() ?: continue
            if (amount <= 0.0) continue
            val nameRaw = t.substring(0, m.start())
                .replace(Regex("""[-–—:,|]+"""), "")
                .trim()
            cleanParticipantName(nameRaw)?.let { result.add(mapOf("name" to it, "amount" to amount)) }
        }
        return result.distinctBy { it["name"].toString().lowercase() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Merge participants from all strategies, deduplicated by name
    // ─────────────────────────────────────────────────────────────────────────
    private fun mergeParticipants(vararg sources: List<Map<String, Any>>): List<Map<String, Any>> {
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<Map<String, Any>>()
        for (source in sources) {
            for (p in source) {
                val key = p["name"].toString().lowercase().trim()
                if (seen.add(key)) merged.add(p)
            }
        }
        return merged
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PAYMENT SUCCESS HANDLER
    // ─────────────────────────────────────────────────────────────────────────
    private fun handlePaymentSuccess(
        pkgName: String,
        texts: List<String>,
        combined: String,
        lower: String,
    ) {
        // ── Amount: multiple strategies ───────────────────────────────────────
        // Strategy A: ₹amount near success text
        var amount: Double? = null
        val successContextTexts = texts.filter { t ->
            val tl = t.lowercase()
            tl.contains("paid") || tl.contains("₹") || tl.contains("success") || tl.contains("sent")
        }
        for (t in successContextTexts) {
            val m = AMOUNT_PATTERN.matcher(t)
            if (m.find()) { amount = m.group(1)?.replace(",", "")?.toDoubleOrNull(); if (amount != null) break }
        }

        // Strategy B: any ₹amount in full combined text
        if (amount == null) {
            val m = AMOUNT_PATTERN.matcher(combined)
            if (m.find()) amount = m.group(1)?.replace(",", "")?.toDoubleOrNull()
        }

        // Strategy C: plain numeric node (GPay shows isolated "450")
        if (amount == null) {
            for (t in texts) {
                val m = PLAIN_AMOUNT_PATTERN.matcher(t.trim())
                if (m.matches()) {
                    val v = t.trim().replace(",", "").toDoubleOrNull()
                    if (v != null && v > 0.0 && v < 1_00_00_000.0) { amount = v; break }
                }
            }
        }

        if (amount == null || amount <= 0.0) return

        // ── Merchant: multiple strategies ─────────────────────────────────────
        var merchant = ""

        // Strategy A: "Paid to X" / "Sent to X" / "To X" / "Paid ₹370 to X" in same node
        for (t in texts) {
            val tl = t.lowercase()
            val prefixes = listOf("paid to ", "sent to ", "transferred to ", "to ")
            for (pref in prefixes) {
                if (tl.startsWith(pref)) {
                    val candidate = t.substring(pref.length).trim()
                    if (isValidMerchant(candidate)) { merchant = candidate; break }
                }
            }
            if (merchant.isEmpty()) {
                PAID_AMOUNT_TO.find(t.trim())?.takeIf { it.range.first == 0 }?.let { m ->
                    val candidate = t.trim().substring(m.range.last + 1).trim()
                    if (isValidMerchant(candidate)) merchant = candidate
                }
            }
            if (merchant.isNotEmpty()) break
        }

        // Strategy B: node immediately AFTER a "Paid to" / "To" / "Paid ₹370 to" label node
        if (merchant.isEmpty()) {
            for (i in texts.indices) {
                val tl = texts[i].trim().lowercase()
                val isLabel = tl == "paid to" || tl == "sent to" || tl == "to" || tl == "transferred to" ||
                    PAID_AMOUNT_TO.matchEntire(tl) != null
                if (isLabel && i + 1 < texts.size) {
                    val candidate = texts[i + 1].trim()
                    if (isValidMerchant(candidate)) { merchant = candidate; break }
                }
            }
        }

        // A/B read the payee from its "Paid to" label; C/D are guesses.
        val merchantIsLabelled = merchant.isNotEmpty()

        // Strategy C: find a UPI ID in texts and extract the name part before @
        if (merchant.isEmpty()) {
            val upiRegex = Regex("""([a-zA-Z0-9.\-_]+)@[a-zA-Z]+""")
            for (t in texts) {
                val match = upiRegex.find(t)
                if (match != null) {
                    val name = match.groupValues[1]
                    if (name.length in 2..30 && !name.all { it.isDigit() }) {
                        merchant = name
                        break
                    }
                }
            }
        }

        // Strategy D: first text node that isn't a keyword, number, or ₹
        if (merchant.isEmpty()) {
            val skipLower = setOf(
                "done", "share", "bank", "upi", "success", "successful", "completed",
                "paid", "payment", "transaction", "amount", "receipt", "view", "details",
                "help", "okay", "ok", "close", "back", "home", "next",
            )
            for (t in texts) {
                val cleaned = t.trim()
                if (cleaned.length in 2..50 &&
                    !cleaned.contains("₹") &&
                    !cleaned.matches(Regex("""[\d.,]+""")) &&
                    !skipLower.any { cleaned.lowercase() == it } &&
                    !isPaymentSuccessScreen(cleaned.lowercase())) {
                    merchant = cleaned; break
                }
            }
        }

        // ── Direction: paying (DEBIT) vs receiving (CREDIT) ────────────────────
        val direction = classifyDirection(lower)
        if (direction == "CREDIT") {
            // The debit "to X" strategies read "you" as the counterparty on a
            // "Payment to you" receipt; replace it with the payer:
            // "Received from Priya Nair" / "Priya Nair paid you ₹200".
            val payer = texts.firstNotNullOfOrNull { t ->
                val line = t.trim()
                RECEIVED_FROM.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { isValidMerchant(it) }
                    ?: PAID_YOU_BY.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { isValidMerchant(it) }
            }
            merchant = payer ?: ""
            if (merchant.equals("you", ignoreCase = true)) merchant = ""
        }

        val merchantFound = merchant.isNotEmpty()
        if (merchant.isEmpty()) {
            merchant = when {
                pkgName.contains("paisa")   -> "Google Pay Merchant"
                pkgName.contains("phonepe") -> "PhonePe Merchant"
                pkgName.contains("paytm")   -> "Paytm Merchant"
                else -> "UPI Payment"
            }
        }

        // ── Merchant category label (part 2): this screen, else the pre-PIN cache
        val gpayCategoryLabel = findCategoryLabel(texts, merchant) ?: cachedCategory(merchant)

        // ── UTR / Reference ID ────────────────────────────────────────────────
        var utrNumber = ""
        // The label and its digits share a text: "UPI transaction ID\n1 2 3 4 …".
        // Digits are joined within each text only, so the next text's can't run on.
        val compactCombined = texts.joinToString(" ") { it.replace(SPACED_DIGITS, "") }
        val utrMatcher = UTR_PATTERN.matcher(compactCombined)
        if (utrMatcher.find()) utrNumber = utrMatcher.group(1) ?: ""
        // The fallback below can pick up a phone number, so only a labelled
        // reference is trusted to tell two payments apart.
        val utrLabelled = utrNumber

        // Fallback: look for 12-digit number in texts that isn't an amount
        if (utrNumber.isEmpty()) {
            for (line in texts.flatMap { it.lines() }) {
                val stripped = line.trim().replace(" ", "")
                if (stripped.matches(Regex("""\d{10,12}"""))) { utrNumber = stripped; break }
            }
        }

        // ── When it happened / seen before? ──────────────────────────────────
        // A time on screen older than a few minutes means history or chat,
        // not a payment just made: the live capture (or the bank SMS) has it.
        val nowMs = System.currentTimeMillis()
        val shownAt = shownPaymentTime(texts)
        val live = shownAt == null || kotlin.math.abs(nowMs - shownAt) <= LIVE_WINDOW_MS
        // An older payment's own details page ("16 Oct 2026, 9:56 am" and
        // its UPI reference) is caught up: the phone may have been locked
        // straight after paying. The reference keeps it from counting twice.
        val fullDateShown = SCREEN_DATE.containsMatchIn(texts.joinToString(" "))
        if (!live && !(utrLabelled.isNotEmpty() && fullDateShown && nowMs - shownAt!! <= CATCH_UP_MS)) {
            Log.d(TAG, "Payment screen shows an old time; not a new payment")
            return
        }
        val paidAt = shownAt ?: nowMs
        // Only the reliable merchant / UTR take part in telling payments
        // apart; the fallbacks can differ between two reads of one screen.
        val record = CaptureRecord(
            pkg = pkgName,
            amount = amount,
            merchant = if (merchantIsLabelled) merchant else "",
            utr = utrLabelled,
            at = paidAt,
            timeShown = shownAt != null,
        )
        if (isAlreadyCaptured(record)) return

        // The tick page offers "Share screenshot" / "See details"; the details
        // page behind it lists the UPI reference.
        val source = when {
            lower.contains("see details") || lower.contains("share screenshot") -> "success page"
            utrLabelled.isNotEmpty() -> "details page"
            else -> "payment screen"
        }
        val score = (if (merchantFound) 2 else 0) + (if (utrNumber.isNotEmpty()) 1 else 0)
        offerPayment(
            record,
            paymentData(pkgName, amount, merchant, utrNumber, paidAt, texts, live, source, direction, gpayCategoryLabel),
            score,
        )
    }

    /** A payment bubble in a chat with a contact; [at] is null when it isn't from today. */
    private data class ChatPayment(val payee: String, val amount: Double, val at: Long?)

    private fun findChatPayments(texts: List<String>): List<ChatPayment> = texts.mapNotNull { t ->
        val m = CHAT_PAYMENT.matchEntire(t.replace(' ', ' ').replace(' ', ' ').trim()) ?: return@mapNotNull null
        val amount = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
        ChatPayment(m.groupValues[1].trim(), amount, bubbleTime(m.groupValues[3].trim()))
    }.distinct()

    /** "9:56 am" is today (a bubble from before midnight, yesterday); "1 Oct" and the like give null. */
    private fun bubbleTime(text: String): Long? {
        val m = BUBBLE_TIME.matchEntire(text) ?: return null
        val at = Calendar.getInstance()
        val hour12 = m.groupValues[1].toInt() % 12
        at.set(Calendar.HOUR_OF_DAY, if (m.groupValues[3].lowercase() == "p") hour12 + 12 else hour12)
        at.set(Calendar.MINUTE, m.groupValues[2].toInt())
        at.set(Calendar.SECOND, 0)
        at.set(Calendar.MILLISECOND, 0)
        if (at.timeInMillis - System.currentTimeMillis() > LIVE_WINDOW_MS) at.add(Calendar.DAY_OF_MONTH, -1)
        return at.timeInMillis
    }

    /**
     * Captures the chat's payments from today. One made just now gets the
     * phone's location; an earlier one (the phone was locked straight after
     * paying, so its screen was never read) is caught up without one. Older
     * bubbles show only a date and are left alone. The bubble names the
     * payee by first name ("Priya"); the chat's header has the full one.
     */
    private fun handleChatPayments(pkgName: String, texts: List<String>, payments: List<ChatPayment>) {
        val now = System.currentTimeMillis()
        for (p in payments) {
            val at = p.at ?: continue
            val payee = texts
                .map { it.lineSequence().first().trim() }
                .filter { sameName(it, p.payee) && it.length > p.payee.length && it.length <= 60 }
                .maxByOrNull { it.length } ?: p.payee
            val record = CaptureRecord(pkg = pkgName, amount = p.amount, merchant = payee, utr = "", at = at, timeShown = true)
            if (isAlreadyCaptured(record)) continue
            val live = kotlin.math.abs(now - at) <= LIVE_WINDOW_MS
            offerPayment(record, paymentData(pkgName, p.amount, payee, "", at, texts, live, source = "chat"), score = 3)
        }
    }

    private fun paymentData(
        pkgName: String,
        amount: Double,
        merchant: String,
        utrNumber: String,
        paidAt: Long,
        texts: List<String>,
        live: Boolean = true,
        source: String,
        direction: String = "DEBIT",
        gpayCategoryLabel: String? = null,
    ): Map<String, Any?> {
        val isCredit = direction == "CREDIT"
        val title = if (isCredit) {
            "Received ₹${String.format("%.2f", amount)} from $merchant"
        } else {
            "Paid ₹${String.format("%.2f", amount)} to $merchant"
        }
        val body = listOfNotNull(
            if (isCredit) "On-screen captured receipt" else "On-screen captured payment",
            if (utrNumber.isNotEmpty()) "UPI Ref: $utrNumber" else null,
            "Completed",
        ).joinToString(" • ")

        return mapOf(
            "packageName"   to pkgName,
            "title"         to title,
            "body"          to body,
            // The app shows which screen it was read from: "… · success page".
            "subText"       to "On-Screen Payment Reader · $source",
            "timestamp"     to paidAt,
            "postTime"      to paidAt,
            "amount"        to amount,
            "merchant"      to merchant,
            "isExpense"     to !isCredit,
            // Explicit paying/receiving classification for Flutter.
            "direction"     to direction,
            // GPay's merchant category label, when shown (else null).
            "gpayCategoryLabel" to gpayCategoryLabel,
            "isTransaction" to true,
            "channelId"     to "accessibility_screen_reader",
            "tag"           to "accessibility",
            "category"      to if (isCredit) "incoming_payment" else "outgoing_payment",
            "utrNumber"     to utrNumber,
            // Filled in with a fresh fix when the capture is sent, if [live].
            "live"          to live,
            "latitude"      to null,
            "longitude"     to null,
            "rawText"       to rawScreenText(texts),
        )
    }

    /**
     * Holds the best reading of a payment for up to [PENDING_FLUSH_MS], so a
     * re-read that has the merchant / UTR replaces one that only had the
     * amount. Sent at once when complete; otherwise the timer sends it, even
     * if the user has already left the screen.
     */
    private fun offerPayment(record: CaptureRecord, data: Map<String, Any?>, score: Int) {
        // Its success page or bubble was read: the "Paying" backup isn't needed.
        payingIntent?.let { p ->
            if (p.pkg.equals(record.pkg, ignoreCase = true) && kotlin.math.abs(p.amount - record.amount) < 0.001) clearPaying()
        }
        val key = "${record.pkg}_${record.amount}"
        if (pendingPayment != null && pendingKey != key) dispatchPendingPayment()
        if (pendingPayment == null || score > pendingScore) {
            if (pendingPayment == null) handler.postDelayed(flushPending, PENDING_FLUSH_MS)
            pendingPayment = data
            pendingRecord = record
            pendingKey = key
            pendingScore = score
        }
        if (pendingScore >= 3) dispatchPendingPayment()
    }

    private fun dispatchPendingPayment() {
        handler.removeCallbacks(flushPending)
        val data = pendingPayment ?: return
        pendingPayment = null
        pendingScore = -1
        pendingRecord?.let { rememberCapture(it) }
        pendingRecord = null

        // A payment made just now is tagged with where the phone is: a fresh
        // fix, up to 10 s. One caught up later happened somewhere else.
        val context = applicationContext
        if (data["live"] == false) {
            GPayNotificationListener.bufferNotification(context, data)
            GPayNotificationListener.postDirectMessage(data)
            CaptureNotifier.notifyPayment(context, data)
            Log.i(TAG, "Caught up payment: ${data["title"]} | merchant=${data["merchant"]}")
            return
        }
        GPayNotificationListener.requestFreshLocation(context) { loc ->
            val tagged = data.toMutableMap()
            tagged["latitude"] = loc?.first
            tagged["longitude"] = loc?.second
            GPayNotificationListener.bufferNotification(context, tagged)
            GPayNotificationListener.postDirectMessage(tagged)
            CaptureNotifier.notifyPayment(context, tagged)
            Log.i(TAG, "Captured payment: ${tagged["title"]} | merchant=${tagged["merchant"]} | " +
                "utr=${tagged["utrNumber"]} | location=${if (loc != null) "yes" else "none"}")
        }
    }

    /** The amount and payee on a "Paying ₹1 securely to" / "PRIYA NAIR" page. */
    private fun findPaying(texts: List<String>): Pair<Double, String>? {
        for ((i, raw) in texts.withIndex()) {
            val m = PAYING_TO.matchEntire(raw.trim().replace('\n', ' ')) ?: continue
            val amount = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            if (amount <= 0.0) continue
            val payee = m.groupValues[2].trim().ifEmpty {
                texts.getOrNull(i + 1)?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            }
            if (isValidMerchant(payee)) return amount to payee
        }
        return null
    }

    private fun notePaying(pkgName: String, amount: Double, payee: String, texts: List<String>) {
        // GPay often shows the merchant's category on this pre-PIN screen but
        // not on the receipt; cache it for 60 s so it attaches to the receipt.
        findCategoryLabel(texts, payee)?.let { cacheCategory(payee, it) }
        val p = payingIntent
        if (p != null && p.pkg == pkgName && kotlin.math.abs(p.amount - amount) < 0.001 && sameName(p.payee, payee)) {
            touchPaying(p)
            return
        }
        Log.d(TAG, "Paying page seen ($pkgName): ₹$amount")
        payingIntent = PayingIntent(pkgName, amount, payee, texts).also { touchPaying(it) }
    }

    /** Still on its way: the not-confirmed save waits from now. */
    private fun touchPaying(p: PayingIntent) {
        p.lastSeen = System.currentTimeMillis()
        handler.removeCallbacks(flushPaying)
        handler.postDelayed(flushPaying, UNCONFIRMED_AFTER_MS)
    }

    private fun clearPaying() {
        payingIntent = null
        handler.removeCallbacks(flushPaying)
    }

    /**
     * Its PIN was entered but no success page was read: GPay was closed or
     * the phone locked during the tick animation. Saved as not confirmed —
     * a payment can still fail after the PIN — for the user to add or skip.
     */
    private fun dispatchUnconfirmedPayment() {
        val p = payingIntent ?: return
        clearPaying()
        if (!p.pinSeen) return // backed out before paying
        val record = CaptureRecord(pkg = p.pkg, amount = p.amount, merchant = p.payee, utr = "", at = p.lastSeen)
        if (isAlreadyCaptured(record)) return
        val data = paymentData(p.pkg, p.amount, p.payee, "", p.lastSeen, p.texts, live = true,
            source = "paying page, not confirmed").toMutableMap()
        data["body"] = "On-screen captured payment • Not confirmed: GPay closed before its success page"
        Log.i(TAG, "No success page after PIN; saving ₹${p.amount} as not confirmed")
        offerPayment(record, data, score = 3)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CAPTURED PAYMENTS — one entry per real payment
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * What identifies a payment. [merchant] and [utr] are empty unless read
     * from their labels; [at] is the time shown on screen ([timeShown]), or
     * the capture time when the screen shows none.
     */
    private data class CaptureRecord(
        val pkg: String,
        val amount: Double,
        val merchant: String,
        val utr: String,
        val at: Long,
        val timeShown: Boolean = false,
    ) {
        /**
         * Last time this payment was seen on screen. Staying on the tick page
         * keeps re-reading it with "now" as its time (no time shown), so the
         * window slides with each sighting instead of expiring after 90 s.
         */
        var lastSeen: Long = at

        fun isSamePaymentAs(o: CaptureRecord): Boolean {
            if (utr.isNotEmpty() && utr == o.utr) return true
            if (!pkg.equals(o.pkg, ignoreCase = true) || kotlin.math.abs(amount - o.amount) > 0.001) return false
            // Two different references, or two different payees: two payments.
            if (utr.isNotEmpty() && o.utr.isNotEmpty()) return false
            val bothNamed = merchant.isNotEmpty() && o.merchant.isNotEmpty()
            if (bothNamed && !sameName(merchant, o.merchant)) return false
            // Both screens show the payment's time (chat bubble, details
            // page): the same payment shows the same minute, while two
            // payments a few minutes apart stay two.
            if (timeShown && o.timeShown) return kotlin.math.abs(at - o.at) <= SAME_PAYMENT_WINDOW_MS
            // One was read as "now": the details page opened a few minutes
            // after the tick page is still that payment, when the payee agrees.
            val window = if (bothNamed) LIVE_WINDOW_MS else SAME_PAYMENT_WINDOW_MS
            return kotlin.math.abs(at - o.at) <= window || kotlin.math.abs(lastSeen - o.at) <= window
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("pkg", pkg); put("amount", amount); put("merchant", merchant); put("utr", utr); put("at", at)
            put("shown", timeShown)
        }
    }

    private val recentCaptures: MutableList<CaptureRecord> by lazy { loadRecentCaptures() }

    private fun loadRecentCaptures(): MutableList<CaptureRecord> {
        val list = mutableListOf<CaptureRecord>()
        try {
            val raw = applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RECENT, null) ?: return list
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(CaptureRecord(
                    pkg = o.optString("pkg"),
                    amount = o.optDouble("amount"),
                    merchant = o.optString("merchant"),
                    utr = o.optString("utr"),
                    at = o.optLong("at"),
                    timeShown = o.optBoolean("shown"),
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read recent captures", e)
        }
        return list
    }

    private fun isAlreadyCaptured(r: CaptureRecord): Boolean {
        val match = recentCaptures.firstOrNull { it.isSamePaymentAs(r) } ?: return false
        match.lastSeen = maxOf(match.lastSeen, r.at)
        return true
    }

    private fun rememberCapture(r: CaptureRecord) {
        val cutoff = System.currentTimeMillis() - RECENT_KEEP_MS
        recentCaptures.removeAll { it.at < cutoff }
        recentCaptures.add(r)
        while (recentCaptures.size > MAX_RECENT) recentCaptures.removeAt(0)
        try {
            val arr = JSONArray().apply { recentCaptures.forEach { put(it.toJson()) } }
            applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_RECENT, arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Could not save recent captures", e)
        }
    }

    /** Records [key]; false when it was already recorded within [RECENT_KEEP_MS]. */
    private fun rememberSplit(key: String): Boolean {
        return try {
            val prefs = applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getString(KEY_RECENT_SPLITS, null)?.let { JSONObject(it) } ?: JSONObject()
            val now = System.currentTimeMillis()
            if (now - seen.optLong(key, 0L) < RECENT_KEEP_MS) return false
            val cutoff = now - RECENT_KEEP_MS
            seen.keys().asSequence().toList().forEach { if (seen.optLong(it) < cutoff) seen.remove(it) }
            seen.put(key, now)
            prefs.edit().putString(KEY_RECENT_SPLITS, seen.toString()).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not check recent splits", e)
            true
        }
    }

    private fun forgetSplit(key: String) {
        try {
            val prefs = applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getString(KEY_RECENT_SPLITS, null)?.let { JSONObject(it) } ?: return
            seen.remove(key)
            prefs.edit().putString(KEY_RECENT_SPLITS, seen.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Could not forget a split", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The screen as the reader read it, one text per line, saved with the
     * capture so the app can show where its amount / payee came from. PIN
     * screens never get this far, and password fields aren't read at all.
     */
    private fun rawScreenText(texts: List<String>): String =
        texts.take(MAX_RAW_TEXTS).joinToString("\n").take(MAX_RAW_CHARS)

    /** Get the display text from a node (text > contentDescription) */
    private fun getNodeText(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        return when {
            text.isNotEmpty() -> text
            desc.isNotEmpty() -> desc
            else -> ""
        }
    }

    /** Recursively collect all non-empty text strings from the view tree */
    private fun collectAllTexts(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return
        // A password / PIN field's text is the secret itself.
        val text = if (node.isPassword) null else node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrBlank()) list.add(text)
        if (!desc.isNullOrBlank() && desc != text) list.add(desc)
        for (i in 0 until node.childCount) collectAllTexts(node.getChild(i), list)
    }

    /** Extract all ₹ amounts from a string */
    private fun extractAllAmounts(text: String): List<Double> {
        val result = mutableListOf<Double>()
        val m = AMOUNT_PATTERN.matcher(text)
        while (m.find()) {
            m.group(1)?.replace(",", "")?.toDoubleOrNull()?.let { result.add(it) }
        }
        return result
    }

    /** Look for an amount that immediately follows a label like "Total" */
    private fun extractLabeledAmount(texts: List<String>, labels: List<String>): Double? {
        for (i in texts.indices) {
            val tl = texts[i].trim().lowercase()
            if (labels.any { tl.contains(it) }) {
                // Check same node for an embedded amount
                val m1 = AMOUNT_PATTERN.matcher(texts[i])
                if (m1.find()) return m1.group(1)?.replace(",", "")?.toDoubleOrNull()
                // Check next node
                if (i + 1 < texts.size) {
                    val m2 = AMOUNT_PATTERN.matcher(texts[i + 1])
                    if (m2.find()) return m2.group(1)?.replace(",", "")?.toDoubleOrNull()
                }
            }
        }
        return null
    }

    /**
     * Heuristic: is this string a person's name?
     * - 2–40 characters
     * - Starts with a letter
     * - Not in blocklist
     * - Not purely numeric
     * - No ₹ symbol
     */
    /**
     * True when the screen shows a payment made just now: its time is within
     * [LIVE_WINDOW_MS] of now. Without a readable time it isn't live.
     */
    private fun isLivePaymentScreen(texts: List<String>): Boolean {
        val shown = shownPaymentTime(texts) ?: return false
        return kotlin.math.abs(System.currentTimeMillis() - shown) <= LIVE_WINDOW_MS
    }

    /**
     * When the payment on screen happened, from what GPay shows:
     * "30 Sept 2026, 10:14 pm", "Yesterday, 9:02 am", "Tuesday 8:15 pm", or
     * a time alone for today. Null when no time is shown; a date alone that
     * isn't today gives noon of that day, so the screen still reads as old.
     */
    private fun shownPaymentTime(texts: List<String>): Long? {
        val screen = texts.joinToString(" ").replace(' ', ' ').replace(' ', ' ')
        val now = Calendar.getInstance()
        val day = now.clone() as Calendar
        var dayShown = false

        val date = SCREEN_DATE.find(screen)
        if (date != null) {
            val month = MONTHS.indexOf(date.groupValues[2].lowercase().take(3))
            day.set(date.groupValues[3].toInt(), month, date.groupValues[1].toInt())
            dayShown = true
        } else {
            SCREEN_OTHER_DAY.find(screen)?.let { m ->
                val word = m.value.lowercase()
                if (word == "yesterday") {
                    day.add(Calendar.DAY_OF_MONTH, -1)
                } else {
                    // The most recent such weekday, today included.
                    val target = Calendar.SUNDAY + WEEKDAYS.indexOfFirst { word.startsWith(it) }
                    while (day.get(Calendar.DAY_OF_WEEK) != target) day.add(Calendar.DAY_OF_MONTH, -1)
                }
                dayShown = true
            }
        }

        val time = SCREEN_TIME.find(screen)
        if (time == null) {
            val today = day.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                day.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
            if (!dayShown || today) return null
            day.set(Calendar.HOUR_OF_DAY, 12)
            day.set(Calendar.MINUTE, 0)
            return day.timeInMillis
        }
        val hour12 = time.groupValues[1].toInt() % 12
        day.set(Calendar.HOUR_OF_DAY, if (time.groupValues[3].lowercase() == "p") hour12 + 12 else hour12)
        day.set(Calendar.MINUTE, time.groupValues[2].toInt())
        day.set(Calendar.SECOND, 0)
        day.set(Calendar.MILLISECOND, 0)
        // "11:58 pm" read just after midnight is yesterday, not tonight.
        if (!dayShown && day.timeInMillis - now.timeInMillis > LIVE_WINDOW_MS) day.add(Calendar.DAY_OF_MONTH, -1)
        return day.timeInMillis
    }

    /**
     * A participant's display name from a node's text: the first line only
     * (GPay adds "\n5 unpaid expenses" under a name) without the
     * accessibility suffix " is selected". Null when it isn't a person.
     */
    private fun cleanParticipantName(text: String): String? {
        val firstLine = text.trim().lineSequence().firstOrNull()?.trim() ?: return null
        val name = firstLine.replace(SELECTED_SUFFIX, "").trim()
        return if (isLikelyPersonName(name)) name else null
    }

    private fun isLikelyPersonName(text: String): Boolean {
        val t = text.trim()
        if (t.contains('\n')) return false
        if (NON_NAME_PATTERNS.any { it.containsMatchIn(t) }) return false
        if (t.length < 2 || t.length > 40) return false
        if (!t[0].isLetter()) return false
        if (t.contains("₹")) return false
        if (t.matches(Regex("""[\d.,\s]+"""))) return false
        val lower = t.lowercase()
        if (NAME_BLOCKLIST.any { lower == it || lower.startsWith("$it ") }) return false
        // Must contain at least 2 letters
        if (t.count { it.isLetter() } < 2) return false
        return true
    }

    private fun isValidMerchant(candidate: String): Boolean {
        return candidate.isNotEmpty() &&
            !candidate.contains("₹") &&
            candidate.length in 2..60 &&
            !candidate.matches(Regex("""[\d.,]+"""))
    }
}
