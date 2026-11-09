# AskOnce

**Ask once. Heard by everyone.**

AskOnce is a live Q&A board for conference talks. Attendees submit questions from their phones; questions that mean the same thing are merged into one card, so the speaker sees each question once, ranked by how many people asked it. No more spending half of a short Q&A on the same question asked three ways, and people who would never raise their hand still get heard.

The same engine works for any crowd input: topics for next year's conference, feature requests, workshop feedback.

Built at the [Cloud Native Hackathon](https://www.wemakedevs.org/hackathons/cnh) (Salt Lake City, November 2026).

## How it works

```
attendee ──POST question──▶ AskOnce (Spring Boot)
                               │ 1. ELSER semantic search: closest existing groups on this board
                               │ 2. topic-aware similarity: merge, or start a new group
                               ▼
                         Elasticsearch (semantic_text + ELSER, runs locally)
```

1. **Elasticsearch finds candidates** with an ELSER `semantic` query on a `semantic_text` field.
2. **A topic-aware similarity decides**: cosine similarity over ELSER token vectors, with the board topic's own words (for example "Kubernetes" for a Kubernetes talk) down-weighted. Without this, every question mentioning the talk topic looked like a duplicate.

The embedding model is a config setting (`goodfish.questions.inference-id`), so a multilingual model can be swapped in to merge questions asked in different languages.

| Sponsor tool | How AskOnce uses it |
|---|---|
| **Elasticsearch** | Core data store for questions and boards. `semantic_text` with **ELSER** running locally (inference API) for duplicate detection, plus our topic-aware similarity. **Hybrid search**: an RRF retriever fusing fuzzy keyword and semantic retrievers (`GET /search`). **Aggregations** for board stats. **ES\|QL** everywhere: `FORK`/`FUSE` hybrid search, `STATS`. **Agent Builder**: 3 ES\|QL tools and a speaker-assistant agent, called over **MCP** (`elastic/agent-builder`). **Workflows**: a scheduled workflow snapshots board stats into a time-series index (`elastic/workflows`). **Kibana dashboard** built from ES\|QL panels (`elastic/dashboards`). |
| **Dash0** | OpenTelemetry Java agent plus custom spans and metrics (board lock wait, merged vs new, similarity), logs, Elasticsearch shown as its own service, and **real-user monitoring** in the browser. **Agent0** found our 20-second slowdown. **SLO** (95% of questions under 1 s), **check rule** alert, and a **dashboard**, all as code in `observability/`. |
| **Radar** | AskOnce runs on a kind cluster connected to Radar Cloud. We used Radar's **MCP** throughout (resources, issues, changes) and an **AI investigation** that found the lock-wait queue and 5 hardening gaps, which we then fixed in `k8s/askonce.yaml`. |
| **Gitar** | Every feature landed through a Gitar-reviewed PR. Project-aware **review instructions** (`.gitar/review/`) and **repository rules** (`.gitar/rules/`). **Auto-apply** fixed a failing test and a duplicate-group race; reviews also caught an unauthenticated board reset and a metric-cardinality issue. **CI** in GitHub Actions for failure diagnosis. |

## API

| Endpoint | Purpose |
|---|---|
| `PUT /api/boards/{board}` | Set the board topic, e.g. `{"topic": "Kubernetes in production"}` |
| `POST /api/boards/{board}/questions` | Submit `{"text": "..."}`; returns the group and whether it was merged |
| `GET /api/boards/{board}/questions` | Groups ranked by asks + votes |
| `GET /api/boards/{board}/search?q=...` | Hybrid search: keyword (English analyzer, fuzzy) + semantic (ELSER), fused with RRF |
| `GET /api/boards/{board}/stats` | Board statistics from Elasticsearch aggregations (questions, unique, merged, votes, timeline) |
| `DELETE /api/boards/{board}/questions` | Reset a board; only `demo-*` boards, or with the `X-Admin-Token` header |
| `GET /api/rum-config` | Dash0 browser monitoring config (endpoint + ingest-only token from the environment) |
| `POST /api/questions/{id}/upvote` | Upvote a group |

## Run it locally

Requirements: Java 25, Docker, `jq` (for the demo script).

1. Start Elasticsearch and Kibana locally with [start-local](https://github.com/elastic/start-local):
   ```bash
   curl -fsSL https://elastic.co/start-local | sh
   ```
2. Load the Elasticsearch password and start the app:
   ```bash
   set -a; source ~/elastic-start-local/.env; set +a
   ./mvnw spring-boot:run
   ```
3. Submit the demo questions and see them merged:
   ```bash
   demo/submit-questions.sh demo-k8s-talk
   ```

## Run it on Kubernetes (kind)

AskOnce runs in a local [kind](https://kind.sigs.k8s.io/) cluster connected to [Radar Cloud](https://radarhq.io), and reaches Elasticsearch on the host through `host.docker.internal`.

```bash
./mvnw -DskipTests package
docker build -t askonce:0.1.0 .
kind load docker-image askonce:0.1.0 --name cnh
kubectl apply -f k8s/askonce.yaml
set -a; source ~/elastic-start-local/.env; set +a
kubectl -n askonce create secret generic es-credentials --from-literal=password="$ES_LOCAL_PASSWORD"
kubectl -n askonce port-forward svc/askonce 8080:80
```

Tests: `./mvnw test` runs the unit tests without Elasticsearch. With `ES_LOCAL_PASSWORD` set, `QuestionServiceElasticsearchIT` also runs against the local Elasticsearch.

## AI assistance

This project was built with AI coding assistants, as the hackathon rules require us to disclose:

- **Claude Code** helped write code, tests and docs. Every change was reviewed and tested by the team.
- **Gitar** reviewed every pull request and applied fixes (for example, a test configuration fix in PR #4).
