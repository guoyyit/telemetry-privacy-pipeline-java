package com.github.guoyyit.telemetryprivacypipelinejava.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import com.github.guoyyit.telemetryprivacypipelinejava.model.Transform;

/**
 * 字段级脱敏与去标识。
 *
 * <p>确定性保证：</p>
 * <ul>
 *   <li>结果使用按字段名排序的有序 Map，迭代顺序与输入 Map 的迭代顺序无关；</li>
 *   <li>每个字段的变换是纯函数，同一输入同一策略永远得到同一输出；</li>
 *   <li>摘要使用 SHA-256 十六进制，不带盐，保证跨处理顺序、跨批次可复现。</li>
 * </ul>
 */
@Component
public class FieldSanitizer {

    /** 掩码占位符，不保留任何原文片段。 */
    public static final String MASK_TOKEN = "***REDACTED***";

    /** 被删除字段使用的占位键值（字段整体从视图移除时不保留键）。 */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public Map<String, String> sanitize(Map<String, String> rawFields, CollectionPolicy policy) {
        // TreeMap：输出顺序由字段名字典序决定，与输入处理顺序无关；
        // 返回保持该迭代顺序的不可变视图（不能用 Map.copyOf，其迭代顺序未指定）。
        Map<String, String> out = new TreeMap<>();
        if (rawFields == null) {
            return Collections.unmodifiableMap(out);
        }
        for (Map.Entry<String, String> entry : rawFields.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue();
            Transform transform = policy.transformOf(key);
            switch (transform) {
                case KEEP -> out.put(key, value);
                case MASK -> out.put(key, MASK_TOKEN);
                case HASH -> out.put(key, sha256(value));
                case DROP -> { /* 删除：该字段不进入任何视图 */ }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** 对去标识值计算确定性 SHA-256 摘要。 */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            char[] chars = new char[hash.length * 2];
            for (int i = 0; i < hash.length; i++) {
                chars[i * 2] = HEX[(hash[i] >> 4) & 0xF];
                chars[i * 2 + 1] = HEX[hash[i] & 0xF];
            }
            return new String(chars);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
