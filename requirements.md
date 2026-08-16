# PoS Web App — Frontend Requirements & Build Instructions

> **For the coding agent (Claude Code in VS Code):** This is a living spec. Build the **frontend first** with mock/dummy data, then we do the DB schema, then the Spring backend. Work **incrementally, phase by phase**. After each phase: run the app, verify it works, and stop for review before moving on. Do **not** make large sweeping edits across the whole codebase in one shot. Prefer small, verifiable steps.
>
> **Bug log:** when the user reports a bug/UX issue found while manually testing the running app, record it in [`BUGS.md`](./BUGS.md) (root) and update its status when resolved — see the routine at the top of that file. Applies to both frontend and backend.

---

## 1. What we're building

A Point-of-Sale (PoS) web application for retail checkout.

End-to-end flow (target): **log in → scan product → build order/cart → edit quantities → take payment → print receipt**, plus **returns/refunds** against completed orders. Products get a **QR code generated and persisted** when they're created. The counter operator can **edit item quantities** in the active order. Access is gated by **cashier auth with role-based access control (RBAC)**.

Stack:

- **Frontend:** React (**JavaScript / JSX**, no TypeScript), Vite build tool, **Tailwind CSS** for styling.
- **Backend (later):** Spring MVC (**not** Spring Boot), Hibernate, MySQL, Jetty, **Spring Security (RBAC)**, Maven, Log4j2, JUnit.
- **This document covers the frontend only.** The API contract in Section 9 is the bridge to the schema and backend that come next. Auth is **mocked** on the frontend for now (mock users + fake token), designed to swap cleanly to Spring Security later.

---

## 2. Core domain decision — Products vs Variants (read this first)

This is the most important modeling decision and the source of the "two variants, different MRP" edge case.

- A **Product** is the parent concept (e.g., "Amul Milk", "Lay's Chips").
- A **Variant (SKU)** is what actually gets scanned, priced, and stocked (e.g., "Amul Milk 500ml" MRP ₹30, "Amul Milk 1L" MRP ₹58).
- **The QR code lives on the Variant, never on the Product.** Each variant has its own unique code, its own MRP, its own selling price, its own stock.
- Scanning resolves a code → **exactly one variant**.

So "two variants of the same product with different MRPs" is not an edge case to special-case — it's the normal shape of the data. A product with a single variant is just the degenerate case (one variant).

---

## 3. Data model (frontend shapes, backed by mock data)

Keep these as the single source of truth for mock data and the service layer. Field names should match what the backend API will return (Section 9).

> **Multi-tenancy (see Section 13):** the app is **multi-tenant, flat** — a **Tenant is a single store**. **Every record below except `TenantPojo` itself and platform (`SUPER_ADMIN`) users carries a `tenantId`**, and all uniqueness constraints are **scoped per tenant** (`(tenantId, username)`, `(tenantId, sku)`, `(tenantId, qrCode)`), never global. `tenantId` is set server-side from the caller's session, never accepted from the client. It's listed once here rather than repeated on every field bullet.

**Tenant** (a store / merchant — the tenant boundary)

