package com.bitcomputer.hrportal.bgcheck;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 외부 API가 2xx 응답 본문으로 알려 준 업무 결과. DB에는 API 값 그대로(소문자) 저장한다(CHECK ck_bgcheck_result_status).
 */
public enum ResultStatus {
    PENDING("pending"),
    CLEAR("clear"),
    FLAGGED("flagged");

    private final String apiValue;

    ResultStatus(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }

    public boolean isFinal() {
        return this != PENDING;
    }

    /** 화면에 보여 줄 이름. flagged는 "추가 검토 필요"로 적어 연동 상태의 "확인 필요"와 구분한다(DESIGN.md 6.4). */
    public String label() {
        return switch (this) {
            case PENDING -> "대기 중";
            case CLEAR -> "이상 없음";
            case FLAGGED -> "추가 검토 필요";
        };
    }

    public static ResultStatus fromApiValue(String value) {
        for (ResultStatus status : values()) {
            if (status.apiValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("알 수 없는 result status: " + value);
    }

    /** enum 이름(PENDING)이 아니라 API 값(pending)으로 저장하기 위한 변환기. */
    @Converter(autoApply = false)
    public static class DbConverter implements AttributeConverter<ResultStatus, String> {
        @Override
        public String convertToDatabaseColumn(ResultStatus attribute) {
            return attribute == null ? null : attribute.apiValue;
        }

        @Override
        public ResultStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : fromApiValue(dbData);
        }
    }
}
