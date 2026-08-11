package com.adept.api.workspace.dto;

import java.time.DateTimeException;
import java.time.ZoneId;

import com.adept.api.common.error.ApiException;
import com.adept.api.common.error.ProblemCode;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

public class UpdateWorkspaceRequest {

    private String name;
    private boolean namePresent;

    private String timezone;
    private boolean timezonePresent;

    public UpdateWorkspaceRequest() {
    }

    public UpdateWorkspaceRequest(String name, String timezone) {
        if (name != null) {
            this.name = name;
            this.namePresent = true;
        }
        if (timezone != null) {
            this.timezone = timezone;
            this.timezonePresent = true;
        }
    }

    @JsonSetter(value = "name", nulls = Nulls.SET)
    public void setName(String name) {
        this.name = name;
        this.namePresent = true;
    }

    public String getName() {
        return name;
    }

    public boolean isNamePresent() {
        return namePresent;
    }

    @JsonSetter(value = "timezone", nulls = Nulls.SET)
    public void setTimezone(String timezone) {
        this.timezone = timezone;
        this.timezonePresent = true;
    }

    public String getTimezone() {
        return timezone;
    }

    public boolean isTimezonePresent() {
        return timezonePresent;
    }

    public void validate() {
        if (!namePresent && !timezonePresent) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED, "At least one field (name or timezone) must be provided.");
        }

        if (namePresent) {
            if (name == null) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Workspace name cannot be null.");
            }
            if (name.isBlank()) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Workspace name cannot be blank.");
            }
            if (name.length() > 160) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Workspace name cannot exceed 160 characters.");
            }
        }

        if (timezonePresent) {
            if (timezone == null) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Workspace timezone cannot be null.");
            }
            if (timezone.isBlank() || timezone.length() > 64) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Workspace timezone is invalid.");
            }
            try {
                ZoneId.of(timezone);
            } catch (DateTimeException exception) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "Workspace timezone is invalid.");
            }
        }
    }
}
