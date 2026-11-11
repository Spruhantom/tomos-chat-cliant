#!/usr/bin/env bash
# TomChat Linux desktop installer
# Supports Arch Linux/Omarchy and Debian/Ubuntu.
set -Eeuo pipefail

APP_NAME="TomChat"
BIN_NAME="tomchat-client"
REPO_URL="https://github.com/Spruhantom/tomos-chat-cliant.git"
INSTALL_DIR="${HOME}/.local/bin"
DESKTOP_DIR="${HOME}/.local/share/applications"
DESKTOP_FILE="${DESKTOP_DIR}/uk.co.tomserver.TomChat.desktop"
TMP_DIR=""

log() { printf '\n[TomChat] %s\n' "$*"; }
die() { printf '\n[TomChat] ERROR: %s\n' "$*" >&2; exit 1; }
cleanup() { [[ -z "$TMP_DIR" ]] || rm -rf "$TMP_DIR"; }
trap cleanup EXIT

if [[ "${EUID}" -eq 0 ]]; then
  die "Run this installer as your normal user, not with sudo. It uses sudo only for system packages."
fi

if [[ "${1:-}" == "--uninstall" ]]; then
  rm -f "${INSTALL_DIR}/${BIN_NAME}" "$DESKTOP_FILE"
  log "Removed the user-local TomChat binary and desktop launcher."
  exit 0
elif [[ $# -gt 0 ]]; then
  die "Usage: install.sh [--uninstall]"
fi

command -v git >/dev/null 2>&1 || die "git is required. Install git and run this script again."
command -v curl >/dev/null 2>&1 || die "curl is required. Install curl and run this script again."

SUDO=""
if [[ "${EUID}" -ne 0 ]]; then
  command -v sudo >/dev/null 2>&1 || die "sudo is required to install system build dependencies."
  SUDO="sudo"
fi

if command -v pacman >/dev/null 2>&1; then
  log "Detected Arch Linux or Omarchy."
  $SUDO pacman -Sy --needed --noconfirm base-devel pkgconf gtk4 libadwaita git curl
elif command -v apt-get >/dev/null 2>&1; then
  log "Detected Debian or Ubuntu."
  $SUDO apt-get update
  $SUDO apt-get install -y build-essential pkg-config libgtk-4-dev libadwaita-1-dev git curl ca-certificates
else
  die "Unsupported distribution. Supported package managers: pacman (Arch/Omarchy) and apt-get (Debian/Ubuntu)."
fi

if ! command -v cargo >/dev/null 2>&1; then
  log "Installing Rust stable using the official rustup installer."
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
  # shellcheck disable=SC1090
  source "${HOME}/.cargo/env"
fi

command -v cargo >/dev/null 2>&1 || die "Rust installation did not provide cargo. Open a new terminal and retry."
rustup toolchain install stable --profile minimal
rustup default stable

TMP_DIR="$(mktemp -d -t tomchat-install.XXXXXX)"
log "Downloading TomChat source."
git clone --depth 1 "$REPO_URL" "$TMP_DIR/source"

log "Building TomChat in release mode. This may take several minutes."
(
  cd "$TMP_DIR/source"
  cargo build --release --locked
)

[[ -x "$TMP_DIR/source/target/release/$BIN_NAME" ]] || die "Build completed but the expected executable was not found."

mkdir -p "$INSTALL_DIR" "$DESKTOP_DIR"
install -m 0755 "$TMP_DIR/source/target/release/$BIN_NAME" "${INSTALL_DIR}/${BIN_NAME}"
cat > "$DESKTOP_FILE" <<EOF
[Desktop Entry]
Type=Application
Name=TomChat
Comment=TomChat desktop client
Exec=${INSTALL_DIR}/${BIN_NAME}
Terminal=false
Categories=Network;InstantMessaging;
StartupWMClass=uk.co.tomserver.TomChat
EOF

log "Installation completed."
log "Executable: ${INSTALL_DIR}/${BIN_NAME}"
if [[ ":${PATH}:" != *":${INSTALL_DIR}:"* ]]; then
  log "Add ~/.local/bin to PATH, or launch using: ${INSTALL_DIR}/${BIN_NAME}"
fi
log "Desktop launcher: ${DESKTOP_FILE}"
log "To uninstall: bash install.sh --uninstall (from a downloaded copy), or remove the binary and desktop file shown above."
log "Note: this installs the Linux desktop client currently present in this repository; it does not install the Android app."
