---
layout: default
title: Desktops
---

# Desktops

Remote desktop clients: VNC, RDP, SPICE and GLKVM / PiKVM.

## Desktop (VNC)

Remote desktop viewer with RFB 3.8 protocol support. Pinch-to-zoom, two-finger pan and scroll, single-finger drag for window management, soft keyboard with X11 KeySym mapping. Fullscreen mode with NoMachine-style corner hotspot for session controls. Connect directly or tunnel through SSH. Supports Raw, CopyRect, RRE, Hextile, and ZLib encodings. Security types: **None**, classic **VncAuth** (DES), and **VeNCrypt** (security type 19) with TLSPlain/X509Plain/TLSVnc/X509Vnc/TLSNone/X509None/Plain sub-types — connects to wayvnc, TigerVNC, libvncserver, x11vnc and other servers that require a username alongside a password, wrapping the socket in TLS after sub-type negotiation.

## Desktop (RDP)

Remote Desktop Protocol client built on [IronRDP](https://github.com/Devolutions/IronRDP) via UniFFI Kotlin bindings. Connects to Windows Remote Desktop, xrdp (Linux), and GNOME Remote Desktop. **EGFX (MS-RDPEGFX) graphics-pipeline support** — ClearCodec and RemoteFX Progressive decoders light up the fast graphics path on modern Windows (verified against Windows Server 2025), with a slow-path fallback for servers that don't negotiate it. Pinch-to-zoom, pan, keyboard with scancode mapping, mouse input. SSH tunnel support with auto-connect through saved SSH profiles. Saved connection profiles with optional stored password.

## Desktop (SPICE)

[SPICE](https://www.spice-space.org/) client for QEMU/KVM and libvirt virtual machines, built on a vendored Rust SPICE core exposed through UniFFI Kotlin bindings (the same approach as the RDP client). The display channel decodes the SPICE image codecs — raw bitmap, **LZ**, **GLZ** and **ZLIB-GLZ** dictionary compression, and **QUIC** (SFALIC), plus the image cache — alongside server draw operations, hardware-cursor shape and position, and multiple display surfaces. Input covers the keyboard (scancodes), an absolute pointer, mouse buttons and the scroll wheel. The viewer shares the VNC/RDP gestures: two-finger pinch-zoom and pan, and a soft keyboard. SSH tunnelling is supported — the SPICE core dials its own TCP socket, so Haven opens a local `-L` forward through a saved SSH profile and points the client at `127.0.0.1`. Decoding is verified pixel-correct against QEMU and Windows Server 2025 guests (the GLZ and QUIC paths). Configure SPICE connections in the Connections tab.

## Desktop (GLKVM / PiKVM)

Out-of-band hardware KVM client for GL.iNet GLKVM units (a PiKVM `kvmd` fork) over HTTPS. Opens as a desktop tab alongside VNC, RDP, and SPICE: pulls full-resolution HDMI snapshots from `/streamer/snapshot` and sends touches, drags, scroll wheel notches, and keyboard input as USB HID events (`/api/hid/events/*`), so a phone can control a target machine with no software agent installed on the target.

- **Trust-on-first-use TLS & tunnel routing** — pins the unit's self-signed TLS leaf certificate on first connect (stored in the shared TOFU store) and routes over any per-profile SSH tunnel or SOCKS proxy.
- **Target keyboard layout table** — maps ASCII characters to physical `KeyboardEvent.code` key positions + `Shift` for UK or US target OS layouts (preventing `kvmd`'s US-only `/api/hid/print` printer from swapping `"`, `@`, `#`, and `\` on UK targets), falling back to `hid/print` for non-ASCII characters.
- **Automatic horizontal pillarbox calibration** — detects symmetric black pillarbox bars on decoded frames (such as a 16:10 laptop display mirrored inside a 16:9 `2560x1440` HDMI signal) and maps the USB HID absolute digitizer across the active desktop bounds while keeping full-bleed 16:9 displays unscaled.
