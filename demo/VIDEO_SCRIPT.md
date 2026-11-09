# AskOnce — 3-minute demo video script

Target: 2:50 (10 s buffer). Speak slowly; ~140 words per minute. Times are cumulative.

## Before recording (setup checklist)

- [ ] `kubectl -n askonce port-forward svc/askonce 8081:80` is running
- [ ] Chrome window 1: `http://localhost:8081/` (home page)
- [ ] Chrome window 2, phone size (DevTools → device toolbar → iPhone): `http://localhost:8081/ask.html?board=demo-k8s-talk`
- [ ] Chrome window 3: `http://localhost:8081/board.html?board=demo-k8s-talk` (speaker board)
- [ ] Dash0 tabs: Services → askonce (Overview, time range covering 12:15–now), Logging filtered on `Slow submission`, one slow trace open, Agent0 chat, SLOs, Websites → askonce-web
- [ ] Radar tab: the finished AI investigation of deployment/askonce
- [ ] GitHub tabs: PR #7 (Gitar auto-apply commit), PR #8 (Gitar security findings), PR #9 (the perf fix story), `.gitar/review/main.md`
- [ ] Terminal with `demo/load-test.sh 40 20 demo-k8s-talk http://localhost:8081` typed but not run

---

## 0:00 – 0:15 · The problem (home page)

> "At every conference talk, Q&A is short — and the same question gets asked three different ways. Meanwhile, the people who never raise their hand never get heard. This is **AskOnce**: ask once, heard by everyone."

## 0:15 – 0:55 · Live demo (ask page + speaker board side by side)

Click **Load sample board** on the home page, then switch to the two windows.

> "Here's a Kubernetes talk at this hackathon. Attendees ask from their phones."

Type on the phone page: *Is Kubernetes too complex for a team of five engineers?* → the green "Someone already asked this" message appears.

> "AskOnce noticed this means the same as *'Is Kubernetes overkill for a small team?'* — so instead of a duplicate, it adds a vote. The speaker sees each question once, ranked by how many people asked it, with the other wordings underneath."

Point at the stats line on the speaker board: *22 asked · 12 unique · 10 duplicates merged*.

## 0:55 – 1:20 · How it works (Elasticsearch)

Show the README architecture or Kibana Discover on the `questions` index.

> "Elasticsearch is the core. Every question is a `semantic_text` field embedded by ELSER, running locally inside Elasticsearch. A semantic search finds candidate duplicates. But at a Kubernetes talk, *every* question says 'Kubernetes' — raw scores merged twelve different questions into one. So AskOnce down-weights the talk's own topic words: same 22 questions, 6 wrong groups before, 12 correct groups after."

## 1:20 – 2:15 · Production story (Dash0 + Radar)

Run the load test in the terminal (or show the earlier result).

> "Then we tested the moment a talk ends: 40 people asking at once. Median wait: **almost 20 seconds**."

Dash0 → Services → askonce Overview: point at the 22 s spike.

> "Dash0 caught it. Agent0 flagged p95 latency of 17 seconds and slow Elasticsearch calls. The trace shows where the time goes: `board_lock.wait`, while a write waited about a second for an index refresh. The logs say it too: *waited 19 seconds for the board lock*."

Radar → AI investigation.

> "Radar's AI investigation, on our kind cluster, independently found the same queue — 18 waiters, 19-second waits — and flagged five hardening gaps in our Deployment."

Back to Dash0 Overview after the fix / SLO page.

> "The fix: stop holding the lock while waiting for a refresh. Median latency dropped from **19.8 seconds to 0.47** — forty times faster — and we track it with an SLO: 95% of questions answered within a second. Real-user monitoring shows what attendees' browsers see, too."

## 2:15 – 2:40 · How we shipped (Gitar)

GitHub: PR list → PR #7 commit by Gitar → PR #8 findings.

> "Every feature landed through a pull request reviewed by Gitar, using our own rules in `.gitar/review` — like *never hardcode secrets*. Gitar's auto-apply pushed real fixes: a failing test, and a race that created duplicate groups. It also caught that anyone could wipe a live board — we fixed that before the demo."

## 2:40 – 2:55 · Close

Back to the speaker board.

> "AskOnce: Elasticsearch for meaning, Dash0 and Radar to keep it fast under a rush, Gitar to keep every change safe. Next: a multilingual model, so a question in Chinese merges with the same one in English. Ask once — heard by everyone."

---

## Numbers to quote (all measured today)

| Fact | Value |
|---|---|
| Sample board | 22 questions → 12 groups, 0 wrong merges |
| Without topic awareness | 6 groups (one swallows 12 different Kubernetes questions) |
| Load test before fix (40 attendees, 20 concurrent) | p50 19.8 s |
| After fix | p50 0.47 s (200 attendees / 50 concurrent: p50 1.12 s) |
| Root cause | `refresh=wait_for` write: 1,127 ms vs 11 ms with `refresh=true` |
| Dash0 Agent0 | p95 17 s, worst request 27 s, slow ES calls p95 987 ms |
| Radar | 18 waiters / ~19 s waits; 5 hardening findings fixed |
| Dash0 RUM | 224 browser events from `askonce-web` |
| Gitar | Reviewed every PR (#4–#12); auto-apply fixes in #4 and #7 |
