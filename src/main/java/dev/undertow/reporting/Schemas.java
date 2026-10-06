package dev.undertow.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;

public final class Schemas {
    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private Schemas() {}

    public static JsonNode resource(String name) throws IOException {
        try (var stream = Schemas.class.getResourceAsStream("/schemas/" + name + ".json")) {
            if (stream == null) {
                throw new IOException("Missing bundled schema");
            }
            return Json.MAPPER.readTree(stream);
        }
    }

    public static void validate(JsonNode schema, JsonNode value) {
        var errors = FACTORY.getSchema(schema).validate(value);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Schema validation: "
                            + errors.stream().limit(8).map(ValidationMessage::getMessage).toList());
        }
    }
}
