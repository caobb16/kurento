#!/usr/bin/env bash
set -eu -o pipefail  # Abort on errors, disallow undefined variables
IFS=$'\n\t'          # Apply word splitting only on newlines and tabs

# Uninstall every packge related to KMS and its development.
#
# Sources:
# - Installation scripts.
# - Installing KMS and running `aptitude search '~mkurento'`.
#
# Changes:
# 2017-10-03 Juan Navarro <juan.navarro@gmx.es>
# - Initial version.
# 2018-02-02
# - Use a Bash Array to define all packages.

# Check root permissions
[ "$(id -u)" -eq 0 ] || { echo "Please run as root"; exit 1; }

PACKAGES=(
  # KMS main components + extra modules
  '^(kms|kurento).*'

  # Kurento external libraries
  ffmpeg
  '^gir1.2-gst.*1.5'
  gir1.2-nice-0.1
  '^(lib)?gstreamer.*1.5.*'
  '^lib(nice|s3-2|srtp|usrsctp).*'
  '^srtp-.*'
  '^openh264(-gst-plugins-bad-1.5)?'
  '^openwebrtc-gst-plugins.*'

  # System development libraries
  '^libboost-?(filesystem|log|program-options|regex|system|test|thread)?-dev'
  '^lib(glib2.0|glibmm-2.4|opencv|sigc++-2.0|soup2.4|ssl|tesseract|vpx)-dev'
  uuid-dev
)

# Run a loop over all package names and uninstall them.
# apt-get is *stupid*, and won't allow unexisting names, so we cannot do it
# the fancy way and run a single `apt-get` command.
for PACKAGE in "${PACKAGES[@]}"; do
  apt-get purge --auto-remove --yes "$PACKAGE" || { echo "Skip unexisting"; }
done

echo "All packages uninstalled successfully"

# ------------

echo ""
echo "[$0] Done."
