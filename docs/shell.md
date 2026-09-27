# Desktop shell

[Documentation index](README.md)

The phone app can carry the [Omarchy Remote](https://github.com/omarchy/omarchy-remote) shell,
a full-screen view of a computer's desktop with real terminal sessions, its own apps, and
browser tabs. It reaches the computer over its own HTTPS address, not over the Flux pairing,
so it works from anywhere the phone can see that address, such as a Tailscale name.

This is a separate program with its own license. Flux embeds its web shell and its Android
WebView host; the Omarchy Remote service on the computer is not part of Flux.

## Set up the computer

On the computer that should appear on the phone:

```sh
git clone https://github.com/omarchy/omarchy-remote
cd omarchy-remote && ./deploy/install.sh
```

That starts `omarchy-remote.service` and `omarchy-remote-dev.service` and, with
`TAILSCALE_SERVE=1`, publishes the shell on the tailnet. The address it prints is the one the
phone needs. `omarchy-remote.service` runs the Rust backend on `127.0.0.1:4188`, and the dev
service serves the web shell on `127.0.0.1:4187`.

Keep the address in `~/.config/omarchy-remote/backend.env`. It holds the proxy secret, so it
stays out of this repository.

## Use it on the phone

Open Flux, and on the computer list tap **Desktop shell** under *Desktop shell*.
The first tap asks for the computer address, for example `https://desktop.tail1234.ts.net`.
Save it, and later taps open the shell directly.

The shell then behaves like the computer: the **Hosts** sheet adds more computers, each with
its own state, and each keeps working while the phone is offline because a copy of the shell
is bundled in the APK. When the computer answers again, the shell reconnects by itself.

## What the shell can reach

The shell is a web page, so it needs a bridge to the phone. Flux gives it exactly the channels
the web shell defines, and only to the shell's own pages:

| Channel | What the phone does |
| --- | --- |
| `shellHosts` | Add, remove, connect, and disconnect computers, and read the host directory |
| `shellKeyboard` | Open and close the keyboard, and match the shell's shortcut registry |
| `shellStorage` | Keep each computer's state, so a reopened shell looks the same |
| `shellFiles` | Save a file the shell sends, to a document or to another app |
| `weatherDevice` | The temperature unit, and one position for the weather compass |
| `browserDevice` | Place, zoom, darken, search, and capture embedded browser tabs |

Every other request is refused, and the bridge exists only on the app-asset origin and on the
connected computer's origin. A website in a browser tab has no bridge, so it cannot ask the
phone for a file, a key, or a position. Host addresses must be HTTPS without a path, and
plain HTTP is accepted only for the emulator's host aliases in a debug build.

## Build the bundled shell

The APK carries a copy of the web shell so that it also works with the computer switched off.
Refresh that copy from an Omarchy Remote checkout:

```sh
cd android
OMARCHY_REMOTE_DIR=~/src/omarchy-remote ./gradlew :app:prepareShellAssets
```

Without a checkout the build keeps the copy it has, so a release never depends on the machine
that runs it. The generated `android/app/src/main/assets/Web` directory is not committed.

The wallpapers in that copy are most of its 21 MB. Drop them from the upstream
`public/backgrounds` before running the task to trade the offline backgrounds for a smaller APK.

## Attribution

The bundled `android/app/src/main/assets/android-bridge.js` and the shell it loads come from
Omarchy Remote, copyright 2026 Justin Sanders, under the MIT License. The Android host is a
port of that project's `ShellActivity`; see the notice at the top of the bridge file.
