# C1 — Skeleton

**Status: done.** `mvn jetty:run` serves JSON on `http://localhost:8080`; `mvn test`
runs 23 tests with no database.

What this step established: the Maven build, the two Spring contexts, JSON and CORS,
the error-mapping layer, the package layout, and API documentation. No persistence,
no security, no domain logic — those are C2 and C3.

## What runs

| URL | What |
|---|---|
| `GET /api/health` | Liveness. Unauthenticated by design (C3 leaves it outside the security chain) |
| `GET /api/openapi.json` | The OpenAPI 3 document, generated from Spring's handler mappings |
| `/swagger-ui/` | Swagger UI, pointed at the above |

## Layout

```
com.pos
  config/      WebAppInitializer · RootConfig · WebConfig · OpenApiConfig
  controller/  HealthController · OpenApiController · ApiExceptionHandler
  exception/   ApiException + the four domain failures
  model/       ApiError · HealthData          (Form = in, Data = out)
  service/     empty until C3
  dao/         empty until C2
  pojo/        empty until C2
  util/        OpenApiGenerator
```

`exception/` is an addition to the package table in [CONVENTIONS.md](./CONVENTIONS.md).
The conventions require services to throw domain exceptions rather than anything
HTTP-shaped, but didn't say where those live; a package of their own keeps them out of
`model/`, which is the wire contract.

## Decisions worth knowing

### Two Spring contexts, not one

`WebAppInitializer` declares a **root** context (`RootConfig` — services, DAOs; C2 adds
persistence, C3 security) and a **servlet** context (`WebConfig`, `OpenApiConfig` —
controllers, converters, handler mappings).

The split matters for C3: **servlet filters can see the root context but not the servlet
context**, so Spring Security's filter chain has to be a root-context bean. Getting this
wrong is normally discovered halfway through wiring security.

`RootConfig` deliberately does not scan `com.pos.controller`. If both contexts scanned
it there would be two sets of controller beans and the `@ControllerAdvice` would appear
to stop working on one of them.

### The message-converter list is written out, not extended

`WebConfig#configureMessageConverters` **replaces** the defaults rather than extending
them, so what the app can read and write is stated in one place. Four converters:
byte array, string, resource, Jackson.

`StringHttpMessageConverter` is load-bearing, not filler — `OpenApiController` returns
a pre-serialized `String`, and without it that endpoint fails.

### Jackson: nulls are kept

`ObjectMapper` registers `JavaTimeModule` (ISO-8601 timestamps rather than epoch
arrays) and ignores unknown properties on the way in.

Null inclusion is left at `ALWAYS` **on purpose**. The contract has meaningful nulls —
a platform user's `tenantId` is `null` — and omitting the key would read as "not
provided" rather than "belongs to no tenant". A `NON_NULL` default would quietly break
that distinction.

Not done yet: **ids-as-JSON-strings** (`backend-plan.md` §2). No entity exists to
serialize; it lands in C2 on the DTO id fields.

### 404s go through the error envelope

`WebAppInitializer#customizeRegistration` sets `throwExceptionIfNoHandlerFound=true`.
Without it an unmapped URL returns an empty-bodied 404 and the frontend's error path
gets nothing to read. With it, `ApiExceptionHandler` answers in the same
`{message, fields}` shape as everything else.

### `-parameters` is required, not cosmetic

Spring 6 removed bytecode-based parameter-name discovery. Without
`<parameters>true</parameters>` in the compiler plugin, an unnamed `@PathVariable` or
`@RequestParam` **fails at runtime**, and `OpenApiGenerator` cannot name parameters
either.

## API documentation — why there's a class instead of a dependency

The plan called for Swagger from the start. Neither usual library works here:

- **springdoc** declares `spring-boot-autoconfigure` at **compile** scope, and the beans
  it needs come from Boot auto-configuration. Using it means putting Boot on the
  classpath of a project whose premise is not using Boot.
- **springfox** — the classic no-Boot answer, and the one the Increff-style tutorials
  use — last released **July 2020** and compiles against `javax.servlet.ServletContext`.
  On Spring 6 / Jetty 12 those classes don't exist, so it cannot load. Reviving it would
  mean downgrading to Spring 5.3 (OSS support ended August 2024) and Hibernate 5, which
  reshapes C2.

