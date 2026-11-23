package com.chaos.redis.cache;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 多实例两级缓存 L1 主动失效广播消息。
 *
 * <p>仅通过纯 UTF-8 文本编码基础类型 key（避免 Java 反序列化或 Jackson 多态类型风险），
 * 并携带发送方 {@code originId} 以便发布实例忽略自身回声消息。</p>
 *
 * @param originId 发布该失效事件的两级缓存管理器实例标识
 * @param action 失效动作（单 key 失效或全量清空）
 * @param cacheName 缓存命名空间
 * @param key 失效的缓存 key；当 {@code action == CLEAR} 时为 {@code null}
 */
public record TwoLevelCacheInvalidationMessage(
        String originId,
        Action action,
        String cacheName,
        Object key
) {

    private static final String VERSION = "v1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public TwoLevelCacheInvalidationMessage {
        if (originId == null || originId.isBlank()) {
            throw new IllegalArgumentException("originId must not be blank");
        }
        Objects.requireNonNull(action, "action must not be null");
        if (cacheName == null || cacheName.isBlank()) {
            throw new IllegalArgumentException("cacheName must not be blank");
        }
        if (originId.indexOf('|') >= 0) {
            throw new IllegalArgumentException("originId must not contain '|'");
        }
    }

    /**
     * 创建单 key 失效广播消息。
     */
    public static TwoLevelCacheInvalidationMessage evict(String originId, String cacheName, Object key) {
        return new TwoLevelCacheInvalidationMessage(originId, Action.EVICT, cacheName, key);
    }

    /**
     * 创建清空指定缓存命名空间 L1 的广播消息。
     */
    public static TwoLevelCacheInvalidationMessage clear(String originId, String cacheName) {
        return new TwoLevelCacheInvalidationMessage(originId, Action.CLEAR, cacheName, null);
    }

    /**
     * 将消息编码为单行 UTF-8 文本，便于通过 Redis Pub/Sub 通道传输。
     */
    public String encode() {
        String encodedCache = encodeText(cacheName);
        if (action == Action.CLEAR) {
            return VERSION + "|" + originId + "|C|" + encodedCache + "|N|";
        }
        EncodedKey encodedKey = encodeKey(key);
        return VERSION + "|" + originId + "|E|" + encodedCache + "|" + encodedKey.tag() + "|" + encodedKey.payload();
    }

    /**
     * 解析来自 Redis Pub/Sub 通道的失效广播消息；格式非法或版本不支持时返回空。
     */
    public static Optional<TwoLevelCacheInvalidationMessage> decode(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 6 || !VERSION.equals(parts[0])) {
            return Optional.empty();
        }
        String origin = parts[1];
        String actionFlag = parts[2];
        String encodedCache = parts[3];
        String tag = parts[4];
        String encodedKey = parts[5];
        if (origin.isBlank() || encodedCache.isBlank()) {
            return Optional.empty();
        }
        try {
            String cacheName = decodeText(encodedCache);
            if (cacheName.isBlank()) {
                return Optional.empty();
            }
            if ("C".equals(actionFlag)) {
                return Optional.of(clear(origin, cacheName));
            }
            if ("E".equals(actionFlag)) {
                Object key = decodeKey(tag, encodedKey);
                return Optional.of(evict(origin, cacheName, key));
            }
            return Optional.empty();
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static EncodedKey encodeKey(Object key) {
        if (key == null) {
            return new EncodedKey("N", "");
        }
        if (key instanceof String s) {
            return new EncodedKey("S", encodeText(s));
        }
        if (key instanceof Long l) {
            return new EncodedKey("L", encodeText(Long.toString(l)));
        }
        if (key instanceof Integer i) {
            return new EncodedKey("I", encodeText(Integer.toString(i)));
        }
        if (key instanceof Boolean b) {
            return new EncodedKey("B", encodeText(Boolean.toString(b)));
        }
        if (key instanceof UUID u) {
            return new EncodedKey("U", encodeText(u.toString()));
        }
        return new EncodedKey("X", encodeText(String.valueOf(key)));
    }

    private static Object decodeKey(String tag, String encoded) {
        return switch (tag) {
            case "N" -> null;
            case "S" -> decodeText(encoded);
            case "L" -> Long.valueOf(decodeText(encoded));
            case "I" -> Integer.valueOf(decodeText(encoded));
            case "B" -> Boolean.valueOf(decodeText(encoded));
            case "U" -> UUID.fromString(decodeText(encoded));
            case "X" -> new OpaqueKey(decodeText(encoded));
            default -> throw new IllegalArgumentException("Unknown key tag: " + tag);
        };
    }

    private static String encodeText(String raw) {
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String encoded) {
        return new String(DECODER.decode(encoded), StandardCharsets.UTF_8);
    }

    /**
     * L1 失效动作枚举。
     */
    public enum Action {
        /**
         * 失效单个缓存 key。
         */
        EVICT,
        /**
         * 清空整个缓存命名空间的本地 L1。
         */
        CLEAR
    }

    /**
     * 非基础类型的字符串兜底 key，供远端节点按 {@code String.valueOf(localKey)} 匹配淘汰。
     *
     * @param text 原始 {@code String.valueOf(key)} 文本
     */
    record OpaqueKey(String text) {
    }

    private record EncodedKey(String tag, String payload) {
    }
}
