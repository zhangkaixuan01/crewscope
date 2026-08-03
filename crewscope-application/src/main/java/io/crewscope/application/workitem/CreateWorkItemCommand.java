package io.crewscope.application.workitem;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateWorkItemCommand(
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*") String key,
        @NotBlank String title) {}
