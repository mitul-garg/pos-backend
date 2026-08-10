# Bugs Identified & Fixed — human-testing log (Backend)

A running log of bugs and UX issues found by **manual human testing** of the running
app (things that compiled and passed the test suite but broke or felt wrong when a
person actually exercised the API). Covers **backend only** — frontend bugs are logged
in [`frontend/BUGS.md`](../frontend/BUGS.md) in that repo. This complements `requirements.md`
(the spec) — it records what real usage surfaced that the spec/automated checks didn't.

Numbering matches the original combined log this file was split from (2026-08-10),
so existing code comments and doc cross-references (`BUGS.md #15`, etc.) still point
at the right entry — hence starting at #15, with #1–#14 and #16 being frontend entries
now in `frontend/BUGS.md`. Same story for `C4` in Code smells below (`C1`–`C3` are frontend).

## Routine for agents

- **When a human reports a bug during testing, add a row here** (newest at the top of the table), then fix it.
- Update the row's **Status** when it's resolved (`Fixed`, `By design`, `Won't fix`, `Open`, `Deferred`).
- Set **Phase** to the build step it surfaced in (`C3`, `C8`, …).
- If the fix or decision changes a spec decision, also update `requirements.md` **and** the relevant `backend/prompts/` doc in the same change — this file is a log, not a substitute for the spec.
- Keep entries terse: what the human saw, and how it was resolved. Link files with normal relative paths.
- **Bugs found by a failing test rather than by a human don't belong here** — fix them and let the test stand as the record. This file is specifically for what real usage surfaced that the automated checks didn't.
- **Things caught during review go in [Code smells fixed](#code-smells-fixed) instead**, not the log above — a compiler or IDE warning, duplication, an inconsistency. Nothing was broken at runtime, so there's no "what the human saw"; what's worth recording is the pattern, so the next project pre-empts it.

## Log

| # | Date | Phase | What the human saw | Status | Resolution |
|---|------|-------|--------------------|--------|------------|
| 15 | 2026-08-06 | C3 (found in C8) | `POST /api/auth/login` answered 401 with correct credentials whenever the client happened to send a stale/expired `Authorization` header alongside the login request. | Fixed | `JwtAuthenticationFilter` runs in front of `authorizeHttpRequests` (`addFilterBefore`) and processed *any* present Bearer token unconditionally, before the chain ever got to evaluate that `/api/auth/login` is `permitAll()` — an unparseable/expired token tripped `catch (InvalidCredentialsException ex)` and wrote the 401 directly, so the request never reached `AuthController`/`AuthService` at all. The class's own Javadoc only reasoned about a *missing* header ("that is what lets ... `POST /api/auth/login` stay open"), not a present-but-unusable one. Both the filter's own 401 and `AuthService`'s are byte-identical by design (`AuthControllerIT`), so the symptom looked exactly like a credentials/seeding problem — cost real time to isolate. Fixed by threading `SecurityConfig`'s public-path list into the filter as one `OrRequestMatcher` (`publicPathMatcher()`), checked before the token is even read — a public path now skips token processing outright, the same as a missing header. `JwtAuthenticationFilter.java`, `SecurityConfig.java`; regression case in `JwtAuthenticationFilterTest.publicPathIsSkippedRegardlessOfTheToken`. |

## Code smells fixed

Not runtime bugs — duplication / inconsistency caught during review that would compound as the app grows. Logged so future-project prompts can pre-empt the same patterns. Two recurring themes so far: **the second copy is the moment to extract; by the third it's already drift**, and **a comment explaining why something odd is necessary is a claim to check, not a reason to keep it**.

| # | Date | Phase | Smell | Fix |
|---|------|-------|-------|-----|
| C4 | 2026-08-04 | C2 | `SchemaConstraintsIT` injected an `EntityManagerFactory` it never used, with a javadoc explaining it was needed "to force the entity manager factory to start, which is what runs the `create-drop` DDL". An IDE unused-field warning flagged it; the obvious resolutions were both wrong — suppress the warning, or keep the field because the comment justified it. | **The comment was false, and checking took one command.** Deleting the field and re-running left the suite green: refreshing a Spring context eagerly instantiates every singleton, so the factory had already started and the schema already existed. Suppressing would have preserved a comment that misdescribes how the test works — worse than the warning. Field removed, the real precondition stated on the `DataSource` instead. Its *intent* (ensure the schema exists) was replaced with `createsEveryTable`, which asserts all nine tables are present and so separates "an entity was never scanned" from "a constraint was never declared" — previously the former surfaced as `uk_variant_tenant_sku does not exist`, pointing at the wrong thing. Mutation-checked. `backend/src/test/java/com/pos/pojo/SchemaConstraintsIT.java`. |
</content>
