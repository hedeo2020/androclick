# Android dev environment — how to use it

This describes a real, working build-and-deploy environment for Android
apps, running as an LXC container on a home Proxmox server. It has **no
emulator and no GUI Android Studio** — it's a headless command-line
environment: you build with Gradle and deploy straight to a physical
Android phone connected via USB, the same way you would from a terminal
tab inside Android Studio.

If you are an AI agent reading this to build an app: this environment is
ready to use as-is. Don't try to install Android Studio, an emulator, or a
different SDK — everything needed is already set up below. Just SSH in,
put/clone the project there, and run Gradle.

Last updated: 2026-08-20.

---

## Access

- SSH alias (from this machine): `ssh android-dev`
- Direct: `ssh -J proxmox-server -i ~/.ssh/android_dev_ed25519 root@192.168.254.164`
- The container is only reachable through `proxmox-server` (Tailscale IP
  `100.72.65.113`) as a jump host — it has no public IP and isn't on
  Tailscale itself, only on the home LAN (`192.168.254.164`).
- Private key: `~/.ssh/android_dev_ed25519` (this machine only). If running
  from a different machine, generate a new keypair and append the public
  half to `/root/.ssh/authorized_keys` inside the container (via
  `ssh android-dev` from here first), rather than copying the private key
  around.

## What's installed

Container: Proxmox LXC **201**, hostname `android-dev`, privileged (needed
for reliable USB passthrough), static IP `192.168.254.164/24`, 2 cores,
1GB RAM, 8GB disk (~5.9GB free after SDK install).

- **Android SDK** at `/opt/android-sdk` (`$ANDROID_HOME` /
  `$ANDROID_SDK_ROOT`, already exported for all shells via
  `/etc/profile.d/android-sdk.sh`)
  - `platform-tools` (adb, fastboot) — v37.0.1
  - `platforms;android-34`
  - `build-tools;34.0.0`
  - `cmdline-tools/latest` (includes `sdkmanager`, on `$PATH`)
- **OpenJDK 21** (`openjdk-21-jre-headless`) — enough to run a project's own
  Gradle wrapper (`./gradlew`)
- `git`, `curl`, `unzip`, `usbutils`

If a project needs a different API level or build-tools version than what's
installed, add it with:
```
sdkmanager --sdk_root=$ANDROID_HOME "platforms;android-XX" "build-tools;XX.X.X"
yes | sdkmanager --sdk_root=$ANDROID_HOME --licenses
```

No global Gradle is installed — use each project's own `./gradlew` wrapper
(standard for Android projects; it downloads the correct Gradle version on
first run, needs internet access, which the container has).

## Typical workflow

1. Get the project onto the container — `git clone`, `scp`, or `rsync` it
   into e.g. `/root/projects/<app>` inside the container.
2. `cd` into the project.
3. Build: `./gradlew assembleDebug` (produces an APK under
   `app/build/outputs/apk/debug/`)
4. Install straight to the connected phone: `./gradlew installDebug`, or
   manually: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
5. Debug: `adb logcat`, `adb shell`, `adb shell am start -n <package>/<activity>`

## The physical device

- Phone: Xiaomi **Redmi Note 14 5G** (model `24094RAD4G`, codename `citrine`)
- Serial: `TGYX9D595DFYJZNB`
- Connected via USB directly into the Proxmox host (the Acer laptop), with
  the full USB bus passed through into this container (`/dev/bus/usb`,
  cgroup allow rule for major `189`) — so it survives the device
  re-enumerating when the USB-debugging authorization prompt is accepted.
- Confirm it's visible: `adb devices -l` — should show `device` (not
  `unauthorized`, not missing entirely).
- USB debugging must be enabled in the phone's Developer Options, and the
  RSA fingerprint prompt on the phone screen must be accepted the first
  time (or after the container/host reboots, since that can reset the USB
  connection).

## Troubleshooting

- **`adb devices` shows nothing, and `lsusb` inside the container also
  shows nothing for the phone**: this is a hardware/USB-enumeration
  problem, not adb. Check on the Proxmox host itself:
  `ssh proxmox-server "lsusb; dmesg | tail -20"`. Repeated
  `Cannot enable. Maybe the USB cable is bad?` / `unable to enumerate USB
  device` in `dmesg` means a bad cable (very common — many cables are
  charge-only, no data lines) or a bad port. Try a different cable/port.
- **Phone shows up in `lsusb` as `(MTP)` instead of `(ADB Interface)`**:
  the phone reset to its default USB mode (usually after a reboot or the
  cable being unplugged/replugged). Check the USB notification on the
  phone and confirm "USB debugging connected"; you may need to unplug and
  replug, and re-accept the RSA trust prompt.
- **Phone shows as `unauthorized` in `adb devices`**: accept the "Allow USB
  debugging?" prompt on the phone's screen. If it never appears, run
  `adb kill-server && adb start-server` and replug the cable.
- **DNS/internet doesn't work inside the container**: this container uses
  a static IP; if `resolv.conf` ever reverts to pointing at
  `100.100.100.100` (Tailscale's MagicDNS — unreachable from this
  container, which isn't on the tailnet), fix it from the Proxmox host with
  `pct set 201 --nameserver 1.1.1.1 --nameserver 8.8.8.8` then
  `pct reboot 201`.

## Why not full Android Studio / an emulator

The Proxmox host is a resource-constrained laptop (i7-6500U, 7.7GB RAM
total, already running Nextcloud/Jellyfin/Portainer). A GUI IDE plus an
emulator would want 8GB+ RAM on their own and meaningful GPU acceleration
this machine doesn't have. Building via Gradle CLI and deploying to a real
phone over USB avoids all of that and is what this environment is for.
