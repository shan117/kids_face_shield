# Push Notifications (Firebase Cloud Messaging) — how to send

FCM is **wired and dormant** — nothing is sent until you push from the Console. Project
`app-shield-1e78f`, app `com.appsecure.shield`.

## What's in the app

- `firebase-messaging` dependency (same Firebase project + `google-services.json` as Remote Config).
- `fcm/ShieldMessagingService` — shows notifications when the app is **foregrounded** / for data
  messages, logs the device **token**, and subscribes each install to the **`all`** topic.
- `AppLockApplication` — creates the **`announcements`** notification channel at launch + subscribes
  to `all`.
- Manifest — the service + `default_notification_channel_id = announcements` (so **background**
  notifications display on Android 8+).
- `POST_NOTIFICATIONS` permission already declared (Android 13+ asks the user once).

## Send to ALL users (the easy path)

Every install auto-subscribes to the **`all`** topic, so:

1. Firebase Console → **Messaging** (a.k.a. Engage → Messaging) → **Create your first campaign** →
   **Firebase Notification messages**.
2. Enter a **title** + **text**.
3. **Target** → **Topic** → `all`.
4. **Review** → **Publish**. Within a minute every device with the app gets it.

> Topic + test sends work **without Google Analytics** (consistent with your analytics-free setup).
> Only "audience"/segment targeting would need Analytics.

## Send a TEST to one device

1. Run the app once on a device (so it registers).
2. In **Logcat**, filter for tag **`ShieldFCM`** → copy the `FCM registration token: …` value.
3. Console → Messaging → compose → **Send test message** → paste the token → **Test**.

## Foreground vs background (how Android handles it)

| App state | What happens |
|---|---|
| **Background / closed** | The system tray shows the notification automatically (uses the `announcements` channel). |
| **Foreground (open)** | `ShieldMessagingService.onMessageReceived` builds + posts the notification. |
| **Data-only message** | Always delivered to `onMessageReceived` (no auto-display). |

## Good uses
- Announce the free promo is ending (pair with flipping `promo_active` in Remote Config).
- Re-engagement ("check this week's screen-time report").
- Early-adopter rewards, feature announcements.

## Optional polish (later)
- A **custom monochrome notification icon** (currently uses a system info icon). Add a vector drawable
  and a `default_notification_icon` meta-data.
- Tapping a notification currently opens the app's main screen; you can route to a specific screen by
  reading `message.data` keys in `onMessageReceived`.
- Store the token server-side if you ever want to target individual users (not needed for topic sends).
