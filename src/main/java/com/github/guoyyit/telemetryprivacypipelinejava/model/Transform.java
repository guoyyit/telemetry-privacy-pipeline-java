package com.github.guoyyit.telemetryprivacypipelinejava.model;

/**
 * 字段级脱敏变换：保留 / 掩码 / 摘要 / 删除。
 */
public enum Transform {
    KEEP,
    MASK,
    HASH,
    DROP
}
