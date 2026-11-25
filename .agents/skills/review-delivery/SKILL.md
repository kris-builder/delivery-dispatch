---
name: review-delivery
description: Lead a read-only review of a proposed feature or fix before merge by comparing its Spec, actual diff, execution evidence, and affected behavior.
---

# Review delivery

Read the current feature Spec and compare the work against the target branch. Check working-tree changes and untracked files alongside commits. Map each acceptance ID to a test or runtime result and flag any missing, skipped, or weakened assertion.

For stock, money, and state transitions, inspect what prevents a duplicate or partial write and which failure case actually demonstrates it. For changes spanning Java and FastAPI, compare the concrete payload and results for timeout, invalid input, or stale data when relevant.

Run relevant verifications when supported by the current environment and task. Record the command and actual outcome. Report findings by severity with file locations, repro conditions, and the evidence needed to resolve them. Keep the worktree unchanged during review.
