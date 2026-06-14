package com.example.seating.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/*
 * 批次送出的 request DTO。
 *
 * 與傳統 form backing bean 類似，但這裡只負責 HTTP JSON payload，
 * 不承擔 ORM mapping。
 */
public record AssignmentRequest(
        /*
         * snapshotVersion 用來做樂觀比對。
         * 前端 GET /api/seats 時拿到版本，PUT 時帶回來；若 DB 狀態已被別人改過，
         * Stored Procedure 會回 CONFLICT_STALE_SNAPSHOT。
         */
        @NotBlank String snapshotVersion,
        /*
         * @NotEmpty 擋掉空批次。
         * List<@Valid AssignmentChangeRequest> 代表 list 裡每一筆 change 也要驗證。
         */
        @NotEmpty List<@Valid AssignmentChangeRequest> changes
) {
}
