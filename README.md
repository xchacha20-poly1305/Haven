# Haven

A lightweight Android remote-access client. Android 8.0 or newer is required.

## Features

- SSH, Mosh and Eternal Terminal connections.
- VNC, RDP, SPICE and GLKVM / PiKVM remote desktops.
- SFTP/SCP and SMB, with file editing and transfer.
- SSH jump hosts and port forwarding, SOCKS/HTTP proxies, Cloudflare SSH,
  port knocking and fwknop Single Packet Authorization.
- SSH key management, hardware-backed keys, biometric lock and encrypted backups.
- Bluetooth, BLE and USB serial connections; USB/IP and SPICE USB forwarding to remote hosts.

Built-in VPN clients, email, PRoot, QEMU/UML, local Linux desktops and local
application windows have been removed. AI/MCP automation, cloud storage, mesh
networking, OCR, media processing and image editing have also been removed. Connections can still use Android's
network routing, including a VPN provided by another app.

Existing database and backup fields for removed connection types are retained
to preserve stored data. Those connections are hidden and cannot be launched.
Saved VPN routes fail with an explanation so they cannot silently become direct
connections; edit them to use the system network or an external proxy.

## Build

Use JDK 21 and the Android SDK/NDK versions declared by the Gradle modules.
Synchronize the retained submodules before building:

```sh
git submodule update --init --recursive
./gradlew :app:assembleArm64FullDebug
```

The `full` build includes the remote desktop clients. The `terminal` build omits
the RDP/SPICE native libraries. ABI flavors are `arm64`, `x64`
and `armv7`. Native RDP/SPICE components are built from source;
see their build scripts for toolchain requirements.

## Documentation and license

See [the feature index](docs/FEATURES.md) for the retained functionality.
Historical plans and changelog entries describe earlier versions and may mention
features removed from this build.

Haven is licensed under [AGPL-3.0](LICENSE). Third-party components retain their
own licenses and notices.
