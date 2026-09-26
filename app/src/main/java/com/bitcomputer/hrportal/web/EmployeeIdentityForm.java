package com.bitcomputer.hrportal.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 관리자의 직원 신원·연락 정보 수정 폼(DESIGN.md 5.2).
 *
 * <p>사번과 재직 상태는 여기에 없다. 사번은 외부 조회 기록과 연결된 키라 바꾸지 않고,
 * 재직 상태는 계정 비활성화와 함께 움직여야 해서 "퇴사 처리" 기능으로만 바꾼다.
 */
public class EmployeeIdentityForm {

    @NotBlank(message = "성명을 입력하세요.")
    @Size(max = 50, message = "성명은 50자를 넘을 수 없습니다.")
    private String nameKo;

    @Size(max = 10, message = "성은 10자를 넘을 수 없습니다.")
    private String familyNameKo;

    @Size(max = 30, message = "이름은 30자를 넘을 수 없습니다.")
    private String givenNameKo;

    @Past(message = "생년월일은 오늘보다 앞이어야 합니다.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate birthDate;

    @Pattern(regexp = "^[0-9+\\-() ]*$", message = "연락처는 숫자와 -, +, (), 공백만 쓸 수 있습니다.")
    @Size(max = 20, message = "연락처는 20자를 넘을 수 없습니다.")
    private String phone;

    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @Size(max = 254, message = "이메일은 254자를 넘을 수 없습니다.")
    private String email;

    public EmployeeIdentityForm() {
    }

    public EmployeeIdentityForm(String nameKo, String familyNameKo, String givenNameKo, LocalDate birthDate,
                                String phone, String email) {
        this.nameKo = nameKo;
        this.familyNameKo = familyNameKo;
        this.givenNameKo = givenNameKo;
        this.birthDate = birthDate;
        this.phone = phone;
        this.email = email;
    }

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
}
