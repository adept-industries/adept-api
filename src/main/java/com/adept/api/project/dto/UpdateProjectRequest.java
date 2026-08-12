package com.adept.api.project.dto;

import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import io.swagger.v3.oas.annotations.media.Schema;

public class UpdateProjectRequest {

    private String name;
    private boolean namePresent;
    private String description;
    private boolean descriptionPresent;

    public UpdateProjectRequest() {
    }

    @JsonSetter(value = "name", nulls = Nulls.SET)
    public void setName(String name) {
        this.name = name;
        this.namePresent = true;
    }

    public String getName() {
        return name;
    }

    @Schema(hidden = true)
    public boolean isNamePresent() {
        return namePresent;
    }

    @JsonSetter(value = "description", nulls = Nulls.SET)
    public void setDescription(String description) {
        this.description = description;
        this.descriptionPresent = true;
    }

    public String getDescription() {
        return description;
    }

    @Schema(hidden = true)
    public boolean isDescriptionPresent() {
        return descriptionPresent;
    }

    public void validate() {
        if (!namePresent && !descriptionPresent) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "At least one project field is required.");
        }
        if (namePresent && (name == null || name.isBlank() || name.length() > 160)) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Project name must contain 1 to 160 characters.");
        }
        if (descriptionPresent && description != null && description.length() > 1000) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "Project description cannot exceed 1000 characters.");
        }
    }
}
