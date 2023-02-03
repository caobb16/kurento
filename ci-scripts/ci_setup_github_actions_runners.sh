#!/usr/bin/env bash
# Checked with ShellCheck (https://www.shellcheck.net/)

# Download and configure GitHub Actions runner(s) for Kurento.
#
# To unconfigure a runner, run:
#
#     sudo ./svc.sh uninstall
#     ./config.sh remove --token "$RUNNER_TOKEN"



# Shell setup
# ===========

# Bash options for strict error checking.
set -o errexit -o errtrace -o pipefail -o nounset
shopt -s inherit_errexit 2>/dev/null || true

# Trace all commands (to stderr).
#set -o xtrace



# Settings
# ========

# Absolute path to the parent dir where runners will be configured.
ROOT_DIR="$HOME/github-runners/"

# Number of runners to instantiate.
RUNNER_COUNT="3"

# Runner label. Used as label and also as prefix for runner names.
RUNNER_LABEL="kurento"

# GitHub registration token. Generated by the "Create self-hosted runner" page.
RUNNER_TOKEN="<RunnerToken>"



# Download runner package
# =======================

sudo apt-get update && sudo apt-get install --yes wget jq

mkdir --parents "$ROOT_DIR/default/"

# Prepare GitHub Actions runner.
{
    pushd "$ROOT_DIR/default/"

    rm --force actions-runner-*.tar.gz

    wget --quiet --output-document=- "https://api.github.com/repos/actions/runner/releases/latest" \
        | jq --raw-output '.assets[].browser_download_url | select(test("linux-x64-[^-]+tar.gz$"))' \
        | wget --input-file=-

    tar -zxf actions-runner-*.tar.gz

    sudo bin/installdependencies.sh

    popd
}



# Configure runners
# =================

for RUNNER_NAME in $(printf "$RUNNER_LABEL%d " $(seq 1 $RUNNER_COUNT)); do
    cp -a "$ROOT_DIR/default/" "$ROOT_DIR/$RUNNER_NAME/"

    pushd "$ROOT_DIR/$RUNNER_NAME/"

    # https://docs.github.com/en/actions/hosting-your-own-runners/adding-self-hosted-runners
    ./config.sh \
        --unattended \
        --url "https://github.com/Kurento" \
        --token "$RUNNER_TOKEN" \
        --name "$RUNNER_NAME" \
        --labels "$RUNNER_LABEL" \
        --work "$ROOT_DIR/workspace/$RUNNER_NAME/"

    # https://docs.github.com/en/actions/hosting-your-own-runners/configuring-the-self-hosted-runner-application-as-a-service
    sudo ./svc.sh install
    sudo ./svc.sh start

    popd
done



echo "Done! All runners were started successfully."
