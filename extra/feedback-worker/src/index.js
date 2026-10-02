// HelioFITS Studio feedback Worker: takes the JSON report Help > Send Feedback POSTs and opens a
// GitHub issue with it, so nobody needs a GitHub account to report a problem.
//
// Configuration (nothing is hard-coded):
//   GITHUB_TOKEN  Worker secret: a fine-grained token limited to Issues on one repository.
//   GITHUB_REPO   variable: "owner/name" of that repository (keep it private; see README.md).
//   RATE_LIMIT    KV namespace binding holding the rate-limit counters.
//   LIMIT_PER_INSTALL, LIMIT_PER_IP  optional variables: reports per hour (defaults 5 and 20).
//
// The payload is what src/org/helioviewer/jhv/io/FeedbackReport.java builds (schema 1).

const MAX_BYTES = 6 * 1024 * 1024; // a 1600 px PNG screenshot in base64 plus the log fits well inside
const MAX_MESSAGE = 20000;
const BODY_BUDGET = 60000; // GitHub's issue body limit is 65,536 characters (from memory; not checked here)
const CATEGORIES = { bug: "bug", feature: "enhancement", question: "question" }; // GitHub's default labels
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

export default {
  async fetch(request, env) {
    if (request.method !== "POST")
      return reply(405, "POST a report");
    if (!env.GITHUB_TOKEN || !/^[\w.-]+\/[\w.-]+$/.test(env.GITHUB_REPO || "") || !env.RATE_LIMIT)
      return reply(500, "not configured");
    if (Number(request.headers.get("content-length") || 0) > MAX_BYTES)
      return reply(413, "report too large");
    const text = await request.text();
    if (text.length > MAX_BYTES)
      return reply(413, "report too large");

    let report;
    try {
      report = JSON.parse(text);
    } catch {
      return reply(400, "not JSON");
    }
    const problem = validate(report);
    if (problem)
      return reply(400, problem);

    const ip = request.headers.get("cf-connecting-ip") || "unknown";
    if (await overLimit(env, report.installId, ip))
      return reply(429, "too many reports this hour; the app keeps it and tries again at its next launch");

    const issue = toIssue(report);
    const response = await fetch(`https://api.github.com/repos/${env.GITHUB_REPO}/issues`, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${env.GITHUB_TOKEN}`,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "heliofits-studio-feedback-worker",
        "Content-Type": "application/json",
      },
      body: JSON.stringify(issue),
    });
    if (!response.ok) {
      console.log(`GitHub refused report ${report.id}: HTTP ${response.status}`); // status only, never the token
      return reply(502, "could not file the report; the app keeps it and tries again");
    }
    const created = await response.json();
    return new Response(JSON.stringify({ ok: true, issue: created.number }), {
      status: 201,
      headers: { "Content-Type": "application/json" },
    });
  },
};

function reply(status, error) {
  return new Response(JSON.stringify({ ok: false, error }), { status, headers: { "Content-Type": "application/json" } });
}

/** The reason the report is refused, or "" when it is acceptable. */
export function validate(r) {
  if (typeof r !== "object" || r === null || Array.isArray(r)) return "not an object";
  if (r.schema !== 1) return "schema must be 1";
  if (typeof r.id !== "string" || !UUID.test(r.id)) return "id must be a UUID";
  if (typeof r.installId !== "string" || !UUID.test(r.installId)) return "installId must be a UUID";
  if (!(r.category in CATEGORIES)) return "category must be bug, feature or question";
  if (typeof r.message !== "string" || !r.message.trim()) return "message is required";
  if (r.message.length > MAX_MESSAGE) return "message too long";
  if (typeof r.created !== "string" || r.created.length > 40) return "created must be a time";
  if (r.replyTo !== undefined && (typeof r.replyTo !== "string" || r.replyTo.length > 254 || !EMAIL.test(r.replyTo)))
    return "replyTo must be an email address";
  for (const key of ["userAgent", "log"])
    if (r[key] !== undefined && typeof r[key] !== "string") return `${key} must be text`;
  for (const key of ["error", "system", "session", "screenshot"])
    if (r[key] !== undefined && (typeof r[key] !== "object" || r[key] === null || Array.isArray(r[key])))
      return `${key} must be an object`;
  return "";
}

/**
 * KV counters, one per install id and one per IP address (hashed, so no address is stored) per
 * clock hour, each expiring after two hours. KV is eventually consistent, so a burst spread over
 * several Cloudflare locations can pass a few extra reports; that is accepted for a feedback form.
 */
async function overLimit(env, installId, ip) {
  const hour = Math.floor(Date.now() / 3600000);
  const ipHash = await sha256(ip);
  const counters = [
    [`install:${installId}:${hour}`, Number(env.LIMIT_PER_INSTALL) || 5],
    [`ip:${ipHash}:${hour}`, Number(env.LIMIT_PER_IP) || 20],
  ];
  const counts = await Promise.all(counters.map(([key]) => env.RATE_LIMIT.get(key)));
  if (counters.some(([, limit], i) => Number(counts[i] || 0) >= limit))
    return true;
  await Promise.all(counters.map(([key], i) =>
    env.RATE_LIMIT.put(key, String(Number(counts[i] || 0) + 1), { expirationTtl: 7200 })));
  return false;
}

async function sha256(s) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** No @mention in the user's words may notify anyone on GitHub. */
function quiet(s) {
  return String(s).replace(/@(?=[A-Za-z0-9_-])/g, "@\u200b");
}

/** A code fence longer than any run of backticks inside, so content cannot close it early. */
function fenced(s, lang = "") {
  const runs = String(s).match(/`+/g) || [];
  const fence = "`".repeat(Math.max(3, ...runs.map((r) => r.length + 1)));
  return `${fence}${lang}\n${s}\n${fence}`;
}

function details(summary, s, lang = "") {
  return `<details><summary>${summary}</summary>\n\n${fenced(s, lang)}\n\n</details>`;
}

/** The issue: title, labels and a body that stays under GitHub's limit, cutting the log's head and the session's tail first. */
export function toIssue(r) {
  const first = r.message.trim().split("\n")[0].slice(0, 80);
  const parts = [
    `**Kind:** ${r.category}  `,
    `**Reply to:** ${r.replyTo ? "`" + r.replyTo + "`" : "not given"}  `,
    `**Build:** ${r.system ? quiet(r.system.buildId) : "not sent"}  `,
    `**Install:** \`${r.installId.slice(0, 8)}\`, report \`${r.id}\`, ${r.created}`,
    "",
    "### Message",
    "",
    quiet(r.message).split("\n").map((l) => "> " + l).join("\n"),
  ];
  if (r.error) {
    parts.push("", `### Error shown: ${quiet(String(r.error.title || "").slice(0, 200))}`, "", fenced(String(r.error.message || "").slice(0, 4000)));
    if (r.error.stackTrace)
      parts.push("", details("Stack trace", String(r.error.stackTrace).slice(0, 8000)));
  }
  if (r.system) {
    parts.push("", "### System", "", "| | |", "|---|---|");
    for (const [k, v] of Object.entries(r.system))
      parts.push(`| ${k} | ${quiet(String(v)).replace(/\|/g, "\\|")} |`);
  }
  if (r.screenshot) {
    const kb = Math.round(String(r.screenshot.base64 || "").length * 3 / 4 / 1024);
    parts.push("", `Screenshot: attached by the user (${r.screenshot.width} x ${r.screenshot.height} PNG, ${kb} KB) but omitted; this Worker stores no files.`);
  }
  let body = parts.join("\n");

  // What is left goes to the log and the session, half each at most; whatever one does not use, the other may.
  const log = r.log === undefined ? null : r.log;
  const session = r.session === undefined ? null : JSON.stringify(r.session, null, 2);
  let room = BODY_BUDGET - body.length - 400;
  const fit = (s, share, keepEnd) => {
    if (s.length <= share) return s;
    const cut = Math.max(0, share - 80);
    return keepEnd ? `(first ${s.length - cut} characters cut)\n` + s.slice(s.length - cut)
                   : s.slice(0, cut) + `\n(last ${s.length - cut} characters cut)`;
  };
  if (log !== null && session !== null) {
    const logPart = fit(log, Math.max(room / 2, room - session.length), true);
    room -= logPart.length;
    body += "\n\n" + details(`Log tail (${log.split("\n").length} lines)`, logPart);
    body += "\n\n" + details("Session state", fit(session, room, false), "json");
  } else if (log !== null) {
    body += "\n\n" + details(`Log tail (${log.split("\n").length} lines)`, fit(log, room, true));
  } else if (session !== null) {
    body += "\n\n" + details("Session state", fit(session, room, false), "json");
  }
  return {
    title: `[${r.category}] ${quiet(first)}`,
    body,
    labels: ["from-app", CATEGORIES[r.category]],
  };
}
