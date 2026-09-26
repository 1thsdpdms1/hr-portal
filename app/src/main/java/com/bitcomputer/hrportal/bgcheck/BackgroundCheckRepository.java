package com.bitcomputer.hrportal.bgcheck;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 읽기용. 상태 변경(선점·결과 기록)은 {@link BackgroundCheckStore}의 네이티브 SQL만 한다(DESIGN.md 2.5.3).
 *
 * <p>화면에 넘길 값은 모두 생성자 표현식으로 필요한 필드만 읽는다. 엔티티를 넘기면 지연 로딩 필드를
 * 템플릿이 건드려 실패한다({@code open-in-view: false}).
 */
public interface BackgroundCheckRepository extends JpaRepository<BackgroundCheck, Long> {

    /** 직원별 조회 이력, 최신순. 인덱스 ix_bgcheck_employee_requested를 쓴다. */
    @Query("""
        select new com.bitcomputer.hrportal.bgcheck.BackgroundCheckViews$Summary(
            b.id, b.requestedAt, b.syncState, b.resultStatus, b.checkId, a.loginId)
        from BackgroundCheck b join b.requestedBy a
        where b.employee.id = :employeeId
        order by b.requestedAt desc""")
    List<BackgroundCheckViews.Summary> findSummaries(@Param("employeeId") Long employeeId);

    @Query("""
        select new com.bitcomputer.hrportal.bgcheck.BackgroundCheckViews$Detail(
            b.id, e.empNo, e.nameKo, b.requestedAt, a.loginId, b.syncState, b.resultStatus, b.checkId,
            b.criminalRecord, b.educationVerified, b.employmentVerified, b.creditScore,
            b.externalCreatedAt, b.completedAt, b.pollCount, b.consecutiveFailures,
            b.lastErrorCode, b.lastErrorAt, b.lastDiffCount,
            case when b.preListCheckIds is null then true else false end,
            b.recheckRequestedAt, b.detailPurgedAt)
        from BackgroundCheck b join b.employee e join b.requestedBy a
        where b.id = :id""")
    Optional<BackgroundCheckViews.Detail> findDetail(@Param("id") Long id);

    /** 이 직원에게 진행 중(또는 확인 필요)인 조회가 있는지. 화면 안내용이며, 최종 보장은 DB UNIQUE가 한다. */
    @Query("""
        select b.id from BackgroundCheck b
        where b.employee.id = :employeeId
          and b.syncState in (com.bitcomputer.hrportal.bgcheck.SyncState.QUEUED,
                              com.bitcomputer.hrportal.bgcheck.SyncState.SUBMITTING,
                              com.bitcomputer.hrportal.bgcheck.SyncState.SUBMIT_UNKNOWN,
                              com.bitcomputer.hrportal.bgcheck.SyncState.IN_PROGRESS)""")
    List<Long> findActiveIds(@Param("employeeId") Long employeeId);

    /** 직원 목록에 보여 줄 "가장 최근 조회" 한 건. */
    @Query("""
        select new com.bitcomputer.hrportal.bgcheck.BackgroundCheckViews$Summary(
            b.id, b.requestedAt, b.syncState, b.resultStatus, b.checkId, a.loginId)
        from BackgroundCheck b join b.requestedBy a
        where b.employee.id = :employeeId
        order by b.requestedAt desc
        limit 1""")
    Optional<BackgroundCheckViews.Summary> findLatestSummary(@Param("employeeId") Long employeeId);
}
