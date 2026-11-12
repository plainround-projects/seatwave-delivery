#!/usr/bin/env bash
# Formats and validates infra/ with the OpenTofu version the code pins, inside OpenTofu's official
# image pinned by tag and digest (checked 12 November 2026, arm64 variant present). Needs Docker
# and the public provider registry (no account, no credentials); creates nothing in any cloud.
# infra/ is mounted read-only and copied inside the container, so no .terraform folder or state is
# ever written into the project. init checks the provider against infra/live/.terraform.lock.hcl;
# the lock file the run writes (hashes for three platforms) goes to build/tofu/.
set -euo pipefail
cd "$(dirname "$0")/.."
readonly IMAGE="ghcr.io/opentofu/opentofu:1.13.9@sha256:f5aef7f2632b490e3c289a239d7974497d0583f3df6bfa35e8eb31a367ee5ec0"
command -v docker > /dev/null || { echo "Docker is not installed; nothing was run" >&2; exit 1; }
mkdir -p build/tofu
docker pull --quiet "$IMAGE" > /dev/null
docker run --rm --user "$(id -u):$(id -g)" --cap-drop ALL --security-opt no-new-privileges \
  --read-only --tmpfs /tmp:exec --env HOME=/tmp --env TF_IN_AUTOMATION=1 \
  --volume "$PWD/infra:/infra:ro" --volume "$PWD/build/tofu:/out" \
  --entrypoint /bin/sh "$IMAGE" -euc '
    cp -R /infra /tmp/infra
    echo "\$ tofu version"; tofu version | head -1
    echo "\$ tofu fmt -check -recursive infra"
    tofu fmt -check -recursive /tmp/infra && echo "exit status 0: every file is formatted"
    cd /tmp/infra/live
    echo "\$ tofu -chdir=infra/live init -backend=false"
    tofu init -backend=false -input=false -no-color | grep -E "Installing|Installed|initialized"
    echo "\$ tofu -chdir=infra/live validate"
    tofu validate -no-color
    tofu providers lock -no-color -platform=linux_amd64 -platform=linux_arm64 -platform=darwin_arm64 > /dev/null
    cp .terraform.lock.hcl /out/.terraform.lock.hcl
  '
