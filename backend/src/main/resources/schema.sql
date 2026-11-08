CREATE TABLE IF NOT EXISTS release_package (
    id          VARCHAR(64)  PRIMARY KEY,
    feature     VARCHAR(32)  NOT NULL,
    title       VARCHAR(120) NOT NULL,
    package     CLOB         NOT NULL,
    imported_at TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS gate_run (
    id           VARCHAR(36)  PRIMARY KEY,
    scope        VARCHAR(16)  NOT NULL,
    feature      VARCHAR(32),
    target_id    VARCHAR(256) NOT NULL,
    title        VARCHAR(512) NOT NULL,
    summary      VARCHAR(1024),
    request      CLOB         NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    verdict      VARCHAR(16),
    error        VARCHAR(1024),
    evaluations  INT,
    failing      INT,
    started_at   TIMESTAMP    NOT NULL,
    finished_at  TIMESTAMP,
    report       CLOB
);

CREATE INDEX IF NOT EXISTS gate_run_started ON gate_run (started_at);

CREATE TABLE IF NOT EXISTS campaign_result (
    name        VARCHAR(64)  PRIMARY KEY,
    fingerprint VARCHAR(64)  NOT NULL,
    result      CLOB         NOT NULL,
    measured_at TIMESTAMP    NOT NULL
);
