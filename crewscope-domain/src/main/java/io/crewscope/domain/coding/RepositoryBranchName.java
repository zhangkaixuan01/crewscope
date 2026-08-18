package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Validated short Git branch name used as a RepositoryBinding default. */
public record RepositoryBranchName(String value) {

    public static final int MAX_LENGTH = 255;

    public RepositoryBranchName {
        if (!isValid(value)) {
            throw new DomainValidationException(
                    "repositoryBinding.defaultBranch", "must be a safe short Git branch name");
        }
    }

    private static boolean isValid(String value) {
        if (value == null
                || value.isEmpty()
                || value.length() > MAX_LENGTH
                || value.equals("@")
                || value.startsWith("-")
                || value.startsWith("/")
                || value.endsWith("/")
                || value.endsWith(".")
                || value.startsWith("refs/")
                || value.contains("//")
                || value.contains("..")
                || value.contains("@{")) {
            return false;
        }
        for (String component : value.split("/", -1)) {
            if (component.isEmpty()
                    || component.startsWith(".")
                    || component.endsWith(".lock")) {
                return false;
            }
        }
        return value.chars().noneMatch(RepositoryBranchName::isForbiddenCharacter);
    }

    private static boolean isForbiddenCharacter(int character) {
        return character <= 0x20
                || character == 0x7f
                || "~^:?*[\\".indexOf(character) >= 0;
    }
}