- `id`, `name`
- `code` (short human handle, e.g. `acme` — **globally unique**; the login discriminator, Section 5.1)
- `status` (`ACTIVE` | `SUSPENDED` | `PENDING_VERIFICATION` — a suspended or still-pending tenant can't log in or transact. `PENDING_VERIFICATION` is reachable only via public self-registration (Section 5.11, backend C9) and left only by the emailed verification link, or by a `SUPER_ADMIN` suspending it directly — `PATCH /api/tenants/{id}` never accepts it as a target, only `ACTIVE`/`SUSPENDED`, since it has no way to mint the token that status depends on)
- `createdAt`
- `verificationToken`, `verificationExpiresAt` (backend C9, nullable — self-registration only; the token is globally unique when present and cleared the moment `verify()` succeeds, never present in any API response)

**Product**

- `id`, `tenantId`, `name`, `brand`, `category`, `description` (optional)
- `hsnCode` (India GST HSN code, optional for now)
- `taxRatePercent` (GST slab: 0 / 5 / 12 / 18 / 28)
- `createdAt`

**Variant (SKU)**

- `id`, `tenantId`, `productId`
- `variantLabel` (human label, e.g. "500ml", "Large / Red")
- `attributes` (object, e.g. `{ size: "500ml" }` — flexible key/values)
- `sku` (internal code, unique **per tenant**)
- `qrCode` (the persisted QR payload that gets scanned — a string; the QR image is rendered from it on the frontend, never stored). Codes are **QR-only, permanently** — there is no symbology `type` field. Unique **per tenant**; the generated payload **embeds the tenant** (e.g. `POS-QR-{tenantId}-000001`) so printed labels are globally distinct and a label from one tenant can never resolve in another (Section 6).
- `mrp`, `sellingPrice` (must be ≤ `mrp`)
- `stockQuantity`
- `unitOfMeasure` (`EACH` | `KG` | `LITRE` — default `EACH`; weight-based selling is future)
- `isActive`

**CartItem / OrderLine** (denormalize price fields at time of sale so later price changes don't rewrite history)

- `variantId`, `name` (product + variant label snapshot), `qrCode`
- `quantity`, `unitPrice` (selling price applied), `taxRatePercent`
- `lineDiscount` (default 0), `lineTotal`

**Order**

- `id`, `tenantId`, `orderNumber`, `items[]`
- `subtotal`, `totalTax`, `orderDiscount` (default 0), `roundOff`, `grandTotal`
- `status` (`DRAFT` | `HELD` | `COMPLETED` | `CANCELLED`)
- `payment` (see below)
- `cashierId` (who rang it up)
- `createdAt`
- (`orderNumber` is unique **per tenant** — each tenant runs its own `ORD-YYYY-####` sequence)

**Payment** (single payment covering the full amount — no split tender)

- `method` (`CASH` | `CARD` | `UPI`)
- `amount` (equals `grandTotal`)
- `amountTendered`, `change` (cash only)
- `reference` (txn id / UPI ref — dummy for now)

**Return** (against a completed order)

- `id`, `tenantId`, `returnNumber`
- `originalOrderId`, `originalOrderNumber`
- `items[]` (returned lines: `variantId`, `name`, `quantity`, `unitPrice`, `taxRatePercent`, `lineRefund`)
- `refundSubtotal`, `refundTax`, `refundTotal`, `roundOff`
- `refundMethod` (defaults to the original payment method)
- `reason` (optional), `processedBy` (cashier id), `createdAt`
- (`returnNumber` unique **per tenant**; the return's tenant always equals the original order's tenant)

**User** (auth)

- `id`, `tenantId` (**null for a `SUPER_ADMIN`** — platform users belong to no tenant), `username`, `displayName`
- `email` (backend C9, optional — only ever populated for a self-registered tenant's first admin, where it's where the verification link is sent; **not** used for login, not unique, not present on platform-created users)
- `role` (`SUPER_ADMIN` | `ADMIN` | `CASHIER`)
- `isActive`
- `username` is unique **per tenant** (two tenants can each have an `admin`); platform `SUPER_ADMIN` usernames are globally unique in a reserved platform namespace
- (password/credential handling is backend-only later; the mock does a simple username/password check)

---

## 4. Pricing rules (India-specific — get this right early)

- **MRP is the tax-inclusive ceiling.** Selling price must be ≤ MRP.
- **GST is inclusive** (the selling price already contains tax). Extract tax from the inclusive price:
  `taxAmount = unitPrice − (unitPrice / (1 + taxRatePercent/100))`.
- Keep inclusive-vs-exclusive behind a single config flag in case it ever changes, but default is **inclusive**.
- **Show the GST breakdown** (taxable value + tax per slab, and the total tax) **both on the pre-confirmation payment screen and on the printed receipt.** The operator and the customer should see it before the order is confirmed.
- **Round the grand total to the nearest rupee** and record the `roundOff` delta separately.
- Totals must always reconcile: `subtotal − discounts + tax` (inclusive equivalent) `+ roundOff = grandTotal`. Put all of this in pure functions in `domain/pricing.js` with unit tests. Refund math reuses the same functions.

---

## 5. Screens (v1 scope)

1. **Login** — **tenant code** + username + password against mock users, sets the auth session, redirects by role. Usernames are unique *within* a tenant, so the tenant code disambiguates (two tenants can both have `admin`). A platform **`SUPER_ADMIN`** logs in with the **reserved tenant code `platform`** (the platform namespace — not a real tenant; no tenant may register that code). All other routes are protected. Errors are granular (Section 9): unknown tenant code / bad credentials → generic 401; a valid login on a **deactivated user** or a **suspended tenant** → 403.

2. **Checkout / POS** (default landing for cashiers)
   - **Scanning works two ways, both feeding the same lookup path:**
     - **Camera scan** (primary): a "Scan" control opens the device camera and decodes QR codes live.
     - **Keyboard-wedge**: an auto-focused input that accepts a USB scanner typing the code + Enter (also lets the operator type/paste a code manually).
   - On scan: look up variant by code. Found + already in cart → **increment quantity**. Not found → non-blocking "unknown code" message, focus stays ready for the next scan.
   - **Cart table:** name, unit price, editable quantity (stepper + direct input, min 1), line total, remove.
   - **Totals panel:** subtotal, GST breakup, discount, round-off, grand total.
   - **Hold / Park order** (save cart as `HELD`, clear counter) and resume held orders.
   - **Pay** → payment.

3. **Payment**
   - Single method: Cash / Card / UPI, covering the **full** amount.
   - **Show the GST breakdown here before confirming.**
   - Cash: amount tendered → change. UPI: dummy QR for the amount. Card: simulated approval.
   - Confirm → order `COMPLETED` → receipt.

4. **Receipt / Invoice** (printable) — store header, order number, date/time, line items, **GST breakup**, totals, payment method, change. `window.print()` with a print stylesheet.

5. **Returns / Refunds**
   - Look up a **completed order** by order number (or scan the receipt if we put a QR on it later).
   - Show its lines with **returnable quantity** = purchased − already returned; operator selects items and quantities (full or partial return).
   - Compute the refund using the **snapshotted sale prices/tax** (same inclusive math), show the breakdown, confirm.
   - On confirm: create a Return record, **restore stock**, produce a printable **refund receipt / credit note**. Refund method defaults to the original payment method.

6. **Products management** (**ADMIN only**)
   - Searchable/filterable table; add / edit / deactivate.
   - Product form with inline **variant management**: adding a variant **generates and persists a QR code**, renders a live preview, and offers **print-single-label**. Validate `sellingPrice ≤ mrp` and code uniqueness.

7. **User management** (**ADMIN only**) — list/create/deactivate cashier & admin accounts (mock now). Scope is deliberately list/create/deactivate only — no arbitrary field-edit form (the service layer's `update` exists for future use but no UI exposes it). The currently-signed-in admin can't deactivate their own account from the list (guarded client-side beyond the service's "can't deactivate the last active admin" rule).

8. **Order history** — a list of past orders with a status filter (Draft / Held / Completed / Cancelled). Doubles as the **resume** surface for `HELD` orders (and the recovery path for a `DRAFT` stranded by a failed payment) and the reprint surface for `COMPLETED` orders. **A CASHIER sees only their own orders; an ADMIN sees all** (with a Cashier column).

9. **Return history** — a list of past returns; each row reprints its credit note via a dedicated `/returns/:returnId` credit-note view, which is now the **single** place a credit note is printed (Returns hands off to it on completion, mirroring how Payment hands off to Receipt). Same scoping: **a CASHIER sees only returns they processed; an ADMIN sees all** (with a Processed-by column).

10. **Tenant management** (**`SUPER_ADMIN` only**) — the platform surface (Section 13): list all tenants, create a tenant (name + code) together with its **first ADMIN** user, and **suspend / reactivate** a tenant. `SUPER_ADMIN` does not do day-to-day POS work (no tenant context); the POS/catalog/history screens require belonging to a tenant. This screen is unreachable and hidden for tenant-scoped roles. Lists **every** non-platform tenant regardless of status, so a still-`PENDING_VERIFICATION` self-registration (item 11) shows up here too, with its own badge.

11. **Public self-registration** (`/register`, `/verify` on the frontend — unauthenticated, no session, not gated by a login at all; backend C9) — `../tenant-registration-plan.md` is the up-front design doc, `backend/prompts/c9-tenant-registration.md` the implementation record. Anyone can create a store: name, code, admin display name/email/username/password. The tenant is created **`PENDING_VERIFICATION`**, not `ACTIVE` — it can't be logged into until the admin clicks a link emailed to the address they gave, the ownership check that makes an otherwise-unauthenticated tenant-creation endpoint safe to expose publicly. Verifying is deliberately a `POST` with the token in the body, not a bare link-`GET`, so an email client's link-preview/scanner bot can't burn the single-use token before a human clicks a button. A `resendVerification({ tenantCode, adminEmail })` action covers an expired or lost link, and answers the identical generic acknowledgement whether or not the pair matched anything real (no enumeration, same reasoning as login's 401). The token is never present in any response body — it only ever travels inside the email. On the platform side (item 10), a `SUPER_ADMIN` can **Suspend** a still-pending tenant to block it before it ever goes live; `verify()` checks the tenant's current status as well as the token, so a suspended-while-pending tenant can't be silently reactivated by someone clicking a still-unexpired link afterward (`frontend/BUGS.md` #17, `backend/BUGS.md` #18).

RBAC summary: **CASHIER** → login, checkout, payment, hold/resume, returns, print, and their **own** order/return history — all **within their tenant**. **ADMIN** → everything a cashier can do **plus** products/variants, users, and viewing **all** orders and returns — still **scoped to their own tenant**. **`SUPER_ADMIN`** → the platform: create/suspend tenants and provision tenant admins; **not** a cashier and **cannot** see another tenant's catalog/orders/returns through the POS screens. **No role at all** — an anonymous caller — reaches `POST /api/tenants/register|verify|resend-verification` (item 11, backend C9); that's the point, and the reason a tenant created that way starts `PENDING_VERIFICATION` rather than `ACTIVE`. **Tenant isolation is absolute**: no tenant-scoped role can ever read or write another tenant's data, enforced server-side by filtering every query on the session's `tenantId` (Section 13). The cashier-scoped-vs-admin-sees-all rule (via `cashierId` / `processedBy`) operates *within* that tenant boundary. Enforce with route guards **and** by hiding controls the role can't use. (Note: the mock enforces this client-side for UX; the real backend re-enforces every rule — RBAC *and* tenant scoping — server-side.)

**Hold / resume lifecycle:** one order record per real-world sale. Resuming a `HELD`/`DRAFT` order loads it into the cart and remembers its id, so a subsequent re-hold or payment **continues the same record** instead of spawning a duplicate. Clearing a resumed cart **cancels** that order (drops it out of the held list); a freshly-held order that was never resumed is untouched.

**Keyboard shortcuts (Checkout only):** F2 refocus the scan input · F4 Pay · F6 Hold · Esc close the camera scanner. Deliberately narrow and non-destructive — there is **no** shortcut for confirming a payment or refund (financial actions stay an explicit click).

Every screen needs explicit **loading, empty, and error states**. Desktop-first layout (the counter runs on a monitor); responsive is secondary.

---

## 6. QR code generation & persistence (decision: use QR)

**Why QR over 1D barcodes:** since camera scanning is required, QR is the low-failure choice. Camera decoding of 1D barcodes (CODE128/EAN-13) is finicky about focus, lighting, and aspect ratio, whereas QR has built-in error correction and decodes reliably from a webcam. QR generation also has no check-digit or length/charset constraints — you just encode the SKU/variant id. **Decision (Phase 2): QR is the only symbology, permanently** — there is no barcode/type abstraction. Each variant carries a single `qrCode` string.

- The **code value is data** (a string) generated once and **persisted** on the variant. The **visual** (the QR image) is rendered on the frontend from that value — never store an image.
- In the real system the **server generates** the unique value on variant creation; for now the **mock service generates it** so the frontend is self-contained. Keep this behind the service layer so swapping to server-generated values changes nothing in the UI.
- **Multi-tenant (Section 13):** uniqueness is **per tenant** (`(tenantId, qrCode)`), and the generated payload **embeds the tenant** (e.g. `POS-QR-{tenantId}-000001`) so printed labels are globally distinct in practice. Lookup is **always tenant-scoped** — `lookupByQrCode` resolves within the caller's tenant only, so a label physically carried from one tenant to another can never resolve to a real product. Each tenant also runs its **own** code sequence (counters don't collide because the tenant segment differs).
- The rendering component takes just a `{ value }` (the QR string). No pluggable symbology — 1D/laser support is explicitly out of scope, now and later.
- Suggested libraries: **`qrcode.react`** for generating/rendering QR, **`html5-qrcode`** for camera scanning.

---

## 7. Mock data & service layer (so the UI runs with no backend)

- Seed file in `src/mocks/` with ~15–20 products. **Include several products with multiple variants at different MRPs** (the primary case), plus a single-variant product, an out-of-stock variant, and a couple of completed orders + mock users (1 admin, 1 cashier) so returns and login work end-to-end.
- **Service layer** in `src/services/`: `authService`, `tenantService` (new — platform tenant CRUD/suspend), `productService`, `variantService`, `orderService`, `paymentService`, `returnService`, `userService`. Each exports `async` functions returning Promises with small simulated latency over the in-memory mock store.
- **Function signatures must match the future API contract (Section 9)** so switching from mock to real Axios calls later is an internal swap only — no UI changes.
- `variantService.lookupByQrCode` is the hot path for POS; unknown codes return a clean "not found" (resolves to `null`), never a thrown error that breaks the flow.
- **Tenant scoping (Section 13):** every service call is scoped to the caller's tenant. The real backend derives `tenantId` from the auth token/session and filters every query by it; the mock derives it from the current user (in the store/session) and filters the in-memory arrays the same way — **service call signatures do NOT take a `tenantId` argument** (it comes from the session, never the client), so the mock→HTTP swap stays body-only. Seed data spans **two tenants** plus a platform `SUPER_ADMIN`, so cross-tenant isolation is exercisable end-to-end.

**The swap landed in backend C9, and it's a toggle rather than a deletion (decided in frontend C9).** Each service module is now a small dispatcher (`services/<name>.js`) choosing between `<name>.mock.js` (everything above, unchanged) and a new `<name>.http.js` calling the real backend, based on a build-time `VITE_USE_MOCKS` env var — default `false` (real backend). Every function signature and return shape stayed identical, so this section is still accurate for either implementation. See `frontend/prompts/features/parity.md`.

---

## 8. Suggested project structure

```
src/
  main.jsx
  App.jsx
  routes/                 # route definitions + ProtectedRoute guard
  pages/
    Login/
    Checkout/
    Payment/
    Receipt/
    Returns/
    Products/             # admin
    Users/                # admin
  components/             # BarcodeView (QR), CartTable, QtyStepper, CameraScanner, ...
  context/                # CartContext, AuthContext (React Context API)
  services/               # auth/product/variant/order/payment/return/user services
  mocks/                  # seed data + in-memory store
  domain/                 # pricing.js (tax/rounding/refunds), constants, validators, roles
  styles/                 # tailwind entry
```

- **Tailwind CSS** for all styling (set up in Phase 0).
- **State:** React Context API — a `CartContext` (+ `useCart` hook) for the cart, and an `AuthContext` (+ `useAuth` hook) for current user/role/token. Keep pricing math in `domain/pricing.js` (pure, unit-tested), not in components.
- **Routing:** React Router with a `ProtectedRoute` that checks auth + `allowedRoles`.
- **Auth:** persist the token/user in `localStorage` so a refresh keeps the session; restore via `authService.me()` on load. (This is a real Vite app, so `localStorage` is fine here.)
- **HTTP — done in C9:** one Axios instance (`services/httpClient.js`); base URL (`VITE_API_BASE_URL`) + auth-header injection configured centrally, so pointing at a different deployed backend is an env-file change (`frontend/.env.production`), not a code change.

---

## 9. API contract (frontend mocks it now; backend implements it next)

> **Tenant scoping (Section 13) applies to every endpoint below.** The tenant is **resolved server-side from the auth token** (a `tenantId` claim in the JWT later) — it is **never** a query/body parameter a client can set. Every tenant-scoped endpoint filters on that `tenantId`; a request for another tenant's resource returns **404** (not 403 — don't reveal that the id exists in some other tenant). Platform (`/api/tenants/*`) endpoints require `SUPER_ADMIN` and are the only cross-tenant surface. The paths below are unchanged from the single-tenant contract precisely *because* tenancy rides on the token, not the URL.

**Auth**

- `POST /api/auth/login` — body `{ tenantCode, username, password }` (`tenantCode: "platform"` = platform `SUPER_ADMIN` login; a blank or unknown code is simply a failed login) → `{ token, user }` (the token encodes the resolved `tenantId`, or none for a platform user). Failures are distinguished: unknown tenant code / bad username / bad password → **401** ("Invalid tenant, username, or password", no hint which was wrong); valid credentials on a **deactivated user**, a **suspended tenant**, or a still-`PENDING_VERIFICATION` tenant (Section 5.11, backend C9) → **403**, each with its own message. All three only reach a caller who already supplied correct credentials, so none leaks account/tenant existence.
- `POST /api/auth/logout`
- `GET /api/auth/me`

**Tenants** (`SUPER_ADMIN` only — the platform surface, Section 13)

- `GET /api/tenants`, `GET /api/tenants/{id}`
- `POST /api/tenants` (create a tenant + its first ADMIN in one call)
- `PATCH /api/tenants/{id}` (suspend / reactivate: `status` — accepts only `ACTIVE`/`SUSPENDED` as a target, never `PENDING_VERIFICATION`; backend C9)

**Tenant self-registration** (public, unauthenticated — Section 5.11, backend C9)

- `POST /api/tenants/register` — body `{ storeName, tenantCode, adminDisplayName, adminEmail, adminUsername, adminPassword, website, recaptchaToken }` (`website` is a honeypot — must arrive blank; a non-blank value resolves as if it succeeded but creates nothing. `recaptchaToken` is the frontend's v2 checkbox widget response — peer-review Phase 0 — checked *after* the honeypot, so a trip never spends a call verifying it; a rejected/missing token answers a plain 400, `"Please confirm you're not a robot and try again."`, no `fields` key). Same field validation as the platform `POST /api/tenants` (code format/reserved/duplicate) plus a required, format-checked `adminEmail`. Creates the tenant `PENDING_VERIFICATION` and its first `ADMIN` atomically, mints a verification token (24h expiry), and sends a verification email **after** the transaction commits, never inside it. Returns `201` with `{ tenantCode, adminEmail }` — never the token. Rate-limited per client IP.
- `POST /api/tenants/verify` — body `{ token }`. Deliberately `POST`, not a bare link-`GET`, so an email client's link-preview/scanner bot can't burn the single-use token before a human clicks a button. Requires the token to both match an unexpired row **and** that tenant's *current* status to still be `PENDING_VERIFICATION` (not just token/expiry — `backend/BUGS.md` #18's frontend counterpart, `frontend/BUGS.md` #17) → flips the tenant to `ACTIVE`, clears the token, returns `{ tenantCode }`. Unknown token, expired token, and a token whose tenant is no longer `PENDING_VERIFICATION` all answer the identical generic 400 — no distinguishing which. Not reCAPTCHA-gated (peer-review Phase 0 scope decision) — a token is single-use and self-expiring, unlike `register`/`resend-verification`'s real DB writes.
- `POST /api/tenants/resend-verification` — body `{ tenantCode, adminEmail, recaptchaToken }`. Re-validates the pair against a `PENDING_VERIFICATION` tenant, regenerates the token (invalidating the old one) and re-sends. Answers the identical generic acknowledgement whether or not the pair matched anything real. `recaptchaToken` is checked the same way `register`'s is, and a rejection answers its own 400 rather than the generic ack — that doesn't weaken the no-enumeration guarantee, since "the widget wasn't solved" says nothing about whether the pair matched. Rate-limited per client IP, independently of `register`.

**Users** (admin — always within the caller's tenant)

- `GET /api/users`, `POST /api/users`, `PUT /api/users/{id}`, `DELETE /api/users/{id}` (deactivate). All implicitly scoped to the caller's `tenantId`; an ADMIN can only ever manage users in their own tenant.

**Products**

- `GET /api/products?search=&category=&page=`, `GET /api/products/{id}` — both carry
  `imageUrl` (peer-review Phase 3), a freshly-minted signed GCS read URL, or `null` if
  the product has no image. Never stored; see §12
- `POST /api/products`, `PUT /api/products/{id}`, `DELETE /api/products/{id}` (soft —
  cascades `isActive` to every variant of the product, both directions; peer-review
  Phase 3, see §12)
- `POST /api/products/{id}/image-upload-url` — body `{ contentType }` → a signed GCS
  `PUT` URL plus the exact headers the upload request must carry (peer-review Phase 3,
  see §12). `ADMIN` only; the backend never touches the file bytes
- `PUT /api/products/{id}/image` — confirms an upload actually completed (also how a
  replacement is confirmed — one product has exactly one fixed object path).
  `ADMIN` only
- `DELETE /api/products/{id}/image` — deletes the GCS object itself, not just the
  reference; idempotent. `ADMIN` only

**Variants**

- `GET /api/products/{productId}/variants`
- `POST /api/products/{productId}/variants` (server generates & returns the QR value)
- `PUT /api/variants/{id}`, `DELETE /api/variants/{id}` (deactivating a product's last
  active variant, or reactivating any one variant of an inactive product, cascades back
  up to the product — peer-review Phase 3, see §12)
- `POST /api/variants/{id}/qr-code` (re-issue the code — added in backend C5). The frontend's `variantService.regenerateQrCode(id)` always needed a server endpoint, since §6 puts code generation on the server; this section simply never named one. A `POST` rather than a `PUT` because it *generates* rather than accepting a value, and because it is not idempotent: calling it twice consumes two sequence values. **The previous code stops resolving immediately**, so any label already printed with it is dead — which is why it is an explicit operation rather than an editable `qrCode` field.
- `GET /api/variants/lookup?qrCode={code}` → resolves a scan to one variant (or 404)
- `GET /api/variants/search?q={term}` → manual-add search on the checkout screen (by product name/brand or variant SKU/label), returns enriched active variants. Added in Phase 4 as the operator-friendly form of "type/paste a code manually" (Section 5.2) — no one memorises raw QR strings. Mock-implemented now (`variantService.search`); a real endpoint follows with the backend.

**Orders**

- `POST /api/orders` (checkout — server recomputes totals authoritatively)
- `GET /api/orders/{id}`, `GET /api/orders?status=&cashierId=&page=` (history / held). `cashierId` scopes to one operator's orders — the UI passes it for a CASHIER (own orders only) and omits it for an ADMIN (all orders).
- `PATCH /api/orders/{id}` (hold / resume / cancel)
- `POST /api/orders/{id}/payments`

**Returns**

- `GET /api/orders/lookup?orderNumber={n}` (fetch a completed order for return, incl. already-returned quantities)
- `POST /api/returns` (create a return; server recomputes refund & restores stock)
- `GET /api/returns/{id}`
- `GET /api/returns?processedBy=&page=` (return history, newest first). `processedBy` scopes to one operator's returns — same cashier-scoped-vs-admin-sees-all rule as `GET /api/orders`.

Note for later: the backend **recomputes prices, totals, and refunds server-side** and never trusts client-sent amounts; it also **re-enforces RBAC and tenant scoping** on every endpoint (every query filtered by the token's `tenantId`; cross-tenant reads return 404).

**A `SUPER_ADMIN` calling a tenant-scoped endpoint gets 403, not an empty list** (decided in backend C4). It has no tenant context, and §13.2 says the POS surface is *unavailable* to it rather than empty — "this store has no products" is the wrong thing to show someone who is on the wrong surface entirely. This is the one place the API answers something other than 404 for data it will not return, and it is safe precisely because nothing is being concealed: a platform admin gets the identical 403 for an id that does not exist, so the answer reveals nothing about which ids are real. The frontend's equivalent already throws `"This action requires a store account"` from `requireTenantId()`.

**Catalogue writes are `ADMIN`-only, enforced server-side (decided in backend C5).** §13.2 gives products, variants and user management to an `ADMIN`; a `CASHIER` reads the same catalogue and changes none of it. The client's route guard is UX, so the rule is re-stated as URL rules in the security chain — `POST/PUT/DELETE /api/products`, `POST /api/products/{id}/variants`, `PUT/DELETE /api/variants/{id}` and the QR re-issue. They are **method-scoped**: `GET /api/products` and both variant lookups stay open to a cashier, because a till is useless without them. A cashier attempting a write gets **403**, not 404 — nothing is being concealed, since the same caller may read the very row they cannot edit.

**A create answers `201`, and every write returns the full updated object** (backend C5), which is what the frontend's mock services already return — so the mock→HTTP swap stays body-only. A soft delete (`DELETE`) also returns the row, now deactivated, rather than `204`.

**Actor identity, like `tenantId`, comes from the session — not the body.** `POST /api/orders` and `POST /api/returns` do **not** accept `cashierId` / `processedBy` from the client; the server sets them from the token's subject (the mock reads them from the session user). Same rule, same reason as tenant scoping: anything identifying *who is calling* is derived, never declared.

**`GET /api/orders?cashierId=` narrows to the caller's own id for a `CASHIER`, regardless of what's passed (decided in backend C6).** Section 8 states the RBAC rule in prose ("a CASHIER sees only their own orders"); read on its own, this section's "a query, not an identity claim" line could be taken to mean the parameter is trusted as given, which would let a cashier page through a colleague's sales by id. It is not: a `CASHIER`'s value is always overridden to their own session id server-side, and only an `ADMIN`'s passes through. **Applies identically to `GET /api/returns?processedBy=` (decided in backend C7).**

**Every order line's price and tax rate is recomputed from the variant's current row, never taken from the request body (backend C6).** The create/edit input carries only `variantId`, `quantity` and `lineDiscount` — no price, no name, no tax rate — closing the obligation this section already named ("recompute prices, totals... server-side"). **A return line follows the identical rule against the ORIGINAL order line's own snapshot rather than the variant's current row (backend C7)** — `POST /api/returns`'s input carries only `variantId` and `quantity`, and the refund reads `unitPrice`/`taxRatePercent` back from the sale being returned, never from today's price.

**`POST /api/orders` accepts only `status: DRAFT` or `HELD`; `PATCH /api/orders/{id}` transitions `status` only to `HELD` or `CANCELLED` (decided in backend C6).** Not stated elsewhere in this section and not enforced by the mock, which accepts any string. Without it a request could synthesize a `COMPLETED` order without paying, or a `CANCELLED` one without going through the patch path. `COMPLETED` is set by `POST .../payments` alone.

**A missing/cross-tenant order and an existing-but-not-`COMPLETED` order answer different statuses on both return endpoints (decided in backend C7).** The mock's `returnService.lookupOrder`/`create` throw one undifferentiated message for both; the backend follows the precedent its own C6 already set for order state (an unpaid order is 404, a re-paid `COMPLETED` one is 400) rather than the mock's single message: `GET /api/orders/lookup` and `POST /api/returns` 404 ("Order not found") when the order doesn't exist or belongs to another tenant, and 400 ("Only a completed order can be returned") when it exists but was never paid.

**The platform gate's rejection is the generic role-403, not the mock's specific message (decided in backend C8).** `tenantService.js`'s `requireSuperAdmin()` throws `"Only a platform administrator can manage tenants"`; the backend enforces the same rule as a `SecurityConfig` URL rule instead of a per-service check (the identical choice C5 made for catalogue writes), so a non-`SUPER_ADMIN` hitting `/api/tenants/**` gets the same generic `"You do not have permission to perform this action."` every other role-gated endpoint answers with. Requirements.md §9's "the backend re-enforces every rule server-side" is satisfied either way; the message just isn't reproduced verbatim.

**An unrecognised `status` on `PATCH /api/tenants/{id}` is a malformed-body 400, not the mock's `"Invalid tenant status"` field message (decided in backend C8).** `status` is typed as the `TenantStatus` enum on the backend's input DTO, matching `PATCH /api/orders/{id}`'s identical `status` field (backend C6); Jackson rejects an unrecognised value before the service runs. Both are 400s a client can act on.

**User creation/update reject any role outside `ADMIN`/`CASHIER`, server-side (decided in backend C8).** Mirrors the catalogue-writes precedent (C5): the frontend's `TENANT_ROLES` list is UX, and the backend re-checks it on both `POST /api/users` and `PUT /api/users/{id}`, closing the exact escalation BUGS.md's Phase 8/B4 entry describes — a tenant `ADMIN` must never be able to mint a `SUPER_ADMIN` by sending the role directly.

**Self-registration's write and its email are two separate transactions, deliberately (decided in backend C9).** `POST /api/tenants/register`/`resend-verification` must never roll back the tenant/admin row because an outbound SMTP send was slow or failed — and holding a database transaction open across that network round trip is its own problem on a connection pool already sized tightly for free-tier MySQL (§1). `TenantRegistrationService`'s two public methods that send an email therefore carry no `@Transactional` of their own; a second bean (`TenantRegistrationWriter`) owns the actual write so the caller only sends the email once that write has genuinely committed, proven by `TenantRegistrationCommitOrderingIT` rather than assumed.

**The tenant-code validation rule is shared, not duplicated, between the platform and public creation paths (decided in backend C9).** `TenantCodeRule` — required/format/reserved/duplicate — is the same class `TenantService.create` and `TenantRegistrationWriter.register` both call; the two endpoints' other required-field messages differ (`"Tenant name is required"` vs `"Store name is required"`, matching each endpoint's own wire field name), so only the code rule, which is genuinely identical either way, was extracted.

**`tenant.status` is `VARCHAR(32)`, not the usual `VARCHAR(16)` (decided in backend C9, `backend/BUGS.md` #18).** `PENDING_VERIFICATION` is 21 characters; MySQL rejects an over-length `VARCHAR` insert outright rather than truncating, so every `register()` call failed 500 until this was widened. Found by manually testing the new endpoint, not by `mvn test` — nothing in the automated suite persisted that status until C9 gave it a producer.

---

## 10. Edge cases to handle in v1

- Same product, multiple variants, different MRPs → handled by the variant-level model (Section 2).
- Unknown code scanned → clean "not found" message, scanner stays ready.
- Item already in cart → increment quantity, don't duplicate the line.
- Quantity edit: minimum 1; removing the line is how you reach zero. (Optional: warn when quantity exceeds `stockQuantity`, don't hard-block yet.) **The frontend only warns — the hard availability check (rejecting a cart quantity that exceeds sellable stock) is a backend responsibility at checkout, since stock is authoritative server-side and can change between terminals. Do not hard-block on the client.**
- `sellingPrice > mrp`, or duplicate code/SKU → validation errors on the product form.
- Rounding: grand total rounds to nearest rupee; `roundOff` tracked and shown.
- Out-of-stock variant (`stockQuantity === 0`) → still findable (search/scan resolve it), but **not added to the cart on the client**: a scan of a stock-0 variant shows an "out of stock — not added" warning instead of creating a phantom line, so the operator gets immediate feedback. (Distinct from the warn-not-block case above: an *in-stock* variant whose cart quantity is later raised beyond its stock still warns without blocking — that's the backend-authoritative case. Only the trivially-unsellable stock-0 add is blocked client-side, for UX.)
- **Returns:** can't return more than purchased (respect already-returned quantities); partial returns allowed; refund restores stock; refund math uses snapshotted sale prices.
- **Auth/RBAC:** unauthenticated access → redirect to login; a cashier hitting an admin route → blocked (guard + hidden controls); expired/missing token → force re-login.
- **Tenant isolation (Section 13):** a tenant-scoped user must never see or touch another tenant's data — every list is pre-filtered by the session `tenantId`, and any direct fetch of an out-of-tenant id resolves as **not found** (a resumed/looked-up order, a receipt/credit-note URL, a variant scan all fail cleanly as if the id didn't exist). A **suspended tenant** can't log in (403) and its active sessions stop transacting. A `SUPER_ADMIN` has **no** tenant context, so the POS/catalog/history screens are unavailable to it (not just empty). Unknown tenant code at login → generic 401 (don't confirm which tenants exist).
- Camera permission denied/unavailable → fall back gracefully to the keyboard-wedge input.

---

## 11. Build phases (do these in order; verify after each)

- **Phase 0 — Scaffold:** Vite + React + Tailwind, router, folder structure (Section 8). App runs with a placeholder page.
- **Phase 1 — Auth & RBAC:** mock users, `authService`, `authStore`, login page, `ProtectedRoute` with role checks, session persistence, logout.
- **Phase 2 — Domain + mocks + services:** data shapes, `domain/pricing.js` + unit tests, mock seed data, remaining service modules.
- **Phase 3 — Products management (admin):** product list + form + variant management + **QR generation/persistence** + label preview/print.
- **Phase 4 — POS checkout:** keyboard-wedge + **camera** scanning, variant lookup, cart with editable quantities, live totals with GST breakup.
- **Phase 5 — Payment + receipt:** single full payment, GST breakdown shown **before confirm**, completed order, printable receipt with GST breakup.
- **Phase 6 — Returns / refunds:** order lookup, partial/full return selection, refund computation, stock restore, printable credit note.
- **Phase 7 — Polish:** ✅ **done** — hold/resume orders (one record per sale, no duplicates; clearing a resumed cart cancels it), order history + return history (both cashier-scoped vs admin-sees-all), a dedicated credit-note view (single print path), user management (list/create/deactivate), keyboard shortcuts (F2/F4/F6/Esc on Checkout), and a loading/empty/error pass with shared `StatusBadge` + `EmptyState` primitives. See `frontend/prompts/features/orders.md`, `users.md`, and the updated `checkout.md` / `returns.md`.
- **Phase 8 — Multi-tenancy (Section 13):** ✅ **done** — flat tenant model (tenant = store), `tenantId` on every domain record, per-tenant uniqueness (username / sku / qrCode / order & return numbers), tenant-scoped service layer (tenant from session, not client), tenant-code login, `SUPER_ADMIN` role + tenant-management screen, and cross-tenant isolation. Built in seven reviewable steps (B1–B7) per `multi-tenant-plan.md`, verifying isolation after each. Key pieces: `mocks/session.js` (the ambient session standing in for the server reading the JWT), `services/_tenant.js` (the mandatory scoping helpers — the mock's equivalent of a per-request Hibernate `@Filter`), and `services/tenantService.js` (the only cross-tenant module, `SUPER_ADMIN`-gated). Covered by `services/isolation.test.js`. See `frontend/prompts/features/multi-tenancy.md`.

**The frontend is now feature-complete against this spec.** Next: the DB schema and the Spring backend (Section 1 stack; the isolation model sketched in `multi-tenant-plan.md` Part C).

After each phase: run the app, confirm the behavior manually, pause for review. Keep commits small.

---

## 12. Resolved decisions

- **Language:** JavaScript (no TypeScript).
- **Styling:** Tailwind CSS.
- **Scanning:** camera-based (primary) + keyboard-wedge fallback.
- **Payment:** single payment, full amount (no split tender).
- **GST:** inclusive; breakdown shown before confirmation and on the receipt.
- **Code symbology:** **QR only, permanently** (Phase 2 decision) — no `barcodeType`/pluggable-symbology abstraction; each variant has a single `qrCode` string. Renderer takes `{ value }`.
- **Returns/refunds:** in v1.
- **Auth & RBAC:** in v1 — roles `ADMIN` and `CASHIER`, mocked on the frontend, designed for Spring Security later.
- **Testing:** **Vitest** is the test runner (`npm test`). Domain math in `domain/pricing.js` is unit-tested; `test:watch` for watch mode. On the backend it's **JUnit 5** (`mvn test`).
- **Backend API docs (C1):** OpenAPI 3 + Swagger UI, but **generated by our own `com.pos.util.OpenApiGenerator`** rather than a library. Neither usual option survives the "Spring MVC, **not** Spring Boot" constraint in §1: **springdoc** declares `spring-boot-autoconfigure` at compile scope, and **springfox** — the classic no-Boot answer — last released in July 2020 and compiles against `javax.servlet`, so it cannot load on Spring 6's `jakarta` namespace. Only one of Swagger's three jobs was actually missing (rendering and schema derivation are both Boot-free), and Spring already exposes the rest through `RequestMappingHandlerMapping`. Served at `/swagger-ui/`, spec at `/api/openapi.json`. See `backend/prompts/c1-skeleton.md`.
- **Backend persistence (C2):** Hibernate **6.6** (not 7.x — Hibernate 7 needs Jakarta Persistence 3.2, which Spring supports only from Framework 7) + MySQL 8.0.16+ via HikariCP, pool size set explicitly for the free-tier connection cap. Schema is generated from entities (`hbm2ddl`) with **no migration tool**, and the generated `backend/schema.sql` is committed; `SchemaSqlTest` fails the build if it drifts. Two Hibernate defaults are deliberately overridden because they contradict the schema design: enums would otherwise become MySQL's native `ENUM` (which `update` can't extend) and booleans `bit` rather than `TINYINT`. **`hibernate.dialect` is deliberately left unset** — naming it without a version pins Hibernate to MySQL 5.7 and silently drops every `CHECK` constraint. Dev and test use separate databases (`pos_dev`, `pos_test`), both auto-created by `createDatabaseIfNotExist` so there is no manual setup step. Ids serialize as JSON **strings** via `com.pos.model.JsonId`, per field rather than as a blanket mapper rule, so paginated counts stay numeric. See `backend/prompts/c2-persistence.md`.
- **Backend auth (C3):** stateless **JWT** bearer tokens (HS256, jjwt), **BCrypt** password hashing, Spring Security 6.4 wired by hand as a **root-context** config. Token lifetime **12 hours, no refresh**; `POST /api/auth/logout` is an acknowledgement only — there is no revocation list, so the token stays valid until it expires. The token carries only subject, tenant and role: **the user row is re-read on every request**, which is what makes a mid-session suspension or deactivation take effect at the next call rather than at the next refresh (§13.6's first deliberately-undone item, now done). The login body is **not** bean-validated — a blank tenant code must fail as a login (401, generic) rather than as a malformed request (400), or the difference becomes an enumeration oracle. Dev seeding is a gated, idempotent startup listener. See `backend/prompts/c3-auth.md`.
- **Backend tenant scoping (C4):** a request-scoped `TenantContext` populated from the token by the security filter, and a Hibernate `@Filter` **auto-enabled on every session** that appends `tenant_id = ?` to every query against a tenant-owned entity — so a forgotten `WHERE` is impossible rather than discouraged. `applyToLoadByKey` is set, without which `em.find()` alone would be unfiltered. An absent tenant resolves to a **sentinel `-1`**, never to "unfiltered", so a forgotten guard returns nothing rather than everything. Two entities are permanently outside it: `tenant` (it *is* the discriminator) and `app_user` (authentication establishes which tenant a caller is in, so it cannot be scoped by its own answer). **Writes are not filtered** — an `INSERT` has no `WHERE` — so a write is scoped by whoever builds the entity. See `backend/prompts/c4-tenancy.md`.
- **Backend catalogue (C5):** `PUT` is a **merge patch** — a field absent from the body is left alone, which is what lets the client reactivate a row with `{"isActive": true}` alone — so the input DTOs carry no bean validation and the service validates the **merged row** instead. QR codes and the fallback SKU are **minted server-side** from that store's own `tenant_sequence` counter, taken under a row lock in the same transaction as the insert they number; the tenant row is locked first, which is the fix for a real deadlock rather than a precaution. Uniqueness is enforced by the **unique index**, whose violation is mapped onto the same field-level 400 the service's pre-check produces, so losing a race is invisible to the caller. `stockQuantity < 0` is rejected in the application layer as well as by the `CHECK` constraint — the frontend's validator has no equivalent rule because the mock had no constraint to answer to. See `backend/prompts/c5-catalogue.md`.
- **Product soft-delete:** Products gained an `isActive` flag (Phase 2) so `DELETE /api/products/{id}` can deactivate rather than remove; a product is treated as active unless `isActive === false`. **Verified, not just assumed (peer-review Phase 1):** `ProductDao`/`VariantDao` expose no hard-delete method at all, so a product or variant referenced by order/return history can't be removed through the application — and even a hypothetical raw `DELETE` would be rejected by the database, since `fk_order_line_variant`/`fk_return_line_variant`/`fk_variant_product` carry no `ON DELETE` clause (MySQL default `RESTRICT`). See `backend/prompts/database/constraints-and-indexes.md`.
- **Hold/resume = one order per sale (Phase 7):** resuming a `HELD`/`DRAFT` order continues that same record on re-hold or payment (no duplicate orders); clearing a resumed cart cancels the order. See Section 5.
- **History scoping (Phase 7):** order history and return history are cashier-scoped (own records only) for CASHIER and unscoped (all records) for ADMIN, via `cashierId` / `processedBy` filters.
- **Login error granularity (Phase 7):** login distinguishes bad credentials (401) from a deactivated-but-valid account (403); the 403 doesn't leak account existence. See Section 9.
- **Shared UI primitives (Phase 7):** `StatusBadge` (tone→class map) and `EmptyState` (nothing-yet vs filtered-to-zero) are the standard for status pills and empty states; `PAYMENT_METHOD_LABELS` in `domain/constants.js` is the single source for method display labels.
- **Multi-tenancy (Phase 8 — see Section 13):** **flat** model, **tenant = a single store**; `tenantId` on every domain record; **per-tenant** uniqueness (username / sku / qrCode / order & return numbers); QR payload embeds the tenant; a platform **`SUPER_ADMIN`** role above tenant `ADMIN`/`CASHIER`; **tenant-code** login with per-tenant usernames (reserved code `platform` = platform login); `tenantId` resolved **from the session, never the client**; cross-tenant reads resolve as 404; a suspended tenant can't log in (403). Org→multiple-stores hierarchy was considered and **deferred** — revisit only if a single merchant needs multiple outlets under one account.

- **Identity from the session (Phase 8):** `cashierId` / `processedBy` are derived from the session user (server-side: the token subject), not passed by the client — the same rule as `tenantId`, applied to the actor. `orderService.create` / `returnService.create` dropped those arguments.
- **Seed tenants (Phase 8):** `t1` MG Road Store (`mg-road`) and `t2` Airport Store (`airport`); QR payload format `POS-QR-{tenantId}-{seq}`.
- **Platform login uses a reserved tenant code (Phase 8/B3):** `SUPER_ADMIN` signs in with the code **`platform`**, not a blank field — revised during B3 from the original "leave it blank" decision. Reserved codes (`platform`, `admin`, `super`, `system`) are rejected by `validateTenantCode`, so a real tenant can never shadow the platform namespace. See §13.4 for the reasoning.
- **reCAPTCHA v2 on register + resend-verification only, not login (peer-review Phase 0):** the honeypot (`website`) stops a naive scraper but nothing that knows to leave it blank; a v2 "I'm not a robot" checkbox — not v3's invisible/score-based variant — is the backstop, matching the project's existing taste for explainable mechanisms (the honeypot itself, fixed-window rate limiting) over opaque ones. Login was deliberately left out: it already has `LoginRateLimiter` + `LoginAttemptGuard` (earlier in this same phase), and CAPTCHA friction there would hit every cashier's shift-start login for little added benefit. One Google site registration covers both `localhost` and the deployed `sslip.io` domain; the site key is public and committed in the frontend's `.env`/`.env.production`, the secret key is externalized on the backend the same way `jwt.secret`/`db.password` are. See `backend/prompts/c9-tenant-registration.md`.
- **HTML/XSS output-encoding pass came back with nothing to fix (peer-review Phase 0's dedicated OWASP pass):** this API has **no HTML-rendering surface at all** — every controller returns JSON, no Thymeleaf/JSP or `text/html` producer exists anywhere in `src/main/java` — so there's no server-side sink where an unencoded user-supplied field (product name, return reason, tenant/display name) could reach a browser as executable markup. The one place text leaves the system outside the JSON API, outgoing verification email (`JavaMailEmailSender`), uses Spring's `SimpleMailMessage` deliberately (see its Javadoc) — plain text, not a `MimeMessage`/HTML template, so there's no HTML-injection surface there either. The frontend side of the same pass (React's default JSX escaping, confirmed by a sweep for `dangerouslySetInnerHTML`/`.innerHTML`/`document.write`) is in `frontend/requirements.md` and `frontend/prompts/CONVENTIONS.md`. Revisit only if this API ever grows an HTML-producing endpoint or the email sender ever moves to an HTML template.
- **Resource-creation guardrails are two-tier, as of peer-review Phase 1:** the products/tenant, users/tenant and variants/product ceilings (Phase 0, above) originally counted every row ever created, active or not, deliberately — so a create/deactivate loop couldn't spam unlimited rows. That also meant they never released a slot, which a long-lived, high-churn tenant (retired SKUs, cashiers who left) could exhaust from history alone regardless of how small its *live* catalogue/roster actually was. Fixed by splitting each into two checks: the existing number (2000/20/50) now counts **active** rows only — `ProductDao.count(..., false)`, `AppUserDao.countActiveByOwnTenant`, `VariantDao.countActiveByProduct` — so deactivating something frees a slot again, the same reclaimable shape every other soft-delete in this codebase already has; a new, much higher **lifetime** number (10000/100/250 — 5x each real ceiling) counts any status, ever, the same queries Phase 0 originally used (`ProductDao.count(..., true)`, `AppUserDao.countByOwnTenant`, `VariantDao.countByProduct`) and is the pure abuse backstop that was the entire reason a durable ceiling was chosen over a time-windowed one in the first place. **`tenants.maxPerEmail` (5) deliberately stays single-tier** — the sharp form of that guardrail's version of this problem (abandoned `PENDING_VERIFICATION` registrations counting forever) is fixed differently, by outright deleting those rows (see the cleanup-job decision below) rather than by adding a reclaimable/lifetime split; the remaining edge case, a legitimate owner with several `SUSPENDED` tenants under one email, has no owner-facing "reactivate" state the way `isActive` gives the other three, so it was left as a rare case to revisit only if it's a real complaint. Manually verified end-to-end against a real running app (curl, products case: filled the active ceiling, confirmed 400, deactivated one, confirmed the create succeeds, then confirmed the lifetime backstop still rejects even with active headroom); each of the three guardrails also has automated IT coverage at its real threshold (`ProductWriteIT`, `UserWriteIT`, `VariantIT`) proving both the reclaim and the backstop. See `AppProperties`' "Two-tier guardrail backstops" comment block for the numbers.
- **Abandoned self-registrations are deleted, not merely capped (peer-review Phase 1):** `uk_tenant_code` is global, and nothing previously cleared an expired, never-verified `PENDING_VERIFICATION` tenant — a code once claimed stayed claimed forever, and the row kept counting against `tenants.maxPerEmail` besides. `com.pos.job.AbandonedTenantCleanupJob` (the first scheduled job in this codebase — new `SchedulingConfig`/`@EnableScheduling`, new `com.pos.job` package) runs `AbandonedTenantCleanupService.cleanUp()` every `pos.job.abandonedTenant.intervalMinutes` (6h default, plus once at boot), deleting any tenant still `PENDING_VERIFICATION` once its `verificationExpiresAt` has passed — no new time constant, that's the existing 24h token TTL — and that tenant's one admin `app_user` row first (`fk_app_user_tenant` has no cascade). `TenantDao.delete` is **the only hard delete anywhere in this codebase**, safe only because a `PENDING_VERIFICATION` tenant can carry nothing else (login is blocked pre-verification). See `backend/prompts/database/constraints-and-indexes.md`'s "Deleting a product/variant..." section for why every *other* delete in this system stays soft.
- **Every free-text form field is now length-bounded to match its column (peer-review Phase 1):** the mock had no schema to answer to, so nothing capped a string client-side or server-side — an overlong field reached MySQL's data-too-long error unmapped, an unhandled 500 rather than a clean 400. Since every `*Form` deliberately carries no bean validation (`PUT` is a merge patch; a field absent from the body has to stay legal), the bound is a service-layer check in each `validate()`, the same as every other rule — a new shared `com.pos.util.MaxLength` (`.check` for the "report every broken field" map style, `.require` for the single-fault-throw style, both already in use across services) rather than ten copies of the same `if (value.length() > max)`. Covers `ProductService` (name/brand/category/description/hsnCode), `VariantService` (variantLabel/sku), `UserService` (username/displayName), `TenantService`/`TenantRegistrationWriter` (name-or-storeName/adminUsername/adminDisplayName/adminEmail), `TenantCodeRule` (code/tenantCode — shared by both tenant-creation paths, and the sharpest finding: its regex bounded the character set but never the length at all), `ReturnService` (reason) and `PaymentService` (reference). **Password is capped at 72** on both create and update, in every one of `UserService`/`TenantService`/`TenantRegistrationWriter` — not a column width (the stored value is the BCrypt hash, not the raw password) but BCrypt's own input ceiling, so an uncapped password was a latent surprise (silent truncation or a thrown exception, depending on version) rather than a future one. Manually verified against a real running app (curl: an overlong product name now answers a clean 400 with a field message instead of the old unmapped 500; an overlong password answers the same rather than a BCrypt exception). Automated: one IT case per form (most combine every bounded field into a single multi-error request, matching each service's existing "report every broken field" style; the single-fault-throw services get one case per field since only the first violation is ever visible in one response) across `ProductWriteIT`, `VariantIT`, `UserWriteIT` (create and update), `TenantAdminIT`, `TenantRegistrationIT`, `ReturnWriteIT`, `PaymentIT`. `mvn test`: 431/431. The servlet-level max-request-body-size half of the same review item is a different mechanism (bounds the whole request, not one field) and is deliberately not done here — see the "Open decisions" note.
- **`sales_return` gained the `(tenant_id, created_at)` index `pos_order` already had (peer-review Phase 1):** `ReturnDao.list()` sorts `ORDER BY created_at DESC` for every `GET /api/returns` call, and `idx_return_tenant_processor`'s leftmost prefix only covered a `processedBy`-scoped read, not the ADMIN "all returns" case — a one-line `@Index` addition (`idx_return_tenant_created`), `schema.sql` regenerated in the same change. See `backend/prompts/database/constraints-and-indexes.md`.
- **`GET /api/orders` and `GET /api/returns` no longer N+1 their line items (peer-review Phase 1):** each row's lazy `lines` used to trigger its own `SELECT` — up to 201 round trips for one page. A batched `WHERE ... IN (:ids)` query, grouped by parent id, loads every order's/return's lines on the page in one call; `OrderService`/`ReturnService`'s `toData` mapper now takes the lines explicitly instead of reading the lazy collection itself. The wire contract is unchanged — every row still carries `items[]` exactly as `requirements.md`'s Order/Return shape (above) already promises — this is a backend-only fix, not a leaner list DTO, since trimming `items[]` from list responses would have been a contract change the frontend never asked for. `GET /{id}` and `PaymentService` still read the line set directly for their one row, which was never the N+1. (Originally `OrderDao.findLinesByOrderIds`/`ReturnDao.findLinesByReturnIds`; peer-review Phase 2's `@ManyToOne`-removal sweep moved them to `OrderLineDao.findByOrders`/`ReturnLineDao.findByReturns` once `lines` stopped being a lazy collection to begin with — same batching, same query shape, new home.)
- **`GET /api/tenants` no longer runs `3N+1` queries (peer-review Phase 1):** `TenantService.list()`'s per-tenant user/product/order counts (`AppUserDao.countByTenant`, `TenantDao.productCount`/`orderCount`) are now three `GROUP BY tenant_id` queries run once for the whole list (`countsByTenants`/`productCountsByTenants`/`orderCountsByTenants`), not three calls per tenant. `get`/`create`/`updateStatus` still use the original per-tenant counts, which is the right shape for mapping exactly one tenant.
- **Catalogue search (`LIKE '%term%'`) indexing strategy is documented, not implemented (peer-review Phase 1):** fine at today's scale (bounded to one tenant's rows by the filter, and the project's own no-pager ground rule), but a leading wildcard can't use any B-tree index and will be the first thing to slow down as a single tenant's catalogue grows past a few thousand SKUs. The strategy — MySQL `FULLTEXT` on `product(name, brand)` and `variant(sku, variant_label)` — is written up rather than built, because it isn't a drop-in swap: it changes substring matching to word/prefix matching (a UX decision), and it can't be expressed through this project's entity-annotation-driven schema pipeline the way every other index here is, so adopting it means either a one-off DDL step outside `schema.sql` regeneration or introducing a migration tool for the first time. See `backend/prompts/database/constraints-and-indexes.md`'s "Catalogue search indexing strategy" section for the full writeup and the revisit trigger.
- **No entity in `com.pos.pojo` navigates a relationship (peer-review Phase 2):** every `@ManyToOne`/`@OneToMany` — `product.getTenant()`, `order.getLines()`, `variant.getProduct()`, all of it — was removed in a full retroactive sweep across all 9 mapped entities, not just new code going forward. The item as originally raised in the review ("entities carrying zero FK constraints") was mis-stated: investigation found every relationship was already correctly mapped at the database level. The actual, opposite intent, confirmed with the user: minimize Java-level object-graph navigation while keeping every FK constraint exactly as it was, continuing this project's existing "hand-written DAOs, no lazy-navigable object graph" philosophy one layer further in. **Mechanism:** the real, writable field becomes a plain `Long` id; a second, no-accessor `@ManyToOne` sits on the identical column (`insertable = false, updatable = false`) purely so Hibernate's schema generation still emits the named `@ForeignKey` — a "DDL-only shadow association." Since every entity here uses field access, Hibernate needs no accessor for the shadow either, so it's unreachable from outside the entity file by construction, not by convention. A caller that needs the related row now makes an explicit DAO call — a single-row `find`, or (for a list/search read that used to `JOIN FETCH` a parent) an ad-hoc `JOIN ... ON` returning a small record tuple instead of an enriched entity, same single SQL join as before. Two bidirectional cascade collections (`PosOrderPojo.lines`, `SalesReturnPojo.lines`) became explicit writes through a new DAO per line entity (`OrderLineDao`, `ReturnLineDao`) instead of `cascade = ALL, orphanRemoval = true`. Every FK constraint's name, parent/child and `ON DELETE` behavior is unchanged — confirmed via `schema.sql` diffs after every entity, `add constraint` lines identical throughout. See `backend/prompts/CONVENTIONS.md`'s Persistence section and `backend/prompts/database/constraints-and-indexes.md`'s "Not Java-navigable, but still real constraints" section for the mechanism in full.

- **Product/variant `isActive` is kept in sync, both directions (peer-review Phase 3):**
  reverses the "deactivating a product doesn't cascade to its variants" half of the Phase 1
  product-deletion investigation above — found while manually verifying the Phase 2
  `opacity-60` fix on the mock frontend (2026-08-16), and confirmed the real backend matched
  the mock's no-sync behavior too before any code changed. Four rules, all in
  `ProductService`/`VariantService`, no schema change: deactivating a product cascades down
  to every variant; reactivating a product cascades down too, unconditionally (every variant
  ends up active, not "restore each one's own prior state" — full symmetry with
  deactivate's cascade); deactivating a product's last active variant auto-deactivates the
  product; reactivating any one variant of an inactive product auto-reactivates the product
  alone (a sibling that's still inactive stays that way). A product with zero variants is
  exempt from the last two ("sync-up") rules by construction — they only ever run as a side
  effect of a variant-level toggle, and a zero-variant product has none to toggle — not by a
  special-cased check; the two cascade-down rules still apply to it as a no-op. New
  `VariantDao.setActiveByProduct` (a bulk `UPDATE`, the cascade-down half); the cascade-up
  half counts active variants **before** mutating the one being toggled, the same
  "count-before-flip" shape `UserService.deactivate`'s last-admin guard already uses, so it's
  not an off-by-one. Both the dedicated `DELETE` and the merge-patch `{"isActive": false}`
  path on `PUT` cascade identically — the frontend only ever reaches deactivation through
  `DELETE`, but the two mean the same thing on the wire and the backend treats them alike.
  Manually verified end-to-end against a real running app (curl: all four rules, the
  zero-variant exemption, and both the `DELETE` and merge-patch paths on each side) before
  writing the automated tests, per `backend/prompts/CONVENTIONS.md` §2. Automated: a
  dedicated `ProductVariantActiveSyncIT` (7 cases) rather than folding into
  `ProductWriteIT`/`VariantIT`, since this behavior is the two of them talking to each other
  rather than a CRUD case belonging to either endpoint family alone — the same reasoning
  `AbandonedTenantCleanupServiceIT` got its own file. `mvn test`: 441/441. See
  `backend/prompts/database/constraints-and-indexes.md`'s "The product/variant active-status
  sync" section for the full mechanism.
- **Product images via GCS, backend half (peer-review Phase 3):** the image bytes never
  transit the backend — `POST /api/products/{id}/image-upload-url` mints a short-lived
  signed GCS `PUT` URL scoped to a fixed, deterministic object path
  (`{tenantId}/{productId}/image`), the frontend PUTs directly to GCS, then
  `PUT /api/products/{id}/image` stamps `ProductPojo.imageUpdatedAt` (nullable —
  `NULL` is the whole "no image" signal, there is no separate boolean or stored
  path/URL column) once the upload has actually completed. `GET`/list responses'
  `imageUrl` field is a signed **read** URL minted fresh by `ProductService.toData`
  on every response, never stored (a stored one would eventually go stale).
  `DELETE /api/products/{id}/image` deletes the GCS object synchronously in the same
  request — the resolved design decision over leaving it orphaned. One image per
  product means a *replacement* upload is a plain GCS overwrite at the same path, no
  separate delete needed. All three endpoints are `ADMIN`-only.
  `com.pos.util.images.GcsImageSigner` hand-rolls GCS's V4 URL-signing algorithm
  against `google-auth-library-oauth2-http` rather than the full
  `google-cloud-storage` SDK — verified via `mvn dependency:tree` that the SDK pulls
  in the full gRPC stack, OpenTelemetry's SDK, OpenCensus and a transitive Cloud
  Monitoring client, wildly disproportionate for "sign a URL and delete a blob" on
  the 1GB deployment VM. Signing is local RSA-SHA256 (`ServiceAccountCredentials.sign`),
  zero network calls per signature — the reason this design uses a downloaded signing
  key over the VM's attached identity via IAM `signBlob` (iac/requirements.md
  decision #21): safe to mint a signed URL on every row of a paginated products list
  without reintroducing the N+1-shaped cost Phase 1 eliminated for orders/returns.
  Feature-flagged off by default (`pos.images.enabled`, same shape as
  `pos.recaptcha.enabled`/`pos.mail.enabled`) — `NoopImageSigner` throws rather than
  faking success if ever reached, since (unlike a logged email or an always-pass
  captcha check) there's no harmless local substitute for "upload to a bucket that
  doesn't exist"; the real, clean rejection is `ProductService`'s own
  `pos.images.enabled` check, ordered *after* the existence/tenant check and request-
  shape validation so a cross-tenant id still answers its usual 404 and a bad
  content-type still answers its usual 400, regardless of whether images are enabled
  on this deployment at all. Manually verified end-to-end against the real live
  bucket (a signed PUT succeeded and was genuinely rejected by GCS itself on a wrong
  `Content-Type` or an oversized body, not merely client-side; a signed GET
  round-tripped real content while an unsigned GET got 403; delete removed the
  object and was safely idempotent) before writing the automated
  `ProductImageIT`/`GcsImageSignerTest`/`NoopImageSignerTest`/`ImagesConfigTest` —
  the real GCS round trip is structurally unreachable from `mvn test` and stays
  manually verified only, the same boundary `GoogleRecaptchaVerifierTest` draws for
  the real Google network call. `mvn test`: 465/465. Frontend upload UI not built
  yet. See `backend/prompts/c5-catalogue.md`'s "Product images" section,
  `backend/prompts/database/{schema,er-diagram}.md`, `iac/prompts/06-product-images.md`,
  and `review/peer-review.md`'s Phase 3 item for the full cross-repo design.
- **Logging audit came back clean, with one real gap fixed (peer-review Phase 2):** the codebase already used slf4j + **Log4j2** (not Logback — see the "Stack" §1 note above) consistently everywhere it logged, with one identical `Logger`-declaration shape across all nine classes that log, and zero `System.out`/`System.err`/`printStackTrace` anywhere in `src/`. The one inconsistency found: `AuthService.requireUsable()`'s three 403 branches (deactivated user, pending-verification tenant, suspended tenant) threw with no log line at all, unlike every sibling rejection in the same class — and because `JwtAuthenticationFilter` catches `ForbiddenException` itself and responds directly when this fires from `resolveSession()` (a live session cut mid-shift), that path never reaches `ApiExceptionHandler`'s own generic log line either, so the event left no trace anywhere. Fixed by logging at DEBUG at each throw site, matching the log-then-throw shape `resolveSession()`'s own `InvalidCredentialsException` branches already used three lines above. Manually verified against a real running app (deactivated a live cashier session mid-shift, suspended a live tenant admin's session mid-shift, confirmed both new DEBUG lines in the console). No new automated test — a logging-only change with no behavior/response difference, and existing IT coverage already exercises all three `requireUsable()` branches for status code/message. `mvn test`: 434/434. See `backend/prompts/CONVENTIONS.md`'s new "Logging" section for the full convention.

**Still open / future scope:** weight-based selling (`KG`/`LITRE`), discounts/promotions, **multi-store under one tenant** (the org→stores hierarchy deferred from Phase 8) and multi-terminal, a `MANAGER` tier or refund-approval thresholds, and putting a QR on receipts to speed up returns.

---

## 13. Multi-tenancy (architecture)

The app is **multi-tenant**. This section is the authoritative reference; the other sections were updated to point here (§3 data model, §5 login/RBAC/screen 10, §6 QR, §7 services, §9 API, §10 edge cases, §11 Phase 8, §12 decisions).

### 13.1 Model — flat, tenant = store

A **Tenant is a single store** (a merchant/shop). There is **no** org→stores hierarchy in this version: each store is its own independent tenant with its own catalog, stock, users, orders, and returns. (The two-level org→multiple-stores shape was considered and **deferred** — §12; the flat model keeps tenant scoping to a single `tenantId` column rather than an org/store pair, and stays additive to the existing foundations.)

### 13.2 Roles

| Role | Belongs to a tenant? | Can do |
|---|---|---|
| `SUPER_ADMIN` | **No** (platform) | Create/suspend/reactivate tenants; provision each tenant's first `ADMIN`; view the tenant list. **Not** a cashier — has no tenant context, so POS/catalog/history/user screens are unavailable to it. |
| `ADMIN` | Yes (exactly one) | Everything a cashier can, **plus** products/variants, user management, and all orders/returns — **all within their own tenant only**. |
| `CASHIER` | Yes (exactly one) | Checkout, payment, hold/resume, returns, print, and their **own** order/return history — within their tenant. |

`SUPER_ADMIN` is added to `domain/roles.js` alongside `ADMIN`/`CASHIER`. It is **not** a superset of tenant permissions layered with cross-tenant reach: it is a *different* surface (platform management), and it deliberately **cannot** read a tenant's catalog/orders/returns through the POS screens. (If "enter a tenant for support" is ever wanted, that's a future explicit impersonation feature, not implicit cross-tenant access.)

### 13.3 Scoping rules

- **`tenantId` on every domain record** except `TenantPojo` itself and platform (`SUPER_ADMIN`) users (§3).
- **Uniqueness is per tenant**, never global: `(tenantId, username)`, `(tenantId, sku)`, `(tenantId, qrCode)`, and per-tenant `orderNumber` / `returnNumber` sequences. Tenant `code` is the **one** globally-unique field (it's the login discriminator), and a small set of codes is **reserved** so no tenant can shadow the platform login (§13.4).
- **`tenantId` comes from the session, never the client.** Service/API call signatures do **not** take a `tenantId` argument. The backend reads it from the JWT claim; the mock reads it from the logged-in user. Every read/write is filtered by it.
- **Isolation is absolute and fail-closed.** A tenant-scoped caller can never read/write another tenant's data. Direct fetch of an out-of-tenant id resolves as **not found** (404 / `null`), not forbidden — so ids in one tenant don't reveal existence in another. This covers resumed orders, receipt/credit-note URLs, and QR scans alike.

### 13.4 Login & tenant resolution

- Login body: `{ tenantCode, username, password }`.
  - **A real tenant code** → resolve the tenant by code, then the user by `(tenantId, username)`. Reject (generic **401**) if the tenant code is unknown or blank, the username is unknown *within that tenant*, or the password is wrong.
  - **The reserved code `platform`** → platform login: resolve a `SUPER_ADMIN` by username in the reserved platform namespace. Chosen over "leave the field blank" so the value is explicit at every layer (form, request body, server logs) rather than an unreadable empty string, and so a tenant user who forgets their code gets a plain failed login instead of silently landing in the platform namespace. **No tenant may register a reserved code** (`platform`, `admin`, `super`, `system`) — enforced by `validateTenantCode`, which is what keeps the two namespaces from colliding.
  - Valid credentials but the **user is deactivated** or the **tenant is `SUSPENDED`** → **403** (only reaches a caller who already supplied correct credentials, so no account/tenant enumeration).
- The issued token encodes the resolved `tenantId` (or none, for a platform user). `GET /api/auth/me` restores it. In the mock, the persisted session (localStorage) carries the same, so a refresh keeps both identity and tenant.

### 13.5 Mock strategy (so isolation is exercisable with no backend)

- Seed **two tenants** — `t1` **MG Road Store** (`mg-road`) and `t2` **Airport Store** (`airport`), both ACTIVE — each with its own products/variants/stock/orders and an `admin` + `cashier` (usernames intentionally repeat across tenants to prove per-tenant uniqueness), **plus one platform `SUPER_ADMIN`**.
- The in-memory store keeps a `tenants` array and stamps `tenantId` on seeded records; the ID/number/QR generators become **per-tenant** (a tenant segment in the QR payload; per-tenant order/return counters).
- Every service filters its array by the current user's `tenantId` before doing anything else — the same one-line guard the backend applies. `tenantService` (platform) is the only module that operates across tenants and is `SUPER_ADMIN`-gated.
- The active tenant rides on `AuthContext`'s user (`tenantId` + `tenantCode`/`tenantName` for display) rather than a separate `TenantContext` — the POS screens read it implicitly through the services, so most components didn't change. The header names the active store, because usernames repeat across tenants and "admin" alone doesn't tell an operator whose till they're on.

### 13.6 Implementation — **done**, and how it actually landed

Built in seven reviewable steps (B1–B7; see `multi-tenant-plan.md` for the record, and `frontend/prompts/features/multi-tenancy.md` for how the result works). The five sub-steps originally sketched here held up, with these refinements worth carrying into the backend:

1. **Data + store** — plus an **ambient session** (`mocks/session.js`) as the mock's stand-in for the server reading the JWT. This is the piece that makes "tenant from session, never the client" literally true rather than a convention.
2. **Auth + session** — tenant-code login, and the platform login moved from a blank code to the reserved code **`platform`** (§13.4).
3. **Service scoping** — done through mandatory helpers (`services/_tenant.js`) rather than per-service hand-filtering, mirroring the backend's per-request Hibernate `@Filter`. A service that filters by hand is one that can forget to.
4. **Platform surface** — `tenantService` + `pages/Tenants/`, plus **route gating**. Learned the hard way (BUGS.md #11): keeping a `SUPER_ADMIN` off the POS screens is a *routing* concern; the service check is defence in depth. A guard whose visible behaviour depends on whether a screen fetches on mount isn't a guard.
5. **Isolation edge-case pass** — automated as `services/isolation.test.js` (23 cases) rather than left to manual checking, then confirmed manually in the browser.

**Two things deliberately left undone**, both to revisit with the backend: a suspended tenant's already-open tab keeps working until it refreshes (`me()` is where status is re-checked — true mid-session enforcement needs a per-call check, which the backend gets for free from `TenantContext`), and there is no `SUPER_ADMIN` impersonation of a tenant.

---

_This file will be updated as development and ideas progress. Treat Sections 2–4 as stable design decisions; the rest can evolve._
