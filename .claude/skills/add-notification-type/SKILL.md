---
name: add-notification-type
description: >
  Wire a new upstream domain event (NEW_POST, NEW_MAGAZINE, ADMIN_ANNOUNCEMENT, ...)
  end-to-end into a push notification: RabbitMQ binding, listener translation,
  use case, template copy, optional preference flag, and tests. Use whenever the
  task is "when X happens upstream, notify users" or "add a notification type".
---

# Add a notification type

This service turns a business fact a producer published onto RabbitMQ into "who
gets told, and how". Adding a type is deliberately small: a payload record, a
use case, a template entry, and a binding. It is **not** a schema change —
`notification.type` and `notification_preference.notification_type` are
`VARCHAR(40)` and every `NotificationType` value is already named in the enum.

Read `CLAUDE.md` first. The load-bearing rules there (idempotency claim inside
the recording transaction, send *after* commit, per-event bindings not `#`,
tokens never logged/returned) all apply to the new type unchanged. Do not
reintroduce `@Transactional` on the handler service, `REQUIRES_NEW` on the
claim, or a blanket `#` binding.

## Decisions to pin before coding

- **Event type string** — the producer's `eventType`, e.g. `MAGAZINE_PUBLISHED`.
- **Routing key / binding** — `<domain>.<event>.v1` and pattern `<domain>.<event>.*`
  (version lives in the key so a v2 can be bound alongside v1).
- **Payload version** — the integer the producer sets; each event type carries
  its own `SUPPORTED_VERSION`.
- **`NotificationType`** — reuse an existing enum value; add one only if none fits
  (no migration needed, but keep the name aligned with the event).
- **Reference type** — the short `referenceType` string stored on `notification`
  (`PODCAST`, `MAGAZINE`, ...), used by the app to route.
- **Template copy** — title + body, body may use `{{placeholder}}`.
- **User-facing opt-out?** — does the mobile Settings screen get a toggle for
  this type? If yes, the `/preferences` contract changes and that is a
  cross-repo change (see step 7). If no, nothing preference-side is needed —
  the fan-out's `COALESCE(p.push_enabled, TRUE)` already treats a missing row
  as enabled.

## Steps

Coordinate with the producer in parallel: it must publish `eventType` to
exchange `application.events` with routing key `<domain>.<event>.v1`, the shared
`DomainEventEnvelope` shape, and a **stable `eventId` across re-emissions** —
that id is the only thing preventing duplicates.

### 1. Payload record — `adapter/in/messaging/events/<Event>Payload.java`

Mirror `PodcastPublishedPayload`: a `record`, `@JsonIgnoreProperties(ignoreUnknown = true)`,
only the fields the producer sends. It describes the *business fact*, not the
notification — no title copy, no recipients, no push concepts.

### 2. Inbound port — `application/port/in/Handle<Event>UseCase.java`

Copy `HandlePodcastPublishedUseCase`: nested `Input` (`eventId`, `tenantId`,
`occurredAt`, then the payload-derived fields) and `Result` (`processed`,
`devicesTargeted`, `sent`) with a static `Result.duplicate()`.

### 3. Handler service — `application/service/Handle<Event>Service.java`

Copy `HandlePodcastPublishedService` structure exactly:

- constants `EVENT_TYPE` and `REFERENCE_TYPE`
- `templateResolver.resolve(NotificationType.X, Map.of(...))`
- `Notification.create(tenantId, type, title, body, imageUrl, referenceType, referenceId, dataJson)`
- `notificationRecorder.record(eventId, EVENT_TYPE, draft)` → if empty, `Result.duplicate()`
- `dispatchNotificationService.send(prepared.get())`
- one structured `log.info` line with `eventId`, `notificationId`, counts
- `buildData(...)` producing the deep-link map: `type`, `targetType`, `targetId`,
  `targetSlug` (whatever the app routes on)

**Not** `@Transactional`. The transaction lives entirely in `NotificationRecorder`;
the send happens after it commits.

### 4. Template — `application/service/NotificationTemplateResolver.java`

Add an entry to `DEFINITIONS` for the new `NotificationType`. If the map grows
past `Map.of`'s 10-pair limit, switch to `Map.ofEntries(...)`. Body is truncated
to 500 chars (matches `notification.body`); unresolved `{{placeholders}}` are
dropped, not rendered literally.

### 5. Topology — `infrastructure/messaging/`

- `EventTopology.java`: add `<EVENT>_ROUTING_KEY = "<domain>.<event>.v1"` and
  `<EVENT>_BINDING = "<domain>.<event>.*"`.
