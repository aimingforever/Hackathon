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
2. **A topic-aware similarity decides**: cosine similarity over ELSER token vectors, with the board topic's own words (for example "eBPF" for an eBPF talk) down-weighted. Without this, every question mentioning the talk topic looked like a duplicate.

The embedding model is a config setting (`goodfish.questions.inference-id`), so a multilingual model can be swapped in to merge questions asked in different languages.

| Sponsor tool | How AskOnce uses it |
|---|---|
| Elasticsearch | Core data store; ELSER semantic search for duplicate detection |
| Gitar | Every feature lands through a reviewed PR; project rules in `.gitar/review/` |
| Dash0 | OpenTelemetry traces, logs and metrics (in progress) |
| Radar | Runs on a kind Kubernetes cluster connected to Radar Cloud; used to deploy, watch and troubleshoot |

## API

| Endpoint | Purpose |
|---|---|
| `PUT /api/boards/{board}` | Set the board topic, e.g. `{"topic": "eBPF in production"}` |
| `POST /api/boards/{board}/questions` | Submit `{"text": "..."}`; returns the group and whether it was merged |
| `GET /api/boards/{board}/questions` | Groups ranked by asks + votes |
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
   demo/submit-questions.sh demo-ebpf-talk
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
