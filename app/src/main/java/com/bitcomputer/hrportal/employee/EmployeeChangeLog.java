package com.bitcomputer.hrportal.employee;

import com.bitcomputer.hrportal.account.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 직원 정보 변경 이력(DESIGN.md 4.4). 한 번 쓰면 고치지 않으므로 모든 컬럼이 updatable = false다.
 * 개인정보가 들어가므로 관리자만 본다.
 */
@Entity
@Table(name = "employee_change_log")
public class EmployeeChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false, updatable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "changed_by_id", nullable = false, updatable = false)
    private Account changedBy;

    /** 허용 값은 DB CHECK(ck_change_log_field_name)가 정한다. */
    @Column(name = "field_name", nullable = false, updatable = false, length = 30)
    private String fieldName;

    @Column(name = "old_value", updatable = false, columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", updatable = false, columnDefinition = "TEXT")
    private String newValue;

    /** DB 기본값(UTC_TIMESTAMP(6))이 채운다. */
    @Column(name = "changed_at", insertable = false, updatable = false)
    private Instant changedAt;

    protected EmployeeChangeLog() {
    }

    public EmployeeChangeLog(Employee employee, Account changedBy, String fieldName, String oldValue, String newValue) {
        this.employee = employee;
        this.changedBy = changedBy;
        this.fieldName = fieldName;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public Long getId() { return id; }
    public Employee getEmployee() { return employee; }
    public Account getChangedBy() { return changedBy; }
    public String getFieldName() { return fieldName; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public Instant getChangedAt() { return changedAt; }
}
