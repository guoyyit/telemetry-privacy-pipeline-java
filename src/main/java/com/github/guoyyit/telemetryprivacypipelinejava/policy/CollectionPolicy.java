package com.github.guoyyit.telemetryprivacypipelinejava.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.github.guoyyit.telemetryprivacypipelinejava.model.DiscardReason;
import com.github.guoyyit.telemetryprivacypipelinejava.model.Transform;

/**
 * 采集与字段策略决定哪些事件/字段可进入存储，以及每个字段的脱敏变换。
 *
 * <p>策略对象不可变，判定方法为纯函数：相同输入始终得到相同结论，与调用顺序无关。</p>
 */
public final class CollectionPolicy {

    /** 允许的事件类型；为空表示允许全部类型。 */
    private final List<String> allowedEventTypes;

    /** 字段名 -&gt; 脱敏变换，使用有序 Map 以保证处理顺序确定。 */
    private final Map<String, Transform> fieldTransforms;

    /** 禁止进入存储的字段名集合（命中即整事件拒绝）。 */
    private final Set<String> deniedFields;

    public CollectionPolicy(List<String> allowedEventTypes, Map<String, Transform> fieldTransforms,
                            List<String> deniedFields) {
        this.allowedEventTypes = List.copyOf(allowedEventTypes);
        this.fieldTransforms = Collections.unmodifiableMap(new LinkedHashMap<>(fieldTransforms));
        this.deniedFields = Set.copyOf(deniedFields);
    }

    /**
     * 判定同意与事件类型是否满足采集策略。
     *
     * @return 拒绝原因；{@code null} 表示允许进入下一步判定
     */
    public DiscardReason evaluateConsentAndType(String eventType, boolean consent) {
        if (!consent) {
            return DiscardReason.NO_CONSENT;
        }
        if (!allowedEventTypes.isEmpty() && !allowedEventTypes.contains(eventType)) {
            return DiscardReason.EVENT_TYPE_NOT_ALLOWED;
        }
        return null;
    }

    /**
     * 查找命中拒绝规则的字段。
     *
     * @return 命中的字段名；{@code null} 表示未命中
     */
    public String findDeniedField(Map<String, String> fields) {
        if (fields == null) {
            return null;
        }
        for (String key : fields.keySet()) {
            if (deniedFields.contains(key)) {
                return key;
            }
        }
        return null;
    }

    /** 返回字段的脱敏变换，未配置的字段默认 KEEP。 */
    public Transform transformOf(String fieldName) {
        return fieldTransforms.getOrDefault(fieldName, Transform.KEEP);
    }

    public Map<String, Transform> fieldTransforms() {
        return fieldTransforms;
    }

    public Set<String> deniedFields() {
        return deniedFields;
    }

    public List<String> allowedEventTypes() {
        return allowedEventTypes;
    }
}
