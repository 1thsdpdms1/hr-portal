package com.bitcomputer.hrportal.bgcheck;

/**
 * 우리 서버와 외부 API 사이의 연동 상태(DESIGN.md 4.3 상태표). 업무 결과({@link ResultStatus})와 섞지 않는다.
 */
public enum SyncState {
    /** 행만 있고 외부에 아무것도 보내지 않았다. 다시 해도 안전하다. */
    QUEUED,
    /** POST 직전에 커밋했다. POST를 보냈을 수 있으므로 절대 재POST하지 않는다. */
    SUBMITTING,
    /** 외부에 확실히 생성되지 않았다(POST 400/401, 또는 관리자의 "생성 안 됨으로 종료"). */
    SUBMIT_FAILED,
    /** 외부에 생성됐는지 알 수 없다(확인 필요). */
    SUBMIT_UNKNOWN,
    /** checkId를 받았고 상세 결과를 아직 받지 못했다. */
    IN_PROGRESS,
    /** 최종 상태와 상세 결과를 받았다. */
    DONE,
    /** 자동 확인을 중단했다(결과 미확인). checkId는 보존한다. */
    UNRESOLVED;

    /**
     * 화면에 보여 줄 이름(DESIGN.md 4.3 상태표).
     * 업무 결과의 flagged를 "확인 필요"라고 부르지 않는 이유: 연동 상태의 "확인 필요"와 헷갈리기 때문이다.
     */
    public String label() {
        return switch (this) {
            case QUEUED -> "요청 대기";
            case SUBMITTING -> "요청 중";
            case SUBMIT_FAILED -> "요청 실패";
            case SUBMIT_UNKNOWN -> "확인 필요";
            case IN_PROGRESS -> "조회 중";
            case DONE -> "정상";
            case UNRESOLVED -> "결과 미확인";
        };
    }

    /** 워커가 더 할 일이 남아 있는 상태인가(화면 자동 갱신 여부 판단에 쓴다). */
    public boolean isActive() {
        return this == QUEUED || this == SUBMITTING || this == IN_PROGRESS;
    }
}
