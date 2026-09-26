package com.bitcomputer.hrportal.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 관리자의 직원 생성 폼(DESIGN.md 5.2).
 *
 * <p>사번은 서버가 발급하므로 폼에 없다. 성·이름은 복성 때문에 규칙으로 자를 수 없어 관리자가 직접 확인해 넣는다.
 * 생년월일은 비워 둘 수 있다(EMP-007처럼 확인되지 않은 경우). 가짜 값을 넣지 않는다.
 */
public class NewEmployeeForm {

    @NotBlank(message = "성명을 입력하세요.")
    @Size(max = 50, message = "성명은 50자를 넘을 수 없습니다.")
    private String nameKo;

    @Size(max = 10, message = "성은 10자를 넘을 수 없습니다.")
    private String familyNameKo;

    @Size(max = 30, message = "이름은 30자를 넘을 수 없습니다.")
    private String givenNameKo;

    /** 비우면 "미확인". 미래 날짜는 받지 않는다. */
    @Past(message = "생년월일은 오늘보다 앞이어야 합니다.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate birthDate;

    @Pattern(regexp = "^[0-9+\\-() ]*$", message = "연락처는 숫자와 -, +, (), 공백만 쓸 수 있습니다.")
    @Size(max = 20, message = "연락처는 20자를 넘을 수 없습니다.")
    private String phone;

    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @Size(max = 254, message = "이메일은 254자를 넘을 수 없습니다.")
    private String email;

    @NotBlank(message = "로그인 ID를 입력하세요.")
    @Pattern(regexp = "^[A-Za-z0-9._-]{3,50}$", message = "로그인 ID는 영문·숫자·._- 조합 3~50자입니다.")
    private String loginId;

    /** 저장은 BCrypt 해시로만 한다. 이 값은 화면으로 다시 내려보내지 않는다. */
    @NotBlank(message = "초기 비밀번호를 입력하세요.")
    @Size(min = 10, max = 100, message = "초기 비밀번호는 10자 이상이어야 합니다.")
    private String password;

    public String getNameKo() { return nameKo; }
    public void setNameKo(String nameKo) { this.nameKo = nameKo; }

    public String getFamilyNameKo() { return familyNameKo; }
    public void setFamilyNameKo(String familyNameKo) { this.familyNameKo = familyNameKo; }

    public String getGivenNameKo() { return givenNameKo; }
    public void setGivenNameKo(String givenNameKo) { this.givenNameKo = givenNameKo; }

    public LocalDate getBirthDate() { return birthDate; }
    public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getLoginId() { return loginId; }
    public void setLoginId(String loginId) { this.loginId = loginId; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
