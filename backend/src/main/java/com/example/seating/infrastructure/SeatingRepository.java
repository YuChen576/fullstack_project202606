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

@Repository
public class SeatingRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SimpleJdbcCall getSeatsCall;
    private final SimpleJdbcCall getEmployeesCall;
    private final SimpleJdbcCall getSeatSequencesCall;
    private final SimpleJdbcCall assignCall;

    public SeatingRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.getSeatsCall = new SimpleJdbcCall(jdbcTemplate).withFunctionName("sp_get_seats");
        this.getEmployeesCall = new SimpleJdbcCall(jdbcTemplate).withFunctionName("sp_get_employees");
        this.getSeatSequencesCall = new SimpleJdbcCall(jdbcTemplate).withFunctionName("sp_get_seat_sequences");
        this.assignCall = new SimpleJdbcCall(jdbcTemplate)
                .withFunctionName("sp_assign_seats")
                .declareParameters(new SqlParameter("p_snapshot_version", VARCHAR), new SqlParameter("p_changes", OTHER));
    }

    public SeatSnapshotResponse getSeats() {
        return parse(functionText(getSeatsCall), SeatSnapshotResponse.class);
    }

    public List<EmployeeDto> getEmployees() {
        return parse(functionText(getEmployeesCall), new TypeReference<>() {});
    }

    public Set<Integer> getSeatSequences() {
        List<Integer> seatSequences = parse(functionText(getSeatSequencesCall), new TypeReference<>() {});
        return seatSequences.stream().collect(Collectors.toSet());
    }

    public SeatSnapshotResponse assign(String snapshotVersion, List<AssignmentChangeRequest> changes) {
        try {
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
