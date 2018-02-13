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
  kurento-module-creator-4.0
  kms-cmake-utils
  kms-jsonrpc-1.0
  kms-jsonrpc-1.0-dev
  kms-core-6.0
  kms-core-6.0-dev
  kms-elements-6.0
  kms-elements-6.0-dev
  kms-filters-6.0
  kms-filters-6.0-dev
  kurento-media-server-6.0
  kurento-media-server-6.0-dev

  # KMS extra modules
  kms-chroma-6.0
  kms-chroma-6.0-dev
  kms-crowddetector-6.0
  kms-crowddetector-6.0-dev
  kms-datachannelexample
  kms-datachannelexample-dev
  kms-platedetector-6.0
  kms-platedetector-6.0-dev
  kms-pointerdetector-6.0
  kms-pointerdetector-6.0-dev

  # Optional but recommended packages
  openh264-gst-plugins-bad-1.5
)

apt-get update
apt-get install --no-install-recommends --yes "${PACKAGES[@]}"

echo "All packages installed successfully"

# ------------

echo ""
echo "[$0] Done."
