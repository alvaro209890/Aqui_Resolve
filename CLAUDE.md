# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AquiResolve — Android marketplace app connecting clients with service providers. Built with Kotlin, Firebase backend, Pagar.me payment integration. Package: `com.aquiresolve.app`.

## Build Commands

```bash
./gradlew assembleDebug        # Build debug APK
./gradlew installDebug         # Install on connected device
./gradlew assembleRelease      # Build release APK
./gradlew bundleRelease        # Build release AAB (Play Store)
./gradlew lint                 # Run lint checks
./gradlew test                 # Run unit tests
./gradlew connectedAndroidTest # Run instrumented tests
```

## Build Configuration

- **Compile/Target SDK:** 35 | **Min SDK:** 24
- **Kotlin:** 1.9.22 | **Gradle:** 8.8.0
- **ViewBinding:** enabled
- **ProGuard:** disabled (minifyEnabled false)
- **Lint:** abortOnError false

## Architecture

Three-layer architecture with Manager pattern as the central abstraction:

**Presentation → Managers → Firebase**

- **Activities (44+):** Each screen is a separate Activity using ViewBinding and coroutines (`lifecycleScope`).
- **Managers:** All business logic lives in manager classes — not in Activities. Two categories:
  - **Firebase Managers** (`FirebaseAuthManager`, `FirebaseOrderManager`, `FirebaseChatManager`, `FirebaseImageManager`, `FirebaseStorageManager`, `FirebaseProviderManager`, `FirebasePrivacyManager`, `FirebaseBankDataManager`, `FirebaseChecklistManager`) — wrap Firestore/Auth/Storage operations.
  - **Business Logic Managers** (`OrderManager`, `OrderDistributionManager`, `PaymentManager`, `SchedulingManager`, `ChatManager`, `NotificationManager`, `RatingManager`, `ServiceHistoryManager`, `CashbackManager`, `PromotionManager`) — orchestrate workflows.
- **Models** in `models/` with Firestore `@PropertyName` annotations and a `payment/` subdirectory.
- **Adapters** in `adapters/` — 16 RecyclerView adapters.
- **Views** in `views/` — custom component (SignaturePad for digital signatures).
- **Utils** in `utils/` — permission helpers, code generators, image utilities.

## Key Subsystems

**Order flow:** pending → distributing → assigned → in_progress → completed. Uses 6-digit verification codes. Cancellation refund policy changes after 5 minutes.

**OS Checklist:** Ordem de Serviço lifecycle — GPS + timestamp on start, 10-question checklist (arrival + execution), 3-category photos (before/during/after), digital signatures (provider + client). Status: checklist_pending → photos_pending → signatures_pending → completed. Data in Firestore `checklists/{orderId}`.

**Payment:** Pagar.me v5 API via Retrofit/OkHttp. Supports credit card (Luhn validation, brand detection) and PIX (QR code generation with ZXing, 5-second auto-polling for confirmation).

**Cashback / AquiCash:** Loyalty program (`CashbackManager`, `PromotionManager`) configured by a single Firestore doc `app_config/cashback`. Two phases switched by `activePhase`: **growth** (tiered cashback Bronze/Prata/Ouro = 3/5/8% by accumulated `cashbackTotalSpent`, credited idempotently when the client opens a completed order; redeemable as discount in `PaymentActivity`) and **launch** (direct cart discount by service count: 2→5%, 3→10%, 4+→15%). **Combos** (apply in both phases) give a discount by category combination; the larger of quantity/combo wins. Cart discount is applied in `FirebaseCartManager.prepareCheckout` (writes `finalPrice`/`cartDiscountPercent`; provider commission untouched). Full details in `docs/SISTEMA_CASHBACK_AQUICASH.md` and admin-panel field reference in `docs/cashback-painel-admin.md`.

**Chat:** Real-time via Firestore listeners. 5-minute access lock after order acceptance.

**Images:** Compressed to max 1MB/1920x1080, stored in Firebase Storage, loaded with Glide.

**Location:** Google Play Services with 5-minute interval updates, stored as GeoPoint in Firestore. Maps via OSMDroid.

**Notifications:** FCM with privacy-aware delivery and multiple notification channels.

## App Entry Flow

MainActivity (login) → SignUpActivity → ClientSignUpActivity/ProviderSignUpActivity → ClientHomeActivity/ProviderHomeActivity

## Dependencies

Firebase BOM 32.7.0 (Auth, Firestore, Storage, Messaging, Analytics), Retrofit 2.9.0, OkHttp 4.12.0, Glide 4.16.0, ZXing 3.5.2, OSMDroid 6.1.18, Material Design 3, Coroutines 1.7.3, Lifecycle/ViewModel 2.7.0, PhotoView 2.3.0.

## Firebase

Project: `aplicativoservico-143c2`. Config in `app/google-services.json`. Storage rules in `storage.rules`, indexes in `firestore.indexes.json`. Collections: `orders`, `checklists`, `chats`, `carts`, `users` (with `cashback_transactions` subcollection), `app_config` (doc `cashback`).
