# Wake Tracker relay

One PHP script, `api.php`, that lets phones share their Wake Tracker record with the phones
paired with them. A phone that shares uploads its record here; the paired phones fetch it
from here.

The relay cannot read what it holds. A record is encrypted on the phone that uploads it
(AES-256-GCM), with a key derived from a secret that travels only in the pairing code,
from one phone's screen to the other's camera. The relay has no part in that.

## What the relay knows

- That a device with a random id exists, and hashes of its two access tokens.
- The names of its files: one per day, named by the day's number (`d20779`), and `meta`.
- Their sizes, which the phone pads to whole blocks (4 KiB for a day), and when they were
  written. A day with two events is as large as a day with five hundred.

It never sees a time the screen went on, a total, a time zone or the name a phone was
given. Whoever runs the relay can delete or withhold files, and can serve an older copy of
a file. They cannot read one, alter one unnoticed, or pass one day off as another: each file
is sealed to its device and its name.

## Access

Each device has two tokens, both random and both chosen by the phone:

- The **write** token never leaves that phone. It is needed to upload and to remove the device.
- The **read** token is derived from the secret in the pairing code, so exactly the phones
  that scanned the code have it. It is needed to list and to fetch files.

A phone revokes every reader at once by making itself a new pairing code: it then appears
as a new device and has the relay delete the old one.

Registration is open to anyone who knows the relay's address. The constants at the top of
`api.php` bound what that can cost: at most `MAX_DEVICES` devices of at most
`MAX_DEVICE_BYTES` each.

## Protocol

Tokens travel as `Authorization: Bearer`. Errors are `{"error": code}`.

| Request | Token | Answer |
|---|---|---|
| `GET ?a=hello` | | `{service, api, version}` |
| `POST ?a=register&device=ID`, body `{"write": T, "read": T}` | | `{registered}`. Safe to repeat with the same tokens; `409 device_taken` with others. |
| `PUT ?a=blob&device=ID&name=NAME`, body: the file | write | `{version}`. Replaces the file of that name. |
| `GET ?a=changes&device=ID&since=N` | read | `{version, blobs: [{name, version, size}]}`: the files written after version N. |
| `GET ?a=blob&device=ID&name=NAME` | read | the file |
| `DELETE ?a=device&device=ID` | write | `{deleted}` |

Every write gives the device a new version number, and the file written carries it. A
reader keeps the version it has seen and asks for what came after.

`404 unknown_device` means the relay has no such device; a phone that uploads registers
again and sends everything. `403 wrong_token`, `413 too_large`, `507 device_full` or
`relay_full` are what they say.

The app's side of this is in `app/src/main/kotlin/com/hawwwran/waketracker/share/`:
`ShareCrypto.kt` and `Blobs.kt` for what a file is, `Relay.kt` for the requests.

## Running one

Needs PHP 8.1 or newer behind https, and a folder the script can write to. Put `api.php`,
`.htaccess` and `VERSION.md` into the folder the address points at. The
script creates `data/` beside itself on first use and closes it to the web with an
`.htaccess` of its own. On a web server that ignores `.htaccess`, close `data/` in the
server's configuration; what is in it is ciphertext and token hashes either way.

Then give the address to the app: Record and settings, Sharing, Server address.

When updating, replace the files and leave `data/` where it is.

## Tests

```bash
server/test.sh                         # on this computer, with PHP's built-in server
server/test.sh https://relay.example   # against a deployed relay
```

Both register a device for the purpose, write to it, read it back and remove it.
