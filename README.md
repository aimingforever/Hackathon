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

Requirements: Java 25, Docker, `jq` (used by the demo and setup scripts).

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

AskOnce runs in a local [kind](https://kind.sigs.k8s.io/) cluster connected to [Radar Cloud](https://radarhq.io), and reaches Elasticsearch on the host through `host.docker.internal`. Requirements: `kind`, `kubectl`, a kind cluster named `cnh` (`kind create cluster --name cnh`), and Elasticsearch running locally as above.

1. Build the image with the tag the manifest expects, and load it into kind:
   ```bash
   ./mvnw -DskipTests package
   TAG=$(grep -oE 'image: askonce:[0-9.]+' k8s/askonce.yaml | cut -d: -f3)
   docker build -t askonce:$TAG .
   kind load docker-image askonce:$TAG --name cnh
   ```
2. Create the namespace and the Secrets. Credentials never go into the repository.
   ```bash
   kubectl create namespace askonce
   set -a; source ~/elastic-start-local/.env; set +a
   kubectl -n askonce create secret generic es-credentials --from-literal=password="$ES_LOCAL_PASSWORD"
   ```
   Dash0 (required): the OTLP endpoint from *Organization Settings → Endpoints → OTLP via HTTP* and an auth token. `read -rs` keeps the token off the screen and out of shell history.
   ```bash
   read -rs DASH0_TOKEN
   kubectl -n askonce create secret generic dash0-otlp \
     --from-literal=endpoint=https://ingress.us-west-2.aws.dash0.com \
     --from-literal=headers="Authorization=Bearer $DASH0_TOKEN"
   ```
   Dash0 real-user monitoring (optional): an **ingest-only** token, because browsers can see it. Without it the pages run without RUM.
   ```bash
   read -rs DASH0_RUM_TOKEN
   kubectl -n askonce create secret generic dash0-rum --from-literal=token="$DASH0_RUM_TOKEN"
   ```
3. Deploy and open the app at http://localhost:8080:
   ```bash
   kubectl apply -f k8s/askonce.yaml
   kubectl -n askonce rollout status deploy/askonce
   kubectl -n askonce port-forward svc/askonce 8080:80
   ```

## Set up the Elastic and Dash0 extras (optional)

These scripts recreate everything we configured outside the app. `setup-agent-builder.sh` is safe to run again (it updates existing tools). Run the other two once: each run of `setup-workflows.sh` or `setup-dashboard.sh` creates another workflow or dashboard.

```bash
set -a; source ~/elastic-start-local/.env; set +a
elastic/setup-agent-builder.sh   # Agent Builder: 3 ES|QL tools + the speaker-assistant agent
elastic/setup-workflows.sh       # Workflow: board snapshots every 5 minutes
elastic/setup-dashboard.sh       # Kibana dashboard "AskOnce: live Q&A"
```

The Dash0 SLO, check rule and dashboard are in `observability/`. They were created with the Dash0 API, for example:

```bash
curl -X POST "https://api.us-west-2.aws.dash0.com/api/slos?dataset=default" \
  -H "Authorization: Bearer $DASH0_TOKEN" -H 'Content-Type: application/json' \
  --data @observability/slo-question-latency.json
```

(`/api/alerting/check-rules` for `check-rule-question-latency.json`, `/api/dashboards` for `dash0-dashboard.json`.)

## Tests

`./mvnw test` runs the unit tests without Elasticsearch. With `ES_LOCAL_PASSWORD` set, `QuestionServiceElasticsearchIT` also runs against the local Elasticsearch.

## AI assistance

This project was built with AI coding assistants, as the hackathon rules require us to disclose:

- **Claude Code** helped write code, tests and docs. Every change was reviewed and tested by the team.
- **Gitar** reviewed every pull request and applied fixes (for example, a test configuration fix in PR #4).
