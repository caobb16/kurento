#!/usr/bin/env bash
set -eu -o pipefail  # Abort on errors, disallow undefined variables
IFS=$'\n\t'          # Apply word splitting only on newlines and tabs

# Install every package related to Kurento Media Server.
#
# This not only includes KMS itself and all its modules, but also all
# development and debugging packages, and the tools used to write new modules
# (module creator, CMake utils).
#
# Notes:
# - gstreamer1.5-x is needed for the "timeoverlay" GStreamer plugin,
#   used by some tests in kms-elements.
#
# Changes:
# 2018-02-08 Juan Navarro <juan.navarro@gmx.es>
# - Initial version.
# 2018-02-12
# - Moved debugging packages to their own install script.

# Check root permissions
[ "$(id -u)" -eq 0 ] || { echo "Please run as root"; exit 1; }

PACKAGES=(
  # KMS main components
  kurento-module-creator
  kms-cmake-utils
  kms-jsonrpc
  kms-jsonrpc-dev
  kms-core
  kms-core-dev
  kms-elements
  kms-elements-dev
  kms-filters
  kms-filters-dev
  kurento-media-server
  kurento-media-server-dev

  # KMS extra modules
  kms-chroma
  kms-chroma-dev
  kms-crowddetector
  kms-crowddetector-dev
  kms-datachannelexample
  kms-datachannelexample-dev
  kms-platedetector
  kms-platedetector-dev
  kms-pointerdetector
  kms-pointerdetector-dev

  # Optional but recommended packages
  openh264-gst-plugins-bad-1.5

  # [Optional] Debug symbols
  kms-jsonrpc-dbg
  kms-core-dbg
  kms-elements-dbg
  kms-filters-dbg
  kurento-media-server-dbg
  kms-chroma-dbg
  kms-crowddetector-dbg
  kms-platedetector-dbg
  kms-pointerdetector-dbg
)

apt-get update
apt-get install --no-install-recommends --yes "${PACKAGES[@]}"

echo "All packages installed successfully"

# ------------

echo ""
echo "[$0] Done."
