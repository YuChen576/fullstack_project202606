package com.example.seating.application;

import com.example.seating.common.BusinessException;
import com.example.seating.common.ErrorCode;
import com.example.seating.infrastructure.SeatingRepository;
import com.example.seating.presentation.AssignmentChangeRequest;
import com.example.seating.presentation.AssignmentRequest;
import com.example.seating.presentation.EmployeeDto;
import com.example.seating.presentation.SeatSnapshotResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SeatingService {
    private final SeatingRepository repository;

    public SeatingService(SeatingRepository repository) {
        this.repository = repository;
    }

    public SeatSnapshotResponse getSeats() {
        return repository.getSeats();
    }

    public List<EmployeeDto> getEmployees() {
        return repository.getEmployees();
    }

    @Transactional
    public SeatSnapshotResponse assign(AssignmentRequest request) {
        validateBatch(request.changes());
        return repository.assign(request.snapshotVersion(), request.changes());
    }

    private void validateBatch(List<AssignmentChangeRequest> changes) {
        Set<String> employeeIds = new HashSet<>();
        Set<Integer> targetSeats = new HashSet<>();
        Set<Integer> existingSeats = repository.getSeatSequences();

        for (AssignmentChangeRequest change : changes) {
            if (!employeeIds.add(change.empId())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Employee appears more than once in the same batch");
            }
            if (change.toSeatSeq() != null) {
                if (!existingSeats.contains(change.toSeatSeq())) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Seat does not exist");
                }
                if (!targetSeats.add(change.toSeatSeq())) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Two changes cannot target the same seat");
                }
            }
        }
    }
}
