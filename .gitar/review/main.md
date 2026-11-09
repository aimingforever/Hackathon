# Review instructions for goodfish

goodfish is a conference talk search app built for the Cloud Native Hackathon.
Stack: Spring Boot 4 (Java 25, Maven), Spring Data Elasticsearch, OpenTelemetry Java agent
sending to Dash0, deployed to a kind Kubernetes cluster watched by Radar.

## Secrets (block the merge)

- Never hardcode passwords, API keys, tokens or Dash0 auth headers in code, `application*.yaml`,
  `application*.properties`, Dockerfiles, Kubernetes manifests or README examples.
- Credentials must come from environment variables, for example `${ES_LOCAL_PASSWORD}`.
  In Kubernetes they must come from a `Secret` referenced through `secretKeyRef`, never inline `env.value`.
- `.env` files, `opentelemetry-javaagent.jar` and any downloaded binaries must not be committed.
- If a PR removes a leaked secret, remind the author that the secret also has to be rotated.

## Configuration

- Connection settings (Elasticsearch URI, OTLP endpoint) must be overridable through environment
  variables with a sensible local default, such as `${ES_URI:http://localhost:9200}`.
- Inside the kind cluster the app reaches Elasticsearch at `http://host.docker.internal:9200`;
  do not hardcode `localhost` in Kubernetes manifests.

## Elasticsearch

- Elasticsearch is the core data layer. Search logic goes through Spring Data Elasticsearch
  repositories or the Elasticsearch client, not in-memory filtering of all documents.
- Index mappings for talks (title, abstract, speakers, track, room, start/end time) should be explicit.
  Flag text fields that are searched but mapped only as `keyword`.
- Session times must be stored with a time zone (ISO-8601 with offset, or UTC) and converted for
  display. Flag `LocalDateTime` used for talk start/end times.

## Code style

- Constructor injection, no field `@Autowired`.
- REST controllers stay thin; search and indexing logic lives in services.
- New endpoints and services need at least one test. Tests must not require a real Elasticsearch
  password to be committed anywhere.

## Kubernetes

- Manifests live in `k8s/`. Every Deployment needs resource requests/limits and readiness/liveness
  probes on the Spring Boot actuator health endpoint.
- Image tags must be explicit (no implicit `latest` in shared manifests unless `imagePullPolicy` is set
  for kind).
