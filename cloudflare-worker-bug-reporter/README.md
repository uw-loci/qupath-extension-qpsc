# QPSC bug-reporter backend (Cloudflare Worker)

One Cloudflare Worker that receives in-app bug reports from the QPSC QuPath
extensions and files them as GitHub Issues. It holds a GitHub fine-grained PAT
server-side as a Worker secret, so no token is ever shipped inside an extension
JAR.

**Serves three repos from a single Worker**, chosen per request via an
allow-list in [`src/index.js`](src/index.js) (`REPOS`):

| `repo` key in payload | GitHub repo |
|---|---|
| `qpsc` (default) | `uw-loci/qupath-extension-qpsc` |
| `dl-pixel-classifier` | `uw-loci/qupath-extension-dl-pixel-classifier` |
| `qpcat` | `uw-loci/qupath-extension-cell-analysis-tools` |

Unknown `repo` values are rejected, so the Worker can only ever create issues in
those three repos even though the PAT may have wider scope.

Derived from `OtherDocuments/bug_reporting_implementation_guide.md` (TRACE
recipe), adapted to multi-repo and the QuPath/Java client.

---

## One-time setup (needs you at the keyboard)

### 1. Cloudflare account
- Sign up: https://dash.cloudflare.com/sign-up (free, no credit card for Workers).
- Skip the "add a site" prompt.
- Pick a permanent `*.workers.dev` subdomain.

### 2. GitHub fine-grained PAT
At https://github.com/settings/personal-access-tokens/new:
- Name: `QPSC bug reporter (Cloudflare Worker)`
- Expiration: 1 year (set a calendar reminder to rotate).
- Repository access -> **Only select repositories** -> add all three:
  `qupath-extension-qpsc`, `qupath-extension-dl-pixel-classifier`,
  `qupath-extension-cell-analysis-tools`.
- Repository permissions:
  - **Issues**: Read and write (required).
  - **Contents**: Read and write (only if you want screenshot uploads; can add later).
- Copy the `github_pat_...` value.

### 3. Orphan branch (only for screenshot attachments)
Run once per repo that should accept screenshots. The helper script does the
blob/tree/orphan-commit/ref dance for you:

```bash
TOKEN=github_pat_xxx ./create-orphan-branch.sh uw-loci/qupath-extension-qpsc
TOKEN=github_pat_xxx ./create-orphan-branch.sh uw-loci/qupath-extension-dl-pixel-classifier
TOKEN=github_pat_xxx ./create-orphan-branch.sh uw-loci/qupath-extension-cell-analysis-tools
```

Skip this entirely for a text-only MVP.

**Note:** inline screenshot rendering via `raw.githubusercontent.com` requires
the repo to be **public**. Private repos will show a broken image.

### 4. Deploy the Worker
A Cloudflare API token (separate from the GitHub PAT) is needed to deploy.
Generate one at https://dash.cloudflare.com/profile/api-tokens using the
"Edit Cloudflare Workers" template.

```bash
cd cloudflare-worker-bug-reporter

# temporary env file (gitignored)
echo "CLOUDFLARE_API_TOKEN=<your-cf-token>"  > .env
echo "GITHUB_PAT_FOR_WORKER=<your-github-pat>" >> .env

set -a && source .env && set +a
npx --yes wrangler@latest deploy

# store the GitHub PAT as the Worker secret (read from env, not history)
printf '%s' "$GITHUB_PAT_FOR_WORKER" | npx --yes wrangler@latest secret put GITHUB_PAT

rm .env   # optional but tidy
```

The Worker URL will be `https://qpsc-bug-reporter.<your-subdomain>.workers.dev`.
That URL goes into each extension's Java client as a constant.

---

## Smoke test

Text-only, default repo (qpsc):

```bash
curl -X POST https://qpsc-bug-reporter.<subdomain>.workers.dev \
  -H "Content-Type: application/json" \
  -H "User-Agent: bug-reporter-client" \
  -d '{"description":"Smoke test from curl. Please close this issue.","app_version":"smoke-test"}'
```

Target a specific repo with the `repo` key:

```bash
curl -X POST https://qpsc-bug-reporter.<subdomain>.workers.dev \
  -H "Content-Type: application/json" \
  -H "User-Agent: bug-reporter-client" \
  -d '{"repo":"qpcat","description":"Smoke test for QP-CAT. Please close.","app_version":"smoke-test"}'
```

Expect `{"ok":true,"issue_url":"...","issue_number":N,...}` and a real issue in
the target repo.

---

## Request payload (what the Java client sends)

```jsonc
{
  "repo": "qpsc",                  // allow-list key; omit -> defaults to qpsc
  "extension": "QPSC 0.5.0",       // free-text label shown in the issue body
  "description": "min 20 chars",   // required, 20..10000 chars
  "app_version": "0.5.0",          // shown in the issue body
  "sysinfo": "OS / Java / QuPath", // optional, wrapped in a code fence
  "artifacts": {                   // optional text artifacts -> <details> blocks
    "run_log": "....",
    "qupath_log": "...."
  },
  "screenshot": {                  // optional; needs orphan branch + Contents:write
    "content_base64": "iVBORw0K...",
    "mime_type": "image/png"
  }
}
```

Artifact keys recognized by the Worker are defined in `ARTIFACT_SECTIONS`
(`run_log`, `qupath_log`). Add more there if a client starts sending them.

---

## Maintenance
- **Rotate PAT** (~1/yr): create a new PAT, `wrangler secret put GITHUB_PAT`, revoke old.
- **Add a repo**: add an entry to `REPOS` in `src/index.js`, add the repo to the
  PAT's selected-repositories list, redeploy. Create its orphan branch if it
  needs screenshots.
- **Clean old screenshots**: `gh api -X DELETE /repos/<owner>/<repo>/contents/<file>?branch=bug-attachments`.
- **Worker URL is hard-coded in each extension.** If it ever moves, bump and ship each extension.
