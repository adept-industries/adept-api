package com.adept.api.workspace;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class WorkspaceSlugService {

    private static final int MAX_SLUG_LENGTH = 80;
    private static final int MAX_BASE_LENGTH = 71;

    private final WorkspaceRepository workspaceRepository;

    public WorkspaceSlugService(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    public String generate(String workspaceName) {
        String base = base(workspaceName);
        for (int attempt = 0; attempt < 20; attempt++) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String candidate = trimToFit(base) + "-" + suffix;
            if (!workspaceRepository.existsBySlug(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("workspace slug could not be generated");
    }

    private static String base(String workspaceName) {
        String cleaned = workspaceName == null ? "" : workspaceName.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+", "")
            .replaceAll("-+$", "");
        if (cleaned.isBlank()) {
            cleaned = "workspace";
        }
        if (cleaned.length() > MAX_BASE_LENGTH) {
            cleaned = cleaned.substring(0, MAX_BASE_LENGTH).replaceAll("-+$", "");
        }
        return cleaned.isBlank() ? "workspace" : cleaned;
    }

    private static String trimToFit(String base) {
        int available = MAX_SLUG_LENGTH - 9;
        if (base.length() <= available) {
            return base;
        }
        String trimmed = base.substring(0, available).replaceAll("-+$", "");
        return trimmed.isBlank() ? "workspace" : trimmed;
    }
}
