package com.github.guoyyit.telemetryprivacypipelinejava.tests;

import com.github.guoyyit.telemetry.export.ExportService;
import com.github.guoyyit.telemetry.ingest.IngestionService;
import com.github.guoyyit.telemetry.store.EventStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Context smoke test: the whole privacy pipeline wires up with its config. */
@SpringBootTest(properties = {
        "privacy.wal.dir=target/test-wal-context",
        "privacy.policy.allowed-event-types=page_view,error",
        "privacy.policy.denied-fields=ssn",
        "privacy.redaction.rules=email:HASH,deviceId:MASK,internalNote:DROP",
        "privacy.redaction.salt=context-test-salt",
        "privacy.retention-period=PT720H"
})
public class ApplicationTests {
    @Autowired
    IngestionService ingestionService;
    @Autowired
    ExportService exportService;
    @Autowired
    EventStore eventStore;

    @Test
    public void contextLoad() {
        assertNotNull(ingestionService);
        assertNotNull(exportService);
        assertNotNull(eventStore);
    }
}
