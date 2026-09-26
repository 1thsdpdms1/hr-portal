package com.bitcomputer.hrportal.account;

import com.bitcomputer.hrportal.employee.Employee;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인할 때 DB에서 계정을 찾아 {@link AccountPrincipal}로 바꾼다.
 *
 * <p>계정을 찾지 못하면 Spring Security가 "비밀번호가 틀렸다"와 같은 오류로 바꿔 주므로(기본 설정),
 * 로그인 화면에서 "그 아이디는 없다"는 정보가 새어 나가지 않는다.
 */
@Service
public class AccountUserDetailsService implements UserDetailsService {

    private final AccountRepository accounts;

    public AccountUserDetailsService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String loginId = Account.normalizeLoginId(username);
        Account account = accounts.findByLoginIdWithEmployee(loginId)
            .orElseThrow(() -> new UsernameNotFoundException("계정을 찾을 수 없습니다"));

        Employee employee = account.getEmployee();
        // 직원 계정은 그 직원이 재직 중이어야 쓸 수 있다. 퇴사 처리는 계정 비활성화와 같은 트랜잭션에서 일어나지만,
        // 둘 중 하나만 바뀐 데이터가 있어도 막히도록 두 값을 모두 본다.
        boolean enabled = account.isActive()
            && (account.getRole() != Role.EMPLOYEE || (employee != null && employee.isActive()));

        return new AccountPrincipal(
            account.getId(),
            account.getLoginId(),
            account.getPasswordHash(),
            account.getRole(),
            employee != null ? employee.getId() : null,
            enabled);
    }
}
