package com.risheesh.offlinepay.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import org.springframework.context.annotation.*;

@Configuration
public class TimeConfig {
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    com.fasterxml.jackson.databind.Module istTimestamps() {
        SimpleModule module = new SimpleModule("IST API timestamps");
        module.addSerializer(Instant.class, new JsonSerializer<Instant>() {
            @Override
            public void serialize(Instant value, JsonGenerator json, SerializerProvider provider) throws IOException {
                json.writeString(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.atZone(IST)));
            }
        });
        return module;
    }
}
