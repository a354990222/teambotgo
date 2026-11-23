package com.chaos.autoconfigure;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.chaos.audit.AuditEventPublisher;
import com.chaos.autoconfigure.audit.jdbc.ChaosAuditJdbcAutoConfiguration;
import com.chaos.autoconfigure.gateway.nacos.ChaosGatewayNacosAutoConfiguration;
import com.chaos.autoconfigure.security.redis.ChaosSecurityRedisAutoConfiguration;
import com.chaos.core.context.RequestContext;
import com.chaos.core.result.ApiResultJson;
import com.chaos.core.result.DefaultApiResult;
import com.chaos.tenant.TenantContext;
import com.chaos.trace.TraceContext;
import com.chaos.web.result.Result;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 基于 ArchUnit 的字节码级架构边界测试。
 *
 * <p>在 {@code chaos-autoconfigure} 的测试类路径上汇总了全部分功能库，直接检验编译后的字节码依赖与装配约束，
 * 避免仅靠源码字符串匹配导致的误通过。</p>
 */
class ArchitectureRulesArchUnitTest {

    private static JavaClasses chaosClasses;

    @BeforeAll
    static void importClasses() {
        chaosClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.chaos");
    }

    @Test
    void coreMustNotDependOnOtherChaosModulesOrWebSecurity() {
        noClasses().that().resideInAPackage("com.chaos.core..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.chaos.domain..",
                        "com.chaos.process..",
                        "com.chaos.trace..",
                        "com.chaos.audit..",
                        "com.chaos.tenant..",
                        "com.chaos.security..",
                        "com.chaos.authorization..",
                        "com.chaos.web..",
                        "com.chaos.http..",
                        "com.chaos.service..",
                        "com.chaos.gateway..",
                        "com.chaos.mybatis..",
                        "com.chaos.redis..",
                        "com.chaos.excel..",
                        "com.chaos.mq..",
                        "com.chaos.job..",
                        "com.chaos.storage..",
                        "com.chaos.autoconfigure..",
                        "org.springframework.web..",
                        "org.springframework.http..",
                        "org.springframework.security..",
                        "jakarta.servlet..")
                .check(chaosClasses);
    }

    @Test
    void securityApiMustNotDependOnSpringOrOtherModulesBeyondCore() {
        noClasses().that().resideInAPackage("com.chaos.security.api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "com.chaos.security.access..",
                        "com.chaos.security.permission..",
                        "com.chaos.security.config..",
                        "com.chaos.security.filter..",
                        "com.chaos.security.redis..",
                        "com.chaos.authorization..",
                        "com.chaos.gateway..",
                        "com.chaos.web..",
                        "com.chaos.mybatis..",
                        "com.chaos.redis..")
                .check(chaosClasses);
    }

    @Test
    void gatewayMybatisRedisMustOnlyDependOnSecurityApi() {
        noClasses().that().resideInAnyPackage("com.chaos.gateway..", "com.chaos.mybatis..", "com.chaos.redis..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.chaos.security.permission..",
                        "com.chaos.security.access..",
                        "com.chaos.security.config..",
                        "com.chaos.security.filter..",
                        "com.chaos.security.redis..",
                        "com.chaos.authorization..")
                .check(chaosClasses);
    }

    @Test
    void redisMustNotContainOrDependOnSecurityTypes() {
        noClasses().that().resideInAPackage("com.chaos.redis..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.chaos.security..",
                        "com.chaos.authorization..",
                        "org.springframework.security..")
                .check(chaosClasses);
    }

    @Test
    void autoConfigurationsMustResideInAutoconfigurePackageOnlyAndContainWiringOnly() {
        classes().that().areAnnotatedWith(AutoConfiguration.class)
                .should().resideInAPackage("com.chaos.autoconfigure..")
                .check(chaosClasses);

        noClasses().that().resideInAPackage("com.chaos.autoconfigure..")
                .and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Filter")
                .orShould().haveSimpleNameEndingWith("Interceptor")
                .orShould().haveSimpleNameEndingWith("Repository")
                .orShould().haveSimpleNameEndingWith("Service")
                .orShould().haveSimpleNameEndingWith("Controller")
                .orShould().haveSimpleNameEndingWith("Aspect")
                .orShould().haveSimpleNameEndingWith("Publisher")
                .orShould().haveSimpleNameEndingWith("Listener")
                .check(chaosClasses);
    }

    @Test
    void configurationPropertiesMustBeValidated() {
        classes().that().areAnnotatedWith(ConfigurationProperties.class)
                .should().beAnnotatedWith(Validated.class)
                .check(chaosClasses);
    }

    @Test
    void securityAndAuthorizationPathsMustInvokeAuditEventPublisher() {
        classes().that().haveFullyQualifiedName("com.chaos.security.permission.PermissionAspect")
                .or().haveFullyQualifiedName("com.chaos.gateway.filter.JwtAuthenticationGatewayFilter")
                .or().haveFullyQualifiedName("com.chaos.gateway.filter.BlacklistFilter")
                .or().haveFullyQualifiedName("com.chaos.gateway.filter.TenantGatewayFilter")
                .or().haveFullyQualifiedName("com.chaos.authorization.grant.ChaosGrantAuthenticationProvider")
                .or().haveFullyQualifiedName("com.chaos.authorization.token.ChaosRefreshTokenAuthenticationProvider")
                .or().haveFullyQualifiedName("com.chaos.authorization.token.ChaosTokenRevocationAuthenticationProvider")
                .or().haveFullyQualifiedName("com.chaos.authorization.kickout.IndexedAuthorizationKickoutService")
                .should().dependOnClassesThat().areAssignableTo(AuditEventPublisher.class)
                .check(chaosClasses);
    }

    @Test
    void contextPropagationMustNotUseInheritableThreadLocalAndGatewayResponseMatchesResultShape() {
        noClasses().that().belongToAnyOf(RequestContext.class, TraceContext.class, TenantContext.class)
                .should().dependOnClassesThat().areAssignableTo(InheritableThreadLocal.class)
                .check(chaosClasses);

        classes().that().haveFullyQualifiedName("com.chaos.gateway.filter.GatewayErrorResponseWriter")
                .should().dependOnClassesThat().areAssignableTo(ApiResultJson.class)
                .check(chaosClasses);

        List<String> webComponents = Arrays.stream(Result.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        List<String> coreComponents = Arrays.stream(DefaultApiResult.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(coreComponents).containsExactlyElementsOf(webComponents);
    }

    @Test
    void autoConfigurationOrderingAnnotationsMustMatchContracts() {
        assertThat(ChaosSecurityRedisAutoConfiguration.class.getAnnotation(AutoConfiguration.class).beforeName())
                .contains("com.chaos.autoconfigure.security.ChaosSecurityAutoConfiguration");
        assertThat(ChaosAuditJdbcAutoConfiguration.class.getAnnotation(AutoConfiguration.class).beforeName())
                .contains("com.chaos.autoconfigure.audit.ChaosAuditAutoConfiguration");
        assertThat(ChaosGatewayNacosAutoConfiguration.class.getAnnotation(AutoConfiguration.class).beforeName())
                .contains("org.springframework.cloud.gateway.config.GatewayAutoConfiguration");
    }
}
