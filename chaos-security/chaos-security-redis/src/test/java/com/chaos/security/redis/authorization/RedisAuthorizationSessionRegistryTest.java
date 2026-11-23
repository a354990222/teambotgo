package com.chaos.security.redis.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaos.authorization.core.ChaosAuthorizationProperties;
import com.chaos.authorization.grant.ChaosAuthorizationGrantTypes;
import com.chaos.authorization.kickout.AuthorizationSession;
import com.chaos.authorization.session.AuthorizationLoginContext;
import com.chaos.authorization.session.AuthorizationSessionAttributes;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * {@link RedisAuthorizationSessionRegistry} 单元测试。
 */
class RedisAuthorizationSessionRegistryTest {

    private final RegisteredClient client = RegisteredClient.withId("client-1")
            .clientId("chaos-client")
            .authorizationGrantType(ChaosAuthorizationGrantTypes.PASSWORD)
            .build();

    @Test
    void shouldSaveFindCleanStaleAndRemoveSessions() {
        InMemorySessionRedisTemplate redisTemplate = new InMemorySessionRedisTemplate();
        RedisAuthorizationSessionRegistry registry = new RedisAuthorizationSessionRegistry(
                redisTemplate, new ChaosAuthorizationProperties());

        OAuth2Authorization authWithDevice = OAuth2Authorization.withRegisteredClient(client)
                .id("auth-1")
                .principalName("1001")
                .authorizationGrantType(ChaosAuthorizationGrantTypes.PASSWORD)
                .attribute(
                        AuthorizationSessionAttributes.LOGIN_CONTEXT,
                        new AuthorizationLoginContext("password", "dev-1", "10.0.0.1", "ua"))
                .accessToken(new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        "access-1",
                        Instant.now(),
                        Instant.now().plusSeconds(300)))
                .refreshToken(new OAuth2RefreshToken(
                        "refresh-1",
                        Instant.now(),
                        Instant.now().plusSeconds(3600)))
                .build();

        registry.save(null);
        registry.save(authWithDevice);

        assertThat(registry.findByPrincipal("1001")).singleElement()
                .extracting(AuthorizationSession::authorizationId)
                .isEqualTo("auth-1");
        assertThat(registry.findByPrincipalAndClient("1001", "client-1")).singleElement()
                .extracting(AuthorizationSession::deviceId)
                .isEqualTo("dev-1");
        assertThat(registry.findByPrincipalClientAndDevice("1001", "client-1", "dev-1")).hasSize(1);
        assertThat(registry.findByPrincipalClientAndDevice("1001", "client-1", "  ")).isEmpty();

        // Simulate expired/missing session entry so readSessions cleans up stale set member.
        redisTemplate.values.remove("chaos:authorization:kickout:session:id:auth-1");
        assertThat(registry.findByPrincipal("1001")).isEmpty();
        assertThat(redisTemplate.sets.get("chaos:authorization:kickout:session:principal:1001:global")).isEmpty();

        // Save again and test remove(authorization) and guards.
        registry.save(authWithDevice);
        registry.remove((OAuth2Authorization) null);
        registry.remove("", "client-1", "auth-1");
        registry.remove("1001", "client-1", "");
        registry.remove(authWithDevice);

        assertThat(registry.findByPrincipal("1001")).isEmpty();
        assertThat(registry.findByPrincipalAndClient("1001", "client-1")).isEmpty();
        assertThat(registry.findByPrincipalClientAndDevice("1001", "client-1", "dev-1")).isEmpty();
    }

    private static final class InMemorySessionRedisTemplate extends RedisTemplate<Object, Object> {

        private final Map<Object, Object> values = new HashMap<>();

        private final Map<Object, Set<Object>> sets = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<Object, Object> opsForValue() {
            return (ValueOperations<Object, Object>) Proxy.newProxyInstance(
                    ValueOperations.class.getClassLoader(),
                    new Class<?>[] {ValueOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "set" -> {
                            values.put(args[0], args[1]);
                            yield null;
                        }
                        case "get" -> values.get(args[0]);
                        default -> null;
                    });
        }

        @Override
        @SuppressWarnings("unchecked")
        public SetOperations<Object, Object> opsForSet() {
            return (SetOperations<Object, Object>) Proxy.newProxyInstance(
                    SetOperations.class.getClassLoader(),
                    new Class<?>[] {SetOperations.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "add" -> {
                            Set<Object> members = sets.computeIfAbsent(args[0], k -> new LinkedHashSet<>());
                            long added = 0;
                            for (Object v : (Object[]) args[1]) {
                                if (members.add(v)) {
                                    added++;
                                }
                            }
                            yield added;
                        }
                        case "members" -> sets.getOrDefault(args[0], Set.of());
                        case "remove" -> {
                            Set<Object> members = sets.get(args[0]);
                            if (members == null) {
                                yield 0L;
                            }
                            long removed = 0;
                            for (Object v : (Object[]) args[1]) {
                                if (members.remove(v)) {
                                    removed++;
                                }
                            }
                            yield removed;
                        }
                        default -> null;
                    });
        }

        @Override
        public Boolean delete(Object key) {
            return values.remove(key) != null;
        }

        @Override
        public Boolean expire(Object key, Duration timeout) {
            return Boolean.TRUE;
        }

        @Override
        public Boolean expire(Object key, long timeout, TimeUnit unit) {
            return Boolean.TRUE;
        }
    }
}
