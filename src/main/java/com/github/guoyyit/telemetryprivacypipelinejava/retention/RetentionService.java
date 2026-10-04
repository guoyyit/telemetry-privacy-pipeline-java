package com.github.guoyyit.telemetryprivacypipelinejava.retention;

import java.util.Set;

/**
 * 保留期与删除：清除过期数据；处理用户撤回同意的删除请求；防止已删除内容被并发写入复活。
 */
public interface RetentionService {

    /** 删除指定用户的全部数据（撤回同意）。 */
    int deleteUsers(Set<String> userIds, String reason);

    /** 清除超过保留期的数据。返回清除事件数。 */
    int purgeExpired();
}
