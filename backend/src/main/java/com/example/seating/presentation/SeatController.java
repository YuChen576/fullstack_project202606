package com.example.seating.presentation;

import com.example.seating.application.SeatingService;
import com.example.seating.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SeatController {
    private final SeatingService seatingService;

    public SeatController(SeatingService seatingService) {
        this.seatingService = seatingService;
    }

    @GetMapping("/seats")
    ApiResponse<SeatSnapshotResponse> seats() {
        return ApiResponse.ok(seatingService.getSeats());
    }

    @GetMapping("/employees")
    ApiResponse<List<EmployeeDto>> employees() {
        return ApiResponse.ok(seatingService.getEmployees());
    }

    @PutMapping("/seats/assignments")
    ApiResponse<SeatSnapshotResponse> assign(@Valid @RequestBody AssignmentRequest request) {
        return ApiResponse.ok(seatingService.assign(request));
    }
}
