-- ======================================================================
-- Compliance Doc Agent 数据库结构（H2 MySQL 兼容模式）
-- ======================================================================

CREATE TABLE IF NOT EXISTS compliance_document (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(255) NOT NULL,
    doc_type    VARCHAR(32)  DEFAULT 'GENERAL',
    content     TEXT,
    status      VARCHAR(24)  DEFAULT 'PENDING',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS compliance_check (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id  BIGINT       NOT NULL,
    rule_code    VARCHAR(64)  NOT NULL,
    severity     VARCHAR(16)  NOT NULL,
    message      TEXT,
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP
);
