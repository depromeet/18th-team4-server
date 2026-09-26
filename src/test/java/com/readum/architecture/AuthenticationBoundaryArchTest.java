package com.readum.architecture;

import com.readum.domain.auth.service.SessionAuthenticationService;
import com.readum.model.user.repository.UserRepository;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.web.bind.annotation.CookieValue;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 인증 신원 해석 일원화 규칙 (2026-06-20 429 사고 재발 방지).
 * 신원은 인증 필터가 해석해 principal 로만 전달한다 — 다른 컴포넌트가
 * 쿠키/SecurityContext 를 직접 뒤지는 코드를 빌드 단계에서 차단한다.
 */
@AnalyzeClasses(packages = "com.readum", importOptions = ImportOption.DoNotIncludeTests.class)
class AuthenticationBoundaryArchTest {

    @ArchTest
    static final ArchRule SecurityContextHolder_는_security_패키지_밖에서_직접_참조하지_않는다 =
            noClasses()
                    .that().resideOutsideOfPackage("com.readum.presentation.common.security")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.security.core.context.SecurityContextHolder")
                    .because("신원은 @AuthenticatedUserId 로만 받는다 — SecurityContext 직접 접근은 인증 필터/리졸버의 책임");

    @ArchTest
    static final ArchRule user_session_쿠키는_컨트롤러가_직접_받지_않는다 =
            methods().should(new ArchCondition<>("user_session 을 @CookieValue 로 직접 받지 않는다") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    method.getParameters().forEach(parameter ->
                            parameter.tryGetAnnotationOfType(CookieValue.class).ifPresent(cookieValue -> {
                                String cookieName = !cookieValue.name().isEmpty()
                                        ? cookieValue.name()
                                        : cookieValue.value();
                                if ("user_session".equals(cookieName)) {
                                    events.add(SimpleConditionEvent.violated(method,
                                            method.getFullName() + " 가 user_session 쿠키를 직접 받는다 — @AuthenticatedUserId 를 사용할 것"));
                                }
                            }));
                }
            }).because("신원 수령 통로는 @AuthenticatedUserId 하나다");

    @ArchTest
    static final ArchRule findBySessionId_는_SessionAuthenticationService_만_호출한다 =
            noClasses()
                    .that().doNotHaveFullyQualifiedName(SessionAuthenticationService.class.getName())
                    .should().callMethod(UserRepository.class, "findBySessionId", String.class)
                    .because("세션 쿠키 → 사용자 해석은 SessionAuthenticationService 한 곳에서만 일어난다");
}
