# Kaso sa Kalye Maligaya

A family detective game for 2 to 4 players on Android phones, played over local Wi-Fi.
One fiesta treasure is missing. Find who borrowed it, what it is, and where it was left.

## Layout
- `docs/index.html` is the whole game. Edit this one. (`docs/fonts/` holds the bundled fonts.)
- `app/` is the Android app: a WebView, a small game server for the host phone (`RelayServer.java`) and the Wi-Fi game finder (`LanBridge.java`).
- `.github/workflows/build-apk.yml` builds the APK on every push to `main`.

## Get the APK
After each push, the Actions tab builds it (about 4 minutes). Download `kaso-sa-kalye.apk` from
https://github.com/gnotesamsung7-hue/kaso-sa-kalye/releases/tag/apk-latest

## Play
1. All phones on the same Wi-Fi, or connected to the host phone's hotspot. No internet needed.
2. One phone taps **Host a game on this phone**. The others tap that game in the list, or type the address shown in the host's lobby.
3. The host taps **Start the case** once 2 to 4 players are in.
