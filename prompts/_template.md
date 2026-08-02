<!--
Template for a new feature/step doc under prompts/features/.
Copy this file, fill it in, delete these comments, add a row to prompts/README.md's table.
Keep it short — link to classes instead of restating their code. If everything here would
just be "obvious from reading the code", it belongs in CONVENTIONS.md instead (or nowhere).
-->

# <Feature / C-step name>

One or two sentences: what this does and which `backend-plan.md` step and
`requirements.md` section it corresponds to.

## Key classes

- `com.pos.<package>.<Class>` — what it's responsible for, in a few words
- `com.pos.<package>.<Other>` — ...

## Decisions & gotchas

Things that aren't obvious from reading the code — why something is shaped the way
it is, a constraint that drove a choice, a transaction boundary that matters, a
workaround. Skip this section if there's nothing non-obvious.

## Tenant scoping

How this feature stays inside the tenant boundary, and anything unusual about it
(a filter-disabled path, an inherited tenant, a cross-entity join). **Every
feature that touches tenant-owned data needs a line here**, even if it's just
"nothing special — the filter handles it".

## Tests

Which suites cover this, and what they'd catch. Note anything deliberately
untested and why.

## Extension points

Concrete "if you need to do X, edit Y" pointers for common future changes.

## Related

- [CONVENTIONS.md](../CONVENTIONS.md) for cross-cutting patterns used here
- [database/](../database/) if this feature added or changed tables
- Other feature docs this one depends on
