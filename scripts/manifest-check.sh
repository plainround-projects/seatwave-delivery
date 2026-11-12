#!/usr/bin/env bash
# Validates delivery/rollout.yaml with kubeconform in strict mode against the vendored Argo Rollouts
# schemas, then checks that the validation is real: a copy with setWeight misspelled must be
# refused. Exits non-zero if the manifest is invalid or if the mutant is accepted.
# Reads only; writes one temporary folder and removes it on every path.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kubeconform > /dev/null || { echo "kubeconform is not installed (0.8.0 was used)" >&2; exit 1; }
readonly SCHEMAS='delivery/schemas/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
scratch="$(mktemp -d "${TMPDIR:-/tmp}/manifest-check.XXXXXX")"
trap 'rm -rf "$scratch"' EXIT
# kubeconform reads only files named .yaml or .yml, so the mutant keeps the extension.
mutant="$scratch/rollout-mutant.yaml"

echo "\$ kubeconform -strict -summary -schema-location <vendored> delivery/rollout.yaml"
kubeconform -strict -summary -output text -schema-location "$SCHEMAS" delivery/rollout.yaml

sed 's/setWeight: 5$/setWieght: 5/' delivery/rollout.yaml > "$mutant"
cmp -s delivery/rollout.yaml "$mutant" && { echo "the mutant is identical: setWeight: 5 not found" >&2; exit 1; }
echo "\$ kubeconform ... rollout-mutant.yaml   (setWeight misspelled)"
if kubeconform -strict -summary -output text -schema-location "$SCHEMAS" "$mutant" 2>&1 | sed -e "s#$scratch/##" -e "s#file://$PWD/##"; then
  echo "the misspelled field was accepted: the check proves nothing" >&2
  exit 1
fi
echo "mutant refused, as it must be"
