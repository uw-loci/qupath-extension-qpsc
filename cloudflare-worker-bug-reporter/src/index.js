// QPSC in-app bug reporter -- Cloudflare Worker.
//
// One Worker, many repos. The client picks a target by sending `repo` set to
// one of the keys in REPOS below. Unknown keys are rejected, so this Worker
// can only ever create issues in the three allow-listed repositories -- it
// cannot be coerced into spamming an arbitrary repo even though the PAT it
// holds may have wider access.
//
// Holds a GitHub fine-grained PAT as the Worker secret GITHUB_PAT (set via
// `wrangler secret put GITHUB_PAT`). The PAT must have Issues:write on every
// allow-listed repo, plus Contents:write on any repo that accepts screenshots.
//
// ASCII-only on purpose: issue bodies flow back into a Windows/cp1252 toolchain.

const REPOS = {
  "qpsc":                "uw-loci/qupath-extension-qpsc",
  "dl-pixel-classifier": "uw-loci/qupath-extension-dl-pixel-classifier",
  "qpcat":               "uw-loci/qupath-extension-cell-analysis-tools",
};
const DEFAULT_REPO_KEY = "qpsc";

// Text artifacts the client may attach. Each becomes a collapsed <details>
// block in the issue body. Keys here must match the keys the app sends.
const ARTIFACT_SECTIONS = [
  { key: "run_log",    label: "QPSC session log",  lang: "", max: 40000 },
  { key: "server_log", label: "Python server log", lang: "", max: 20000 },
  { key: "qupath_log", label: "QuPath log",        lang: "", max: 12000 },
];

const MAX_BODY_CHARS = 64000;                      // GitHub issue body cap is 65,536
const MAX_SCREENSHOT_B64_CHARS = 4 * 1024 * 1024;  // ~3 MB binary
const ATTACHMENTS_BRANCH = "bug-attachments";

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { headers: corsHeaders() });
    if (request.method !== "POST") return jsonError("Method not allowed.", 405);

    let payload;
    try { payload = await request.json(); } catch { return jsonError("Invalid JSON.", 400); }

    // Resolve target repo from the allow-list. Accept either a short key
    // ("qpsc") or the full "owner/name" slug, but only if it is allow-listed.
    const repoKey = (payload.repo || DEFAULT_REPO_KEY).toString().trim();
    const repo = resolveRepo(repoKey);
    if (!repo) return jsonError(`Unknown repo '${repoKey}'.`, 400);

    const description = (payload.description || "").toString().trim();
    const sysinfo = (payload.sysinfo || "").toString().trim();
    const appVersion = (payload.app_version || "unknown").toString().trim();
    const extension = (payload.extension || repoKey).toString().trim();
    const artifacts = (payload.artifacts && typeof payload.artifacts === "object") ? payload.artifacts : {};
    const screenshot = (payload.screenshot && typeof payload.screenshot === "object") ? payload.screenshot : null;

    if (description.length < 20) return jsonError("Description must be at least 20 characters.", 400);
    if (description.length > 10000) return jsonError("Description must be at most 10000 characters.", 400);
    if (screenshot && typeof screenshot.content_base64 === "string" &&
        screenshot.content_base64.length > MAX_SCREENSHOT_B64_CHARS) {
      return jsonError("Screenshot exceeds size cap (~3 MB binary).", 400);
    }

    // Upload screenshot first so we can inline its URL in the body. Upload
    // failure does NOT block issue creation -- the body gets a transparency note.
    let screenshotUrl = null;
    let screenshotError = null;
    if (screenshot && typeof screenshot.content_base64 === "string" && screenshot.content_base64.length > 0) {
      const result = await uploadScreenshot(env, repo, screenshot);
      if (result.url) screenshotUrl = result.url;
      else { screenshotError = result.error; console.error(`Screenshot upload failed: ${screenshotError}`); }
    }

    const title = `[bug] ${description.split("\n")[0].slice(0, 80)}`;
    const bodyLines = ["**Description**", description, "",
                       `**Extension:** ${extension}`, `**App version:** ${appVersion}`];
    if (screenshotUrl) bodyLines.push("", "**Screenshot**", "", `![Screenshot](${screenshotUrl})`);
    else if (screenshotError) bodyLines.push("", `_(Screenshot upload failed: ${screenshotError}.)_`);
    if (sysinfo) bodyLines.push("", "**System info**", "```", sysinfo, "```");

    for (const { key, label, lang, max } of ARTIFACT_SECTIONS) {
      let content = (artifacts[key] || "").toString();
      if (!content) continue;
      if (content.length > max) content = `... [truncated to last ${max} chars]\n` + content.slice(-max);
      bodyLines.push("", `<details><summary>${label}</summary>`, "", "```" + lang, content, "```", "</details>");
    }
    bodyLines.push("", "_Submitted via in-app bug reporter._");

    let body = bodyLines.join("\n");
    if (body.length > MAX_BODY_CHARS) body = body.slice(0, MAX_BODY_CHARS) + "\n\n... [body truncated]";

    const ghResp = await fetch(`https://api.github.com/repos/${repo}/issues`, {
      method: "POST",
      headers: githubHeaders(env),
      body: JSON.stringify({ title, body }),
    });
    if (!ghResp.ok) {
      const text = await ghResp.text();
      console.error(`GitHub error ${ghResp.status}: ${text.slice(0, 200)}`);
      return jsonError(`GitHub returned ${ghResp.status}.`, 502);
    }
    const issue = await ghResp.json();
    return new Response(JSON.stringify({
      ok: true, issue_url: issue.html_url, issue_number: issue.number,
      screenshot_url: screenshotUrl, screenshot_error: screenshotError,
    }), { status: 200, headers: jsonHeaders() });
  },
};

function resolveRepo(keyOrSlug) {
  if (REPOS[keyOrSlug]) return REPOS[keyOrSlug];
  // Allow a full slug only if it is one of the allow-listed values.
  for (const slug of Object.values(REPOS)) {
    if (slug === keyOrSlug) return slug;
  }
  return null;
}

async function uploadScreenshot(env, repo, screenshot) {
  const filename = `${crypto.randomUUID()}.png`;
  const resp = await fetch(`https://api.github.com/repos/${repo}/contents/${filename}`, {
    method: "PUT",
    headers: githubHeaders(env),
    body: JSON.stringify({
      message: `Upload screenshot ${filename}`,
      content: screenshot.content_base64,
      branch: ATTACHMENTS_BRANCH,
    }),
  });
  if (!resp.ok) {
    const text = await resp.text();
    return { error: `HTTP ${resp.status}: ${text.slice(0, 200)}` };
  }
  return { url: `https://raw.githubusercontent.com/${repo}/${ATTACHMENTS_BRANCH}/${filename}` };
}

function githubHeaders(env) {
  return {
    "Authorization": `Bearer ${env.GITHUB_PAT}`,
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "qpsc-bug-reporter-worker",
    "Content-Type": "application/json",
  };
}
function jsonError(message, status) { return new Response(JSON.stringify({ ok: false, error: message }), { status, headers: jsonHeaders() }); }
function jsonHeaders() { return { "Content-Type": "application/json", ...corsHeaders() }; }
function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
  };
}
