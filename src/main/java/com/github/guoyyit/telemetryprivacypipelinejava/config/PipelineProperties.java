package com.github.guoyyit.telemetryprivacypipelinejava.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管道配置项，前缀 pipeline。
 */
@ConfigurationProperties(prefix = "pipeline")
public class PipelineProperties {

    /** 持久化日志与审计文件目录。 */
    private String dataDir = "data";

    /** 默认保留期（天）；超过保留期的事件被清除。 */
    private int retentionDays = 90;

    /** 聚合导出最小分组规模；小于该值的分组被抑制或合并。 */
    private int minGroupSize = 5;

    /** 聚合时被抑制的分组是否确定性合并为 __other__。 */
    private boolean mergeSuppressedGroups = true;

    /** 默认采集的事件类型（为空表示允许全部）。 */
    private java.util.List<String> allowedEventTypes = new java.util.ArrayList<>();

    /** 字段名 -> 脱敏变换（KEEP/MASK/HASH/DROP），顺序即处理顺序的确定依据之一。 */
    private Map<String, String> fieldRules = new LinkedHashMap<>();

    /** 无论脱敏配置如何都禁止进入存储的字段名（命中即整事件拒绝）。 */
    private java.util.List<String> deniedFields = new java.util.ArrayList<>();

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }

    public int getMinGroupSize() { return minGroupSize; }
    public void setMinGroupSize(int minGroupSize) { this.minGroupSize = minGroupSize; }

    public boolean isMergeSuppressedGroups() { return mergeSuppressedGroups; }
    public void setMergeSuppressedGroups(boolean mergeSuppressedGroups) {
        this.mergeSuppressedGroups = mergeSuppressedGroups;
    }

    public java.util.List<String> getAllowedEventTypes() { return allowedEventTypes; }
    public void setAllowedEventTypes(java.util.List<String> allowedEventTypes) {
        this.allowedEventTypes = allowedEventTypes;
    }

    public Map<String, String> getFieldRules() { return fieldRules; }
    public void setFieldRules(Map<String, String> fieldRules) { this.fieldRules = fieldRules; }

    public java.util.List<String> getDeniedFields() { return deniedFields; }
    public void setDeniedFields(java.util.List<String> deniedFields) { this.deniedFields = deniedFields; }
}