- `RabbitConfig.java`: add a `@Bean Binding <event>Binding(Queue notificationEventsQueue, TopicExchange applicationEventsExchange)`
  binding the **same** queue with the new pattern. One queue, multiple bindings.
  Do not add a second queue; do not use `#`.

### 6. Listener — `adapter/in/messaging/`

The queue has one consumer. Today `PodcastPublishedEventListener` owns it and
rejects any `eventType` it does not recognise. For the second type, evolve it
into a dispatcher rather than adding a competing `@RabbitListener` on the same
queue (consumers would round-robin and each would dead-letter the other's
events):

- Rename to `DomainEventListener` (or similar). Keep the single
  `@RabbitListener(queues = EventTopology.QUEUE)` `onMessage`.
- Switch on `envelope.eventType()` to a per-event `translate<Event>(envelope)`
  method that validates its own payload/version and calls its own use case.
- Unknown `eventType` still throws `AmqpRejectAndDontRequeueException` (via the
  existing `dropped(...)` helper) — never retried, straight to the DLQ.
- Keep the correlation-id MDC handling in `onMessage` untouched.

Each `translate` method reproduces the current validation shape: reject a null
envelope / missing `eventId` / wrong `version` / missing `tenantId` / missing
`payload`, then `objectMapper.convertValue` to the payload record inside a
try/catch that rethrows as `dropped(...)`.

### 7. Preference flag — only if there is a user-facing opt-out

The `/preferences` contract is byte-compatible with `skateboard-user-be`'s
`/me/preferences` and the mobile Settings screen consumes it directly. Changing
its shape is a coordinated change:

- `api/openapi.yaml`: add `<type>Enabled: {type: boolean}` to the
  `NotificationPreferences` schema. `mvn` regenerates the DTO.
- `adapter/in/rest/NotificationPreferenceController.java`: map the new flag in
  `updateNotificationPreferences` (into the `byType` `EnumMap`) and in
  `toResponse` (`.<type>Enabled(preferences.isEnabledFor(NotificationType.X))`).
- `PreferencePersistenceAdapter` already persists any `NotificationType` key
  generically — no change.
- **Also update** `skateboard-ui-backend`'s vendored `openapi.yaml` copy, its
  `bff-openapi.yaml`, and the app. Do not ship the spec change here alone.
- Backfill migration (`V6__...`) only if an existing upstream opt-out table
  must be carried over, as `V2__backfill_user_preferences.sql` did for
  `NEW_PODCAST`. Usually not needed.

### 8. Tests

- `application/service/Handle<Event>ServiceTest.java` — unit, mirror
  `HandlePodcastPublishedServiceTest` (happy path, duplicate short-circuit,
  no-devices path).
- Listener test — extend the renamed listener's test: valid event of the new
  type reaches its use case; the "unknown eventType" case now uses a genuinely
  unknown string.
- `NotificationTemplateResolverTest` — add a case for the new type's copy.
- Integration — extend `PodcastPublishedIntegrationTest` or add a sibling. Any
  `@SpringBootTest` here **must** keep the four job-disable properties
  (`push.retry.enabled`, `push.receipts.enabled`, `retention.enabled`,
  `messaging.dead-letter.monitor-enabled` all `false`) or background jobs fire
  mid-assertion and the retry pass calls the real Expo API. Testcontainers
  needs a running Docker daemon.

### 9. Build

`mvn package` (needs JDK 21 — `export JAVA_HOME=<jdk-21>` first). After editing
`api/openapi.yaml` or `pom.xml`, do **not** pass `-o`, the generator/plugin
resolution needs the network.

## Quick checklist

- [ ] `<Event>Payload` record
- [ ] `Handle<Event>UseCase` port
- [ ] `Handle<Event>Service` (not `@Transactional`)
- [ ] `NotificationTemplateResolver` DEFINITIONS entry
- [ ] `NotificationType` value (reuse if possible)
- [ ] `EventTopology` routing key + binding constants
- [ ] `RabbitConfig` binding bean (same queue)
- [ ] Listener dispatches on `eventType`; unknown → `AmqpRejectAndDontRequeueException`
- [ ] Preference flag across openapi.yaml + controller + ui-backend + app (only if user-facing)
- [ ] Unit + listener + template + integration tests (job-disable props)
- [ ] Producer emits to `application.events` / `<domain>.<event>.v1` with a stable `eventId`
- [ ] `mvn package` green on JDK 21
