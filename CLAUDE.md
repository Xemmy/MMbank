# MMbank
Android personal-finance app. Kotlin, Jetpack Compose, Room, MVVM.

## Environment — CRITICAL
- NO Android SDK in this sandbox. Gradle downloads are blocked (403).
  NEVER run gradle build/test/run locally.
- Verification happens ONLY via GitHub Actions
  (.github/workflows/test-build.yml) after pushing to the remote branch.
- Workflow: write code + pure-JVM unit tests -> push -> read CI results.

## What CI actually runs (.github/workflows/test-build.yml)
Triggered on push to `main` and `claude/**`. Three jobs:

1. **lint-and-unit-tests** — runs `./gradlew :app:testDebugUnitTest --no-daemon -x lint`.
   ⚠️ This step has `continue-on-error: true`: the job (and the whole workflow)
   stays green even if unit tests FAIL. Despite the job name, lint never runs
   (`-x lint` skips it). Test results are uploaded as the `unit-test-results`
   artifact.
2. **build-apk** — runs `./gradlew :app:assembleDebug` (up to 4 retry attempts
   with clean/daemon-stop/full-reset), verifies `app-debug.apk` exists, uploads
   it. Also attempts `:app:assembleRelease` (continue-on-error, non-gating).
3. **final-status** — fails the workflow iff build-apk failed.

**Consequence: a green workflow proves COMPILATION only. To verify tests, open
the "Run unit tests" step output (or the unit-test-results artifact) and check
for failures explicitly. Never declare a phase done off the green check mark
alone.**

## Conventions
- Room schema changes: bump `version` in data/local/AppDatabase.kt and add an
  explicit Migration object with raw SQL. Never use destructive migration.
- Domain models (domain/model/Models.kt) mirror entities
  (data/local/entity/Entities.kt) via toEntity()/toDomain() mappers.
  Any field added to one must be added to all three, with a mapper
  round-trip unit test.
- Every new piece of logic gets a pure-JVM unit test in the same commit.
- MMbank-PHASED-UPGRADE-PLAN.md (Phases 0-6) is complete — see
  MMbank-HANDOVER.md for that state. Further work proceeds directly per user
  request, not phase-gated; the conventions on this page still apply to it.
- Settings toggles come in two shapes, pick deliberately: a plain
  AppPreferences boolean re-read inside the one ViewModel's combine (e.g.
  hideZeroBalances) when only that ViewModel needs it; a reactive StateFlow
  on AppContainer (e.g. hideBalances) when multiple independent
  ViewModels/screens must reflect the change live.

## Active workstream: Autonomous local-AI parsing (Gemma via LiteRT-LM)

**Status: target spec — none of the AI stack below exists in the code yet.
Everything in this section is to be built; do not assume any of it exists.**
Only pick this work up when the user asks for it; it does not override the
conventions above.

### Goal
A fully autonomous, on-device "self-healing" fallback for transaction parsing.
Zero user interaction: no dialogs, no confirmation prompts, no blocking UI.
When the deterministic parser can't handle a bank message, a local LLM parses
it in the background and teaches the rule table the new pattern.

- Model: Gemma E2B-class via LiteRT-LM (`.litertlm` binary), local execution
  only. Target device: Galaxy S26 Ultra — optimize for Snapdragon 8 Elite NPU.
- `LocalAiManager` (data layer): verify memory/hardware capability before
  init, cache the inference engine instance (no re-init per call), cap
  context at 1024 tokens for thermals/battery.

### Dual-gate parser flow
- **Gate 1 (existing, unchanged)**: the deterministic
  domain/parser/TransactionParser.kt signature+regex path. On success, save
  immediately. On failure, do NOT fail the pipeline — fall through to Gate 2.
- **Gate 2 (new)**: an expedited WorkManager CoroutineWorker (WorkManager is
  a new dependency) forwards the raw message string to the model with a
  constrained-JSON prompt. Output must be strictly the keyset
  `{"transaction_intent": Boolean, "amount": Float, "currency": String,
  "merchant": String}`.
- **Self-healing**: from a successful model parse, synthesize a candidate
  regex for that message shape, validate it in an isolated try/catch trial
  against the source string, then persist it into the existing
  `parsing_rules` table (ParsingRuleEntity/ParsingRuleDao) so Gate 1 handles
  that shape next time. Prefer reusing that table; only add schema fields via
  the migration convention above.

### AI-evaluated backup retention
Net-new: no pruning/retention exists today (data/backup/BackupManager.kt only
exports/imports). Add a background retention pass over backups where a model
evaluation of financial-activity metadata can veto a pruning target (anomaly
or high historical importance ⇒ skip deletion).

### Guardrails
- Keep MVVM + manual DI via di/AppContainer.kt (this codebase is MVVM, not
  MVI). UI stays decoupled from inference; extraction lives behind domain
  wrappers. Structured coroutines + StateFlow.
- Privacy (scoped to this AI feature): inference is 100% on-device. Zero
  cloud API calls and zero logging of message/token text. (The existing
  Binance remote integration is a separate feature, outside this rule.)
- No destructive migrations — same rule as above.

### Suggested implementation order
1. `LocalAiManager` interface + LiteRT-LM initialization skeleton in the
   data layer.
2. Expedited background CoroutineWorker targeting the Gate 2 parsing flow.
3. Pure-JVM unit tests for the JSON extraction logic using mock LLM text
   outputs — per convention, each step ships with its tests in the same
   commit.
