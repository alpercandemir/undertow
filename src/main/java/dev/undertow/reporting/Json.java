package dev.undertow.reporting;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Json {
    public static final ObjectMapper MAPPER =
            JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build();
    public static final ObjectMapper YAML =
            new ObjectMapper(
                            YAMLFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .build())
                    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    static {
        MAPPER.getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder()
                                .maxNestingDepth(40)
                                .maxStringLength(200000)
                                .maxNumberLength(30)
                                .build());
    }

    private Json() {}

    public static JsonNode parse(String value) throws IOException {
        return MAPPER.readTree(value);
    }

    public static String stringify(Object value) throws IOException {
        return MAPPER.writeValueAsString(value);
    }

    public static void write(Path path, Object value) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(
                path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }

    public static String read(Path path, int limit) throws IOException {
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException("Input exceeds size limit");
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    public static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            value.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
