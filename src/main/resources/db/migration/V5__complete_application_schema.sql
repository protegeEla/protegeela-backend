-- Schema consolidado e idempotente para bancos novos e existentes.
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    email VARCHAR(254) NOT NULL UNIQUE,
    phone VARCHAR(30) NOT NULL DEFAULT '',
    privacy_mode VARCHAR(20) NOT NULL DEFAULT 'standard',
    password VARCHAR(60) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone VARCHAR(30) NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN IF NOT EXISTS privacy_mode VARCHAR(20) NOT NULL DEFAULT 'standard';
ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER';
ALTER TABLE users ADD COLUMN IF NOT EXISTS enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE UNIQUE INDEX IF NOT EXISTS app_users_email_lower ON users (lower(email));

CREATE TABLE IF NOT EXISTS auth_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS app_session_expiration ON auth_sessions(expires_at);

CREATE TABLE IF NOT EXISTS app_contacts (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(150) NOT NULL,
    phone VARCHAR(30) NOT NULL,
    email VARCHAR(254),
    relationship VARCHAR(40) NOT NULL,
    invitation_status VARCHAR(20) NOT NULL DEFAULT 'not_sent',
    can_view_exact_location BOOLEAN NOT NULL DEFAULT FALSE,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    preferred_channel VARCHAR(20) NOT NULL DEFAULT 'whatsapp',
    invitation_sent_at TIMESTAMPTZ,
    confirmed_at TIMESTAMPTZ
);
ALTER TABLE app_contacts ADD COLUMN IF NOT EXISTS preferred_channel VARCHAR(20) NOT NULL DEFAULT 'whatsapp';
ALTER TABLE app_contacts ADD COLUMN IF NOT EXISTS invitation_sent_at TIMESTAMPTZ;
ALTER TABLE app_contacts ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMPTZ;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'app_contacts_preferred_channel_check') THEN
        ALTER TABLE app_contacts ADD CONSTRAINT app_contacts_preferred_channel_check
            CHECK (preferred_channel IN ('whatsapp', 'sms', 'email', 'call'));
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS app_contacts_owner ON app_contacts(owner_user_id, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS app_contacts_primary ON app_contacts(owner_user_id) WHERE is_primary;

CREATE TABLE IF NOT EXISTS app_alerts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_request_id UUID NOT NULL,
    alert_type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'acknowledged', 'closed')),
    is_silent BOOLEAN NOT NULL DEFAULT FALSE,
    location_status VARCHAR(40) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    end_reason VARCHAR(40),
    public_visibility BOOLEAN NOT NULL DEFAULT FALSE,
    public_latitude DOUBLE PRECISION CHECK (public_latitude BETWEEN -90 AND 90),
    public_longitude DOUBLE PRECISION CHECK (public_longitude BETWEEN -180 AND 180),
    UNIQUE (user_id, client_request_id),
    CHECK (NOT is_silent OR NOT public_visibility)
);
CREATE UNIQUE INDEX IF NOT EXISTS app_alerts_one_active ON app_alerts(user_id)
    WHERE status IN ('active', 'acknowledged');
CREATE INDEX IF NOT EXISTS app_alerts_map ON app_alerts(status, public_latitude, public_longitude)
    WHERE public_visibility;

CREATE TABLE IF NOT EXISTS app_alert_locations (
    id UUID PRIMARY KEY,
    alert_id UUID NOT NULL UNIQUE REFERENCES app_alerts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    accuracy DOUBLE PRECISION NOT NULL CHECK (accuracy >= 0 AND accuracy <= 100000),
    altitude DOUBLE PRECISION,
    source VARCHAR(40) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS app_support_points (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    category VARCHAR(40) NOT NULL,
    description TEXT,
    address VARCHAR(300) NOT NULL,
    city VARCHAR(100) NOT NULL,
    state VARCHAR(2) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    phone VARCHAR(30),
    opening_hours VARCHAR(300),
    website VARCHAR(500),
    accessibility_info VARCHAR(500),
    is_verified BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS app_safety_contents (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    content TEXT NOT NULL,
    category VARCHAR(40) NOT NULL,
    is_published BOOLEAN NOT NULL DEFAULT FALSE,
    source_name VARCHAR(200),
    source_url VARCHAR(500),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE app_safety_contents ADD COLUMN IF NOT EXISTS source_name VARCHAR(200);
ALTER TABLE app_safety_contents ADD COLUMN IF NOT EXISTS source_url VARCHAR(500);
ALTER TABLE app_safety_contents ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE IF NOT EXISTS app_emergency_services (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    phone VARCHAR(30) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    region VARCHAR(100) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS app_contact_invitations (
    id UUID PRIMARY KEY,
    contact_id UUID NOT NULL UNIQUE REFERENCES app_contacts(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    responded_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS app_contact_invitations_expiration ON app_contact_invitations(expires_at);

CREATE TABLE IF NOT EXISTS app_safety_checkins (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expected_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    grace_minutes INTEGER NOT NULL DEFAULT 5 CHECK (grace_minutes BETWEEN 0 AND 30),
    status VARCHAR(20) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'completed', 'cancelled', 'overdue')),
    alert_id UUID REFERENCES app_alerts(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    share_location BOOLEAN NOT NULL DEFAULT FALSE,
    share_token_hash VARCHAR(64) UNIQUE,
    share_token_expires_at TIMESTAMPTZ,
    last_location_at TIMESTAMPTZ,
    CHECK (expires_at >= expected_at),
    CONSTRAINT app_safety_checkins_share_token_required CHECK (NOT share_location OR
        (share_token_hash IS NOT NULL AND share_token_expires_at IS NOT NULL))
);
ALTER TABLE app_safety_checkins ADD COLUMN IF NOT EXISTS share_location BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_safety_checkins ADD COLUMN IF NOT EXISTS share_token_hash VARCHAR(64);
ALTER TABLE app_safety_checkins ADD COLUMN IF NOT EXISTS share_token_expires_at TIMESTAMPTZ;
ALTER TABLE app_safety_checkins ADD COLUMN IF NOT EXISTS last_location_at TIMESTAMPTZ;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'app_safety_checkins_share_token_required') THEN
        ALTER TABLE app_safety_checkins ADD CONSTRAINT app_safety_checkins_share_token_required
            CHECK (NOT share_location OR
                (share_token_hash IS NOT NULL AND share_token_expires_at IS NOT NULL));
    END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS app_safety_checkins_one_active ON app_safety_checkins(user_id)
    WHERE status = 'active';
CREATE INDEX IF NOT EXISTS app_safety_checkins_due ON app_safety_checkins(status, expires_at);

CREATE TABLE IF NOT EXISTS app_safety_checkin_locations (
    id UUID PRIMARY KEY,
    check_in_id UUID NOT NULL REFERENCES app_safety_checkins(id) ON DELETE CASCADE,
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    accuracy DOUBLE PRECISION NOT NULL CHECK (accuracy >= 0 AND accuracy <= 100000),
    altitude DOUBLE PRECISION,
    source VARCHAR(40) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    UNIQUE (check_in_id, captured_at)
);
CREATE INDEX IF NOT EXISTS app_safety_checkin_locations_timeline
    ON app_safety_checkin_locations(check_in_id, captured_at);
