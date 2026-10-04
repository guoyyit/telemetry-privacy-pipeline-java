package com.github.guoyyit.telemetryprivacypipelinejava.export;

import com.github.guoyyit.telemetryprivacypipelinejava.model.AggregateRow;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * k-匿名式聚合导出。
 *
 * <p>规则（全部确定）：</p>
 * <ul>
 *   <li>按指定字段取值分组计数；取值域中计数小于最小分组规模的分组必须被抑制；</li>
 *   <li>若启用合并，所有被抑制的分组确定性地合并为单一 {@code __other__} 行，
 *       合并行自身低于最小分组规模时也一并抑制（不泄露小样本）；</li>
 *   <li>输出按组键字典序排列，{@code __other__} 固定排在末尾；同一输入永远得到同一结果。</li>
 * </ul>
 */
@Component
public class Aggregator {

    public static final String OTHER_KEY = "__other__";

    public List<AggregateRow> aggregate(List<StoredEvent> events, String field, int minGroupSize,
                                        boolean mergeSuppressed) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("聚合字段不能为空");
        }
        if (minGroupSize < 1) {
            throw new IllegalArgumentException("最小分组规模必须 >= 1");
        }

        // TreeMap 计数：分组与输出顺序由组键字典序确定。
        Map<String, Long> counts = new TreeMap<>();
        for (StoredEvent event : events) {
            // 优先使用脱敏视图；投影层已按授权决定 rawFields 是否可作为来源。
            String value = event.redactedFields().get(field);
            if (value == null) {
                // 原始值仅在授权投影中才会与脱敏值不同，因此这里只作为字段存在性回退。
                value = event.rawFields().get(field);
            }
            if (value == null) {
                // 缺失字段的事件不进入聚合，避免形成可识别的“缺失”小分组。
                continue;
            }
            counts.merge(value, 1L, Long::sum);
        }

        Map<String, Long> released = new LinkedHashMap<>();
        long otherCount = 0L;
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (entry.getValue() >= minGroupSize) {
                released.put(entry.getKey(), entry.getValue());
            } else if (mergeSuppressed) {
                otherCount += entry.getValue();
            }
            // 不合并则直接抑制，不出现在结果中。
        }

        List<AggregateRow> rows = new java.util.ArrayList<>();
        released.forEach((key, count) -> rows.add(new AggregateRow(key, count, false)));
        // 合并行也必须满足最小分组规模，否则继续抑制。
        if (mergeSuppressed && otherCount >= minGroupSize) {
            rows.add(new AggregateRow(OTHER_KEY, otherCount, true));
        }
        return List.copyOf(rows);
    }
}
