package com.example.sms.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Schema(description = "인증번호 발송 요청")
public class SmsRequestDto {

    @Schema(description = "수신자 전화번호 (010으로 시작하는 11자리 숫자)", example = "01012345678")
    @NotBlank(message = "전화번호는 필수입니다.")
    @Pattern(regexp = "^010\\d{8}$", message = "올바르지 않은 전화번호 형식입니다.")
    private String phoneNumber;
}
