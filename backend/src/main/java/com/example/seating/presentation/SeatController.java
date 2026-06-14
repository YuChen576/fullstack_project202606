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

/*
 * Controller 只處理 HTTP 介面：
 *   1. URL 對應
 *   2. request body 驗證
 *   3. 呼叫 Service
 *   4. 包成統一 ApiResponse
 *
 * 不在 Controller 寫 SQL、不寫交易邏輯，也不直接碰 Stored Procedure。
 */
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
    /*
     * @Valid 會啟動 AssignmentRequest / AssignmentChangeRequest 上的 Bean Validation。
     * 驗證失敗會丟 MethodArgumentNotValidException，再由 GlobalExceptionHandler 統一轉 400。
     */
    ApiResponse<SeatSnapshotResponse> assign(@Valid @RequestBody AssignmentRequest request) {
        return ApiResponse.ok(seatingService.assign(request));
    }
}
