package com.github.guoyyit.telemetryprivacypipelinejava.export;

import java.util.List;
import com.github.guoyyit.telemetryprivacypipelinejava.model.AggregateRow;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent;

/**
 * 导出/查询门面：读取快照 -> 鉴权投影 -> 明细或聚合导出。
 */
public interface ExportService {

    List<StoredEvent> queryEvents(Caller caller, String eventType, boolean rawRequested);

    List<AggregateRow> aggregate(Caller caller, String field, boolean rawRequested);
}
