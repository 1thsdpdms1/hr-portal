package com.bitcomputer.hrportal.account;

import com.bitcomputer.hrportal.employee.Employee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;

/**
 * 로그인 계정(DESIGN.md 4.2). 관리자 계정은 직원과 연결하지 않는다(employee = null).
 * 일반 직원 계정은 반드시 직원과 연결된다(DB CHECK ck_account_employee_link).
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "login_id", nullable = false, length = 50)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 10)
    private Role role;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** DB 기본값(UTC_TIMESTAMP(6))이 채운다. */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
    }

    private Account(String loginId, String passwordHash, Role role, Employee employee) {
        this.loginId = normalizeLoginId(loginId);
        this.passwordHash = passwordHash;
        this.role = role;
        this.employee = employee;
    }

    public static Account admin(String loginId, String passwordHash) {
        return new Account(loginId, passwordHash, Role.ADMIN, null);
    }

    public static Account employee(String loginId, String passwordHash, Employee employee) {
        return new Account(loginId, passwordHash, Role.EMPLOYEE, employee);
    }

    /** 로그인 ID는 소문자로 저장하고 소문자로 찾는다. */
    public static String normalizeLoginId(String loginId) {
        return loginId == null ? null : loginId.trim().toLowerCase(Locale.ROOT);
    }

    @PrePersist
    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getLoginId() { return loginId; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public Employee getEmployee() { return employee; }
    public boolean isActive() { return active; }
    public Instant getDeactivatedAt() { return deactivatedAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
