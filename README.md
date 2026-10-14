# Zuno app

Android wrapper (WebView) around the Zuno website, with a foreground music service.

## Google sign-in inside the app

Google does not allow "Sign in with Google" inside a WebView, so the app shows Android's own
Google account picker (the accounts already signed in on the phone) and passes the result to the website.

One-time setup in Google Cloud Console (same project as the website's Web client ID):

1. APIs & Services -> Credentials -> Create credentials -> OAuth client ID
2. Application type: **Android**
3. Package name: `com.wonder.music`
4. SHA-1 certificate fingerprint: `62:5E:4E:CB:D5:BA:47:7E:30:C3:AB:BC:C0:E4:E8:D7:81:F3:E8:F2`
   (this is the key in `zuno-debug.keystore`; every build uses it, so it never changes)
5. If the OAuth consent screen is in "Testing", add your Google account under Test users (or publish the app).
6. Wait a few minutes, then build and install the APK.

The website keeps using its existing Web client ID (`GOOGLE_CLIENT_ID` in index.html), so the server needs no change.

Note: `zuno-debug.keystore` is a *debug* key, fine for sideloaded APKs. Use your own private key for a Play Store release
and add that key's SHA-1 as another Android client.
