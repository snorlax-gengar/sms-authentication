package com.example.sms.domain.auth.sender;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 로컬 개발 전용 무료 발송 구현체.
 * 외부 SMS Gateway 없이 콘솔에 인증번호를 출력하여 전체 인증 플로우를 테스트한다.
 * 운영 환경에서는 사용하지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sms.sender.provider", havingValue = "dev", matchIfMissing = true)
public class DevSmsSender implements SmsSender {

    @Override
    public void send(String phoneNumber, String code) {
        log.warn("""
                
                ========== [DEV SMS] ==========
                To   : {}
                Code : {}
                ===============================
                """, phoneNumber, code);
    }
}
