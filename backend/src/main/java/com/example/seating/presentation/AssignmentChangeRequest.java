package com.example.seating.presentation;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;

public record AssignmentChangeRequest(
        @NotBlank @Pattern(regexp = "^\\d{5}$") String empId,
        Integer toSeatSeq
) {
}
