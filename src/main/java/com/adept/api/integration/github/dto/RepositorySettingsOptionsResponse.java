package com.adept.api.integration.github.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record RepositorySettingsOptionsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SettingsOptions branches,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SettingsOptions workflows,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SettingsOptions environments) {

    public record SettingsOptions(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> values,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean complete,
            @Schema(description = "Safe explanation when discovery is unavailable or partial") String warning) {
        public SettingsOptions {
            values = List.copyOf(values);
        }

        public static SettingsOptions unavailable(String warning) {
            return new SettingsOptions(List.of(), false, warning);
        }
    }

    public static RepositorySettingsOptionsResponse unavailable(String warning) {
        SettingsOptions options = SettingsOptions.unavailable(warning);
        return new RepositorySettingsOptionsResponse(options, options, options);
    }

    public boolean complete() {
        return branches.complete() && workflows.complete() && environments.complete();
    }
}
