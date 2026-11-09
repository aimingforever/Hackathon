# AskOnce — 3-minute demo video script

Target: 2:50 (10 s buffer). Speak slowly; ~140 words per minute. Times are cumulative.
Record each section separately, then join them.

## Before recording (setup checklist)

- [ ] `kubectl -n askonce port-forward svc/askonce 8081:80` is running
- [ ] Chrome window 1: `http://localhost:8081/` (home page) → click **Load sample board** once
- [ ] Chrome window 2, phone size (DevTools → device toolbar → iPhone): `http://localhost:8081/ask.html?board=demo-k8s-talk`
- [ ] Chrome window 3: `http://localhost:8081/board.html?board=demo-k8s-talk` (speaker board)
- [ ] Kibana: dashboard **AskOnce: live Q&A**; Agent Builder → Tools filtered on `askonce`; Workflows → **AskOnce board snapshot** (executions tab)
- [ ] Terminal ready with the Agent Builder MCP call (see "Commands" below)
- [ ] Dash0: Services → askonce Overview (time range 12:00–now), one slow trace open, Logging filtered on `Slow submission`, Agent0 chat, SLOs, Dashboards → **AskOnce: health**, Websites → askonce-web
- [ ] Radar: the finished AI investigation of deployment/askonce
- [ ] GitHub: PR #7 (Gitar auto-apply commit), PR #8 (Gitar security finding), PR #9 (perf fix story), PR #12 (CI + rules), `.gitar/rules/`

---

## 0:00 – 0:12 · The problem (home page)

> "At every conference talk, Q&A is short — and the same question gets asked three different ways. The people who never raise their hand never get heard. This is **AskOnce**: ask once, heard by everyone."

## 0:12 – 0:45 · Live demo (phone page + speaker board side by side)

Type on the phone page: *Is Kubernetes too complex for a team of five engineers?* → green "Someone already asked this".

> "This Kubernetes talk already has 22 questions. AskOnce sees that mine means the same as *'Is Kubernetes overkill for a small team?'* — so it adds a vote instead of a duplicate. The speaker sees each question once, ranked, with the other wordings underneath."

On the speaker board, type **Postgres** in *Find a question*.

> "The speaker can search too — 'Postgres' finds the databases question even though the word is never used."

## 0:45 – 1:20 · Elasticsearch, end to end

Show README table → Kibana dashboard → Agent Builder tools / terminal.

> "Elasticsearch is the whole data layer. Questions are `semantic_text`, embedded by ELSER running inside Elasticsearch. Every question at a Kubernetes talk says 'Kubernetes', so raw scores merged twelve different questions into one; we down-weight the talk's own topic words — 6 wrong groups became 12 correct ones. Search is hybrid: fuzzy keywords and semantic, fused with RRF. Aggregations drive the stats, an Elastic Workflow snapshots every board every five minutes into this Kibana dashboard, and Agent Builder exposes our ES|QL tools over MCP, so an AI assistant can brief the speaker."

## 1:20 – 2:10 · Production story (Dash0 + Radar)

Show the load test result, then Dash0 Overview (22 s spike).

> "Then we simulated the end of a talk: 40 people asking at once. Median wait: almost **20 seconds**. Dash0 caught it — Agent0 flagged p95 at 17 seconds and slow Elasticsearch calls; the trace shows the time in `board_lock.wait`, and the logs say *waited 19 seconds for the board lock*."

Radar AI investigation.

> "Radar's AI investigation on our cluster found the same queue — 18 waiters — and five hardening gaps, which we fixed."

Dash0 dashboard / SLO.

> "The cause: a write held the lock while waiting for an index refresh. After the fix: **0.47 seconds** — forty times faster. Now an SLO, an alert and a dashboard keep it there, and real-user monitoring shows what attendees' browsers see."

## 2:10 – 2:35 · How we shipped (Gitar)

GitHub PR list → PR #7 → `.gitar/rules`.

> "Every feature landed through a pull request reviewed by Gitar, with our own review instructions and repository rules — like keeping Radar's hardening in place. CI runs on every PR, and Gitar's auto-apply pushed real fixes: a failing test and a race that duplicated questions. It even caught that anyone could wipe a live board."

## 2:35 – 2:50 · Close

Back to the speaker board.

> "AskOnce: Elasticsearch for meaning, Dash0 and Radar to stay fast under a rush, Gitar to keep every change safe. Next: a multilingual model, so the same question in Chinese and English merges. Ask once — heard by everyone."

---

## Commands

Agent Builder over MCP (top questions for the speaker):

```bash
set -a; source ~/elastic-start-local/.env; set +a
curl -s -u "elastic:$ES_LOCAL_PASSWORD" -X POST localhost:5601/api/agent_builder/mcp \
  -H 'kbn-xsrf: true' -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"askonce_board_stats","arguments":{"board":"demo-k8s-talk"}}}'
```

Load test against the cluster:

```bash
demo/load-test.sh 40 20 demo-k8s-talk http://localhost:8081
```

## Numbers to quote (all measured today)

| Fact | Value |
|---|---|
| Sample board | 22 questions → 12 groups, 0 wrong merges |
| Without topic awareness | 6 groups (one swallows 12 different Kubernetes questions) |
| Hybrid search | "Postgres" → databases question; "kubernets secrets" (typo) → secrets question |
| Load test before fix (40 attendees, 20 concurrent) | p50 19.8 s |
| After fix | p50 0.47 s (200 attendees / 50 concurrent: p50 1.12 s) |
| Root cause | `refresh=wait_for` write: 1,127 ms vs 11 ms with `refresh=true` |
| Dash0 Agent0 | p95 17 s, worst request 27 s, slow ES calls p95 987 ms |
| Radar | 18 waiters / ~19 s waits; 5 hardening findings fixed |
| Dash0 RUM | 224 browser events from `askonce-web` |
| Gitar | Reviewed every PR; auto-apply fixes in #4 and #7; caught an unauthenticated reset in #8 |
