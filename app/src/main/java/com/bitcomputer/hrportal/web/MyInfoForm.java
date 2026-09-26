package com.bitcomputer.hrportal.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 직원 본인이 고칠 수 있는 필드만 담는 폼(DESIGN.md 4.1-B).
 *
 * <p>엔티티를 폼에 직접 바인딩하지 않는 이유: Spring은 모르는 요청 파라미터를 조용히 무시하지만,
 * 엔티티를 바인딩하면 {@code birthDate} 같은 신원 필드까지 요청으로 바꿀 수 있게 된다(대량 할당).
 * 그래서 허용 필드만 가진 클래스를 따로 두고, 컨트롤러가 허용하지 않은 파라미터 자체를 거부한다.
 */
public class MyInfoForm {

    /** 숫자와 -, +, (), 공백만 허용한다. 국가별 형식이 달라 자릿수는 강제하지 않는다. */
    @Pattern(regexp = "^[0-9+\\-() ]*$", message = "연락처는 숫자와 -, +, (), 공백만 쓸 수 있습니다.")
    @Size(max = 20, message = "연락처는 20자를 넘을 수 없습니다.")
    private String phone;

    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @Size(max = 254, message = "이메일은 254자를 넘을 수 없습니다.")
    private String email;

    public MyInfoForm() {
    }

    public MyInfoForm(String phone, String email) {
        this.phone = phone;
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
