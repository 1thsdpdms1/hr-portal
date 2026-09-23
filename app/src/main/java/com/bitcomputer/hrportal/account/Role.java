package com.bitcomputer.hrportal.account;

/** 역할은 두 개뿐이라 별도 권한 테이블 없이 컬럼 하나로 둔다(DESIGN.md 4.2). */
public enum Role {
    ADMIN,
    EMPLOYEE
}
