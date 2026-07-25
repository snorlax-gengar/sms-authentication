package com.example.sms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing(createdAt 자동 채움) 설정.
 * {@code @SpringBootApplication} 클래스가 아닌 별도 Configuration으로 분리한 이유:
 * {@code @WebMvcTest} 등 슬라이스 테스트는 메인 애플리케이션 클래스를 컨텍스트 설정 소스로 그대로 사용하므로,
 * 메인 클래스에 {@code @EnableJpaAuditing}이 있으면 JPA 자동 구성이 빠진 슬라이스 컨텍스트에서도
 * jpaAuditingHandler 빈 생성을 시도하다 실패한다. 별도 Configuration으로 분리하면
 * 슬라이스 테스트의 TypeExcludeFilter에 의해 자동으로 제외되어 이 문제가 발생하지 않는다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
