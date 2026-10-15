# Feedback Worker

Help > Send Feedback... and the Report this... button on error dialogs build a JSON report
(`src/org/helioviewer/jhv/io/FeedbackReport.java`) and POST it here. This Cloudflare Worker checks
it and opens a GitHub issue with it, so a user can report a problem without an account and
without finding the issue tracker. It is written and tested locally; it has not been deployed.

Until it is live, reports reach Gilly by email. The dialog's button reads Send by Email...: it
saves the report in `~/HFStudio/Outbox/` (and any screenshot beside it as a `.png`), opens a draft
to gilly@nwra.com in the user's mail program with the subject `[HFS <version>] <kind>: <first line>`
and the message, version, OS, report id and the top of the stack trace in the body, and shows the
saved file in Finder or the file manager with one sentence asking the user to attach it. Copy to
Clipboard stays as the fallback.

Once the app knows an endpoint (compiled in, the `feedback.endpoint` setting, or the endpoint file
below), Send POSTs the report here, Email Instead... becomes the second button, and the app sends
whatever its outbox holds at each launch, moving each sent report to `Outbox/sent/`. Reports that
already went by email are still in the outbox and will be sent too; the report id in the email and in
the issue tells the two apart. Nothing is deleted.

## The endpoint file (a data contract)

When neither the build nor the setting names an endpoint, the app (0.8.6 and later) reads
`https://gilly.space/hfstudio/feedback-endpoint.txt` once per launch, in the background, with a
5 second timeout, and caches the answer for that launch. The file holds one line: the Worker's URL.
The app takes it only if it is `https://`, has no user name and no port, and its host ends with
`.workers.dev` or is `gilly.space` or ends with `.gilly.space`. Anything else (no file, a 404 page,
two lines, plain `http`, another host) means "no endpoint", and reports keep going by email.

Released builds read that exact address, so it is append-only like the other data contracts: never
move or rename the file, and keep it a single URL line. To stop reports reaching the Worker, empty the
file (the app then falls back to email) rather than deleting it. The file lives in the site repository
(`GillySpace27/GillySpace27.github.io`, path `hfstudio/feedback-endpoint.txt`); it does not exist yet.

## What the Worker does

- Accepts a POST of at most 6 MB and refuses anything that is not a schema 1 report: a UUID id and
  install id, a kind of `bug`, `feature` or `question`, a message of at most 20,000 characters, and
  a reply-to address only if it looks like one.
- Rate-limits with KV counters per clock hour: 5 reports per install id and 20 per IP address. The
  address is stored only as a SHA-256 hash, and each counter expires after two hours. KV is
  eventually consistent, so a burst spread across several Cloudflare locations can let a few
  extra reports through; we accept that for a feedback form. An over-limit report gets 429 and
  stays in the user's outbox for the next launch.
- Opens one issue in `GITHUB_REPO`, titled `[kind] first line of the message`, labelled
  `from-app` and `bug`, `enhancement` or `question`. The log tail, the stack trace and the session
  state go in collapsed `<details>` blocks. The body is kept under GitHub's size limit (65,536
  characters as we understand it; not checked against GitHub's documentation here), cutting the
  start of the log and the end of the session first. An `@name` in the user's text is broken with
  a zero-width space so that it notifies nobody.
- Does not store the screenshot. The issue says one was attached, with its size, and that it was
  omitted. Keeping screenshots needs somewhere to put them (an R2 bucket, or commits to the
  repository through the contents API with a wider token), and that is a separate decision.
- Answers 502 when GitHub refuses the issue, so the app keeps the report and tries again later.

## Test

```sh
cd extra/feedback-worker
node --test        # Node 18 or newer; GitHub is a mocked fetch and KV a Map
```

## Going live (Gilly's steps; nothing here has been run against an account)

1. Choose the repository that receives the issues and make it **private**. Logs and session files
   can carry file names, data paths and, if the user gives one, an email address; the app writes
   the home folder as `~`, which hides the user name but not the rest. Do not name the repository
   `HFStudio` or `PUNCHStudio` (CLAUDE.md: that would end GitHub's forwarding that old update
   checks rely on). Create a label named `from-app` in it once; new repositories already have
   `bug`, `enhancement` and `question`. Whether GitHub creates a missing label by itself was not
   checked.
2. Create a fine-grained personal access token (GitHub > Settings > Developer settings >
   Fine-grained tokens) with access to that one repository only and one permission: Issues, read
   and write. Give it an expiry date and a note on where it lives.
3. From this folder, log in and create the rate-limit store:
   `npx wrangler login`, then `npx wrangler kv namespace create RATE_LIMIT`, and paste the id it
   prints into `wrangler.toml` in place of `REPLACE_WITH_KV_NAMESPACE_ID`.
4. Store the token as a secret (it is prompted for, never typed on the command line):
   `npx wrangler secret put GITHUB_TOKEN`.
5. Set `GITHUB_REPO = "owner/name"` under `[vars]` in `wrangler.toml`.
6. Deploy: `npx wrangler deploy`. It prints the Worker's URL, of the form
   `https://heliofits-studio-feedback.<your-subdomain>.workers.dev`. If wrangler refuses the
   `compatibility_date`, set it to the day of the deploy.
7. Try it before any release carries the address: in a dev build with a throwaway home, set
   `feedback.endpoint=<the URL>` in that home's `HFStudio/Settings/user.properties`, send a report,
   and check that the issue appears and the report moved to `Outbox/sent/`.
8. Publish the URL, with no new build: in the site repository, write the URL as the only line of
   `hfstudio/feedback-endpoint.txt` and push it (a site deploy, so it waits for Gilly's yes). Check
   it with `curl -sS https://gilly.space/hfstudio/feedback-endpoint.txt`. From the next launch every
   0.8.6 or later install sends its reports here, and its outbox with them.
9. Make sure the issues reach your inbox. GitHub's documentation says you automatically watch the
   repositories you create (docs.github.com, "About notifications"); whether a new issue then reaches
   you by email depends on your notification settings, so check them. One catch: the Worker opens each
   issue with the token's account. If that is your own account, the issue is your own activity, and
   GitHub does not email you about your own activity unless you turn that on (GitHub blog, "Email
   updates about your own activity", 2016-06-30; the setting is on github.com/settings/notifications).
   Send one test report in step 7 and confirm the email arrives.
10. Optionally, also put the URL in `ENDPOINT` in `src/org/helioviewer/jhv/io/FeedbackReport.java`,
   so a later release does not depend on the file. The compiled value and the setting win over the file.

The app accepts only an `https://` endpoint (or plain `http://` to this machine, for
`wrangler dev`), with a 20 second timeout, and sends `User-Agent: JHV/SWHV-<version> ...` as it
does to every archive.
