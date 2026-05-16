package io.github.jrdesai.checkpoint_ai.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.github.jrdesai.checkpoint_ai.persistence.PayloadRecord;
import io.github.jrdesai.checkpoint_ai.persistence.PayloadRepository;
import io.temporal.api.common.v1.Payload;
import io.temporal.payload.codec.PayloadCodec;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DatabasePayloadCodec implements PayloadCodec {
    private final PayloadRepository payloadRepository;
    private static final int SIZE_THRESHOLD_BYTES = 10_000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private record PayloadReference(String ref) {}

    @Override
    public @NonNull List<Payload> encode(@NonNull List<Payload> payloads) {
        return payloads.stream()
                .map(payload -> {
                    if (payload.getSerializedSize() > SIZE_THRESHOLD_BYTES) {
                        return storeAndReplace(payload);
                    }
                    return payload;
                })
                .collect(Collectors.toList());
    }

    private Payload storeAndReplace(Payload payload) {
        byte[] bytes = payload.toByteArray();
        String encodedString = Base64.getEncoder().encodeToString(bytes);
        PayloadRecord record = payloadRepository.save(new PayloadRecord(encodedString, Instant.now()));
        UUID id = record.getId();
        return Payload.newBuilder()
                .putMetadata("encoding", ByteString.copyFromUtf8("db/reference"))
                .setData(ByteString.copyFromUtf8("{\"ref\":\"" + id + "\"}"))
                .build();
    }

    @Override
    public @NonNull List<Payload> decode(@NonNull List<Payload> payloads) {
        return payloads.stream()
                .map(payload -> {
                    if (payload.containsMetadata("encoding") &&
                            payload.getMetadataOrThrow("encoding").equals(ByteString.copyFromUtf8("db/reference"))) {
                        String json = payload.getData().toStringUtf8();
                        try {
                            PayloadReference reference = objectMapper.readValue(json, PayloadReference.class);
                            UUID id = UUID.fromString(reference.ref());
                            Optional<PayloadRecord> record = payloadRepository.findById(id);
                            if (record.isPresent()) {
                                byte[] bytes = Base64.getDecoder().decode(record.get().getContent());
                                return Payload.parseFrom(bytes);
                            }
                        } catch (JsonProcessingException | InvalidProtocolBufferException e) {
                            throw new RuntimeException(e);
                        }


                    }
                    return payload;
                })
                .collect(Collectors.toList());
    }
}
