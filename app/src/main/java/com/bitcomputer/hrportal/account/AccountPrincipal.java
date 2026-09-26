package com.bitcomputer.hrportal.account;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 로그인한 사용자를 나타내는 작은 값 객체(DESIGN.md 2.4).
 *
 * <p>여기에는 계정 ID·로그인 ID·역할·직원 ID만 담는다. JPA 엔티티를 그대로 담지 않는 이유는 두 가지다.
 * ① 이 객체는 세션에 복사되어 남는다. 엔티티를 넣으면 DB가 바뀌어도 세션 안의 값은 옛 값 그대로다.
 * ② 세션에 저장하려면 직렬화가 되어야 하는데, 지연 로딩 프록시가 붙은 엔티티는 문제를 일으킨다.
 *
 * <p>계정이 살아 있는지는 세션 값이 아니라 요청마다 DB로 확인한다({@code AccountStatusFilter}).
 */
public class AccountPrincipal implements UserDetails {

    private final Long accountId;
    private final String loginId;
    private final String passwordHash;
    private final Role role;
    private final Long employeeId;
    private final boolean enabled;

    public AccountPrincipal(Long accountId, String loginId, String passwordHash, Role role, Long employeeId,
                            boolean enabled) {
        this.accountId = accountId;
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.role = role;
        this.employeeId = employeeId;
        this.enabled = enabled;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Role getRole() {
        return role;
    }

    /** 관리자 계정은 직원과 연결되지 않으므로 null일 수 있다. */
    public Long getEmployeeId() {
        return employeeId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Spring Security의 hasRole("ADMIN")은 "ROLE_ADMIN" 권한을 찾는다.
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return loginId;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}
