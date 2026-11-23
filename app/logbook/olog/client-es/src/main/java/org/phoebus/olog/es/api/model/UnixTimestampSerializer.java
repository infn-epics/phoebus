package org.phoebus.olog.es.api.model;

import java.time.Instant;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Writes an {@link Instant} as epoch milliseconds, the format the Olog service
 * expects for template dates (counterpart of {@link UnixTimestampDeserializer}).
 */
class UnixTimestampSerializer extends StdSerializer<Instant> {

    protected UnixTimestampSerializer() {
        super(Instant.class);
    }

    @Override
    public void serialize(Instant value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
        gen.writeNumber(value.toEpochMilli());
    }
}
