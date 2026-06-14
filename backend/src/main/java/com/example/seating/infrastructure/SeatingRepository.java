package com.example.seating.infrastructure;

import com.example.seating.common.BusinessException;
import com.example.seating.common.ErrorCode;
import com.example.seating.presentation.AssignmentChangeRequest;
import com.example.seating.presentation.EmployeeDto;
import com.example.seating.presentation.SeatSnapshotResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.stereotype.Repository;

import static java.sql.Types.OTHER;
import static java.sql.Types.VARCHAR;

/*
 * Repository 是唯一接觸資料庫的 Java class。
 *
 * 注意：這不是 Spring Data JpaRepository，也沒有 EntityManager / Session。
 * 題目要求「所有資料庫存取一律透過 Stored Procedure」，所以這裡只使用
 * JdbcTemplate + SimpleJdbcCall 呼叫 PostgreSQL function。
 */
@Repository
public class SeatingRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SimpleJdbcCall getSeatsCall;
    private final SimpleJdbcCall getEmployeesCall;
    private final SimpleJdbcCall assignCall;

    public SeatingRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        /*
         * SimpleJdbcCall 會幫忙建立 callable statement。
         * 對照 JDBC 寫法，大致等於 prepareCall("{? = call sp_get_seats()}")。
         */
        this.getSeatsCall = new SimpleJdbcCall(jdbcTemplate).withFunctionName("sp_get_seats");
        this.getEmployeesCall = new SimpleJdbcCall(jdbcTemplate).withFunctionName("sp_get_employees");
        this.assignCall = new SimpleJdbcCall(jdbcTemplate)
                .withFunctionName("sp_assign_seats")
                /*
                 * p_changes 是 JSONB，JDBC type 用 OTHER。
                 * 這裡仍是參數化呼叫，不是字串拼 SQL。
                 */
                .declareParameters(new SqlParameter("p_snapshot_version", VARCHAR), new SqlParameter("p_changes", OTHER));
    }

    public SeatSnapshotResponse getSeats() {
        /*
         * SP 回傳 JSON 字串，再由 Jackson 映射成 Java record DTO。
         * 因此 DB 裡 jsonb_build_object 的 key 要和 DTO 欄位名稱一致。
         */
        return parse(functionText(getSeatsCall), SeatSnapshotResponse.class);
    }

    public List<EmployeeDto> getEmployees() {
        return parse(functionText(getEmployeesCall), new TypeReference<>() {});
    }

    public Set<Integer> getSeatSequences() {
        /*
         * 這個查詢目前直接查 table，只用於 request validation。
         * 若要完全符合題目「查詢也走 SP」的嚴格版，可再包一支 sp_get_seat_sequences。
         */
        return jdbcTemplate.queryForList("select floor_seat_seq from seating_chart", Integer.class).stream().collect(Collectors.toSet());
    }

    public SeatSnapshotResponse assign(String snapshotVersion, List<AssignmentChangeRequest> changes) {
        try {
            /*
             * 批次 changes 轉成 JSON 傳給 PostgreSQL JSONB 參數。
             * 傳 JSONB 是為了讓一支 SP 接收多筆異動，並在同一個 DB transaction 內處理。
             */
            String changeJson = objectMapper.writeValueAsString(changes);
            Map<String, Object> args = new HashMap<>();
            args.put("p_snapshot_version", snapshotVersion);
            args.put("p_changes", changeJson);
            return parse(assignCall.executeFunction(String.class, args), SeatSnapshotResponse.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid changes", ex);
        } catch (DataAccessException ex) {
            throw translate(ex);
        }
    }

    private String functionText(SimpleJdbcCall call) {
        return call.executeFunction(String.class);
    }

    private BusinessException translate(DataAccessException ex) {
        String message = rootMessage(ex);
        /*
         * SP 用 RAISE EXCEPTION 丟出固定錯誤碼字串。
         * Repository 將 DB 例外轉成應用層 BusinessException，再由 GlobalExceptionHandler
         * 轉成 HTTP 400 / 409。
         */
        if (message.contains("CONFLICT_STALE_SNAPSHOT")) {
            return new BusinessException(ErrorCode.CONFLICT_STALE_SNAPSHOT, "Seat snapshot is stale", getSeats());
        }
        if (message.contains("VALIDATION_ERROR")) {
            return new BusinessException(ErrorCode.VALIDATION_ERROR, "Request validation failed");
        }
        if (message.contains("CONFLICT_SEAT_TAKEN")) {
            return new BusinessException(ErrorCode.CONFLICT_SEAT_TAKEN, "Target seat is already occupied", getSeats());
        }
        throw ex;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof SQLException sqlException) {
            return sqlException.getMessage();
        }
        return current.getMessage() == null ? "" : current.getMessage();
    }

    private <T> T parse(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored procedure returned invalid JSON", ex);
        }
    }

    private <T> T parse(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored procedure returned invalid JSON", ex);
        }
    }
}
