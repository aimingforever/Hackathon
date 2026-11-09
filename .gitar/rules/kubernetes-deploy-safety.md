---
title: "Kubernetes manifest safety"
description: "Protect the hardening Radar asked for and make every app change ship as a new image tag"
when: "A PR changes k8s/askonce.yaml, the Dockerfile, or application code under src/main"
actions: "Label the PR deploy, check hardening and image tag, and comment with what is missing"
---

# Kubernetes manifest safety

Radar's AI investigation flagged hardening gaps in `deployment/askonce`, which we fixed. This rule keeps them fixed.

## When to Use This

- A PR changes `k8s/askonce.yaml` or `Dockerfile`, or changes code under `src/main` (which needs a new image).

## How It Works

1. Add the label `deploy`.
2. In `k8s/askonce.yaml`, confirm these are still present: `runAsNonRoot: true`, `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop: ["ALL"]`, `automountServiceAccountToken: false`, and the startup, liveness and readiness probes.
3. Confirm credentials only come from Secrets (`secretKeyRef`), never from literal `value:` entries.
4. If `src/main` changed, confirm the `image: askonce:x.y.z` tag was bumped compared with the base branch.
5. Post one comment listing anything that is missing, with the exact YAML to add. Post nothing if all checks pass.

## Why This Matters

- Hardening that disappears in a later PR is easy to miss in review.
- On kind, an unchanged image tag means the cluster keeps running the old code after `kubectl apply`.
