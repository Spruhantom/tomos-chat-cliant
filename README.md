# TomChat Desktop Client

Native Linux desktop client for Ubuntu, Fedora, and Arch Linux, built with Rust, GTK4, and Libadwaita.

## Stage 2

The current GUI provides server address, username, and password fields and sends a login request to `POST /api/v1/auth/login`. It displays the authenticated username after a successful response.

The session token is intentionally held only in memory and discarded after the demonstration login. OS keyring integration and authenticated chat views are the next client tasks.

## Development

Install GTK development dependencies, then:

```bash
cargo fmt --all -- --check
cargo check
cargo run
```

On Debian/Ubuntu development hosts:

```bash
sudo apt install build-essential pkg-config libgtk-4-dev libadwaita-1-dev
```

Remote server connections must use HTTPS. Plain HTTP is allowed only for the exact `localhost` hostname or a loopback IP address (`127.0.0.1` or `::1`). Credentials, URL paths, query strings, fragments and redirects are rejected. Login requests have a 20-second overall timeout and a 5-second connection timeout. All redirects are rejected so the password-bearing request cannot be forwarded to another host.

## Not yet implemented

Registration UI, token/keyring persistence, conversations, WebSocket messaging, encryption, notifications, calls, and package builds.
