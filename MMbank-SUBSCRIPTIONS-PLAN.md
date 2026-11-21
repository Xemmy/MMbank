# MMbank — Subscriptions overhaul + CI/APK delivery: plan for the next agent

> Written 2026-11-21 against v0.126.1 (versionCode 183, DB v42), branch
> `claude/mmbank-local-ai-parsing-4cfi14`. **Planning only. Nothing below is implemented yet.**
> Follow CLAUDE.md. For each phase: write code plus pure-JVM tests, bump the version, mark the item
> in MMbank-IMPROVEMENT-PLAN.md, add an entry to MMbank-FEATURE-LOG.md (with "Not covered by CI"),
> push, then wait for CI to go green before starting the next phase.

## Context: what the user reported
1. **Detection barely works.** "It doesn't seem to pick up any pattern; maybe only 1 was picked."
2. **APK delivery.** They don't want to download a zip from GitHub Actions. They want a direct `.apk` link.
3. **Improve the GitHub setup** in general.
4. **Subscriptions screen:**
   - (a) Can't edit a subscription, and can't undo a dismiss.
   - (b) Missing info and polish.
   - (c) Search only matches the merchant.
   - (d) Can't change a transaction's merchant.
   - (e) Can't track several subscriptions at the **same merchant with the same amount on the same card**.
5. **Quick "Add to subscription" / "Split with Tabby (Pay Later)" on any transaction.** You pick the
   number of payments X. The captured charge is often just **one installment** (Tabby takes 1/X at
   checkout), so the plan total must be able to be **amount × X**, not the captured amount.

Answers the user gave:
- The same-merchant subscriptions are identical (same amount, same card), so only manual assignment can tell them apart.
- Merchant editing should support **both** editing one transaction **and** renaming a merchant everywhere, including future SMS.
- Search should match **amount**, **SMS/description text** and a **date filter**, on top of merchant.

## Phase order
| # | Phase | Version | Why this order |
|---|---|---|---|
| 1 | Direct APK download + CI clean-up | 0.126.2 (CI only; bump anyway so the APK name changes) | Independent and small. Every later phase then ships as a direct APK. |
| 2 | Subscriptions keyed by id (migration v43) | 0.127.0 | Needed before detection can return more than one subscription per merchant. |
| 3 | Detection v3 + "Why not detected?" diagnostics | 0.128.0 | Fixes report #1 and needs phase 2. |
| 4 | Edit, dismiss/restore, undo | 0.129.0 | 4a |
| 5 | Search (amount, SMS text, date) | 0.130.0 | 4c |
| 6 | Edit transaction + rename merchant everywhere (migration v44) | 0.131.0 | 4d |
| 7 | Polish | 0.132.0 | 4b |
| 8 | Quick actions: Add to subscription / Split with Tabby ×X (next free migration) | 0.133.0 | #5. Needs only phase 2, so it can move up to straight after phase 3 if the user asks. |

---

## Phase 1: GitHub: direct APK + workflow improvements
File: `.github/workflows/test-build.yml` (the only workflow).

**Today:**
- Every `claude/**` push uploads `app-debug.apk` with `actions/upload-artifact@v4`. GitHub always serves those as a **zip**, and they expire after 7 days.
- A real Release (a direct `.apk`) is only published on a push to `main`.
- Signing is already stable: `app/debug.keystore` is committed and wired in `app/build.gradle.kts`, so APKs from different runs install over each other. Keep it that way.

