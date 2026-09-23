package com.bitcomputer.hrportal.bgcheck;

import com.bitcomputer.hrportal.account.Account;
import com.bitcomputer.hrportal.employee.Employee;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Background Check 기록 1건(DESIGN.md 4.3).
 *
 * <p><b>이 엔티티로는 INSERT와 읽기만 한다.</b> 상태를 바꾸는 컬럼은 모두 {@code insertable = false, updatable = false}다.
 * 상태 변경은 선점 token을 조건으로 거는 네이티브 SQL로만 한다(6단계, DESIGN.md 2.5.4).
 * JPA가 엔티티 전체를 다시 저장하면서 워커가 쓴 값(claim_token, next_run_at 등)을 덮어쓰는 사고를 막기 위해서다.
 *
 * <p>{@code active_employee_id}(진행 중 1건 제한용 생성 칼럼)는 DB가 계산하므로 매핑하지 않는다.
 */
@Entity
@Table(name = "background_check")
public class BackgroundCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ---- INSERT 때 앱이 넣는 값 ----

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_id", nullable = false, updatable = false)
    private Account requestedBy;

    /** 실제로 외부에 보낸 lastName. 나중에 직원 정보가 바뀌어도 "무엇으로 요청했나"를 설명할 수 있다. */
    @Column(name = "sent_family_name", nullable = false, updatable = false, length = 10)
    private String sentFamilyName;

    @Column(name = "sent_given_name", nullable = false, updatable = false, length = 30)
    private String sentGivenName;

    @Column(name = "sent_birth_date", nullable = false, updatable = false)
    private LocalDate sentBirthDate;

    // ---- DB 기본값 또는 워커의 네이티브 SQL만 쓰는 값 (JPA는 읽기만) ----

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_state", insertable = false, updatable = false)
    private SyncState syncState;

    @Column(name = "check_id", insertable = false, updatable = false)
    private String checkId;

    @Convert(converter = ResultStatus.DbConverter.class)
    @Column(name = "result_status", insertable = false, updatable = false)
    private ResultStatus resultStatus;

    @Column(name = "criminal_record", insertable = false, updatable = false)
    private Boolean criminalRecord;

    @Column(name = "education_verified", insertable = false, updatable = false)
    private Boolean educationVerified;

    @Column(name = "employment_verified", insertable = false, updatable = false)
    private Boolean employmentVerified;

    @Column(name = "credit_score", insertable = false, updatable = false)
    private String creditScore;

    @Column(name = "detail_purged_at", insertable = false, updatable = false)
    private Instant detailPurgedAt;

    @Column(name = "requested_at", insertable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "external_created_at", insertable = false, updatable = false)
    private Instant externalCreatedAt;

    @Column(name = "completed_at", insertable = false, updatable = false)
    private Instant completedAt;

    @Column(name = "last_polled_at", insertable = false, updatable = false)
    private Instant lastPolledAt;

    @Column(name = "poll_count", insertable = false, updatable = false)
    private Integer pollCount;

    @Column(name = "consecutive_failures", insertable = false, updatable = false)
    private Integer consecutiveFailures;

    @Column(name = "last_error_code", insertable = false, updatable = false)
    private String lastErrorCode;

    @Column(name = "last_error_at", insertable = false, updatable = false)
    private Instant lastErrorAt;

    @Column(name = "next_run_at", insertable = false, updatable = false)
    private Instant nextRunAt;

    @Column(name = "claim_token", insertable = false, updatable = false)
    private String claimToken;

    @Column(name = "post_sent_at", insertable = false, updatable = false)
    private Instant postSentAt;

    /** SQL NULL = 요청 전 목록 조회 실패(비교 기준 없음), 빈 목록 = 조회 성공·기록 없음. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pre_list_check_ids", insertable = false, updatable = false)
    private List<String> preListCheckIds;

    @Column(name = "last_diff_count", insertable = false, updatable = false)
    private Integer lastDiffCount;

    @Column(name = "poll_deadline_at", insertable = false, updatable = false)
    private Instant pollDeadlineAt;

    @Column(name = "recheck_requested_at", insertable = false, updatable = false)
    private Instant recheckRequestedAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected BackgroundCheck() {
    }

    /** 새 조회 요청. DB 기본값에 따라 sync_state = QUEUED, next_run_at = 현재 UTC 시각으로 들어간다. */
    public BackgroundCheck(Employee employee, Account requestedBy,
                           String sentFamilyName, String sentGivenName, LocalDate sentBirthDate) {
        this.employee = employee;
        this.requestedBy = requestedBy;
        this.sentFamilyName = sentFamilyName;
        this.sentGivenName = sentGivenName;
        this.sentBirthDate = sentBirthDate;
    }

    public Long getId() { return id; }
    public Employee getEmployee() { return employee; }
    public Account getRequestedBy() { return requestedBy; }
    public String getSentFamilyName() { return sentFamilyName; }
    public String getSentGivenName() { return sentGivenName; }
    public LocalDate getSentBirthDate() { return sentBirthDate; }
    public SyncState getSyncState() { return syncState; }
    public String getCheckId() { return checkId; }
    public ResultStatus getResultStatus() { return resultStatus; }
    public Boolean getCriminalRecord() { return criminalRecord; }
    public Boolean getEducationVerified() { return educationVerified; }
    public Boolean getEmploymentVerified() { return employmentVerified; }
    public String getCreditScore() { return creditScore; }
    public Instant getDetailPurgedAt() { return detailPurgedAt; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getExternalCreatedAt() { return externalCreatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getLastPolledAt() { return lastPolledAt; }
    public Integer getPollCount() { return pollCount; }
    public Integer getConsecutiveFailures() { return consecutiveFailures; }
    public String getLastErrorCode() { return lastErrorCode; }
    public Instant getLastErrorAt() { return lastErrorAt; }
    public Instant getNextRunAt() { return nextRunAt; }
    public String getClaimToken() { return claimToken; }
    public Instant getPostSentAt() { return postSentAt; }
    public List<String> getPreListCheckIds() { return preListCheckIds; }
    public Integer getLastDiffCount() { return lastDiffCount; }
    public Instant getPollDeadlineAt() { return pollDeadlineAt; }
    public Instant getRecheckRequestedAt() { return recheckRequestedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
