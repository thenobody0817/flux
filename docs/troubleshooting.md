# Troubleshoot Flux

[Documentation index](README.md)

## Start with diagnostics

```sh
flux-cli version
flux-cli doctor
flux-cli status --json
systemctl --user status fluxd
journalctl --user -u fluxd -n 100 --no-pager
```

Inspect `flux-cli setup` output directly.
It can print a failed setup step and still exit with zero.

## flux runs another program

If `flux` prints a Kubernetes error, the `fluxcd` package owns `/usr/bin/flux`.
That file comes before the Flux short name in `PATH`.
Use `flux-cli` for Flux:

```sh
flux-cli status
```

If the shell says `flux: command not found` after an install, log in again.
`/etc/profile.d/flux-path.sh` adds the short name at login.
See [the command name](install.md#the-command-name).

## The daemon does not run

If you previously turned Flux off, turn it on:

```sh
flux-cli on
```

If the service is missing, repeat user setup:

```sh
flux-cli setup
```

If another daemon holds the socket, stop that process before you start the service.
A foreground development daemon and the user service cannot share one socket.
See [isolated development](development.md#isolated-daemon).

## The phone does not appear

1. Open Flux for Android or Flux for macOS.
2. Check that both devices use the same local network.
3. Check Avahi:

   ```sh
   systemctl status avahi-daemon
   ```

4. Request discovery:

   ```sh
   flux-cli discover
   flux-cli status
   ```

Guest Wi-Fi and client isolation can block devices on the same access point.
Flux uses outbound desktop connections and mDNS, so a new inbound desktop firewall rule is not the default fix.
Keep the existing identity and trust store while you diagnose connectivity.

On a Mac, check that Flux has access to the local network.
To read the Mac logs, run:

```sh
log stream --predicate 'subsystem == "org.omarchy.flux"'
```

## The phone does not connect away from home

Flux reaches a phone outside the local network only through an extra address, for example its Tailscale name.

```sh
flux-cli addresses
flux-cli doctor
tailscale ping pixel-8
journalctl --user -u fluxd -n 50 --no-pager | grep "connect to"
```

If `flux-cli addresses` shows `none` for the phone, add its Tailscale name.
See [Connect through Tailscale](tailscale.md#troubleshoot) for the other checks.

## The window or bar item is missing

Try the Qt host directly:

```sh
FLUX_GUI=app flux-cli open
```

If it works, refresh the installed plugin:

```sh
flux-cli setup
omarchy-shell shell rescanPlugins
```

For a user-only install, install the plugin from the checkout with `make install-plugin`.
See [plugin layout](omarchy.md#install-layout).
Missing icons usually indicate a missing Nerd Font or Qt SVG package.

## Text messages do not show

If the Messages page is missing, the phone does not offer its text messages.

1. Open the device screen of the computer in Flux for Android.
2. Turn on **Text messages**.
3. Allow SMS access when the phone asks.

If the switch stays off, open **Settings > Apps > Flux > Permissions** on the phone and allow **SMS**.
If Android shows **Restricted setting**, open **Settings > Apps > Flux**, open the menu, and select **Allow restricted settings**.
Then turn on **Text messages** again.

If a sent message shows **Not sent**, the phone could not send it.
Check the signal and the SMS app on the phone.
Flux does not send messages to a group. Reply to a group on the phone.

## Media controls do not show on the phone

The phone shows the controls of a desktop player that publishes its state over MPRIS.
To list these players and the media log of the daemon:

```sh
busctl --user list | grep org.mpris.MediaPlayer2
journalctl --user -u fluxd --no-pager | grep media
```

If the list does not show the player, the player has no MPRIS support.
For mpv, install `mpv-mpris`.
If the log shows `media control off`, `fluxd` did not connect to the session bus.
Restart it with `systemctl --user restart fluxd`.

A player that does not accept a new volume shows no volume control.
Chromium is an example.

## Camera, microphone, or screen fails

```sh
flux-cli webcam
flux-cli mic
flux-cli screen
flux-cli doctor
```

The webcam needs `ffmpeg`, `v4l2loopback-dkms`, and headers for the active kernel.
The microphone needs PipeWire and phone microphone permission.
The screen mirror needs `mpv` or `ffplay` and the Android capture prompt.
See [camera and streams](camera.md) for setup commands.

## herdr agents do not show

```sh
herdr status
flux-cli doctor
journalctl --user -u fluxd --no-pager | grep herdr
```

`fluxd` and herdr must run as the same user.
See [herdr agents](herdr.md#troubleshoot) for the socket path and the phone states.

## Android build or install fails

Check the Java and Gradle versions from `android/`:

```sh
java -version
./gradlew --version
```

Use JDK 21 and SDK platform 36.
Keep the local SDK path in `ANDROID_HOME` or ignored `local.properties`.
Do not add a machine-specific JDK path to `gradle.properties`.

An APK with a different certificate cannot update an installed app.
A lower version code cannot replace a higher version code through a normal update.
Use the original release key and a higher code for release upgrades.
See [APK signatures](releasing.md#android-release-key).

If Play Protect shows **App blocked to protect your device**, install with `adb`.
See [Android setup and Play Protect](android-setup.md#install-flux-past-the-block).

## Fingerprint approval falls back to a password

```sh
flux-cli approve
flux-cli status
```

Check the phone connection, enrolled key, fingerprint setup, and approval timeout.
A new fingerprint can invalidate the phone key and require enrollment again.
Keep the password fallback active while you diagnose approval.
See [approval setup](approvals.md).

## Wake-on-LAN does not work

Check the pieces in order.

1. The phone must know a hardware address. Open the device page and its
   **Wake settings** row. The summary counts the learned addresses. If it
   shows none, connect the computer once while the phone is on the same
   network, or enter the address by hand.

2. The desktop network adapter must allow wake. Check the interface name
   from `ip -br link`:

   ```sh
   sudo pacman -S --needed ethtool
   sudo ethtool enp196s0f4u1u2 | grep Wake-on
   ```

   `Supports Wake-on` must include `g` for a magic packet. Turn it on and
   make it survive a reboot through NetworkManager:

   ```sh
   sudo ethtool -s enp196s0f4u1u2 wol g
   nmcli connection modify "Wired connection 1" 802-3-ethernet.wake-on-lan magic
   ```

3. Wake-on-LAN usually works from suspend, not from a full shutdown.
   Use `systemctl suspend`. In the firmware, enable "Power On by PCI-E"
   and disable "ErP Ready" so the adapter keeps power.

4. From 5G the magic packet needs a reachable address. The **Wake
   address** must reach the computer's network: forward UDP 9 on the home
   router to the computer's LAN address, and give the computer a DHCP
   reservation.

   A sleeping adapter often stops answering ARP, so the router cannot
   deliver the forwarded packet to it. Add a static ARP entry on the
   router (here a Merlin RT-AC88U with the PC at `192.168.1.212`):

   ```sh
   ip neigh replace 192.168.1.212 lladdr 10:06:48:c0:1b:f9 dev br0 nud permanent
   ```

   Persist it in `/jffs/scripts/services-start`. On Wi-Fi the phone also
   sends the magic packet to the local broadcast, so a manual **Wake** at
   home does not need the router forward.

   A machine that only offers `s2idle` (no deep sleep in
   `/sys/power/mem_sleep`) can still wake a USB adapter, but the USB
   controller must keep wake on. If it does not, a relay on an always-on
   device or a motherboard Ethernet adapter is more reliable.

Some USB network adapters do not support Wake-on-LAN at all.
Prefer a motherboard Ethernet adapter when you can.
