package com.example.seating.presentation;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;

/*
 * Java record 是 Java 16+ 的語法，可以把它想成「只放資料的不可變 DTO」。
 * 對 JDK 8 + POJO 背景來說，它大約等同於：
 *   private final String empId;
 *   private final Integer toSeatSeq;
 *   constructor + getter + equals/hashCode/toString
 *
 * 這裡不是 Hibernate Entity，也不會對應 table。它只描述 PUT request 中
 * changes 陣列的一筆異動。
 */
public record AssignmentChangeRequest(
        /*
         * @NotBlank 負責拒絕 null / "" / 空白字串。
         * @Pattern 負責拒絕非 5 碼數字。JDK 8 常見做法可能是在 Service 手寫 if，
         * Spring Boot 這裡用 Bean Validation 在進 Controller method 前先擋掉。
         */
        @NotBlank @Pattern(regexp = "^\\d{5}$") String empId,
        /*
         * null 代表「清除座位」。不是 Integer 0，也不是另外開 DELETE API。
         */
        Integer toSeatSeq
) {
}
