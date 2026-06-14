package com.example.seating.presentation;

import java.util.List;

/*
 * GET /api/seats 與 PUT 成功後共用的 response data。
 *
 * snapshotVersion 是目前座位狀態的版本，seats 是完整座位盤。
 * 發生 409 時也可以回最新座位盤，讓前端重繪。
 */
public record SeatSnapshotResponse(String snapshotVersion, List<SeatDto> seats) {
}
