# MMbank — Automated Expense & Asset Tracker

Personal-use, sideloaded Android app that builds a unified multi-currency ledger by
**listening to bank push notifications and SMS simultaneously**, parsing them with a
multi-language regex engine, and deduplicating the two channels against each other.
Also tracks Forex-converted net worth and Binance spot balances via a read-only API key.

> ⚠️ Private tool. Not intended for Google Play — it deliberately uses
> `NotificationListenerService` + `RECEIVE_SMS`, which Play policy restricts.

## Architecture

Clean Architecture in an MVVM shape; background capture is fully detached from the UI.

```
service/   BankNotificationListener (NotificationListenerService)
           SmsReceiver (RECEIVE_SMS broadcast, multipart-safe, goAsync)
                 │  CapturedMessage
                 ▼
domain/    IngestCapturedMessageUseCase
           ├─ TransactionParser  ← CompiledRuleCache (custom rules, in-memory) + BuiltInRules (EN/AR)
           │    TextNormalizer   (Arabic-Indic digits → ASCII, ٫→. , bidi strip)
           │    CurrencyNormalizer ($, ر.س, د.إ … → ISO codes)
           └─ TransactionRepository.recordParsed()  ← sliding-window dedup
                 ▼
data/      Room (accounts, transactions, parsing_rules, asset_balances, exchange_rates)
           Retrofit/OkHttp: Binance (HMAC-signed read-only) + open.er-api.com Forex
                 ▼
ui/        Jetpack Compose + Navigation, manual DI (AppContainer), StateFlow view models
```

- **UI**: 100% Jetpack Compose (Material 3), bottom navigation: Home / Activity /
  Dashboard / Accounts / Settings. Rewards (cashback) lives inside the Dashboard tab;
  parsing rules live inside Settings, which is a grouped menu with focused subpages
  (Capture & permissions, Preferences, Binance, Rules, Backup, Diagnostics).
- **Home**: net worth with hide-balances eye toggle, account balances, collapsible
  Binance card, recent activity.
- **Dashboard tab**: this-month income/spent/net tiles, savings goal with
  months-to-target estimate, 6-month cash-flow columns, spend per person, and
  spend per category.
- **People**: every transaction can be assigned to a person (default person configurable
  in Settings, applied to new captures).
- **Spending categories**: every transaction carries a category (groceries, dining,
  fuel, transport, …), auto-suggested from the merchant at capture time
  (`MerchantCategorizer`) and overridable via a chip — powering the Dashboard's
  by-category breakdown.
- **Search & filters**: the Activity tab has full-text search (merchant, bank,
  amount, category) plus date-range, direction, account and person filters.
- **Manual entry & editing**: the Activity tab's “+” adds a transaction the capture
  pipeline missed (amount, currency, spent/received, merchant, category, account,
  person, date) — stored as a `MANUAL` source row that behaves like any captured one.
  Any transaction (captured or manual) can be edited in place via the ✏️ on its card;
  the same compact card carries tap-to-set account / person / category chips.
- **Balance correction**: when a computed balance drifts from reality (a missed or
  mis-parsed capture), an account's adjust action reconciles it — you type the real
  balance and one auditable “Balance adjustment” entry is posted for the difference.
- **Export**: an AES-256 encrypted `.mmb` backup (full restore) plus a plain,
  unencrypted **CSV** of every transaction for spreadsheets (Settings → Backup).
- **Async**: Kotlin Coroutines + Flow; both capture services hand work to IO dispatchers immediately.
- **Amounts** are `BigDecimal`, persisted as normalized plain-decimal TEXT — exact for fiat and 8-decimal crypto alike.

### Smart, and entirely on the phone (0.54–0.60)

None of these needs an AI model, a network call or a new permission. Each
notification is opt-in (Settings → Preferences, off by default); the in-app
versions always show.

- **Copilot answers offline**: "How much on dining last month?", "biggest
  expense this year?", "compare with last month", "savings rate" — parsed and
  answered from the ledger on the phone, with question chips from your own top
  categories and merchants, and "See transactions" to open Activity filtered
  to exactly those rows. Open-ended questions still go to the configured AI.
- **Unusual-charge radar**: a charge far above what that merchant usually
  costs you ("3.1× your usual at Careem") shows in Smart insights and on the
  transaction, with an optional notification.
- **Capture health**: Home warns when capture is off, the notification
  listener has been disconnected, or nothing has arrived for much longer than
  usual, with Fix and Snooze.
- **Net worth over time**: 12 month-ends rebuilt from the ledger, with a
  sparkline, on the Dashboard tab.
- **Debt payoff planner** (Accounts): every card and loan that owes money,
  Avalanche vs Snowball, when you'd be debt-free and what each costs in
  interest.
- **"This week" digest** on Home: spend vs last week plus what changed: caps
  crossed, unusual charges, payments due, new or repriced subscriptions, and
  capture going quiet. Optionally once a week as a notification. Headline
  numbers compare fairly ("vs this point last month").
- **App PIN and screenshot blocking** (Settings → Security): a PIN just for
  MMbank, which works without a phone screen lock and slows guessing, and
  `FLAG_SECURE` app-wide.

### Battery shape & false-positive guards

