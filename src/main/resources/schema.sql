-- ============================================================================
-- sms-auth RDB 스키마 (PostgreSQL)
-- 대상: SmsSendLog 엔티티 (com.example.sms.domain.auth.repository.SmsSendLog)
-- 적용: spring.profiles.active=prod 일 때만 자동 실행 (spring.sql.init.mode=always)
-- Hibernate ddl-auto는 prod에서 validate 이므로, 이 스크립트가 실제 스키마 소유자다.
-- ============================================================================

CREATE TABLE IF NOT EXISTS sms_send_log (
    id           BIGSERIAL     PRIMARY KEY,
    phone_number VARCHAR(11)   NOT NULL,
    status       VARCHAR(20)   NOT NULL CHECK (status IN ('SENT', 'FAILED')),
    error_msg    VARCHAR(500),
    created_at   TIMESTAMP     NOT NULL
);

-- 전화번호별 발송 이력 조회를 위한 인덱스
CREATE INDEX IF NOT EXISTS idx_sms_send_log_phone_number ON sms_send_log (phone_number);

-- 최근 발송 이력 조회/모니터링을 위한 인덱스
CREATE INDEX IF NOT EXISTS idx_sms_send_log_created_at ON sms_send_log (created_at);

COMMENT ON TABLE sms_send_log IS 'SMS 인증번호 발송 이력';
COMMENT ON COLUMN sms_send_log.phone_number IS '수신자 전화번호 (010으로 시작하는 11자리)';
COMMENT ON COLUMN sms_send_log.status IS '발송 상태: SENT(성공) | FAILED(실패)';
COMMENT ON COLUMN sms_send_log.error_msg IS '발송 실패 시 에러 메시지 (성공 시 NULL)';
COMMENT ON COLUMN sms_send_log.created_at IS '발송 이력 생성 시각 (JPA Auditing @CreatedDate)';
