package com.example.sms.domain.auth.controller;

import com.example.sms.common.dto.ApiResponse;
import com.example.sms.domain.auth.dto.SmsRequestDto;
import com.example.sms.domain.auth.dto.SmsVerifyDto;
import com.example.sms.domain.auth.service.SmsAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "SMS Auth", description = "SMS 인증번호 발송 및 검증 API")
@RestController
@RequestMapping("/api/v1/sms")
@RequiredArgsConstructor
public class SmsAuthController {

    private final SmsAuthService smsAuthService;

    @Operation(summary = "인증번호 발송 요청", description = "인증번호를 생성하여 SMS로 발송합니다. 분당 3회, 일일 5회 제한이 적용됩니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발송 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 전화번호 형식 (INVALID_REQUEST)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Rate Limit 초과 (SMS_RATE_LIMIT_EXCEEDED)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "SMS Gateway 발송 실패 (SMS_SEND_FAILED)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping("/request")
    public ResponseEntity<ApiResponse<Void>> requestVerification(
            @Valid @RequestBody SmsRequestDto request
    ) {
        smsAuthService.requestVerification(request.getPhoneNumber());
        return ResponseEntity.ok(ApiResponse.success("인증번호가 발송되었습니다."));
    }

    @Operation(summary = "인증번호 검증", description = "발송된 인증번호를 검증합니다. 성공 시 인증번호는 즉시 폐기됩니다(재사용 방지).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "인증 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "인증번호 만료/미존재(SMS_VERIFICATION_EXPIRED) 또는 불일치(SMS_VERIFICATION_MISMATCH)",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class)))
    })
    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<Void>> verify(
            @Valid @RequestBody SmsVerifyDto request
    ) {
        smsAuthService.verify(request.getPhoneNumber(), request.getCode());
        return ResponseEntity.ok(ApiResponse.success("인증에 성공했습니다."));
    }
}
