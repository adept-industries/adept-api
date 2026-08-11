package com.adept.api.workspace;

import org.junit.jupiter.api.Test;

import com.adept.api.common.error.ApiException;
import com.adept.api.workspace.dto.UpdateWorkspaceRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpdateWorkspaceRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesNameOnly() throws Exception {
        String json = "{\"name\": \"Updated Name\"}";
        UpdateWorkspaceRequest request = objectMapper.readValue(json, UpdateWorkspaceRequest.class);

        assertThat(request.isNamePresent()).isTrue();
        assertThat(request.getName()).isEqualTo("Updated Name");
        assertThat(request.isTimezonePresent()).isFalse();
        assertThat(request.getTimezone()).isNull();

        request.validate();
    }

    @Test
    void deserializesTimezoneOnly() throws Exception {
        String json = "{\"timezone\": \"America/New_York\"}";
        UpdateWorkspaceRequest request = objectMapper.readValue(json, UpdateWorkspaceRequest.class);

        assertThat(request.isTimezonePresent()).isTrue();
        assertThat(request.getTimezone()).isEqualTo("America/New_York");
        assertThat(request.isNamePresent()).isFalse();

        request.validate();
    }

    @Test
    void deserializesBothFields() throws Exception {
        String json = "{\"name\": \"New Workspace\", \"timezone\": \"Europe/London\"}";
        UpdateWorkspaceRequest request = objectMapper.readValue(json, UpdateWorkspaceRequest.class);

        assertThat(request.isNamePresent()).isTrue();
        assertThat(request.getName()).isEqualTo("New Workspace");
        assertThat(request.isTimezonePresent()).isTrue();
        assertThat(request.getTimezone()).isEqualTo("Europe/London");

        request.validate();
    }

    @Test
    void rejectsEmptyPayload() throws Exception {
        String json = "{}";
        UpdateWorkspaceRequest request = objectMapper.readValue(json, UpdateWorkspaceRequest.class);

        assertThat(request.isNamePresent()).isFalse();
        assertThat(request.isTimezonePresent()).isFalse();

        assertThatThrownBy(request::validate)
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException) ex).safeDetail())
            .asString()
            .contains("At least one field");
    }

    @Test
    void rejectsExplicitNullName() throws Exception {
        String json = "{\"name\": null}";
        UpdateWorkspaceRequest request = objectMapper.readValue(json, UpdateWorkspaceRequest.class);

        assertThat(request.isNamePresent()).isTrue();
        assertThat(request.getName()).isNull();

        assertThatThrownBy(request::validate)
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException) ex).safeDetail())
            .asString()
            .contains("cannot be null");
    }

    @Test
    void rejectsBlankName() throws Exception {
        String json = "{\"name\": \"   \"}";
        UpdateWorkspaceRequest request = objectMapper.readValue(json, UpdateWorkspaceRequest.class);

        assertThatThrownBy(request::validate)
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException) ex).safeDetail())
            .asString()
            .contains("cannot be blank");
    }

    @Test
    void rejectsOverlengthName() throws Exception {
        String longName = "A".repeat(161);
        String json = "{\"name\": \"" + longName + "\"}";
        UpdateWorkspaceRequest request = objectMapper.readValue(json, UpdateWorkspaceRequest.class);

        assertThatThrownBy(request::validate)
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException) ex).safeDetail())
            .asString()
            .contains("cannot exceed 160 characters");
    }

    @Test
    void rejectsExplicitNullTimezone() throws Exception {
        String json = "{\"timezone\": null}";
        UpdateWorkspaceRequest request = objectMapper.readValue(json, UpdateWorkspaceRequest.class);

        assertThat(request.isTimezonePresent()).isTrue();
        assertThat(request.getTimezone()).isNull();

        assertThatThrownBy(request::validate)
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException) ex).safeDetail())
            .asString()
            .contains("cannot be null");
    }

    @Test
    void rejectsInvalidTimezone() throws Exception {
        String json = "{\"timezone\": \"Invalid/Zone_Name_123\"}";
        UpdateWorkspaceRequest request = objectMapper.readValue(json, UpdateWorkspaceRequest.class);

        assertThatThrownBy(request::validate)
            .isInstanceOf(ApiException.class)
            .extracting(ex -> ((ApiException) ex).safeDetail())
            .asString()
            .contains("invalid");
    }
}
