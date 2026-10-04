package com.github.guoyyit.telemetryprivacypipelinejava.policy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import com.github.guoyyit.telemetryprivacypipelinejava.config.PipelineProperties;
import com.github.guoyyit.telemetryprivacypipelinejava.model.Transform;

/**
 * 依据配置构造不可变的 {@link CollectionPolicy}。
 *
 * <p>配置中无法识别的变换名将快速失败（IllegalArgumentException），避免策略被静默放宽。</p>
 */
@Component
public class PolicyFactory {

    public CollectionPolicy create(PipelineProperties properties) {
        Map<String, Transform> transforms = new LinkedHashMap<>();
        for (Map.Entry<String, String> rule : properties.getFieldRules().entrySet()) {
            Transform transform;
            try {
                transform = Transform.valueOf(rule.getValue().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "未知的字段脱敏变换: field=" + rule.getKey() + ", transform=" + rule.getValue());
            }
            transforms.put(rule.getKey(), transform);
        }
        return new CollectionPolicy(
                properties.getAllowedEventTypes(),
                transforms,
                properties.getDeniedFields());
    }
}
