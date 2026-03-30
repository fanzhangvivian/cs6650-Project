
-- Development only: uncomment to reset table during schema iteration
-- DROP TABLE IF EXISTS messages;

CREATE TABLE IF NOT EXISTS messages (
    id            BIGSERIAL    PRIMARY KEY,
    message_id    VARCHAR(36)  UNIQUE NOT NULL,
    room_id       VARCHAR(20)  NOT NULL,
    user_id       VARCHAR(10)  NOT NULL,
    username      VARCHAR(20)  NOT NULL,
    message       TEXT         NOT NULL,
    event_time    TIMESTAMPTZ  NOT NULL,
    message_type  VARCHAR(10)  NOT NULL,
    server_id     VARCHAR(50),
    client_ip     VARCHAR(45),
    published_at  TIMESTAMPTZ,
    received_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_room_event_time
    ON messages(room_id, event_time);

CREATE INDEX IF NOT EXISTS idx_user_event_time
    ON messages(user_id, event_time);

CREATE INDEX IF NOT EXISTS idx_event_time
    ON messages(event_time);