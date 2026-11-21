# CouchMouse protocol (version 1)

How a phone talks to **CouchMouse TV** (the app in `tv/`). Everything stays on the home network.

## Finding the TV

The TV announces itself with DNS-SD (Bonjour/mDNS) as `_couchmouse._tcp`:

| Field | Value |
|---|---|
| Service name | The TV's name from its settings, e.g. `Living Room TV` |
| Port | `8770` |
| TXT `v` | Protocol version (`1`) |
| TXT `fp` | First 16 hex characters of the TV's certificate fingerprint |

## Connection

TLS (1.2 or 1.3) over TCP to port 8770. The TV's certificate is self-signed: an EC P-256 key
created once in the TV's Android Keystore. Phones **pin** its SHA-256 fingerprint (lowercase hex
of the DER certificate):

- from the QR code (`f=`), or
- on first use, when pairing with a code; the stored fingerprint must match on every later connection.

After TLS, both sides send UTF-8 text lines ending in `\n`. Free text (names, typed text) is
percent-encoded (`%20` for spaces) so a line never contains a raw space inside a field or a newline.

## Hello and pairing

The phone's first line:

```
hello 1 <key or -> <phone name>
```

- A known key gets `ok 1 <tv name> <capabilities>`. The phone is now paired.
- Anything else gets `pair`. The phone then pairs in one of two ways.

**QR code.** The TV's setup screen shows:

```
couchmouse://pair?h=<address>&p=8770&f=<fingerprint>&s=<secret>&n=<tv name>
```

The phone connects, checks the fingerprint, says `hello 1 - <name>`, then sends `qr <secret>`.
The secret is single-use and valid for 10 minutes. Replies are `key <K>` followed by `ok …`, or `bad qr`.

**Code.** The phone sends `code?`. The TV shows a 4-digit code for 2 minutes and replies `code`.
The phone sends `code 1234`. Replies:

| Reply | Meaning |
|---|---|
| `key <K>` then `ok …` | Paired |
| `bad N` | Wrong. N tries left for this code; `bad 0` means the TV now shows a new code |
| `code new` | The code expired and a new one is on the TV |
| `wait S` | Too many wrong codes. Pairing is paused for S seconds (10 wrong codes in 10 minutes) |

The phone stores `K` (Keychain / encrypted storage) and the fingerprint, and sends `K` in every
later `hello`. The TV stores only SHA-256(K). Forgetting a phone on the TV disconnects it at once.

## Commands (phone → TV, after `ok`)

| Line | Action |
|---|---|
| `m dx dy` | Move the cursor (pixels, decimals allowed) |
| `t` / `l` | Tap / long press at the cursor |
| `s dy` / `sh dx` | Scroll vertically / sideways (accumulated; swipes at ±40) |
| `b` / `h` / `r` | Back / Home / Recent apps |
| `v+` / `v-` / `vm` | Volume up / down / mute |
| `media <play\|pause\|playpause\|next\|prev\|stop\|ff\|rew>` | Media keys |
| `text <percent-encoded>` | Type into the focused text box (at the cursor) |
| `bksp` / `enter` | Delete before the cursor / keyboard action key (Android 11+) |
| `key <up\|down\|left\|right\|ok>` | Move the focus like the remote's arrows (for TVs without Google's remote service) |
| `apps` | List the TV's apps |
| `launch <package>` | Open an app |
| `power` | Standby (Android 9+) |
| `ping` | Keep-alive. Send every 15 s; the TV hangs up after 45 s of silence |

## Messages (TV → phone)

| Line | Meaning |
|---|---|
| `ok 1 <tv name> <capabilities>` | Paired. Capabilities are comma-separated, e.g. `cursor,scroll,text,apps,media,volume,dpad,back,home,recents,power` |
| `kbd 1` / `kbd 0` | A text box got / lost focus. The phone may open its keyboard |
| `apps <json>` | `[{"p": package, "n": name, "b": banner, or "i": icon}]`, images as base64 WebP |
| `pong` | Reply to `ping` |
| `err <what>` | A command could not be done, e.g. `err text` when no text box is focused |