But "Swagger" is three jobs, and only one of them was actually missing:

| Job | Provided by | Boot-coupled? |
|---|---|---|
| Render the docs page | `org.webjars:swagger-ui` | No — static assets |
| Build the spec model, derive JSON Schema from Java types | `swagger-core-jakarta` | No |
| **Read Spring MVC annotations into paths and operations** | springdoc only | **Yes** |

The third is small, because Spring already holds the data:
`RequestMappingHandlerMapping.getHandlerMethods()` returns every URL pattern, HTTP
method and handler signature in the application. `com.pos.util.OpenApiGenerator` walks
that map and feeds the types through swagger-core's `ModelConverters`.

**What it covers:** path/query/header parameters, request bodies, response types
including generics and `ResponseEntity` unwrapping, `@Operation` prose, controller-name
tags, `@Deprecated`. Unrecognised parameters are skipped with a debug log rather than
guessed at — a missing parameter in the docs beats a wrong one. Output is sorted so the
document is stable between runs, and cached, since the handler map is fixed after
startup.

**Extending it** is the normal path when C5–C8 introduce a shape it doesn't know:
`describeParameter` for a new binding annotation, `responsesFor` for a new return
wrapper. Add a case to `OpenApiGeneratorTest` at the same time — it runs against the
real handler mappings, so it is also what catches a controller that stopped being
discoverable.

**Swagger UI's version appears twice** — `<swagger.ui.version>` in the POM and
`OpenApiConfig.SWAGGER_UI_VERSION` — because the webjar puts its assets under a
version-numbered path and a resource location cannot wildcard.
`SwaggerUiResourcesTest` fails the build if they drift, which is what makes the
duplication safe rather than a latent 404.

Our `index.html` (in `src/main/resources/swagger-ui/`) shadows the webjar's by being
first in the resource-location list, so the UI points at our generated spec. A classpath
directory has no implicit index page, so `/swagger-ui/` and `/swagger-ui` are explicit
redirects to `/swagger-ui/index.html` — without them the URL a person actually types
404s while `/swagger-ui/index.html` works.

## Error mapping

`ApiExceptionHandler` is the only place a status code is chosen. Services throw from
`com.pos.exception` and stay ignorant of the web.

| Exception | Status |
|---|---|
| `InvalidCredentialsException` | 401, one fixed message |
| `ForbiddenException` | 403 |
| `NotFoundException` | 404 — also the answer for a cross-tenant id |
| `ValidationException`, `MethodArgumentNotValidException` | 400 + field → message |
| anything else | 500, message says nothing |

Two things here are security properties rather than style:

- **`InvalidCredentialsException` takes no message argument.** Unknown tenant code,
  unknown username and wrong password must be indistinguishable, and a constructor that
  accepts a message is an invitation for a future caller to helpfully narrow it. The
  string matches the frontend's `INVALID_CREDENTIALS` verbatim.
- **`ForbiddenException` may be specific** only because it's unreachable until the
  password is proved. That holds only while the status checks run *after* the password
  check — reverse them in C3 and a specific 403 becomes an account-existence oracle.

The 500 handler logs at ERROR with the stack trace and returns `"Something went wrong"`.
`ApiExceptionHandlerTest` asserts a thrown message containing a fake connection string
does not reach the response.

## Tests (23, no database)

| Suite | Proves |
|---|---|
| `HealthControllerTest` | The real `WebConfig` boots and serializes; timestamps are ISO-8601 |
| `ApiExceptionHandlerTest` | The whole status matrix, the generic 401, and that 500 leaks nothing |
| `OpenApiGeneratorTest` | Paths, verbs, tags, `@Operation` prose, response schema resolution, caching |
| `OpenApiControllerTest` | The spec is reachable, uses swagger's serializer (`$ref` survives), and the docs URL redirects |
| `SwaggerUiResourcesTest` | The webjar version constant matches the POM |

`src/test/resources/log4j2-test.xml` silences `ApiExceptionHandler` during tests: the
500 case deliberately throws, and printing its stack trace on a green run trains people
to ignore real ones.

## Not in this step

Persistence, entities, `TenantContext`, the Hibernate filter, security, JWTs, and any
domain logic. **Nothing here is tenant-aware yet** — C4 is where that spine lands, and
`GET /api/health` stays outside it deliberately.
