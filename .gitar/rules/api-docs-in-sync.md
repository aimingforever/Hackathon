---
title: "API changes update the README"
description: "Keep the README API table in sync with the REST endpoints in QuestionController"
when: "A PR adds, removes or changes a @GetMapping, @PostMapping, @PutMapping or @DeleteMapping in src/main/java"
actions: "Label the PR api-change and comment with the missing README rows if the API table was not updated"
---

# API changes update the README

AskOnce is demoed and judged from its README, so the API table must match the code.

## When to Use This

- A PR changes request mappings in any `*Controller.java` under `src/main/java`.

## How It Works

1. List the endpoints (method + path) added, removed or changed in the PR.
2. Compare them with the `## API` table in `README.md` on the PR branch.
3. Add the label `api-change` to the PR.
4. If any endpoint is missing or outdated in the README, post one comment listing the exact table rows to add or change.

## Why This Matters

- Judges and new contributors read the README, not the controllers.
- Undocumented endpoints such as destructive ones (board reset) are easy to miss in review.
