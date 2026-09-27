# Everyday use

[Documentation index](README.md)

This page describes Flux for Android.
For the Mac app, see [Flux for macOS](macos.md#features).

## Pair a phone

1. Install [Flux for Android](android.md).
2. Connect the phone and desktop to the same local network.
3. Open Flux on the phone.
4. Run `flux open` on the desktop.
5. Select **+ Pair new device**.
6. Select the phone.
7. Compare the 8-character key on both screens.
8. Accept the matching request on the phone.

To pair from the terminal:

```sh
flux discover
flux pair "Pixel 8"
flux status
```

Flux uses TLS with pinned device certificates after pairing.
The desktop discovers phones through mDNS and opens the connections itself.
To use the phone away from the local network, see [Connect through Tailscale](tailscale.md).

## Files, clipboard, and links

Use the Files and Clipboard pages in the desktop window, or run:

```sh
flux send "$HOME/Downloads/report.txt"
flux clip
flux clip "Text from the desktop"
flux url https://omarchy.org
```

On Android, share content to Flux from the system share sheet.
Received files use `download_dir`.
The phone can browse the desktop home folder read-only when `share_home` is enabled.
The tunnel carries SSH traffic without an inbound SSH firewall rule.

## Notifications and SMS

Enable notification access on the phone to show its notifications on the desktop.
To send a notification in the other direction:

```sh
flux notify "Backup done" "412 files, 2.1 GB"
```

The phone uses the **From computers** notification channel.
The desktop name identifies the sender.
Use the Messages page or [SMS command](cli.md#share-and-communicate) to send text messages through the phone.
Turn on **Text messages** on the phone's device screen and allow SMS access;
the Messages page then shows the phone's conversations and sends replies.

## Media and desktop commands

The phone controls desktop media players.
The desktop can also control supported media on the phone:

```sh
flux media play-pause
```

Add desktop commands in the Phone commands page or through the CLI:

```sh
flux commands add "Lock screen" omarchy-system-lock
flux commands
```

A new configuration has no commands.
The phone can request only the commands configured on the desktop.

## Calls

Enable **Call alerts** on the phone's device screen.
Phone access reports call state.
Call-log access supplies the number, and contacts access supplies the name.
Without those optional details, the notification shows **Unknown caller**.

The daemon pauses desktop players that are active when the call starts.
When the call ends, it resumes those players.
A player that you manually resume during the call keeps its state.
A missed call produces a notification.

To keep media active during calls, set:

```toml
pause_media_on_call = false
```

Reload with `systemctl --user reload fluxd`.

## Wake a sleeping computer

A paired computer advertises the hardware addresses of its network
interfaces. The phone stores them with the device, so it can wake the
computer with a Wake-on-LAN magic packet.

On the phone's device page, set a **Wake address**: a host and UDP port
that deliver the packet to the computer's network. Use the local broadcast
only when the phone is on the computer's Wi-Fi. To wake the computer from
5G, forward UDP 9 on the home router to the computer's LAN address (and
give the computer a DHCP reservation), or point the phone at a relay on an
always-on device.

Turn on **Wake when away** to send the packet automatically when the phone
is off Wi-Fi and the computer is unreachable. The **Wake** button on the
**Not reachable** card sends it at any time.
Wake-on-LAN usually works from suspend, not from a full shutdown, and some
USB network adapters do not support it.
See [troubleshooting](troubleshooting.md#wake-on-lan-does-not-work).

## Follow the Omarchy theme

Flux for Android uses the active Omarchy theme of the connected
computer. `fluxd` reads the same `colors.toml` that the Flux window uses,
and sends it to the phone on connect and after every theme change. The app
follows it right away, so its tiles, accents, and dialogs match your
desktop.

The phone also applies the theme on the computer. On the device page, open
**Theme** to see the installed Omarchy themes and which one is active. Tap
one to apply it: the computer switches theme, and the phone follows the
new colors. The app keeps the last theme, so it looks right before it
connects and while the computer is offline.

The app follows the connected computer. With more than one computer, the
theme of the last one that sent its theme is used.

## Do Not Disturb

Enable **Sync Do Not Disturb** on the phone's device page.
Android requests Do Not Disturb access the first time.
Each side sends state only after a change, so daemon startup does not change either side.

The desktop reads the Omarchy shell notification state every two seconds.
Without the Omarchy shell, it uses mako's `do-not-disturb` mode.
The daemon log identifies the selected service.

To disable the desktop side, set:

```toml
sync_dnd = false
```

Reload with `systemctl --user reload fluxd`.

## Automatic screenshots and photos

Enable **Send new screenshots** or **Send new photos** on the phone's device page.
Both options default to off.
Allow access to all photos when Android asks.
Selected-photo access does not expose new captures.

| Phone folder | Desktop destination |
| --- | --- |
| `Pictures/Screenshots` or `DCIM/Screenshots` | `<photo_dir>/screenshots` |
| `DCIM/Camera` | `<photo_dir>` |

Flux sends each completed image to every connected computer once.
Images from before the option was enabled stay on the phone.
An image that no computer received waits for a computer to connect.
The desktop notification includes an Open action.

See [camera and streams](camera.md) for direct capture and live media.

## herdr agents

When [herdr](https://herdr.dev) runs on the computer, select **Agents** on the phone's device page.
The phone shows the status and the colored output of each coding agent, and posts a notification when an agent needs input or finishes.

To answer agents from the phone, set:

```toml
herdr_control = true
```

Reload with `systemctl --user reload fluxd`.
See [herdr agents](herdr.md) for the replies, the notifications, and the access rules.
