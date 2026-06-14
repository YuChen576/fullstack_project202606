package com.example.seating.presentation;

/*
 * API 回傳的一個座位格資料。
 *
 * 這不是 Entity。傳統 Hibernate 可能會有 SeatingChart POJO + Employee POJO，
 * 再透過 lazy/eager loading 組資料；本專案直接讓 SP 組出前端需要的扁平 DTO。
 */
public record SeatDto(int floorNo, int seatNo, int floorSeatSeq, String occupiedBy, String empName) {
}
