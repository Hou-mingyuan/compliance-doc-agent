-- Compliance Doc Agent v1 schema (H2 in MySQL compatibility mode).
-- Existing MVP tables are retained and expanded; new workflow data is normalized.

CREATE TABLE IF NOT EXISTS compliance_document (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL DEFAULT 'demo-tenant',
    owner_id            VARCHAR(96)  NOT NULL DEFAULT 'demo-user',
    title               VARCHAR(255) NOT NULL,
    source_filename     VARCHAR(255),
    doc_type            VARCHAR(32)  NOT NULL DEFAULT 'GENERAL',
    file_format         VARCHAR(16)  NOT NULL DEFAULT 'txt',
    sha256              VARCHAR(64),
    content             CLOB,
    page_count          INT          NOT NULL DEFAULT 1,
    version_no          INT          NOT NULL DEFAULT 1,
    parent_document_id  BIGINT,
    status              VARCHAR(32)  NOT NULL DEFAULT 'UPLOADED',
    parse_error         VARCHAR(500),
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_parent FOREIGN KEY (parent_document_id)
        REFERENCES compliance_document(id) ON DELETE RESTRICT
);

-- Forward-compatible upgrade path for databases created by the original MVP schema.
ALTER TABLE compliance_document ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'demo-tenant';
ALTER TABLE compliance_document ADD COLUMN IF NOT EXISTS owner_id VARCHAR(96) NOT NULL DEFAULT 'demo-user';
ALTER TABLE compliance_document ADD COLUMN IF NOT EXISTS source_filename VARCHAR(255);
ALTER TABLE compliance_document ADD COLUMN IF NOT EXISTS file_format VARCHAR(16) NOT NULL DEFAULT 'txt';
ALTER TABLE compliance_document ADD COLUMN IF NOT EXISTS sha256 VARCHAR(64);
ALTER TABLE compliance_document ADD COLUMN IF NOT EXISTS page_count INT NOT NULL DEFAULT 1;
ALTER TABLE compliance_document ADD COLUMN IF NOT EXISTS version_no INT NOT NULL DEFAULT 1;
ALTER TABLE compliance_document ADD COLUMN IF NOT EXISTS parent_document_id BIGINT;
ALTER TABLE compliance_document ADD COLUMN IF NOT EXISTS parse_error VARCHAR(500);

CREATE TABLE IF NOT EXISTS compliance_document_chunk (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id    BIGINT      NOT NULL,
    chunk_index    INT         NOT NULL,
    page_no        INT,
    section_title  VARCHAR(255),
    paragraph_no   INT,
    content        CLOB        NOT NULL,
    char_start     INT,
    char_end       INT,
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chunk_document FOREIGN KEY (document_id)
        REFERENCES compliance_document(id) ON DELETE CASCADE,
    CONSTRAINT uq_chunk_document_index UNIQUE (document_id, chunk_index)
);

ALTER TABLE compliance_document_chunk ADD COLUMN IF NOT EXISTS page_no INT;
ALTER TABLE compliance_document_chunk ADD COLUMN IF NOT EXISTS section_title VARCHAR(255);
ALTER TABLE compliance_document_chunk ADD COLUMN IF NOT EXISTS paragraph_no INT;