**Changes:**
1. **Rolling dev pre-release per branch** (the main fix for report #2). After the APK builds on a push to `claude/**`, and only when unit tests passed:
   - Rename the APK to `MMbank-<versionName>-<versionCode>.apk`.
   - Publish it to a **prerelease** tagged `dev-<branch-slug>`, where the slug is the branch name with `/` replaced by `-`. Set `make_latest: false`.
   - Recreate the release on each push: `gh release delete "$TAG" --cleanup-tag -y || true`, then `gh release create "$TAG" --prerelease --target "$GITHUB_SHA" --title "MMbank dev <version> (<short sha>)" --notes "<commit subject + test status>" <apk>`. Use `GH_TOKEN: ${{ github.token }}`. `contents: write` is already granted.
   - Also upload the same file under the fixed name `MMbank-dev.apk`, so the link never changes: `https://github.com/xemmy/mmbank/releases/download/dev-<slug>/MMbank-dev.apk`.
   - Write that link at the top of `$GITHUB_STEP_SUMMARY`.
2. **Main releases:** attach `MMbank-vX.Y.Z.apk` instead of `app-debug.apk`. Before publishing, check that the tag exists with `gh release view`. If it does, print "version not bumped" clearly and skip, instead of failing with an unclear error.
3. **Run in parallel.** Today `build-apk` has `needs: lint-and-unit-tests`, so the whole run is serial. Remove that `needs` so tests and the build run side by side. Move both release steps (dev and main) into a new `publish` job with `needs: [lint-and-unit-tests, build-apk]`. That job gates on test success, downloads the APK artifact and publishes it. Keep `final-status` as it is.
4. **Faster Gradle.**
   - Replace `cache: gradle` in `setup-java` with `gradle/actions/setup-gradle` (better caching of the build and configuration cache).
   - Cut the 4-attempt retry ladder to 2: plain, then `clean`. Attempt 4 runs `rm -rf ~/.gradle`, which throws the cache away and re-downloads everything.
5. **Drop or fix the release build.** `assembleRelease` produces `app-release-unsigned.apk`, which can't be installed. Remove the step: it saves minutes and the APK is useless. Signing a real release would need a secret keystore, which is out of scope.
6. **Skip CI for docs-only pushes.** Add `paths-ignore: ['**/*.md']` under `push` (not under `pull_request`). Update the CLAUDE.md "What CI actually runs" section to match.
7. **Housekeeping.**
   - Rename the job "Lint & Unit Tests" to "Unit Tests", since lint never runs.
   - Narrow `permissions: contents: write` to the `publish` job, with `contents: read` at the top level.
   - Add `.github/dependabot.yml` with weekly updates for `github-actions`, plus `gradle` if wanted.
   - Bump action majors only to versions more than 2 weeks old.
   - Optional: a JUnit annotation step, e.g. `mikepenz/action-junit-report`, so failing tests show inline on the commit.
8. Optional: check whether the current `actions/upload-artifact` supports `archive: false` for a single file. If it does, the 7-day artifact is no longer a zip either. Don't rely on it; the Release path above is the guaranteed direct link.

**Docs:** update CLAUDE.md's CI section and MMbank-HANDOVER.md with the dev-release link pattern.
**Verification:** push, then use the GitHub MCP tools (`mcp__github__list_releases`, `get_release_by_tag`) to confirm that `dev-claude-mmbank-local-ai-parsing-4cfi14` exists with a `.apk` asset, and that a second push replaces it.

---

## Phase 2: Subscriptions keyed by id (fixes 4e; needed for phase 3)
**Root cause:**
- `SubscriptionEntity` has a **UNIQUE index on `merchant`** (`data/local/entity/Entities.kt` ~395).
- `SubscriptionRepositoryImpl.saveManual` reuses any row with the same merchant (`data/repository/SimpleRepositories.kt` ~289), so a second subscription overwrites the first.
- `subscription_links` points to a **merchant string**.
- `SubscriptionMerge` matches rows by exact merchant string.
- `SubscriptionLinks.candidates` hides charges from the same merchant.

**Changes:**
- **`MIGRATION_42_43`**: raw SQL in `MigrationSql.kt`, registered in `AppDatabase.kt` (~827/899), version 43.
  - `DROP INDEX index_subscriptions_merchant; CREATE INDEX index_subscriptions_merchant ON subscriptions(merchant)` (now non-unique).
  - Add `subscriptions.merchantKey TEXT` (brand key, backfilled in Kotlin-free SQL as `LOWER(TRIM(merchant))`; rescans refine it).
  - Rebuild `subscription_links` as `(transactionId INTEGER PK, subscriptionId INTEGER NOT NULL)` with an index on `subscriptionId`. Backfill through a join on the old merchant column, and drop links that don't resolve.
- **Domain:** `SubscriptionLink(transactionId, subscriptionId)` and `Subscription.merchantKey`, added to entity, domain model and mappers, with round-trip tests.
- **DAO / repository:**
  - Link methods take a subscription id.
  - `saveManual` inserts a new row unless an id is given.
  - New methods: `update`, `restore`, `observeDismissed`. Phase 4 uses them; add them now to avoid a second schema change.
- **New pure `domain/intelligence/SubscriptionRescan.kt`**, which replaces `replaceAll` and `SubscriptionMerge`. Given (stored rows, links, ledger) it returns (upserts, deletes, counts):
  - **Linked charges belong only to their subscription** and are removed from the radar pool. A user-owned row (`isManual`) recomputes count, last seen and next due from its own linked charges, using `SubscriptionRadarEngine.nextDue` (already `internal`). If it has no charges, the typed date stays, rolled forward by cadence once it has passed.
  - **Detection runs on unlinked charges.** Match detected rows to stored detected rows by (merchantKey, currency, amount cluster), so an id and its dismissed flag survive a change in spelling.
  - **Delete a detected row that isn't found again and isn't dismissed.** The old `replaceAll` never removed anything, so cancelled subscriptions stayed forever as "Renewal overdue".
  - **Linking a charge to a detected row, or editing it, makes it user-owned.**
- **`AppContainer.refreshSubscriptionForMerchant`** (`di/AppContainer.kt` ~901): today it loads `findRealDebitsByMerchant` by **exact** merchant and only upserts. Change it to call `subscriptionRepository.rescan(full ledger)`. That is cheap and keeps a single code path.
- **Backup** (`data/backup/BackupManager.kt` ~312/470/643):
  - Links carry `subscriptionId`, and manual rows keep their id.
  - Old backups (merchant links) are resolved by merchant on restore.
  - Bump `formatVersion`.
- **UI (minimal in this phase):**
  - `candidates` keeps same-merchant charges and hides only charges already linked anywhere.
  - Add **"Link similar"**: one tap links every unlinked charge with the same merchantKey and an amount within 5%.
  - The `TransactionPickDialog` "New subscription from this charge" always creates a new row.
- **Tests:**
  - `MigrationSqlTest`: v42→v43, including link backfill, two rows with the same merchant allowed, and orphan links dropped.
  - `SubscriptionRescanTest`, including the **identical twins** case: two rows with the same merchant, amount and card, each with alternate linked charges; both survive a rescan.
  - Stale detected row deleted, dismissed row kept.
  - Backup round-trip, plus an old-format restore.
  - Update or delete `SubscriptionMergeTest`.

---

## Phase 3: Detection v3 (fixes report #1)
Engine: `domain/intelligence/SubscriptionRadarEngine.kt`, tests in `app/src/test/.../SubscriptionRadarTest.kt`.

**Why few or none are picked up** (from reading the code; I couldn't see the user's data):
1. **One chain per merchant, plus the 60% chain-share rule** (`MIN_CHAIN_SHARE`, `chainFrom`).
   - Any merchant with more than one recurring charge gets **nothing**, or at most one: Apple (iCloud + Music), Google, Amazon (Prime + shopping), du/Etisalat lines, or the user's identical twins (two per cycle, so 50% < 60%).
   - `bestChain` treats the other subscription's charges as "extra purchases", and then the share check rejects the whole merchant.
2. **Amount variance of 5%** (`AMOUNT_VARIANCE_THRESHOLD`). USD/EUR subscriptions billed in AED drift with exchange rates and fail. Plan line 656 already notes this.
3. **At least 3 occurrences** (2 only with a hint word in the SMS). A user with 2 months of history sees nothing.
4. **Charges with no merchant are dropped** (`filter { !it.merchant.isNullOrBlank() }`). Only `kind == SPEND` counts, so a direct debit parsed as TRANSFER is ignored.
5. **Spelling drift.** `build()` stores `last.merchant` (the raw spelling) and merges by exact string, so a renamed SMS creates duplicates or loses the dismissed flag (fixed in phase 2).
6. **No automatic full scan.** Detection only runs on the capture hook (exact-merchant query) or when the user taps Scan, and `hasScannedOnce` resets every session.

**Changes:**
- **Group by (merchantKey, currency), then split each group into amount clusters** of charges within the tolerance. Run chain detection **per cluster** and compute the chain share per cluster. A merchant can then yield several subscriptions.
- **Parallel lines.** When a cluster has k ≈ 2..4 charges per cycle with consistent spacing (same amount, so likely identical twins), emit **one detected row with `lines = k`**, labelled "2 × AED 50 · monthly". Add a "Split into 2" action that creates k user-owned rows and links the charges alternately by date. The user can fix any mistakes in phase 4's dialog. Store `lines` as part of the v43 migration if possible: fold it into phase 2's migration, not a separate one.
- **Tolerance.** Use 5% for charges in the account's currency and **12% when the charge currency differs from the account currency** (FX-billed). Loosen monthly tolerance from ±5 to ±7 days for weekend and holiday posting.
- **Confidence + "Possible subscriptions".**
  - Compute a score from occurrences, gap regularity, amount stability and hint words.
  - ≥ 0.8: Active.
  - 0.5–0.8: listed under **"Possible subscriptions"** with **Track** (makes it user-owned) and **Not a subscription** (suppresses that merchantKey + amount cluster permanently; store it as a dismissed detected row).
  - This lets 2-charge series and loose matches show up instead of vanishing.
- **Explain mode.** Add pure `SubscriptionRadarEngine.explain(transactions): List<Verdict>`, one per merchantKey + amount cluster with ≥2 charges. A verdict is either `Detected`, `Possible(score)` or `Rejected(reason)`. Reasons:
  - `TOO_FEW` (n/needed)
  - `IRREGULAR_GAPS` (show the gaps in days)
  - `PRICE_VARIES` (max deviation)
  - `MIXED_WITH_OTHER_PURCHASES`

  Show this on the Subscriptions screen under an overflow item **"Why isn't X detected?"**: a searchable list of merchants with their verdicts. It is the user-facing diagnostic for "it picked only one". Ask the user to open it and report back if detection still misses things after this phase.
- **Merchantless debits.** Group them by (sender, amount cluster) under the label "Unknown merchant (sender)", only for the Possible list, never auto-Active.
- **Auto-scan.** Run a full `rescan` when the Subscriptions screen opens (debounced, at most once per 10 minutes, keyed in AppPreferences) and after bulk SMS import.
- **Tests** (pure JVM; fixtures shaped like the UAE SMS the parser produces):
  - Netflix monthly
  - Apple with 3 different amounts on one merchant, giving 3 subscriptions
  - Identical twins at 50 AED twice a month, giving 1 row with `lines = 2` and Split producing 2 rows
  - USD Spotify billed in AED with ±8% drift, detected
  - A 2-charge series without a hint, which lands in Possible
  - Daily coffee, still not a subscription
  - An Amazon mix of Prime plus random shopping, giving Prime only
  - A spelling change between scans, with the id kept
  - `explain()` reasons, one test each
  - The existing `SubscriptionRadarTest` cases must still pass. Adjust expectations only where the new rules deliberately change them, and record each change in the feature log.

---

## Phase 4: Edit, dismiss/restore, undo (4a)
Files: `ui/screens/subscriptions/SubscriptionsScreen.kt`, `SubscriptionManualUi.kt`, `ui/designsystem/MMTopBar.kt` (`MMScaffold` ~81).
- **Tapping a row opens a detail dialog.** It replaces the row's link and ✕ icon buttons and the jump to the merchant page. It holds:
  - a summary (cadence, amount, next date, Yours/Detected, lines)
  - **Edit**
  - Linked charges and Add charge (moved here from `SubscriptionChargesDialog`)
  - Open merchant page
  - **Dismiss** (detected rows) or **Remove** (user-owned rows)
- **Edit:** generalise `AddSubscriptionDialog` into `SubscriptionEditorDialog(initial: Subscription?)` covering name, amount, currency, cadence and next renewal. Saving calls `repository.update` and makes the row user-owned. Pull validation into a pure `SubscriptionForm.validate`, with a test.
- **Undo:** add `snackbarHost` to `MMScaffold`.
  - Dismiss shows "Dismissed Netflix · Undo", which calls `restore(id)`.
  - Remove returns a snapshot (row + links). Undo re-inserts both. Test the pure snapshot/restore helper.
- **Dismissed (N)** at the bottom of the list expands into rows with **Restore**.

## Phase 5: Search: amount, SMS text, date (4c)
- **New pure `domain/intelligence/ChargeFilter.kt`**, `matches(tx, query, from, to)`:
  - Text matches merchant, `rawMessage` and `note`, ignoring case.
  - A numeric query matches the amount: "39.99" exactly; "40" as a prefix of the plain amount, so 40.00 and 40.50 match.
  - The date range is inclusive. Presets: This month · Last 3 months · This year · All · Custom (reuse `DatePickField` from `ui/screens/paylater`).
- **Bug to fix:** `SubscriptionLinks.pickable` applies `limit = 100` **before** the dialog filters, so older charges can't be found. Filter first, then limit, and re-query when the search changes.
- **Where:** the same search field and date chips in `TransactionPickDialog` and in the detail dialog's Add-charge list. Each row also shows a short snippet of the SMS.
- **Tests:** `ChargeFilterTest` (text, number, date edges) and a case where a match older than the newest 100 is still found.

## Phase 6: Edit transaction + rename merchant everywhere (4d)
- **Edit one transaction:** an **Edit** action on every charge row navigates to `Routes.transactionEditor(id)` (`ui/navigation/AppNavigation.kt:73`).
  - Add `onEditTransaction` to `SubscriptionsScreen` and wire it in `ui/MainActivity.kt` (~506).
  - Back the detail dialog with flows (`observeTransactions()` + `observeLinks()`) so it shows edits when the user comes back.
- **Rename everywhere:** a "Rename merchant…" action. The dialog reads "Rename all N transactions named 'APPLE.COM/BILL' to [Apple]", with a checkbox "Also rename future messages".
  - `transactionDao.renameMerchant(from, to)`: one `UPDATE` matching the trimmed name, ignoring case.
  - **`MIGRATION_43_44`** adds `merchant_aliases(fromKey TEXT PK, toName TEXT NOT NULL)`. The capture path (`TransactionRepositoryImpl.recordParsed`) applies the alias before category and account learning.
  - **Settings → Merchant renames** lists aliases with delete.
  - Aliases are included in the backup.
  - A subscription rescan runs afterwards.
- **Tests:**
  - `MigrationSqlTest` v43→v44.
  - Pure `MerchantAliases.apply`.
  - Rename then rescan: renamed charges join the series.
  - Mapper and backup round-trips.

## Phase 7: Polish (4b)
- **Summary card:** "5 active · AED 140.00/month · AED 1,680/year", one line per currency when mixed. Move the monthly math out of `PlanStatus.subscriptions` (`ui/screens/plan/PlanStatus.kt:53`) into a shared pure `SubscriptionTotals` (counting `lines`), so the Plan card and the screen agree.
- **Sections:** Overdue / Due this week / Later / Possible, from a pure `SubscriptionGroups`.
- **Hide balances:** combine `container.hideBalances` into `uiState` and use `MoneyText`.
- **Design system:** use `MMCard` rows with a Yours/Detected chip. Fix the spinner, which sets `width` only; make it `size`. Remove the duplicate `cadenceLabel` and keep `cadenceName`.
- **Empty-state text:** make it accurate (the cadences and the 2-or-3 charge rule).
- **Scan result snackbar:** "Found 2 new · 1 updated · 1 removed", using the counts from `SubscriptionRescan`.
- **Tests:** `SubscriptionTotalsTest`, `SubscriptionGroupsTest`, the existing PlanStatus tests, and the rescan counts.

## Phase 8: Quick actions: Add to subscription / Split with Tabby ×X (#5)
**What exists today (0.125.0/0.126.x):**
- `TransactionEditorScreen.kt` (~350-376) has **"Split with Pay Later"** and **"Add to subscription"** buttons, but only at the bottom of the full editor, for saved SPEND debits.
- "Split" navigates to `Routes.payLaterEditor(fromTransactionId)` (wired in `MainActivity.kt` ~510). `PayLaterEditorScreen` (~78-104) prefills **total = the transaction's amount**, `firstPaid = true` and `sourceTransactionId`. The user can only type a total.
- Payments follow `domain/paylater/PayLaterPayment.kt`. With a source transaction, later payments are a transfer pair or an **excluded** debit, because the full spend is assumed already counted.

**Gap:** when the SMS is one installment (for example AED 100 of a AED 400 Tabby ×4), the plan gets the wrong total. Worse, the later payments are hidden from analytics, so only 1/4 of the purchase ever counts as spending.

**Changes:**
1. **Easy entry points:**
   - Add the two actions to the Activity **`TransactionDetailSheet`** (opened by tapping a row; `TransactionsScreen.kt` ~549), shown for SPEND debits.
   - Move the editor's two buttons to a visible "Actions" row near the top.
   - Optional: show the same actions on Home's recent-transaction tap.
2. **Quick split sheet.** A bottom sheet instead of the full editor, with the full editor kept behind "More options":
   - Provider (default Tabby)
   - **X** as chips: 2 · 3 · 4 · 6 · 12 · Other (`PayLaterSchedule.MIN_COUNT..MAX_COUNT`)
   - **"This charge is…"** as a segmented control:
     - **One payment of X**: total = amount × X. This becomes the default when the SMS matches the Tabby purchase rule (`builtin.en.debit.tabby.purchase` in `BuiltInRules.kt`) or the provider is Tabby.
     - **The full price**: total = amount (today's behaviour).
   - Cadence (default monthly; Tabby "pay in 4" is often bi-weekly).
   - A live preview: "Total AED 400.00 · 4 × AED 100.00 · next due 21 Dec". **Create** saves the plan.
3. **Model:**
   - New `PayLaterPlan.sourceIsInstallment: Boolean` (default false), added to entity, domain model and mappers, plus the backup, using the next free migration (`ALTER TABLE pay_later_plans ADD COLUMN sourceIsInstallment INTEGER NOT NULL DEFAULT 0`).
   - New pure helper **`PayLaterSplit.fromCharge(charge, count, chargeIsInstallment, cadence, zone)`** returns (plan, installments):
     - **Installment mode:** total = amount × X. Installment #1 = the charge, marked paid at the charge time with `paymentTransactionId = charge.id`. The rest are due by cadence from the charge date, and amounts come from `PayLaterSchedule.splitAmounts`.
     - **Full-price mode:** the current `PayLaterSchedule.generate(..., firstPaid = true)` path, unchanged.
4. **Spending counted correctly.** In `PayLaterPayment.shapeOf`, a plan with `sourceIsInstallment = true` records later payments as ordinary **SPEND**, because only 1/X was counted at capture. Full-price plans keep today's transfer or excluded shapes. Show the result in the sheet: "Each payment counts as spending" vs "Already counted in the original purchase".
5. **Auto-match the next Tabby SMS.**
   - When a new debit is captured, look for an open plan with the same provider or merchant (`MerchantNormalizer.key`), an amount within 1% of the next unpaid installment, and a date within ±7 days of its due date.
   - If found, mark that installment paid with `paymentTransactionId = the new tx`. Do **not** record another payment.
   - Logic goes in pure `PayLaterAutoMatch.match(plans, installments, tx)`, called from the post-insert hook in `AppContainer` next to `refreshSubscriptionForMerchant` (~487). Show "Matched to Sephora · payment 2 of 4 · Undo" in the detail sheet.
   - This stops double counting when every installment arrives as its own SMS.
6. **Quick "Add to subscription" sheet.** A one-tap list of subscriptions, built on phase 2's id-based `SubscriptionPickerDialog`, showing the merchant, amount and Yours/Detected. Options:
   - "New subscription from this charge"
   - **"Repeats X times"**: an optional end count for fixed-term plans. If included, store it as `Subscription.remainingCount` in the same migration; otherwise leave it out.

   It should also show which subscription the charge is currently linked to, with Unlink.
7. **Linked state visible.** Once linked, the transaction detail sheet and editor show a chip: "Tabby 1/4 · Sephora" or "Subscription · Netflix". Tapping it opens the plan or subscription.

**Tests (pure JVM):**
- `PayLaterSplitTest`:
  - ×4 installment mode: total = 4 × amount, #1 paid and linked to the charge, the rest dated by cadence, cents add up.
  - Full-price mode unchanged.
  - Each X from 2 to 36 within bounds.
- `PayLaterPaymentTest`: `sourceIsInstallment` makes later payments SPEND.
- `PayLaterAutoMatchTest`: within tolerance, wrong amount, outside the window, already-paid installment skipped, two open plans with the same merchant (picks the soonest due).
- Migration test for the new column, plus mapper and backup round-trips.

**Not covered by CI:** the sheet's layout and default selection, chips and the preview, and the auto-match on a live Tabby SMS.

---

## Verification (each phase)
- **CI:** `:app:testDebugUnitTest` (new pure tests plus `MigrationSqlTest` v43/v44 on real SQLite) and `assembleDebug` must both be green. After phase 1, also confirm the dev pre-release has the new `.apk` asset.
- **Not covered by CI (record in the feature log):** dialog, snackbar and section rendering; navigation to the editor and back; Hide balances masking; the date picker; alias renaming on a live SMS; the dev-release download on a phone.
- **Device checklist for the user:**
  - Install from the direct dev-release link.
  - Open Subscriptions, let the auto-scan run, and check the Possible list and "Why isn't X detected?".
  - Split an identical-twins row into 2.
  - Edit a row, dismiss it and undo, restore from the Dismissed list.
  - Search by amount and by SMS text, with a date range.
  - Rename a merchant everywhere, then receive a new SMS from it.
  - Tap a Tabby charge in Activity → Split → ×4, "One payment of 4", and check total = 4×.
  - When the next Tabby SMS arrives, check it auto-matches payment 2/4 and isn't counted twice.
  - Tap any charge → Add to subscription.
