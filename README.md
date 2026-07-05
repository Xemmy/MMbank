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

- **UI**: 100% Jetpack Compose (Material 3), bottom navigation: Home / Activity / Accounts / Rules / Settings.
- **Async**: Kotlin Coroutines + Flow; both capture services hand work to IO dispatchers immediately.
- **Amounts** are `BigDecimal`, persisted as normalized plain-decimal TEXT — exact for fiat and 8-decimal crypto alike.

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
