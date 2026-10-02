# Feedback Worker

Help > Send Feedback... and the Report this... button on error dialogs build a JSON report
(`src/org/helioviewer/jhv/io/FeedbackReport.java`) and POST it here. This Cloudflare Worker checks
it and opens a GitHub issue with it, so a user can report a problem without an account and
without finding the issue tracker. It is written and tested locally; it has not been deployed.

Until it is live, the app saves every report in `~/HFStudio/Outbox/` and offers to copy it or to
email it to gilly@nwra.com. Once a build carries the endpoint, the app sends whatever its outbox
holds at the next launch and moves each sent report to `Outbox/sent/`. Nothing is deleted.

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
8. Put the URL in `ENDPOINT` in `src/org/helioviewer/jhv/io/FeedbackReport.java`, so the next
   release sends reports, and its first launch sends the outboxes users have accumulated.

The app accepts only an `https://` endpoint (or plain `http://` to this machine, for
`wrangler dev`), with a 20 second timeout, and sends `User-Agent: JHV/SWHV-<version> ...` as it
does to every archive.
