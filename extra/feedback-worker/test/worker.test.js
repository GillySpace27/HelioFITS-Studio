// node --test (Node 18 or newer). GitHub is a mocked fetch and KV a Map: nothing leaves the machine.
import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";

const REPO = "example-owner/example-private-repo";
let calls;
let env;

beforeEach(() => {
  calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url, init, body: JSON.parse(init.body) });
    return new Response(JSON.stringify({ number: 42 }), { status: 201 });
  };
  const kv = new Map();
  env = {
    GITHUB_TOKEN: "test-token-not-a-credential",
    GITHUB_REPO: REPO,
    RATE_LIMIT: { get: async (k) => kv.get(k) ?? null, put: async (k, v) => void kv.set(k, v) },
  };
});

function report(extra = {}) {
  return {
    schema: 1,
    id: "11111111-2222-4333-8444-555555555555",
    created: "2026-10-02T23:06:42Z",
    installId: "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
    userAgent: "JHV/SWHV-0.8.4.1 (test)",
    category: "bug",
    message: "The colour bar vanished, cc @someone",
    ...extra,
  };
}

function post(body, ip = "203.0.113.7") {
  return worker.fetch(new Request("https://feedback.example/", {
    method: "POST",
    headers: { "Content-Type": "application/json", "CF-Connecting-IP": ip },
    body: typeof body === "string" ? body : JSON.stringify(body),
  }), env);
}

test("a valid report becomes one issue in GITHUB_REPO, labelled from-app and its kind", async () => {
  const r = await post(report({
    replyTo: "someone@example.org",
    error: { title: "Error getting the data", message: "Could not read ~/bad.jhv", stackTrace: "java.io.IOException: x\n\tat A.b(A.java:1)" },
    system: { buildId: "0.8.4 (r1, abc)", os: "Mac OS X 26 aarch64" },
    log: "line 1\nline 2",
    session: { "org.helioviewer.jhv.state": { imageLayers: [] } },
    screenshot: { type: "image/png", width: 1600, height: 918, base64: "AAAA" },
  }));
  assert.equal(r.status, 201);
  assert.deepEqual(await r.json(), { ok: true, issue: 42 });
  assert.equal(calls.length, 1);
  const { url, init, body } = calls[0];
  assert.equal(url, `https://api.github.com/repos/${REPO}/issues`);
  assert.equal(init.headers.Authorization, "Bearer test-token-not-a-credential");
  assert.deepEqual(body.labels, ["from-app", "bug"]);
  assert.match(body.title, /^\[bug\] The colour bar vanished/);
  assert.match(body.body, /<details><summary>Log tail \(2 lines\)<\/summary>/);
  assert.match(body.body, /<details><summary>Session state<\/summary>/);
  assert.match(body.body, /<details><summary>Stack trace<\/summary>/);
  assert.match(body.body, /Screenshot: attached by the user \(1600 x 918 PNG.*omitted/);
  assert.match(body.body, /`someone@example.org`/);
  assert.ok(!/@someone/.test(body.title + body.body.replace(/`someone@example.org`/, "")), "an @mention would notify someone");
});

test("feature and question map to GitHub's default labels", async () => {
  await post(report({ category: "feature" }));
  await post(report({ category: "question" }), "203.0.113.8");
  assert.deepEqual(calls.map((c) => c.body.labels[1]), ["enhancement", "question"]);
});

test("refusals: not POST, not JSON, missing fields, bad kind, bad email, too large, not configured", async () => {
  assert.equal((await worker.fetch(new Request("https://feedback.example/"), env)).status, 405);
  assert.equal((await post("{not json")).status, 400);
  assert.equal((await post(report({ message: "  " }))).status, 400);
  assert.equal((await post(report({ category: "rant" }))).status, 400);
  assert.equal((await post(report({ installId: "me" }))).status, 400);
  assert.equal((await post(report({ replyTo: "nobody" }))).status, 400);
  assert.equal((await post(report({ log: "x".repeat(7 * 1024 * 1024) }))).status, 413);
  delete env.GITHUB_REPO;
  assert.equal((await post(report())).status, 500);
  assert.equal(calls.length, 0);
});

test("rate limit: the sixth report from one install in an hour is refused before GitHub is called", async () => {
  for (let i = 0; i < 5; i++)
    assert.equal((await post(report(), `198.51.100.${i}`)).status, 201);
  assert.equal((await post(report(), "198.51.100.99")).status, 429);
  assert.equal(calls.length, 5);
});

test("rate limit: one address is capped across installs", async () => {
  for (let i = 0; i < 20; i++)
    assert.equal((await post(report({ installId: `aaaaaaaa-bbbb-4ccc-8ddd-${String(i).padStart(12, "0")}` }))).status, 201);
  assert.equal((await post(report({ installId: "aaaaaaaa-bbbb-4ccc-8ddd-999999999999" }))).status, 429);
});

test("a huge log and session still fit GitHub's body limit, keeping the end of the log", async () => {
  const log = Array.from({ length: 20000 }, (_, i) => `2026-10-02 line ${i}`).join("\n");
  const session = { big: "y".repeat(200000) };
  assert.equal((await post(report({ log, session }))).status, 201);
  const body = calls[0].body.body;
  assert.ok(body.length <= 65536, `body is ${body.length} characters`);
  assert.match(body, /line 19999/);
  assert.match(body, /characters cut/);
});

test("GitHub failing is a 502, so the app keeps the report and retries", async () => {
  globalThis.fetch = async () => new Response("{}", { status: 503 });
  assert.equal((await post(report())).status, 502);
});
