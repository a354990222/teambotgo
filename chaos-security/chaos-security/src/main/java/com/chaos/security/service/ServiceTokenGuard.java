package com.chaos.security.service;

import com.chaos.security.api.auth.ChaosJwtClaims;
import java.util.Arrays;
import java.util.Collection;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 校验当前请求的服务 JWT 身份及作用域。
 *
 * <p>资源服务器须先完成验签、时效、签发者及所需受众校验。本类只接受已认证的客户端凭证 JWT，
 * 不读取请求头，也不将用户令牌或服务委托身份当作客户端凭证令牌。</p>
 */
public final class ServiceTokenGuard {

    private ServiceTokenGuard() {
    }

    /**
     * 要求服务 JWT 包含指定作用域，并返回其非空客户端标识。
     *
     * @param scope 单个 OAuth2 scope，精确匹配，不进行权限通配
     * @return 已验证令牌中的客户端标识，不为 null
     * @throws IllegalArgumentException scope 为空或不是合法的单个作用域
     * @throws AccessDeniedException 未认证、不是客户端凭证 JWT、客户端标识缺失或作用域不匹配
     */
    public static String require(String scope) {
        if (!validScope(scope)) {
            throw new IllegalArgumentException("scope 必须是单个非空 OAuth2 作用域");
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("需要已认证的服务 JWT");
        }
        var claims = jwtAuthentication.getToken().getClaims();
        if (!AuthorizationGrantType.CLIENT_CREDENTIALS.getValue().equals(claims.get(ChaosJwtClaims.GRANT_TYPE))
                || !(claims.get(ChaosJwtClaims.CLIENT_ID) instanceof String clientId)
                || clientId.isBlank()) {
            throw new AccessDeniedException("需要客户端凭证令牌及客户端标识");
        }
        if (!containsScope(claims.get(OAuth2ParameterNames.SCOPE), scope)) {
            throw new AccessDeniedException("服务令牌缺少所需作用域");
        }
        return clientId;
    }

    private static boolean containsScope(Object claim, String requiredScope) {
        Collection<?> scopes;
        if (claim instanceof String value) {
            scopes = Arrays.asList(value.trim().split(" +"));
        } else if (claim instanceof Collection<?> values) {
            scopes = values;
        } else {
            return false;
        }
        return scopes.stream().allMatch(value -> value instanceof String scope && validScope(scope))
                && scopes.contains(requiredScope);
    }

    private static boolean validScope(String scope) {
        // RFC 6749 scope-token：可打印 ASCII，排除空格、双引号和反斜杠。
        return scope != null && !scope.isEmpty()
                && scope.chars().allMatch(c -> c == 0x21 || (c >= 0x23 && c <= 0x5b) || (c >= 0x5d && c <= 0x7e));
    }
}
