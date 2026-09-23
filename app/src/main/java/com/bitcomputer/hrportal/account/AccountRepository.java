package com.bitcomputer.hrportal.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 호출 전에 {@link Account#normalizeLoginId(String)}로 소문자로 바꾼 값을 넘긴다. */
    Optional<Account> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);
}
