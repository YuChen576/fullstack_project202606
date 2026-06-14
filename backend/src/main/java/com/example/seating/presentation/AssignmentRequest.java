package com.example.seating.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record AssignmentRequest(
        @NotBlank String snapshotVersion,
        @NotEmpty List<@Valid AssignmentChangeRequest> changes
) {
}
