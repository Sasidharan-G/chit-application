# Jothi Vel Chits - Project Fix Checklist

இந்த document project analysis-ல் கண்டுபிடிக்கப்பட்ட மாற்றங்கள் மற்றும் pending வேலைகளை track செய்ய பயன்படுத்த வேண்டும்.

## P0 - Build மற்றும் Runtime Blockers

- [x] `AndroidManifest.xml`-ல் literal `` `n `` text இப்போது இல்லை (verified 2026-09-11).
- [x] `ApiService.getGroups()` / `List<ChitGroupEntity>` types match ஆகின்றன (verified 2026-09-11).
- [x] `ApiService`-ல் `getMembers()` endpoint declaration இப்போது உள்ளது (verified 2026-09-11).
- [x] **FIXED 2026-09-25** - `GroupRepository` / `MemberRepository` / `PaymentRepository` now exist in `data/repository/` and are driven by `data/sync/SyncEngine` (see "Office server sync" under the architecture decision below). Old note, kept for history: `MemberRepository`/`GroupRepository`/`PaymentRepository` **still never actually call the backend** - `refreshGroups()`/`refreshMembers()` are commented-out TODO stubs, `PaymentRepository.recordPayment()` builds an `ApiService` but never calls it. **This is the single biggest remaining gap: almost none of the app's screens talk to this Node backend at all right now.** See new section "P0 - Client/Server Architecture Decision" below - needs a decision before more networking code is written.
- [x] `backend/src/routes/payments.js` already imports `User` correctly (verified 2026-09-11).
- [x] Gradle wrapper already present in repo (verified 2026-09-11).
- [x] `./gradlew assembleDebug` builds successfully end-to-end (verified 2026-09-11, no errors).
- [x] `backend/package.json` `test` script now runs the real calculation test scripts (fixed 2026-09-11).

## P0 - Authentication மற்றும் Security

- [x] **RESOLVED 2026-09-25** - the local PIN stays on purpose as the device lock; the server session is a separate ADMIN login (Settings > Office server sync) that goes through `AuthRepository` -> `POST /auth/login` (agent/member accounts are refused). Old note: **Admin login is still 100% local**: `LoginScreen`'s PIN pad calls `LoginViewModel.verifyPin()`, which only checks `AppPreferences.verifyPin()` against a locally-stored PIN hash - it never calls `AuthRepository`/`ApiService.login()`. The Node backend's `/auth/login`, JWT issuance, etc. are built but currently unreachable dead code from the shipping admin flow. Field agents log in separately via Firebase (`AgentAuthRepository`), not this backend either. Needs a decision - see "P0 - Client/Server Architecture Decision" below.
- [ ] `demo_access_token` / `demo_refresh_token` - not found in current code (may already be gone, or never existed under that name); re-check once the login flow above is actually decided/wired.
- [x] (already fixed - it refreshes through `/auth/refresh-token` and retries once, covered by `TokenAuthenticatorTest`) Old note: `TokenAuthenticator` still just clears tokens and forces logout on 401 instead of calling `/auth/refresh-token` (moot until login above is wired to the real API).
- [x] Tokens already stored via `EncryptedSharedPreferences` in `TokenManager` (verified 2026-09-11).
- [x] `/auth/seed-admin` now returns 404 outside dev, and only runs while no admin exists yet (fixed 2026-09-11).
- [x] Default admin password removed; `seed-admin` now generates and returns a random one-time password (fixed 2026-09-11).
- [x] Hard-coded JWT fallback already removed; server exits if `JWT_SECRET` is unset (verified pre-existing).
- [x] Access and refresh tokens now use separate secrets (`JWT_SECRET` / `REFRESH_TOKEN_SECRET`) (fixed 2026-09-11).
- [ ] Refresh-token rotation, revocation, and logout invalidation still not implemented (needs a stored/blacklistable token table).
- [x] Login rate limiting added (in-memory, 5 attempts / 15 min per IP+phone; swap for Redis if scaling to multiple server instances) (fixed 2026-09-11).
- [x] CORS now restricted via `CORS_ALLOWED_ORIGINS` env var, required in production (fixed 2026-09-11).
- [x] `usesCleartextTraffic` already `false` (verified 2026-09-11).
- [x] (fixed - `BASE_URL` now comes from `BuildConfig.API_BASE_URL` per build type, and the office-server address can be typed in Settings; release builds accept https only) Old note: Hard-coded LAN `BASE_URL` in `ApiClient.java` (`192.168.0.27`) still needs to move to build config/environment.
- [x] Release builds no longer log request/response bodies (gated on `BuildConfig.DEBUG`) (fixed 2026-09-11).
- [ ] `allowBackup="true"` still set - business decision on backup policy still pending.

## P0 - Financial Data Integrity

- [x] Auction finalize now runs in one DB transaction with row locks (fixed 2026-09-11).
- [x] Auction winner must be an actual active subscriber of the group (fixed 2026-09-11).
- [x] A member who already won cannot win again (fixed 2026-09-11).
- [x] `companyCommission` is now saved on the installment (fixed 2026-09-11).
- [x] The DUE payment rows are now actually inserted for every subscriber (this was dead/commented-out code before) (fixed 2026-09-11).
- [x] `Payment.paidAt` is now nullable (fixed 2026-09-11).
- [x] Partial payments can now be topped up to completion instead of being hard-rejected on the second attempt (fixed 2026-09-11).
- [x] Payment creation is now isolated from PDF/WhatsApp failures - a saved payment is never reported back as failed (fixed 2026-09-11).
- [x] Unique constraint added on `(installmentId, memberId)` (fixed 2026-09-11).
- [x] Unique constraint added on `(groupId, slotNo)` (fixed 2026-09-11).
- [ ] `(groupId, memberId)` uniqueness/allowed-slot-count - left as a business-rule decision (a member holding multiple slots in the same chit is normal in some chit-fund setups); ask before adding this constraint.
- [x] `amountPaid`/`chitValue`/etc. validated as positive integers on the endpoints touched in this pass (`POST /groups`, `POST /payments`, auction). Not yet audited on `POST /payouts`.
- [x] `chitValue` non-divisible-by-duration remainder is now rolled into the final installment so paise never leak (fixed 2026-09-11).

## P0 - Client/Server Architecture Decision (NEW - found during 2026-09-11 pass, blocks further networking work)

The app currently has **three disconnected data paths** and this needs a decision before more sync/networking code is written, or effort will be wasted building against the wrong one:

1. **Node/Express + SQLite REST backend** (`backend/`) - JWT auth, groups/members/payments/auctions/reports. Now correctly implements the core financial logic (see fixes above), but as of this pass **the Android app calls almost none of it**: only `POST /auth/login` (never invoked - see below) and `GET /reports/dashboard` (`DashboardViewModel`, actually live) have real call sites. `getGroups`, `getMembers`, `createGroup`, `recordAuction`, `recordPayment` are declared in `ApiService` but never called by any repository - `GroupRepository`/`MemberRepository` have the real API calls commented out as TODOs, and `PaymentRepository.recordPayment()` builds an `ApiService` instance and never calls it.
2. **Local-only Room DB** - what the app actually runs on today. Admin login is a locally-stored PIN check (`AppPreferences.verifyPin`), and Add Group / Add Member / Collect Payment all write only to local Room tables. Nothing here ever reaches a server, so multi-device/admin-office visibility, backend reports, and the backend's validation/integrity rules (all just fixed above) are currently bypassed entirely by the live app.
3. **Firebase/Firestore** - a separate, more recently-started sync system for field agents (`data/firebase/*.kt`, see `firebase_agent_prompt_continuation.md`), with its own agent login (phone+PIN against Firestore `agents/`), its own admin->cloud->agent group/member/installment mirroring, and its own collection-sync queue. This is the only path with any real multi-device sync working, but it's for the agent/labour flow specifically, requires a `google-services.json` Firebase project the user hasn't provided yet, and per its own handoff doc has "not been compiled even once" as of when that doc was written (it does compile now, as of this pass, but hasn't been feature-tested).

**DECIDED 2026-09-25 (final): Firebase/Firestore only.** The office server sync (Node backend + reconciling Android sync layer) was built and then removed on request the same day: the `backend/` folder, `data/remote`, `data/sync`, `data/repository`, `TokenManager`, Settings > Office server sync, the Retrofit/OkHttp dependencies and `API_BASE_URL` are gone, and Room v18 (`MIGRATION_17_18`, tested) drops the `sync_records` table. The app is local Room + Cloud account (Firebase) for the agent flow. Backend-related items further down this checklist no longer apply.

## P1 - Backend API மற்றும் Validation

- [x] Group create/update endpoints-ல் required fields, types, positive ranges மற்றும் valid dates validate செய்ய வேண்டும். (POST /groups fixed 2026-09-11; PUT /groups/:id not yet audited)
- [x] Group member add செய்யும் முன் group exists என்பதை check செய்ய வேண்டும். (fixed 2026-09-11)
- [x] Member exists மற்றும் role `MEMBER` என்பதை subscription creation முன்னால் check செய்ய வேண்டும். (fixed 2026-09-11)
- [x] Group status `DRAFT` இல்லாதபோது member/slot changes தடுக்கப்படுகிறது. (fixed 2026-09-11)
- [x] Auction நடத்தும் முன் group `ACTIVE` என்பதை check செய்கிறது. (fixed 2026-09-11)
- [x] Auction installments chronological order-ல் மட்டுமே finalize செய்ய validation உள்ளது. (fixed 2026-09-11)
- [x] Winning bid upper limit (chitValue) மற்றும் commission percentage allowed range (0-20%) validate செய்கிறது. (fixed 2026-09-11)
- [x] (verified 2026-09-25 - `POST /payments` answers 400 for a member who is not subscribed to the installment's group) Payment member அந்த installment group-ல் subscribed என்பதை validate செய்ய வேண்டும் (still not checked - a payment can currently be recorded for a member not subscribed to that installment's group).
- [x] Payment mode மற்றும் UPI/bank reference requirements validate செய்கிறது. (fixed 2026-09-11)
- [x] `/payments?groupId=` filter now actually implemented via an Installment join. (fixed 2026-09-11)
- [ ] Role-based data scope: not implemented (moot for MEMBER since members can't log in to this backend at all; AGENT scoping needs an agent<->group assignment schema that doesn't exist yet on this backend - Firebase's agent system already has this, see architecture decision above).
- [x] `/groups/:id/members` and `/members` list no longer expose `passwordHash`. (fixed 2026-09-11)
- [ ] Mass-assignment on update endpoints not yet audited.
- [ ] Consistent error response format across all endpoints - mostly consistent already (`errorCode`/`message`/`field`), a few older handlers (`reports.js`) still use a bare `message` field only.
- [ ] No request-validation library/centralized error middleware yet - validation is hand-written per route so far in this pass.
- [x] Pagination added on `GET /payments` (`limit`/`offset`, capped at 200). Other list endpoints (`GET /groups`, `GET /members`) not yet paginated.
- [ ] SQLite → production DB migration strategy still undecided (`sequelize.sync()` at startup, no versioned migrations).

## P1 - Android App Architecture மற்றும் Data Flow

- [ ] Retrofit API models-க்கு raw `Map<String, Object>` பதிலாக typed request/response DTOs உருவாக்க வேண்டும்.
- [ ] Backend JSON field names மற்றும் Room entity fields ஒரே contract-க்கு map செய்ய வேண்டும்.
- [ ] Repository operations-க்கு consistent result/loading/error state பயன்படுத்த வேண்டும்.
- [ ] Java/Kotlin mixed code-ல் duplicate screen/activity flows இருக்கிறதா audit செய்து ஒரே navigation flow வைத்திருக்க வேண்டும்.
- [ ] Main/login navigation மற்றும் persisted session validation சரி செய்ய வேண்டும்.
- [ ] App launch-ல் stored access token validity check மற்றும் refresh flow இயக்க வேண்டும்.
- [ ] Logout-ல் tokens, sensitive cached state மற்றும் scheduled sync state சரியாக clear செய்ய வேண்டும்.
- [ ] UI thread-ல் database/network work நடக்காததை உறுதி செய்ய வேண்டும்.
- [ ] Retrofit/server errors பயனருக்கு Tamil/English friendly messages-ஆக map செய்ய வேண்டும்.
- [ ] Release configuration-ல் debug-only code/logs இல்லாததை உறுதி செய்ய வேண்டும்.

## P1 - Offline-First Sync

- [x] (no mock `SyncWorker` exists any more; `SyncEngine` + `SyncScheduler` replace it) Current mock `SyncWorker` sleep logic-ஐ real sync implementation-ஆக மாற்ற வேண்டும்.
- [x] (done as a separate `sync_records` table, so the business tables are untouched) Local changes-க்கு `syncStatus`, `updatedAt`, client operation ID போன்ற metadata சேர்க்க வேண்டும்.
- [x] (payments carry an idempotency key `clientRef`; chits and customers are re-linked by chit number / mobile, never duplicated) Pending local writes-ஐ server-க்கு idempotently upload செய்ய வேண்டும்.
- [ ] Server updates-ஐ incremental cursor/timestamp மூலம் download செய்ய வேண்டும். (PARTIAL: chits/customers that exist only on the server are pulled by full list and only when new; edits made on the server and server-side payments are not pulled back.)
- [x] (a `sync_records` row is written only after the server answered OK) Successful upload பிறகே local row synced என்று mark செய்ய வேண்டும்.
- [x] (offline / signed-out stops the run and retries on the next trigger; a server refusal is stored per item with its reason and shown in Settings) Network/server failure-க்கு retry மற்றும் permanent validation failure-க்கு user-visible state சேர்க்க வேண்டும்.
- [x] (one sync at a time behind a mutex; payments use `clientRef`) Duplicate sync requests தவிர்க்க stable idempotency keys பயன்படுத்த வேண்டும்.
- [ ] Same record local/server இரண்டிலும் மாறினால் conflict-resolution rule define செய்ய வேண்டும். (Current rule: the phone is the source of truth - a customer/chit edited on the phone overwrites the server's copy on the next sync; there is no merge. Money is never overwritten, see below.)
- [x] (payments are append-only receipts on the server; a void adds a reversing row, nothing is overwritten. Auctions are not synced at all yet, see "Not covered".) Financial records/locked auctions-க்கு silent last-write-wins பயன்படுத்தக்கூடாது.
- [x] Sync status, last synced time மற்றும் failed item count UI-ல் காட்ட வேண்டும். (Settings > Office server sync)

## P1 - Database மற்றும் Migrations

- [ ] Sequelize model changes-க்கு versioned migrations பயன்படுத்த வேண்டும்; startup `sequelize.sync()` மட்டும் நம்பக்கூடாது.
- [ ] Existing SQLite data மீது migrations test செய்ய வேண்டும்.
- [ ] Room versions 1-5 இலிருந்து current version வருவதற்கான migration path இருக்கிறதா verify செய்ய வேண்டும்.
- [ ] Room schema export enable செய்து migration schemas version control-ல் வைக்க வேண்டும்.
- [ ] Foreign keys மற்றும் delete/update behavior (`RESTRICT`, `CASCADE`) தெளிவாக define செய்ய வேண்டும்.
- [ ] Database indexes group, installment, member, payment report queries-க்கு சேர்க்க வேண்டும்.
- [ ] Repository root/backend folder-ல் real customer database commit ஆகாதபடி `.gitignore` மற்றும் deployment storage configure செய்ய வேண்டும்.

## P2 - Reports, Receipts மற்றும் Notifications

- [ ] Dashboard/report calculations seeded realistic data-க்கு verify செய்ய வேண்டும்.
- [ ] Daily tally, dues, collections, P&L மற்றும் settlement reports-க்கு automated tests எழுத வேண்டும்.
- [ ] Report date filters/timezone handling consistent ஆக மாற்ற வேண்டும்.
- [ ] PDF receipts secure storage-ல் உருவாக்கி authenticated download endpoint வழங்க வேண்டும்.
- [ ] Mock hard-coded receipt domain-ஐ configured public URL-ஆக மாற்ற வேண்டும்.
- [ ] WhatsApp/SMS mock service-க்கு பதிலாக provider integration அல்லது Android share intent flow முடிக்க வேண்டும்.
- [ ] Notification delivery status, retry மற்றும் failure audit log சேர்க்க வேண்டும்.
- [ ] Receipt numbering uniqueness மற்றும் financial-year numbering rule define செய்ய வேண்டும்.

## P2 - UI/UX மற்றும் Localization

- [ ] எல்லா visible strings-யும் resources-க்கு move செய்து English/Tamil translations complete செய்ய வேண்டும்.
- [ ] தற்போது encoding corruption உள்ள Tamil comments/text/resources இருக்கிறதா scan செய்து UTF-8 normalize செய்ய வேண்டும்.
- [ ] Currency display எப்போதும் paise-to-rupee formatter வழியாக வர வேண்டும்.
- [ ] Loading, empty, offline, error மற்றும் retry states எல்லா screens-க்கும் சேர்க்க வேண்டும்.
- [ ] Form validation messages field அருகில் காட்ட வேண்டும்.
- [ ] Accessibility labels, touch target sizes, contrast மற்றும் font scaling test செய்ய வேண்டும்.
- [ ] Small phone, tablet, landscape மற்றும் Tamil long-text layouts test செய்ய வேண்டும்.
- [ ] Destructive/financial finalization actions-க்கு confirmation மற்றும் summary screen சேர்க்க வேண்டும்.

## P1 - Testing மற்றும் Release Readiness

- [ ] Kasaru edge cases: zero bid, commission boundary, rounding remainder, large values ஆகியவற்றுக்கு unit tests சேர்க்க வேண்டும்.
- [ ] Auth, roles, groups, subscriptions, auctions, payments மற்றும் payouts backend integration tests எழுத வேண்டும்.
- [ ] Concurrent auction/payment requests-க்கு tests எழுத வேண்டும்.
- [ ] Room DAO மற்றும் migration tests எழுத வேண்டும்.
- [ ] Android repository tests mock API responses உடன் எழுத வேண்டும்.
- [ ] Login-to-payment முக்கிய user flow instrumentation/UI test உருவாக்க வேண்டும்.
- [ ] Debug மற்றும் release APK builds CI-ல் run செய்ய வேண்டும்.
- [ ] Lint, unit tests மற்றும் backend tests CI checks-ஆக configure செய்ய வேண்டும்.
- [ ] Release signing, ProGuard/R8 மற்றும் secret management configure செய்ய வேண்டும்.
- [ ] Restore/backup, offline recovery மற்றும் app upgrade scenarios manually verify செய்ய வேண்டும்.

## Definition of Done

ஒரு feature complete என்று mark செய்ய முன்:

- [ ] Android debug மற்றும் release build இரண்டும் pass ஆக வேண்டும்.
- [ ] Backend automated tests pass ஆக வேண்டும்.
- [ ] Android unit/Room tests pass ஆக வேண்டும்.
- [ ] English மற்றும் Tamil UI இரண்டிலும் verify செய்திருக்க வேண்டும்.
- [ ] Online, offline மற்றும் reconnect flows test செய்திருக்க வேண்டும்.
- [ ] Role/access-control checks verify செய்திருக்க வேண்டும்.
- [ ] Financial calculations மற்றும் database side effects verify செய்திருக்க வேண்டும்.
- [ ] Sensitive data logs/responses-ல் leak ஆகவில்லை என்பதை verify செய்திருக்க வேண்டும்.


## Audit fix pass (2026-09-25)

Verified by `cd backend && npm test` (45 tests) and `cd android-app && gradle testDebugUnitTest` (101 tests).

**Corrections to the notes above**
- The "Dashboard call is live" claim is wrong: no Android code calls `GET /reports/dashboard` (or any other backend endpoint). The Node backend is still not wired to the app - that remains the open architecture decision.

**Backend - fixed**
- Auction no longer fails when a member pre-paid the installment (existing rows are re-based, money kept).
- Append-only `PaymentTransaction` ledger; reports (collections, daily tally, monthly total) read from it; legacy rows are backfilled on start.
- Dues/pending count include PARTIAL; `OVERDUE` is now set from the installment due date; P&L uses stored commission; report dates are local and validated.
- Payout requires collected dues (override `forceUnpaid`) and validated body; `GET /payouts` filters work.
- Receipts are rendered on demand at `GET /payments/:id/receipt.pdf` behind auth (nothing stored on disk).
- `PUT /groups/:id` validated and regenerates installments for DRAFT; new `cancel`, `complete`, `DELETE` group, `DELETE` member (DRAFT).
- MEMBER role scoped to own data; server exits non-zero if the DB cannot start; JSON 404/400 handlers; WhatsApp Cloud API adapter (`WHATSAPP_PROVIDER=meta`), masked mock logging.
- Tests now exercise the real `KasaruService` and the HTTP API.

**Android - fixed**
- Installments fall due on the chit's start day (not the 1st); paise remainder goes into the last installment.
- Daily Closing: day summary (office cash / agent cash / UPI / bank, settlement / delivery) and agent cash handover tracking (Room v14, migration tested). The cash drawer count (opening / counted / difference) was removed on request: `daily_closings` table dropped in Room v17 (`MIGRATION_16_17`, tested).
- Ledger inline edits validated, logged and crash-safe; CSV import transactional, matches chits by number/name (ambiguous = stop), never overwrites chits, creates receipts, optional payment-date column.
- Add Member: existing-code/mobile identity rules, rejoin, ticket, nominee, Aadhaar last-4; chit edit/close/reopen, member removal, auction winners; Delivery capped by the recorded prize.
- Single language store; restore/sync no longer overwrite local rows; agents pull colleagues' collections; Pending/Group detail use bulk queries and no longer wait on the cloud.
- Room schemas exported; release signing via `keystore.properties`; ProGuard rules; build-type API URL; token refresh implemented; dead code removed.

**Still open (decisions / out of scope)**
- Wire (or retire) the Node backend; Firestore auth model (see `firestore.rules` header); default admin PIN; allowBackup.
- `payments.installmentId` still stores the installment number (no FK); "old code" still lives in the `panNo` column (renaming would break the deployed Firestore rules/cloud data).
- Settlement remains free-form (its business rule is undefined). Settings > "Fix chit schedules" and the Danger Zone "Delete all members" were removed on request (2026-09-25).
- Cloud account: no password is built into the app. On each new phone the admin types the Firebase email + password once in Settings > Cloud account (stored encrypted on that phone); the app never sets or changes the Firebase password. The admin app PIN is kept on each phone (offline login) and as a salted hash in Firestore `adminPins/{uid}` (`AdminPinSync`): a PIN change works at once on the phone and is saved to the cloud when online (retried every minute), the newest change wins and reaches the admin's other phones, and a new phone takes over the existing PIN with the cloud email + password + that PIN (Settings first-launch dialog). Rules for `adminPins` also need `firebase deploy --only firestore:rules`. One login, one phone (`SessionGuard`, Firestore `sessions/{uid}`): a second phone is refused while the first is live (30 min without check-in frees it, logout frees it at once, a phone that finds itself replaced is logged out); offline never blocks. Firestore rules for `sessions` need `firebase deploy --only firestore:rules`.
- Validation messages coming from the data layer are English; screen labels/toasts are localized.
