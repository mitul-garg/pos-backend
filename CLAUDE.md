# backend/

Before making any change in this directory, read [prompts/README.md](./prompts/README.md) — it indexes per-feature docs, cross-cutting conventions (tenant scoping, transactions, error mapping, testing), and the database documentation, so you don't need to scan the whole source tree to find established patterns.

Spec/roadmap lives in `../requirements.md` (repo root) and `../backend-plan.md` (the build sequence, C1–C9). `prompts/` documents what was built and how; `requirements.md` says what to build and tracks resolved decisions — keep both in sync when a decision is made.

**Anything about the database** — tables, columns, indexes, constraints, the ER diagram — lives in [prompts/database/](./prompts/database/). Update it in the same change as the entity, not afterwards.

**Work in small, reviewable steps, and commit each one.** One C-step (or one bug fix, or one refactor) per change, ending runnable with `mvn test` green. After each, offer a commit message and remind the user to commit before starting the next — don't run `git commit` unless asked. No cross-cutting rewrites; if a change starts touching every package, stop and split it.

Note `backend/` is its own git repo — `../backend-plan.md`, `../requirements.md` and `../BUGS.md` live outside it and aren't captured by a backend commit.

When the user reports a bug found while manually testing, log it in `../BUGS.md` (repo root, `Area = Backend`) and update its status when fixed — routine is at the top of that file.
