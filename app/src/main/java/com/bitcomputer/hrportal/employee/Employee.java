package com.bitcomputer.hrportal.employee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 직원(DESIGN.md 4.1). 테이블 구조는 Flyway V1이 정하고, 이 클래스는 그 테이블을 읽고 쓰는 창구다.
 *
 * <p>사람을 구분하는 키는 {@code empNo}(사번)다. 성명은 동명이인이 있어 UNIQUE가 아니다.
 * {@code birthDate}가 null이면 "생년월일 미확인"이다.
 */
@Entity
@Table(name = "employee")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "emp_no", nullable = false, updatable = false, length = 20)
    private String empNo;

    @Column(name = "name_ko", nullable = false, length = 50)
    private String nameKo;

    @Column(name = "family_name_ko", length = 10)
    private String familyNameKo;

    @Column(name = "given_name_ko", length = 30)
    private String givenNameKo;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", length = 254)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_status", nullable = false, length = 12)
    private EmploymentStatus employmentStatus = EmploymentStatus.ACTIVE;

    @Column(name = "terminated_at")
    private Instant terminatedAt;

    /** DB 기본값(UTC_TIMESTAMP(6))이 채운다. */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA가 DB에서 읽어 올 때 쓰는 생성자. */
    protected Employee() {
    }

    public Employee(String empNo, String nameKo, String familyNameKo, String givenNameKo, LocalDate birthDate) {
        this.empNo = empNo;
        this.nameKo = nameKo;
        this.familyNameKo = familyNameKo;
        this.givenNameKo = givenNameKo;
        this.birthDate = birthDate;
    }

    @PrePersist
    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }

    public boolean isActive() {
        return employmentStatus == EmploymentStatus.ACTIVE;
    }

    public boolean isBirthDateUnknown() {
        return birthDate == null;
    }

    public Long getId() { return id; }
    public String getEmpNo() { return empNo; }
    public String getNameKo() { return nameKo; }
    public String getFamilyNameKo() { return familyNameKo; }
    public String getGivenNameKo() { return givenNameKo; }
    public LocalDate getBirthDate() { return birthDate; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public EmploymentStatus getEmploymentStatus() { return employmentStatus; }
    public Instant getTerminatedAt() { return terminatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
