---
title: "Demo questions stay verified"
description: "Changes to the demo question set must keep the expected groups and say how they were verified"
when: "A PR changes demo/questions.txt or src/main/resources/static/js/sample-questions.js"
actions: "Label the PR demo-data and ask for the verified grouping result if the PR description does not include it"
---

# Demo questions stay verified

The live demo depends on the sample questions merging into exactly the expected groups with Elasticsearch ELSER.

## When to Use This

- A PR changes `demo/questions.txt` or `src/main/resources/static/js/sample-questions.js`.

## How It Works

1. Add the label `demo-data`.
2. Check that both files contain the same questions (CI also checks this).
3. Check that the PR description states the grouping result from a real run (for example "22 questions → 12 groups, 0 wrong merges").
4. If it is missing, comment asking the author to run `demo/submit-questions.sh` against a local Elasticsearch and paste the result.

## Why This Matters

- A plausible-looking question can silently merge into the wrong group, which only shows up live in front of an audience.