CREATE TABLE IF NOT EXISTS compliance_check (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id   BIGINT       NOT NULL,
    rule_code     VARCHAR(64)  NOT NULL,
    severity      VARCHAR(16)  NOT NULL,
    message       CLOB,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_check_document FOREIGN KEY (document_id)
        REFERENCES compliance_document(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS regulation_entry (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    code            VARCHAR(64)  NOT NULL UNIQUE,
    title           VARCHAR(255) NOT NULL,
    version_label   VARCHAR(64)  NOT NULL,
    effective_date  DATE         NOT NULL,
    expiry_date     DATE,
    scope           VARCHAR(128) NOT NULL,
    source_name     VARCHAR(255) NOT NULL,
    source_url      VARCHAR(500),
    article_no      VARCHAR(64)  NOT NULL,
    content         CLOB         NOT NULL,
    keywords        VARCHAR(500) NOT NULL,
    demo_data       BOOLEAN      NOT NULL DEFAULT TRUE,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS review_run (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    review_key        VARCHAR(64)  NOT NULL UNIQUE,
    tenant_id         VARCHAR(64)  NOT NULL,
    document_id       BIGINT       NOT NULL,
    created_by        VARCHAR(96)  NOT NULL,
    status            VARCHAR(32)  NOT NULL,
    rule_pack_version VARCHAR(64)  NOT NULL,
    llm_provider      VARCHAR(32)  NOT NULL,
    risk_score        INT          NOT NULL DEFAULT 0,
    summary           CLOB,
    cancel_requested  BOOLEAN      NOT NULL DEFAULT FALSE,
    started_at        TIMESTAMP,
    finished_at       TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_review_document FOREIGN KEY (document_id)
        REFERENCES compliance_document(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS risk_finding (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    finding_key       VARCHAR(64)  NOT NULL UNIQUE,
    tenant_id         VARCHAR(64)  NOT NULL,
    review_id         BIGINT       NOT NULL,
    document_id       BIGINT       NOT NULL,
    severity          VARCHAR(16)  NOT NULL,
    title             VARCHAR(255) NOT NULL,
    description       CLOB         NOT NULL,
    source_type       VARCHAR(32)  NOT NULL,
    rule_code         VARCHAR(64),
    evidence_text     CLOB,
    suggestion        CLOB,
    chunk_id          BIGINT,
    page_no           INT,
    section_title     VARCHAR(255),
    paragraph_no      INT,
    match_start       INT,
    match_end         INT,
    confidence        DECIMAL(5,4) NOT NULL DEFAULT 1.0,
    status            VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    reviewer_comment  CLOB,
    reviewed_by       VARCHAR(96),
    reviewed_at       TIMESTAMP,
    dedupe_key        VARCHAR(64)  NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_finding_review FOREIGN KEY (review_id)
        REFERENCES review_run(id) ON DELETE CASCADE,
    CONSTRAINT fk_finding_document FOREIGN KEY (document_id)
        REFERENCES compliance_document(id) ON DELETE RESTRICT,
    CONSTRAINT fk_finding_chunk FOREIGN KEY (chunk_id)
        REFERENCES compliance_document_chunk(id) ON DELETE SET NULL,
    CONSTRAINT uq_finding_review_dedupe UNIQUE (review_id, dedupe_key)
);

CREATE TABLE IF NOT EXISTS finding_regulation (
    finding_id       BIGINT        NOT NULL,
    regulation_code VARCHAR(64)    NOT NULL,
    relevance_score DECIMAL(6,4)  NOT NULL,
    excerpt          CLOB          NOT NULL,
    PRIMARY KEY (finding_id, regulation_code),
    CONSTRAINT fk_citation_finding FOREIGN KEY (finding_id)
        REFERENCES risk_finding(id) ON DELETE CASCADE,
    CONSTRAINT fk_citation_regulation FOREIGN KEY (regulation_code)
        REFERENCES regulation_entry(code) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS document_entity (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_key       VARCHAR(64)  NOT NULL UNIQUE,
    tenant_id        VARCHAR(64)  NOT NULL,
    review_id        BIGINT       NOT NULL,
    document_id      BIGINT       NOT NULL,
    entity_type      VARCHAR(32)  NOT NULL,
    entity_value     VARCHAR(500) NOT NULL,
    normalized_value VARCHAR(500),
    match_start      INT          NOT NULL,
    match_end        INT          NOT NULL,
    chunk_id         BIGINT,
    page_no          INT,
    section_title    VARCHAR(255),
    paragraph_no     INT,
    confidence       DECIMAL(5,4) NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_entity_review FOREIGN KEY (review_id)
        REFERENCES review_run(id) ON DELETE CASCADE,
    CONSTRAINT fk_entity_document FOREIGN KEY (document_id)
        REFERENCES compliance_document(id) ON DELETE RESTRICT,
    CONSTRAINT fk_entity_chunk FOREIGN KEY (chunk_id)
        REFERENCES compliance_document_chunk(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS remediation_task (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_key         VARCHAR(64)  NOT NULL UNIQUE,
    tenant_id        VARCHAR(64)  NOT NULL,
    review_id        BIGINT       NOT NULL,
    finding_id       BIGINT       NOT NULL,
    assignee_id      VARCHAR(96)  NOT NULL,
    due_date         DATE         NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    description      CLOB         NOT NULL,
    review_comment   CLOB,
    created_by       VARCHAR(96)  NOT NULL,
    version_no       INT          NOT NULL DEFAULT 1,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at        TIMESTAMP,
    CONSTRAINT fk_remediation_review FOREIGN KEY (review_id)
        REFERENCES review_run(id) ON DELETE RESTRICT,
    CONSTRAINT fk_remediation_finding FOREIGN KEY (finding_id)
        REFERENCES risk_finding(id) ON DELETE RESTRICT,
    CONSTRAINT uq_remediation_finding UNIQUE (finding_id)
);

CREATE TABLE IF NOT EXISTS remediation_evidence (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id        BIGINT      NOT NULL,
    submitted_by   VARCHAR(96) NOT NULL,
    evidence_text  CLOB        NOT NULL,
    submitted_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evidence_task FOREIGN KEY (task_id)
        REFERENCES remediation_task(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS audit_report (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_key    VARCHAR(64)  NOT NULL UNIQUE,
    tenant_id     VARCHAR(64)  NOT NULL,
    review_id     BIGINT       NOT NULL,
    version_no    INT          NOT NULL,
    format        VARCHAR(16)  NOT NULL,
    file_name     VARCHAR(255) NOT NULL,
    source_digest VARCHAR(64)  NOT NULL,
    content_blob  BLOB         NOT NULL,
    sha256        VARCHAR(64)  NOT NULL,
    created_by    VARCHAR(96)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_report_review FOREIGN KEY (review_id)
        REFERENCES review_run(id) ON DELETE RESTRICT,
    CONSTRAINT uq_report_review_version UNIQUE (review_id, version_no),
    CONSTRAINT uq_report_review_source UNIQUE (review_id, source_digest)
);

CREATE TABLE IF NOT EXISTS tool_execution (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    execution_key VARCHAR(64)  NOT NULL UNIQUE,
    tenant_id     VARCHAR(64)  NOT NULL,
    review_id     BIGINT,
    tool_name     VARCHAR(64)  NOT NULL,
    success       BOOLEAN      NOT NULL,
    error_code    VARCHAR(64),
    args_digest   VARCHAR(64)  NOT NULL,
    summary       VARCHAR(1000) NOT NULL,
    duration_ms   BIGINT       NOT NULL,
    actor_id      VARCHAR(96)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tool_review FOREIGN KEY (review_id)
        REFERENCES review_run(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS audit_event (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_key      VARCHAR(64)  NOT NULL UNIQUE,
    tenant_id      VARCHAR(64)  NOT NULL,
    actor_id       VARCHAR(96)  NOT NULL,
    actor_role     VARCHAR(32)  NOT NULL,
    action         VARCHAR(64)  NOT NULL,
    resource_type  VARCHAR(64)  NOT NULL,
    resource_id    VARCHAR(96)  NOT NULL,
    from_state     VARCHAR(32),
    to_state       VARCHAR(32),
    details_json   CLOB         NOT NULL,
    previous_hash  VARCHAR(64)  NOT NULL,
    event_hash     VARCHAR(64)  NOT NULL UNIQUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_document_tenant_updated
    ON compliance_document (tenant_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_document_parent_version
    ON compliance_document (tenant_id, parent_document_id, version_no);
CREATE INDEX IF NOT EXISTS idx_chunk_document_location
    ON compliance_document_chunk (document_id, page_no, paragraph_no, chunk_index);
CREATE INDEX IF NOT EXISTS idx_review_tenant_status_updated
    ON review_run (tenant_id, status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_review_document_created
    ON review_run (tenant_id, document_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_finding_review_status
    ON risk_finding (tenant_id, review_id, status, severity);
CREATE INDEX IF NOT EXISTS idx_regulation_scope_dates
    ON regulation_entry (active, scope, effective_date, expiry_date);
CREATE INDEX IF NOT EXISTS idx_entity_review_type
    ON document_entity (tenant_id, review_id, entity_type);
CREATE INDEX IF NOT EXISTS idx_remediation_assignee_status
    ON remediation_task (tenant_id, assignee_id, status, due_date);
CREATE INDEX IF NOT EXISTS idx_tool_review_created
    ON tool_execution (tenant_id, review_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_tenant_created
    ON audit_event (tenant_id, created_at, id);