The ingest path runs for *every* notification on the device, so it is built to
exit as cheaply as possible:

- **Digit gate**: messages with no digits (most chat/media notifications) are
  dropped before any rule or DB access — an amount always needs a digit
  (Arabic-Indic digits included).
- **Compiled-rule cache**: custom rules are compiled once and kept in memory
  (`CompiledRuleCache`, backed by a Room `Flow`), instead of a DB read + regex
  compile per message. Rule edits still apply instantly.
- **Ongoing notifications** (media playback, navigation, downloads) are skipped
  in the listener — they repost constantly and are never bank alerts.
- **Social-app guard**: notifications from chat/social apps (Telegram, WhatsApp,
  Instagram, TikTok, …) never run against the generic built-in rules, so a
  Telegram channel quoting numbers can't be mis-captured as a transaction. A
  custom rule whose sender filter matches the package still works, so an
  intentional bot/channel feed can be mapped deliberately (`SocialApps`).

There is no background polling: Forex and Binance are fetched only on demand
from the UI. The Settings screen offers a one-tap battery-optimization
exemption — it exists purely so aggressive OEM battery managers don't kill the
notification listener and silently stop capture.

### Sliding-window deduplication

The same purchase usually arrives twice (bank app push + SMS), seconds apart.
`TransactionRepositoryImpl.recordParsed` runs *check + insert inside one Room
transaction*: an existing row with identical **amount + currency + direction** whose
event timestamp is within **±2 minutes** (`ABS(timestamp - :timestamp) <= 120000`,
backed by an index) marks the incoming message as a duplicate and it is dropped.

### Custom Rule Engine (no recompile needed)

Rules screen → “+” lets you map any unknown bank message layout:

- Pattern must contain `(?<amount>...)`; optional `(?<currency>...)` and `(?<merchant>...)`.
- Optional **sender filter** regex (SMS short-code or app package name).
- Direction (DEBIT/CREDIT), fallback currency, priority (custom rules default to 100 and
  outrank built-ins at 0–10).
- Built-in **live tester**: paste a real bank message and see the parsed amount /
  currency / merchant through the exact same code path the services use.
- Invalid regexes are rejected at edit time and silently skipped at runtime — a bad rule
  can never crash capture.

Example rule for a quirky bank: pattern `TXN:(?<amount>\d+(?:\.\d+)?)/(?<currency>[A-Z]{3})`,
sender filter `MYBANK`, direction `DEBIT`.

A rule's **“Assign matches to”** account pins every match to one account, winning over
card-number detection (for messages that carry no account number, e.g. Tabby Card vs
Tabby Cash). Setting it also **backfills** the rule's already-captured, still-unassigned
transactions, so history is fixed too — not just future captures.

### Binance integration (read-only)

- Create an API key on Binance with **trading and withdrawals disabled**.
- Keys are stored in `EncryptedSharedPreferences` (Android Keystore-backed).
- `GET /api/v3/account` is signed per request (HMAC-SHA256 over the query string via an
  OkHttp interceptor); `GET /api/v3/ticker/price` values holdings in USD.
- `GET /sapi/v1/asset/wallet/balance` adds per-wallet totals (Earn, Funding, Trading
  Bots, Margin, …), BTC-valued by Binance and converted via BTCUSDT — so assets parked
  outside Spot still count. Best-effort: keys without that permission keep spot-only sync.
- The app only ever calls read endpoints.

### Forex

Rates come from [open.er-api.com](https://www.exchangerate-api.com/docs/free) (no API key;
supports AED and 160+ base currencies, unlike the ECB feed), fetched with your default
currency as base and cached in Room. Dashboard net worth converts every account balance +
crypto USD total into the default currency; unconvertible balances are flagged with “≈”.

## Building

```bash
./gradlew :app:assembleDebug     # requires Android SDK 35
./gradlew :app:testDebugUnitTest # parser/normalizer unit tests (pure JVM)
adb install app/build/outputs/apk/debug/app-debug.apk
```

## First-run setup (important)

1. Install the APK, open **Settings** tab in the app.
2. Grant **SMS permission** (normal runtime prompt).
3. Enable **Notification access** — on Android 13+ the toggle will be **greyed out**
   ("Restricted setting") because the app is sideloaded:
   1. System Settings → Apps → **MMbank**
   2. Tap the **⋮** menu (top-right) → **Allow restricted settings** → authenticate
   3. Return to Notification access and enable MMbank.
4. Tap **Exempt from battery optimization** so the system never kills the
   notification listener (the app does no background polling, so the exemption
   costs effectively nothing).
5. (Optional) Paste Binance read-only keys and tap *Test & sync*.
6. Set your default currency and refresh Forex rates.

## Repo layout

- `app/src/main/java/com/xemmy/mmbank/domain` — models, parsing engine, use case, repo interfaces
- `app/src/main/java/com/xemmy/mmbank/data` — Room, Retrofit (Binance/Forex), encrypted prefs, repo impls
- `app/src/main/java/com/xemmy/mmbank/service` — notification listener + SMS receiver
- `app/src/main/java/com/xemmy/mmbank/ui` — Compose screens, view models, navigation, theme
- `app/src/test` — JVM unit tests for the multi-language parser
