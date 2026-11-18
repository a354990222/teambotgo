package com.chaos.core.exception;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 异常诊断只包含类型与代码位置，禁止携带消息、SQL、请求值或令牌。 */
public final class SafeExceptionDiagnostics {
    private SafeExceptionDiagnostics() { }

    /** 有界遍历原因链，保留定位信息；不调用异常的 toString/getMessage。 */
    public static String describe(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int depth = 0; failure != null && depth < 8 && seen.add(failure); depth++) {
            result.append(failure.getClass().getName());
            StackTraceElement[] frames = failure.getStackTrace();
            for (int i = 0; frames != null && i < Math.min(frames.length, 20); i++) {
                result.append("\n  at ").append(frames[i]);
            }
            failure = failure.getCause();
            if (failure != null) {
                result.append("\nCaused by type: ");
            }
        }
        return result.toString();
    }
}
