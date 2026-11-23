package org.phoebus.olog.es.api.model;

import org.junit.jupiter.api.Test;
import org.phoebus.logbook.LogTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LogTemplateDateTest {

    @Test
    public void templateDatesRoundTripAsEpochMillis() throws Exception {
        long millis = 1763934090000L; // 2025-11-23T21:41:30Z
        String json = "{\"id\":\"qZRhCKIBgo5ShqzPO9WT\",\"name\":\"test\",\"owner\":\"me\","
                + "\"createdDate\":" + millis + ",\"title\":\"Test template\",\"level\":\"Info\","
                + "\"logbooks\":[],\"tags\":[],\"properties\":[]}";

        LogTemplate template = OlogObjectMappers.logEntryDeserializer.readValue(json, LogTemplate.class);
        assertEquals(Instant.ofEpochMilli(millis), template.createdDate());

        String out = OlogObjectMappers.logEntrySerializer.writeValueAsString(template);
        assertTrue(out.contains("\"createdDate\":" + millis), out);
    }
}
