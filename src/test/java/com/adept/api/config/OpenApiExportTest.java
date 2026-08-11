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
class OpenApiExportTest extends PartCIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exportOpenApiSpec() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn();

        String rawJson = result.getResponse().getContentAsString();
        assertThat(rawJson).isNotEmpty();

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);

        JsonNode jsonNode = mapper.readTree(rawJson);
        String prettyJson = mapper.writeValueAsString(jsonNode);

        Path docsPath = Paths.get("docs/openapi/adept-api-v1.json").toAbsolutePath();
        Files.createDirectories(docsPath.getParent());
        Files.writeString(docsPath, prettyJson + "\n");

        assertThat(Files.exists(docsPath)).isTrue();
    }
}
