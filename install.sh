#!/usr/bin/env bash
# TomChat Linux GTK4/libadwaita installer.
# Supports Arch Linux/Omarchy and Debian/Ubuntu.
set -Eeuo pipefail

readonly APP_NAME="TomChat"
readonly BIN_NAME="tomchat-client"
readonly REPO_URL="https://github.com/Spruhantom/tomos-chat-cliant.git"
readonly INSTALL_DIR="${HOME}/.local/bin"
readonly DESKTOP_DIR="${HOME}/.local/share/applications"
readonly DESKTOP_FILE="${DESKTOP_DIR}/uk.co.tomserver.TomChat.desktop"
TMP_DIR=""

log() { printf '\n[TomChat] %s\n' "$*"; }
die() { printf '\n[TomChat] ERROR: %s\n' "$*" >&2; exit 1; }
cleanup() { [[ -z "$TMP_DIR" ]] || rm -rf "$TMP_DIR"; }
trap cleanup EXIT

[[ "${EUID}" -ne 0 ]] || die "Run as your normal user, not with sudo. The installer uses sudo for system packages."
[[ "$#" -le 1 ]] || die "Usage: install.sh [--uninstall]"

if [[ "${1:-}" == "--uninstall" ]]; then
  rm -f "${INSTALL_DIR}/${BIN_NAME}" "$DESKTOP_FILE"
  log "Removed the user-local executable and desktop launcher. User data and keyring entries were not touched."
  exit 0
elif [[ "$#" -ne 0 ]]; then
  die "Unknown option: $1"
fi

for command_name in git curl sudo; do
  command -v "$command_name" >/dev/null 2>&1 || die "$command_name is required."
done

if command -v pacman >/dev/null 2>&1; then
  log "Installing Arch Linux/Omarchy build and runtime dependencies."
  sudo pacman -Syu --needed --noconfirm base-devel pkgconf gtk4 libadwaita libsecret git curl ca-certificates
elif command -v apt-get >/dev/null 2>&1; then
  log "Installing Debian/Ubuntu build and runtime dependencies."
  sudo apt-get update
  sudo apt-get install -y build-essential pkg-config libgtk-4-dev libadwaita-1-dev libsecret-tools git curl ca-certificates
else
  die "Unsupported distribution. Supported package managers: pacman (Arch/Omarchy) and apt-get (Debian/Ubuntu)."
fi

if ! command -v cargo >/dev/null 2>&1; then
  log "Installing the Rust stable toolchain for this user."
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
  # shellcheck disable=SC1090
  source "${HOME}/.cargo/env"
fi

command -v cargo >/dev/null 2>&1 || die "Rust/Cargo is unavailable after installation."
if command -v rustup >/dev/null 2>&1; then
  rustup toolchain install stable --profile minimal
  rustup default stable
fi

TMP_DIR="$(mktemp -d -t tomchat-install.XXXXXX)"
log "Fetching the current main branch."
git clone --depth 1 --branch main "$REPO_URL" "$TMP_DIR/source"

log "Building the Linux desktop client in release mode."
(
  cd "$TMP_DIR/source"
  cargo build --release --locked
)

[[ -x "$TMP_DIR/source/target/release/$BIN_NAME" ]] || die "Build finished but $BIN_NAME was not produced."

mkdir -p "$INSTALL_DIR" "$DESKTOP_DIR"
install -m 0755 "$TMP_DIR/source/target/release/$BIN_NAME" "${INSTALL_DIR}/${BIN_NAME}"
cat > "$DESKTOP_FILE" <<EOF
[Desktop Entry]
Type=Application
Name=${APP_NAME}
Comment=Decentralised peer-to-peer messaging
Exec=${INSTALL_DIR}/${BIN_NAME}
Terminal=false
Categories=Network;InstantMessaging;
StartupWMClass=uk.co.tomserver.TomChat
EOF

log "Installation complete."
log "Launch with: ${INSTALL_DIR}/${BIN_NAME}"
log "Desktop launcher installed at: ${DESKTOP_FILE}"
if [[ ":${PATH}:" != *":${INSTALL_DIR}:"* ]]; then
  log "Add ~/.local/bin to PATH to launch using: ${BIN_NAME}"
fi
log "Uninstall with: curl -fsSL https://raw.githubusercontent.com/Spruhantom/tomos-chat-cliant/main/install.sh | bash -s -- --uninstall"
log "This script installs only the Linux client. Android APKs are distributed separately through GitHub Releases."
