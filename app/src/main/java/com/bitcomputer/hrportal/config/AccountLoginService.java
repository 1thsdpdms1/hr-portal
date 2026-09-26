package com.bitcomputer.hrportal.config;

import com.bitcomputer.hrportal.account.AccountRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 성공 뒤 마지막 로그인 시각을 기록한다.
 *
 * <p>왜 별도 클래스인가: {@code @Transactional}은 Spring이 만든 빈을 다른 빈이 호출할 때만 동작한다.
 * {@code RoleBasedSuccessHandler}는 {@code new}로 직접 만들기 때문에 거기에 붙여 두면 트랜잭션이 열리지 않고,
 * 데이터를 바꾸는 쿼리가 "트랜잭션이 없다"는 오류로 실패한다.
 */
@Service
public class AccountLoginService {

    private final AccountRepository accounts;

    public AccountLoginService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public void recordLogin(Long accountId, Instant at) {
        accounts.updateLastLoginAt(accountId, at);
    }
}
