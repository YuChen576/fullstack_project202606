# JDK 8 / JPA / Hibernate XML 工程師導讀

這份專案刻意沒有使用 JPA Entity、Hibernate XML mapping、DAO HQL 或 Criteria API，原因是題目要求所有資料庫存取都必須經由 Stored Procedure。可以把本專案理解成：

```text
Controller -> Service -> Repository(SimpleJdbcCall) -> PostgreSQL Stored Procedure
```

對照傳統 JDK 8 + JPA/Hibernate 專案：

| 傳統 JPA/Hibernate | 本專案寫法 |
| --- | --- |
| POJO Entity + hbm.xml / annotation | 不建立 Entity，避免繞過 SP 直接操作 table |
| DAO / Repository 使用 Session 或 EntityManager | Repository 只包裝 `SimpleJdbcCall` |
| HQL / Criteria / JPQL 查詢 | 查詢也走 `sp_get_seats()`、`sp_get_employees()` |
| Hibernate transaction flush | PostgreSQL Stored Procedure 內完成主要交易規則 |
| Entity lazy loading | API DTO 一次組好需要的資料 |
| optimistic lock version 欄位 | 使用 `seat_change_log.max(log_id)` 當 `snapshotVersion` |

## 1. Java 版本差異

本專案使用 Spring Boot 3，因此最低需要 Java 17。你會看到 `record`，這不是 JDK 8 語法。

例如：

```java
public record SeatDto(int floorNo, int seatNo, int floorSeatSeq, String occupiedBy, String empName) {
}
```

可以把它視為 JDK 8 中的不可變 DTO：

```java
public final class SeatDto {
    private final int floorNo;
    private final int seatNo;
    // constructor + getter + equals + hashCode + toString
}
```

`record` 會自動產生 constructor、getter-like accessor、`equals`、`hashCode`、`toString`。Accessor 名稱是 `floorNo()`，不是 JavaBean 風格的 `getFloorNo()`。

## 2. DTO 與 Entity 的差異

`presentation` package 裡的 class 都是 API DTO，不是資料庫 Entity。

- DTO 對外描述 request / response 格式。
- Entity 通常對應 table，但本專案不建立 Entity。
- 因為題目要求所有 DB 存取走 Stored Procedure，所以 Java 端不應有 `@Entity`、`EntityManager`、Hibernate mapping XML。

## 3. Bean Validation

`@NotBlank`、`@Pattern`、`@NotEmpty` 會在 Controller 收到 request body 後自動驗證。

```java
ApiResponse<SeatSnapshotResponse> assign(@Valid @RequestBody AssignmentRequest request)
```

`@Valid` 類似在進 Service 前先做格式檢查。驗證失敗會被 `GlobalExceptionHandler` 統一轉成：

```json
{ "code": "VALIDATION_ERROR", "message": "Request validation failed", "data": null }
```

## 4. Repository 不是 JPA Repository

`SeatingRepository` 沒有繼承 `JpaRepository`。它只做三件事：

1. 準備 Stored Procedure 呼叫。
2. 傳入參數。
3. 將 SP 回傳的 JSON 轉成 DTO。

這是本專案最重要的差異。商業資料一致性不是靠 Hibernate dirty checking，而是靠 PostgreSQL constraint、row lock、Stored Procedure transaction。

## 5. Stored Procedure 回傳 JSON

`sp_get_seats()` 回傳的是 JSON 字串，Java 端用 Jackson 轉成 `SeatSnapshotResponse`。

這樣做的好處是：

- Java 不需要寫複雜 `RowMapper`。
- 查詢形狀集中在 DB/SP。
- API response 欄位可以穩定對齊前端需要。

代價是：

- SP 的 JSON key 必須和 Java DTO 欄位名稱一致。
- SQL 寫錯時會在 runtime 才發現，因此需要測試補強。

## 6. 交易與一致性

傳統 Hibernate 專案常見流程是：

```text
load entity -> 修改欄位 -> transaction commit -> Hibernate flush
```

本專案的 PUT 流程是：

```text
Controller 收 request
Service 做批次格式與語意檢查
Repository 呼叫 sp_assign_seats
SP 在 DB 內鎖資料、檢查衝突、更新 employee、寫 log
```

關鍵一致性保護在 DB：

- `employee.floor_seat_seq UNIQUE`：一個座位最多一人。
- `CHECK (emp_id ~ '^[0-9]{5}$')`：員編固定 5 碼。
- `FOR UPDATE`：交易內鎖住相關資料，避免併發覆寫。
- `seat_change_log`：每次異動留下紀錄，也提供 snapshot version。

## 7. Docker Compose 的角色

本機目前 Java 是 1.8，因此專案用 Docker 提供 Java 17 建置與執行環境：

- `backend/Dockerfile`：用 Gradle + JDK 17 打包 Spring Boot jar，再用 JRE 17 執行。
- `nginx/Dockerfile`：用 Node 20 build Vue，再交給 Nginx 提供靜態檔。
- `docker-compose.yml`：一次啟動 PostgreSQL、Spring Boot、Nginx。

啟動：

```bash
docker compose up -d --build
```

測試：

```bash
./gradlew test
```

這個 `./gradlew` 不是標準 Gradle Wrapper，而是一個 Docker 包裝腳本，目的是讓 Java 8 主機也能跑 Java 17 Gradle build。

## 8. 建議閱讀順序

1. `DB/001_init.sql`：先看 table、constraint、SP，理解資料規則。
2. `SeatingRepository.java`：看 Java 如何呼叫 SP。
3. `SeatingService.java`：看 Service 做哪些進 DB 前的檢查。
4. `SeatController.java`：看 REST API 對外介面。
5. `frontend/src/App.vue`：看前端如何暫存座位異動與送出。
