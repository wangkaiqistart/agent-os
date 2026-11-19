package com.agentos.tool.registry;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ToolDefinitionValidatorTest {
    @Test
    void rejectsUnsafeAndMalformedSchemasBeforeOfficialValidation() {
        var validator = new ToolDefinitionValidator();
        for (String schema :
                List.of(
                        "{}",
                        "[]",
                        "true",
                        "{\"type\":\"array\"}",
                        "{\"type\":\"object\",\"$schema\":\"http://example.invalid/schema\"}",
                        "{\"type\":\"object\",\"properties\":{\"x\":{\"$ref\":\"https://example.invalid/x\"}}}",
                        "{\"type\":\"object\",\"$dynamicRef\":\"other.json#x\"}",
                        "{\"type\":\"object\",\"$id\":\"https://example.invalid/x\",\"$ref\":\"#\"}",
                        "{\"type\":\"object\",\"required\":123}",
                        "{\"type\":\"object\",\"description\":\"" + "x".repeat(65536) + "\"}",
                        "{\"type\":\"object\",\"x\":"
                                + "[".repeat(65)
                                + "0"
                                + "]".repeat(65)
                                + "}")) {
            assertThatThrownBy(() -> validator.validateSchema(schema))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("example.invalid");
        }
    }

    @Test
    void officialValidatorAcceptsLocalReferencesAndChecksInput() {
        var validator = new ToolDefinitionValidator();
        String schema =
                """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                 "$defs":{"positive":{"type":"integer","minimum":1}},
                 "properties":{"x":{"$ref":"#/$defs/positive"}},"required":["x"]}
                """;
        validator.validateSchema(schema);
        validator.validateInput(schema, "{\"x\":1}");
        assertThatThrownBy(() -> validator.validateInput(schema, "{\"x\":0}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateInput(schema, "{} trailing"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
