package com.bitcomputer.hrportal.employee;

/** 재직 상태. DB에는 이름 그대로(ACTIVE/TERMINATED) 저장한다(CHECK ck_employee_status). */
public enum EmploymentStatus {
    ACTIVE,
    TERMINATED
}
