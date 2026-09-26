package com.bitcomputer.hrportal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 컨트롤러의 {@code @PathVariable} · {@code @RequestParam}에 이름이 적혀 있는지 검사한다.
 *
 * <p>왜 필요한가: 이름을 생략하면 Spring은 컴파일된 클래스에 남은 <b>파라미터 이름</b>에 기댄다.
 * 그 이름은 {@code -parameters} 옵션으로 컴파일해야 남는다. Gradle(Spring Boot 플러그인)은 이 옵션을 붙이지만
 * IntelliJ가 자체 컴파일러로 빌드하면 빠질 수 있어, <b>테스트는 통과하는데 IntelliJ로 실행하면 500</b>이 나는
 * 상황이 실제로 있었다. 이름을 항상 적어 두면 컴파일 옵션과 무관하게 동작한다.
 *
 * <p>이 테스트는 스프링 컨텍스트 없이 리플렉션만 쓴다(DB도 필요 없다).
 */
class ControllerParameterNameTest {

    private static final List<Class<?>> CONTROLLERS =
        List.of(AdminEmployeeController.class, MyInfoController.class, LoginController.class);

    @Test
    void 경로변수와_요청파라미터에는_이름이_적혀_있다() {
        List<String> missing = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    checkPathVariable(controller, method, parameter, missing);
                    checkRequestParam(controller, method, parameter, missing);
                }
            }
        }
        assertThat(missing)
            .as("애너테이션에 이름을 적어야 한다. 예: @PathVariable(\"empNo\") String empNo")
            .isEmpty();
    }

    private void checkPathVariable(Class<?> controller, Method method, Parameter parameter, List<String> missing) {
        PathVariable annotation = parameter.getAnnotation(PathVariable.class);
        if (annotation != null && annotation.value().isEmpty() && annotation.name().isEmpty()) {
            missing.add(describe(controller, method, "@PathVariable"));
        }
    }

    private void checkRequestParam(Class<?> controller, Method method, Parameter parameter, List<String> missing) {
        RequestParam annotation = parameter.getAnnotation(RequestParam.class);
        // Map으로 전체 파라미터를 받는 경우는 이름이 필요 없다(내 정보 수정의 허용 파라미터 검사에서 쓴다).
        if (annotation != null && Map.class.isAssignableFrom(parameter.getType())) {
            return;
        }
        if (annotation != null && annotation.value().isEmpty() && annotation.name().isEmpty()) {
            missing.add(describe(controller, method, "@RequestParam"));
        }
    }

    private String describe(Class<?> controller, Method method, String annotationName) {
        return controller.getSimpleName() + "." + method.getName() + "() 의 " + annotationName;
    }
}
