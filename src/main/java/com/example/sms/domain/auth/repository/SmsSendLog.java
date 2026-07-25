package com.example.sms.domain.auth.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.EntityListeners;
import java.time.LocalDateTime;

@Entity
@Table(name = "sms_send_log")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SmsSendLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 11)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SendStatus status;

    @Column(length = 500)
    private String errorMsg;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public SmsSendLog(String phoneNumber, SendStatus status, String errorMsg) {
        this.phoneNumber = phoneNumber;
        this.status = status;
        this.errorMsg = errorMsg;
    }

    public static SmsSendLog success(String phoneNumber) {
        return SmsSendLog.builder()
                .phoneNumber(phoneNumber)
                .status(SendStatus.SENT)
                .build();
    }

    public static SmsSendLog failure(String phoneNumber, String errorMsg) {
        return SmsSendLog.builder()
                .phoneNumber(phoneNumber)
                .status(SendStatus.FAILED)
                .errorMsg(errorMsg)
                .build();
    }
}
