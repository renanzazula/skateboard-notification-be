-- One-time carry-over of explicit opt-outs from skateboard-user-be.
--
-- skateboard-user-be owned notification preferences until this service took
-- them over; its table defaulted both flags to TRUE, and this service treats a
-- missing row as enabled, so users who never touched the settings screen need
-- nothing copied. Users who deliberately turned something OFF do: without this
-- they would be silently re-enabled and pushed to against their wishes.
--
-- All services share one database and are separated only by schema, so this
-- reads across the boundary once and never again. It is guarded rather than
-- assumed: a fresh local database, a test container, or a deployment where
-- user-be has not migrated yet simply has no source table, and this must
-- no-op instead of failing the migration. The schema name is checked in both
-- spellings because skateboard-user-be disagrees with itself between its
-- application.yml (skateboard-user) and application-railway.yml
-- (skateboard_user).
--
-- user_notification_preferences.user_id is user-be's internal user_profile.id,
-- not the Keycloak subject this service keys on, hence the join.
--
-- The tenant id is written as a literal because a migration cannot read
-- app.tenancy.default-tenant-id, and because there is provably one tenant at
-- the moment this runs: it is the value every seed user carries in the realm
-- export and the default in every service's config.

DO $$
DECLARE
    source_schema TEXT;
BEGIN
    SELECT s INTO source_schema
    FROM (VALUES ('skateboard-user'), ('skateboard_user')) AS candidates(s)
    WHERE to_regclass(quote_ident(s) || '.user_notification_preferences') IS NOT NULL
      AND to_regclass(quote_ident(s) || '.user_profile') IS NOT NULL
    LIMIT 1;

    IF source_schema IS NULL THEN
        RAISE NOTICE 'skateboard-user preferences not present; nothing to backfill';
        RETURN;
    END IF;

    -- Master push switch, only where it was explicitly disabled.
    EXECUTE format($f$
        INSERT INTO notification_channel_setting (user_id, tenant_id, push_enabled, created_at, updated_at)
        SELECT p.keycloak_user_id,
               '00000000-0000-0000-0000-000000000001'::uuid,
               FALSE,
               np.updated_at,
               np.updated_at
        FROM %I.user_notification_preferences np
        JOIN %I.user_profile p ON p.id = np.user_id
        WHERE np.push_enabled = FALSE
        ON CONFLICT (user_id) DO NOTHING
    $f$, source_schema, source_schema);

    -- Per-type opt-out for the only type that existed there.
    EXECUTE format($f$
        INSERT INTO notification_preference
            (id, user_id, tenant_id, notification_type, push_enabled, created_at, updated_at)
        SELECT gen_random_uuid(),
               p.keycloak_user_id,
               '00000000-0000-0000-0000-000000000001'::uuid,
               'NEW_PODCAST',
               FALSE,
               np.updated_at,
               np.updated_at
        FROM %I.user_notification_preferences np
        JOIN %I.user_profile p ON p.id = np.user_id
        WHERE np.new_podcast_enabled = FALSE
        ON CONFLICT (user_id, notification_type) DO NOTHING
    $f$, source_schema, source_schema);
END
$$;
