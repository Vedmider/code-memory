package com.example.codemem.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScopeResolverTest {

    private final ScopeResolver scope = new ScopeResolver();

    @Test
    void defaultsToAll() {
        assertThat(scope.resolve("src/main/java/App.java")).containsExactly("all");
    }

    @Test
    void testsAreDevAndQa() {
        assertThat(scope.resolve("src/test/java/AppTest.java")).containsExactlyInAnyOrder("dev", "qa");
        assertThat(scope.resolve("pkg/ServiceIntegration_test.java")).containsExactlyInAnyOrder("dev", "qa");
        assertThat(scope.resolve("pkg/app.test.js")).containsExactlyInAnyOrder("dev", "qa");
        assertThat(scope.resolve("pkg/test_service.py")).containsExactlyInAnyOrder("dev", "qa");
    }

    @Test
    void deployAndSecretsAreDevops() {
        assertThat(scope.resolve("deploy/chart/values.yaml")).containsExactly("devops");
        assertThat(scope.resolve("charts/app/x.yaml")).containsExactly("devops");
        assertThat(scope.resolve("k8s/deployment.yaml")).containsExactly("devops");
        assertThat(scope.resolve("infra/main.tf")).containsExactly("devops");
        assertThat(scope.resolve("config/app.env")).containsExactly("devops");
        assertThat(scope.resolve("src/SecretManager.java")).containsExactly("devops");
    }

    @Test
    void secretTakesPrecedenceOverTestLocation() {
        assertThat(scope.resolve("test/secret-values.env")).containsExactly("devops");
    }

    @Test
    void pathNormalisationHandlesBackslashesLeadingDotAndCase() {
        assertThat(scope.resolve("src\\test\\java\\AppTest.java")).containsExactlyInAnyOrder("dev", "qa");
        assertThat(scope.resolve("./deploy/values.yaml")).containsExactly("devops");
        assertThat(scope.resolve("Deploy/Values.yaml")).containsExactly("devops");
    }
}
