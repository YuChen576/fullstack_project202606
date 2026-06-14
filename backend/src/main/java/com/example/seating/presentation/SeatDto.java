package com.example.seating.presentation;

public record SeatDto(int floorNo, int seatNo, int floorSeatSeq, String occupiedBy, String empName) {
}
