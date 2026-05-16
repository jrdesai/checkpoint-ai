package io.github.jrdesai.checkpoint_ai.config;

import io.github.jrdesai.checkpoint_ai.codec.DatabasePayloadCodec;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class TemporalConfig {

    @Bean
    public DataConverter dataConverter(DatabasePayloadCodec codec) {
        return new CodecDataConverter(
                DefaultDataConverter.newDefaultInstance(),
                List.of(codec)
        );
    }
}
