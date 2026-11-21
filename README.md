# CouchMouse TV

A real mouse cursor for Android TV and Google TV, controlled from your phone.

This repository holds **CouchMouse TV**, the free, open-source app that runs on the TV. You
control it with the **CouchMouse** app on your iPhone or Android phone, which works as a touchpad
and keyboard.

- **Cursor:** move it, tap, long-press, and scroll in any direction.
- **Typing:** use your phone's keyboard to type into the TV's text boxes.
- **Apps and media:** open TV apps from your phone; play, pause and skip; change the volume.
- **Private:** everything stays on your home network. Only phones you pair can connect, using a
  QR code or a 4-digit code shown on the TV. The connection is encrypted with a key that never
  leaves the TV.

## How it works

CouchMouse TV uses Android's accessibility service to draw the cursor and to perform taps,
scrolls and typing. Window content is read only to find the text box you are typing into.
Nothing is collected, stored or sent anywhere. The phone protocol is documented in
[PROTOCOL.md](PROTOCOL.md).

## Building

You need JDK 17 and the Android SDK (API 36).

```
./gradlew :tv:assembleDebug
```

Release builds read their signing settings from `signing.properties`, which is not in the repository.

## License

GPL-3.0. See [LICENSE](LICENSE).
