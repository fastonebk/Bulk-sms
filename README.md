# BPH Bulk SMS — Standalone Android APK project

This project builds a standalone Android app for importing a CSV contact list, composing one SMS, selecting an active SIM subscription, and sending a paced queue from the phone. It does not require a website or SMS gateway.

## Easiest way to get the installable APK (GitHub Actions)
1. Create a GitHub repository and upload the contents of this ZIP.
2. Open **Actions**. Enable Actions if GitHub asks.
3. Select **Build BPH Bulk SMS APK** and click **Run workflow**, or push a commit to `main`.
4. When the workflow completes, open its run and download the artifact **BPH-Bulk-SMS-debug-apk**.
5. Extract the artifact and install `app-debug.apk` on your Samsung Galaxy A17 5G.
6. Android may ask you to allow installation from that browser/file manager. Only install the APK you built from your own repository.

## CSV
CSV must contain a `phone` or `mobile` header. Optional `name` header.
```csv
phone,name
98XXXXXXXX,Customer One
+97797XXXXXXXX,Customer Two
```
The app normalizes common Nepal mobile formats and removes duplicates.

## App permissions and SIM
- `SEND_SMS` is requested only when you start a campaign.
- `READ_PHONE_STATE` is requested to list active SIM subscriptions.
- Choose the Ncell SIM in the SIM dropdown before sending. If the device cannot expose the SIM list, Android's default SMS SIM may be used.
- The app displays a review confirmation before sending and requires an opt-in confirmation checkbox.

## Important limitations
- This is an installable **debug APK** built by GitHub Actions, not a Play Store release.
- Android/OEM/carrier controls may restrict high-volume SMS. The app uses a 5-second pacing delay; it cannot guarantee that 600 messages will be accepted or delivered.
- The status shown by this app is a submission/network result, not proof that a recipient read or received the SMS. Delivery receipts vary by carrier.
- The app must remain active during sending in this initial version. Do not force-stop it or let the phone shut it down.
- Send only to people who agreed to receive your messages. Check Ncell's terms, applicable local rules and your SMS balance first.
- The source has not been compiled or tested on a physical Samsung device in this environment. Use the GitHub Actions build and test with your own number first before any larger campaign.

## Build locally
Open `android/` in Android Studio with JDK 17 and Android SDK 35, then build `app` → `assembleDebug`.
