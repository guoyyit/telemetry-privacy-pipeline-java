package com.github.guoyyit.telemetryprivacypipelinejava.model;

/**
 * 事件被拒绝进入存储的可区分原因。
 */
public enum DiscardReason {
    /** 用户未给出采集同意。 */
    NO_CONSENT,
    /** 事件类型不在采集策略允许范围内。 */
    EVENT_TYPE_NOT_ALLOWED,
    /** 命中字段级拒绝规则（禁止上报的字段）。 */
    DENY_RULE,
    /** 用户已撤回同意或处于删除状态，数据不得（重新）进入存储。 */
    CONSENT_WITHDRAWN,
    /** 缺少事件标识或用户标识等必要字段。 */
    INVALID_EVENT
}
