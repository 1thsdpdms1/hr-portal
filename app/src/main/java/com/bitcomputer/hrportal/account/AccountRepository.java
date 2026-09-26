package com.bitcomputer.hrportal.account;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 호출 전에 {@link Account#normalizeLoginId(String)}로 소문자로 바꾼 값을 넘긴다. */
    Optional<Account> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);

    /** 로그인할 때 계정과 연결 직원을 한 번에 읽는다(지연 로딩 없이). */
    @Query("select a from Account a left join fetch a.employee where a.loginId = :loginId")
    Optional<Account> findByLoginIdWithEmployee(@Param("loginId") String loginId);

    /**
     * 요청마다 하는 계정 상태 확인(DESIGN.md 2.4). 엔티티를 불러오지 않고 필요한 값만 한 번에 읽는다.
     */
    @Query("select new com.bitcomputer.hrportal.account.AccountStatusView("
        + "a.active, a.role, e.employmentStatus) "
        + "from Account a left join a.employee e where a.id = :accountId")
    Optional<AccountStatusView> findStatusById(@Param("accountId") Long accountId);

    @Modifying
    @Query("update Account a set a.lastLoginAt = :at where a.id = :accountId")
    void updateLastLoginAt(@Param("accountId") Long accountId, @Param("at") Instant at);
}
