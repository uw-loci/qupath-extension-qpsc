#!/usr/bin/env bash
# Create the `bug-attachments` orphan branch in a target repo.
# Only needed if that repo will accept screenshot attachments.
#
# Usage:
#   TOKEN=github_pat_xxx ./create-orphan-branch.sh uw-loci/qupath-extension-qpsc
#
# Verify afterwards:
#   git ls-remote https://github.com/<owner>/<repo> bug-attachments
set -euo pipefail

OWNER_REPO="${1:?usage: TOKEN=<pat> $0 <owner>/<repo>}"
: "${TOKEN:?set TOKEN to a fine-grained PAT with Contents:write on $OWNER_REPO}"

README='# Bug attachment storage\n\nThis orphan branch holds image attachments from the in-app bug reporter.'

echo "Creating bug-attachments orphan branch in $OWNER_REPO ..."

BLOB_SHA=$(curl -sS -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$OWNER_REPO/git/blobs" \
  -d "$(python3 -c "import json,sys; print(json.dumps({'content': sys.argv[1], 'encoding':'utf-8'}))" "$README")" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['sha'])")

TREE_SHA=$(curl -sS -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$OWNER_REPO/git/trees" \
  -d "{\"tree\":[{\"path\":\"README.md\",\"mode\":\"100644\",\"type\":\"blob\",\"sha\":\"$BLOB_SHA\"}]}" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['sha'])")

# parents:[] is what makes the commit orphan (separate history).
COMMIT_SHA=$(curl -sS -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$OWNER_REPO/git/commits" \
  -d "{\"message\":\"Initialize bug-attachments orphan branch\",\"tree\":\"$TREE_SHA\",\"parents\":[]}" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['sha'])")

curl -sS -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$OWNER_REPO/git/refs" \
  -d "{\"ref\":\"refs/heads/bug-attachments\",\"sha\":\"$COMMIT_SHA\"}" >/dev/null

echo "Done. Verify: git ls-remote https://github.com/$OWNER_REPO bug-attachments"
