# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A standalone Spring Boot service (`notification`, package `com.skateboard.notification`) that owns push
notifications for the platform, in hexagonal (ports & adapters) style. It is the first service here to
consume asynchronous events: `skateboard-podcast-be` publishes `PODCAST_PUBLISHED` to RabbitMQ, and this
service decides whether that becomes a notification, who receives it and how it is delivered.

The governing principle, and the reason this is a separate service rather than a hook in `podcast-be`:

> Business services describe **what happened**.
> This service decides **whether a notification should be generated, who should receive it, and how it
> should be delivered**.

`api/openapi.yaml` is the **canonical, build-driving spec**. `pom.xml`'s `openapi-generator-maven-plugin`
generates `DevicesApi`, `PreferencesApi` and the request/response DTOs from it at build time into
`com.skateboard.notification.infrastructure.web.{api,dto}`. Edit that file to change the API.

## Build & run

- `mvn package` — runs the openapi-generator step first, then compiles and runs tests.
- `mvn spring-boot:run` — runs on `:8084` (8080 podcast-be, 8081 Expo/Metro, 8082 user-be, 8083
  app-config-be, 8090 ui-backend, 8180 Keycloak). Needs Postgres, RabbitMQ and Keycloak, all in
  `../skateboard-infrastructure/.docker/docker-compose.yaml`.
- Flyway applies `src/main/resources/db/migration` to the `skateboard_notification` schema inside the
  `skateboard` database. The schema name is **underscored in every profile**; podcast-be and user-be
  disagree with themselves between `application.yml` and `application-railway.yml`, which forces quoted
  identifiers — do not copy that.
- `mvn test` — the unit tests need nothing. `NotificationPersistenceIntegrationTest` and
  `PodcastPublishedIntegrationTest` need a Docker daemon for Testcontainers (Postgres 16, RabbitMQ 4),
  the same as the integration tests in `skateboard-podcast-be` and `skateboard-user-be`.

## Architecture

```
adapter/in/rest         → NotificationDeviceController, NotificationPreferenceController
                          (implement the generated interfaces; @PreAuthorize mirrors
                          x-required-permissions in api/openapi.yaml)
adapter/in/messaging    → PodcastPublishedEventListener (@RabbitListener) + events/ records
adapter/out/persistence → *JpaEntity, Spring*Repository, and the @Component adapters that
                          implement the outbound ports and own domain↔entity mapping
adapter/out/push/expo   → ExpoPushNotificationProvider — the only class that knows Expo exists
application/port/in     → one interface per use case, each with nested Input/Result records
application/port/out    → DeviceRepositoryPort, PreferenceRepositoryPort, NotificationRepositoryPort,
                          DeliveryRepositoryPort, ProcessedEventPort, PushNotificationProviderPort
application/service     → one @Service per use case, plus DispatchNotificationService and
                          NotificationTemplateResolver
domain/model            → NotificationDevice, NotificationPreferences, Notification, UserNotification,
                          NotificationDelivery + the enums. No framework annotations here.
infrastructure/         → messaging (topology), push (config), security, web
```

### Five tables, kept apart deliberately

`notification_device` (where), `notification_channel_setting` + `notification_preference` (whether),
`notification` (what happened, once), `user_notification` (who, and read state),
`notification_delivery` (one attempt to one device). Collapsing any pair makes the others wrong: a
`SENT` delivery is not a read notification, and one notification must not be duplicated per recipient.

`processed_event` is the idempotency ledger.

### Things that are load-bearing

- **A missing preference row means enabled.** That reproduces the `DEFAULT true` these preferences had
  while `skateboard-user-be` owned them, which is what let this service take the feature over without
  migrating anyone or changing the frontend. The fan-out query encodes it as
  `COALESCE(cs.push_enabled, TRUE)` / `COALESCE(p.push_enabled, TRUE)` over left joins.
- **The `/preferences` contract is byte-compatible with user-be's `/me/preferences`** — same nested
  `notifications` object, same `FUNC_USER_SELF_READ`/`FUNC_USER_SELF_UPDATE`. `skateboard-ui-backend`
  re-points one route at this service and the mobile settings screen is untouched. Do not reshape it
  without changing the BFF, `bff-openapi.yaml` and the app together.
- **Registering a push token another user holds disables their registration.** A token identifies a
  handset, not an account; without this a shared phone keeps receiving the previous account's
  notifications whenever the client could not de-register (involuntary sign-out, crash, reinstall).
- **`tenant_id` is on every table and in every recipient query**, though exactly one tenant exists. The
  isolation is cheap to guarantee now and expensive to retrofit.
- **Idempotency is a primary-key collision, not a check-then-insert.** `ProcessedEventPersistenceAdapter`
  claims the event id in its own `REQUIRES_NEW` transaction; two consumers handed the same redelivery
  would both pass a `SELECT`. Producers re-emit with a *stable* event id (podcast-be derives it from the
  post id), so a lost event is recoverable while a duplicate push is not retractable.
- **The Expo failure taxonomy.** `DeviceNotRegistered` disables the device; rate limiting, 5xx and
  transport failures stay retryable; anything else is a permanent rejection. A batch that never reached
  Expo is owed in full — never assumed sent. Expo answers `200` with a per-message ticket array, so an
  HTTP success can still contain dead tokens.
- **`@ImportAutoConfiguration(AopAutoConfiguration.class)` in the controller security tests.** The
  controllers implement generated interfaces and `@PreAuthorize` proxies them; a `@WebMvcTest` slice
  omits that autoconfiguration, so the proxy becomes a JDK dynamic one that carries no
  `@RestController`, and every route silently 404s.
- **Push tokens are never logged in full and never returned in a response.**

## Auth model

OAuth2 resource server against the shared `skateboard-podcast` Keycloak realm — the one realm every
backend uses, despite the name. `SecurityConfig`, `AudienceValidator`, `CorrelationIdFilter` and
`GlobalExceptionHandler` are copied per-service, which is this platform's convention rather than an
oversight; there is no shared library.

- `AudienceValidator` requires `skateboard-notification-be` in `aud`, populated by the
  `audience-notification-be` protocol mapper on both public clients. **The committed realm export is a
  dev fixture; production drifted from it and must be edited by hand** — without that mapper every
  device call fails closed with a 401.
- Authorities come verbatim (no `ROLE_`/`SCOPE_` prefix) from the `authorities` claim.
- `CurrentUserProvider` resolves the caller's id (`sub`) and tenant (`tenant_id`, falling back to
  `app.tenancy.default-tenant-id`). It throws rather than returning null, unlike podcast-be's
  per-controller helper: every endpoint here is scoped to the caller, so an unresolvable subject is
  never legitimate.

## Messaging

`EventTopology` holds the names shared with producers. Topic exchange `application.events`, durable
queue `notification.events` bound to `podcast.published.*`, dead-lettered to
`application.events.dlx` → `notification.events.dlq`. Retry (4 attempts, 2s doubling to 30s) and
`default-requeue-rejected: false` are in `application.yml`.

Bindings are per business event rather than `#`: a queue receiving everything would dead-letter every
type it has no handler for. Add a pattern when a handler exists.

The listener validates the envelope and translates — no notification policy lives there. A message that
can never succeed is dead-lettered immediately via `AmqpRejectAndDontRequeueException` rather than
retried four times first; anything else is rethrown so the backoff gets its attempts.
