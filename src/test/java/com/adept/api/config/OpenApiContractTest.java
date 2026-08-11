package com.adept.api.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.adept.api.auth.PartCIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
    "springdoc.api-docs.enabled=true",
    "springdoc.swagger-ui.enabled=true"
})
class OpenApiContractTest extends PartCIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .configure(SerializationFeature.INDENT_OUTPUT, true);

    @Test
    void liveOpenApiSpecMatchesExportedContract() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn();

        String rawLiveJson = result.getResponse().getContentAsString();
        assertThat(rawLiveJson).isNotEmpty();

        JsonNode liveJsonNode = mapper.readTree(rawLiveJson);
        String liveCanonicalJson = mapper.writeValueAsString(liveJsonNode) + "\n";

        Path fileSpecPath = Paths.get("docs/openapi/adept-api-v1.json").toAbsolutePath();
        assertThat(Files.exists(fileSpecPath))
            .withFailMessage("docs/openapi/adept-api-v1.json must exist")
            .isTrue();

        String fileJsonContent = Files.readString(fileSpecPath);
        JsonNode fileJsonNode = mapper.readTree(fileJsonContent);
        String fileCanonicalJson = mapper.writeValueAsString(fileJsonNode) + "\n";

        assertThat(liveCanonicalJson).isEqualTo(fileCanonicalJson);
    }

    @Test
    void schemasExcludeJpaEntitiesAndPasswordHash() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn();

        String jsonContent = result.getResponse().getContentAsString();
        JsonNode root = mapper.readTree(jsonContent);
        JsonNode schemasNode = root.path("components").path("schemas");

        assertThat(schemasNode.isMissingNode()).isFalse();

        // Assert no raw JPA entities exist in schemas
        assertThat(schemasNode.has("User")).isFalse();
        assertThat(schemasNode.has("Workspace")).isFalse();
        assertThat(schemasNode.has("Membership")).isFalse();
        assertThat(schemasNode.has("GitRepository")).isFalse();
        assertThat(schemasNode.has("JiraIntegration")).isFalse();
        assertThat(schemasNode.has("GithubIntegration")).isFalse();
        assertThat(schemasNode.has("ProcessingJob")).isFalse();

        // Assert no schema contains passwordHash field
        schemasNode.fieldNames().forEachRemaining(schemaName -> {
            JsonNode properties = schemasNode.get(schemaName).path("properties");
            assertThat(properties.has("passwordHash"))
                .withFailMessage("Schema %s contains internal passwordHash field", schemaName)
                .isFalse();
        });
    }
}
