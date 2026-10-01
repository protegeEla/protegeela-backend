-- Independent history: preserves the existing account tables and legacy history.
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
CREATE UNIQUE INDEX IF NOT EXISTS app_users_email_lower ON users (lower(email));
CREATE TABLE IF NOT EXISTS auth_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS app_session_expiration ON auth_sessions(expires_at);

CREATE TABLE app_contacts (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(150) NOT NULL,
    phone VARCHAR(30) NOT NULL,
    email VARCHAR(254),
    relationship VARCHAR(40) NOT NULL,
    invitation_status VARCHAR(20) NOT NULL DEFAULT 'not_sent',
    can_view_exact_location BOOLEAN NOT NULL DEFAULT FALSE,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX app_contacts_owner ON app_contacts(owner_user_id, created_at);
CREATE UNIQUE INDEX app_contacts_primary ON app_contacts(owner_user_id) WHERE is_primary;

CREATE TABLE app_alerts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_request_id UUID NOT NULL,
    alert_type VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'acknowledged', 'closed')),
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
CREATE UNIQUE INDEX app_alerts_one_active ON app_alerts(user_id) WHERE status IN ('active', 'acknowledged');
CREATE INDEX app_alerts_map ON app_alerts(status, public_latitude, public_longitude) WHERE public_visibility;
CREATE TABLE app_alert_locations (
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

CREATE TABLE app_support_points (
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
CREATE TABLE app_safety_contents (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    content TEXT NOT NULL,
    category VARCHAR(40) NOT NULL,
    is_published BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE app_emergency_services (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    phone VARCHAR(30) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    region VARCHAR(100) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT FALSE
);
