# Configuration

[Documentation index](README.md)

`fluxd` creates `~/.config/flux/config.toml` on its first start.
After an edit, reload the service:

```sh
systemctl --user reload fluxd
```

## Settings

```toml
name = "omarchy-desktop"
download_dir = "~/Downloads"
scan_dir = "~/Documents/flux/scanned"
photo_dir = "~/Pictures/flux"
auto_clipboard = true
notifications = true
share_home = true
pause_media_on_call = true
sync_dnd = true
gui = ""
approve_timeout = 20

[[commands]]
id = "lock"
name = "Lock screen"
command = "omarchy-system-lock"
```

| Key | Effect |
| --- | --- |
| `name` | The desktop name shown on the phone. An empty name uses the host name. |
| `download_dir` | Destination for received files. Defaults to the XDG Downloads directory, then `~/Downloads`. |
| `scan_dir` | Destination for scanned text and documents. Defaults to `flux/scanned` inside the XDG Documents directory. |
| `photo_dir` | Destination for camera photos. Defaults to `flux` inside the XDG Pictures directory. |
| `auto_clipboard` | Sync clipboard text in both directions. Defaults to `true`. |
| `notifications` | Show phone notifications on the desktop. Defaults to `true`. |
| `share_home` | Let the phone browse the desktop home folder read-only. Defaults to `true`. |
| `pause_media_on_call` | Pause desktop media during calls. Defaults to `true`. |
| `sync_dnd` | Sync Do Not Disturb. Defaults to `true`. |
| `gui` | Use `app`, `plugin`, or an empty value for automatic host selection. |
| `approve_timeout` | Wait 5 to 120 seconds for approval. Zero or an omitted value uses 20 seconds. |
| `commands` | Desktop commands available to the phone. A new configuration has no commands. |

The destination paths expand `~`.
Use the [CLI](cli.md#media-and-desktop-commands) or the Phone commands page to add commands without editing TOML.

## Remote devices

Flux normally connects over the local network. A paired device can also
name an address that is reachable away from home, such as a Tailscale
MagicDNS name. `fluxd` keeps it in `devices.json`:

```json
{
  "name": "Moto Edge 40 Pro",
  "remote": "moto-edge-40-pro.tailb898c2.ts.net"
}
```

Set it with the CLI instead of editing the file:

```sh
flux remote moto-edge-40-pro.tailb898c2.ts.net --device "Moto Edge 40 Pro"
```

The address may include a port, for example `moto-edge-40-pro.tailb898c2.ts.net:1716`;
without one, fluxd uses 1716. `fluxd` dials the local address first and,
when that does not connect, dials the remote address. It does not send
the UDP identity to a remote address, because a peer that answers it
would open a second link at the same time, and on a high latency path the
two links can close each other. The desktop always opens the connection,
so the phone needs no new inbound rule.

## Wake-on-LAN

`fluxd` advertises the hardware addresses of its physical network
interfaces in the `fluxWakeMacs` field of its identity. Flux for Android
stores them with the paired device, so the phone can send a Wake-on-LAN
magic packet when the computer is asleep.

A magic packet is a local broadcast. From 5G the phone cannot reach the
home network directly, so set a **Wake address** on the phone's device
page: a host and UDP port that deliver the packet to the home network.
Common choices are a router with a UDP port forward to the computer's LAN
address, or a small relay on an always-on device. Without an address, the
phone falls back to the local broadcast, which works only on the
computer's own Wi-Fi.

Flux for Android stores `wakeHost`, `wakePort`, and `wakeEnabled` with the
paired device in its own trust store.
The desktop trust store keeps the paired phone, not the wake settings.
Enable **Wake when away** to send the packet automatically when the phone
is off Wi-Fi and the computer is unreachable.
See [everyday use](features.md#wake-a-sleeping-computer).

## Data paths

| Path | Content |
| --- | --- |
| `~/.config/flux/config.toml` | Settings and commands |
| `~/.config/flux/off` | Marker that disables automatic daemon start |
| `~/.local/share/flux/certificate.pem` | Desktop identity certificate |
| `~/.local/share/flux/privateKey.pem` | Desktop identity private key |
| `~/.local/share/flux/devices.json` | Paired devices and pinned certificates |
| `~/Documents/flux/scanned/` | Scanned text and documents by default |
| `~/Pictures/flux/` | Camera photos by default |
| `~/Pictures/flux/screenshots/` | Automatically received screenshots by default |
| `~/.cache/flux/` | Notification icons and album art |
| `$XDG_RUNTIME_DIR/flux/fluxd.sock` | Local IPC socket |
| `/etc/flux/approve/<user>.pub` | Root-owned phone approval public key |
| `/etc/flux/approve/pam-backup/` | Original PAM files from approval setup |

The config and data paths honor `XDG_CONFIG_HOME` and `XDG_DATA_HOME`.
Keep the identity private key private.
Its replacement changes the desktop identity and requires new pairing.

## Environment variables

| Variable | Use |
| --- | --- |
| `FLUX_SOCKET` | Override the local IPC socket path. |
| `FLUX_GUI` | Select `app` or `plugin` for `flux open`. |
| `FLUX_QML_DIR` | Load shared views from disk in the Qt host during development. |
| `FLUX_THEME_FILE` | Select a theme file for the snapshot harness. |
| `FLUX_SNAPSHOT` | Select the output directory for the QML snapshot harness. |
| `FLUX_SNAPSHOT_ONLY` | Filter snapshot names by text. |

Without `XDG_RUNTIME_DIR`, the daemon uses `flux-<uid>` inside the system temporary directory.
See [development](development.md) for an isolated test environment.

## Turn Flux off or on

```sh
flux off
flux on
```

`flux off` stops the daemon and creates the off marker.
`flux on` removes the marker and starts the daemon.
On Android, use **Turn off Flux** in the device-list menu or **Turn off** in its notification.
The phone stays off after a restart until you select **Turn on Flux** in the app.
