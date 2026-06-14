package com.example.seating.presentation;

/*
 * 下拉選單使用的員工 DTO。
 * currentSeatSeq 可為 null，代表這位員工目前沒有座位。
 */
public record EmployeeDto(String empId, String name, Integer currentSeatSeq) {
}
