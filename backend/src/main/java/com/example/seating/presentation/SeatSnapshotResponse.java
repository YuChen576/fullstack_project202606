package com.example.seating.presentation;

import java.util.List;

public record SeatSnapshotResponse(String snapshotVersion, List<SeatDto> seats) {
}
