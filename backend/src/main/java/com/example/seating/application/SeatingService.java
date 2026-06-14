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

/*
 * Service 是應用層，負責「進 DB 前」的業務語意檢查與交易邊界。
 *
 * 對 JPA/Hibernate 背景來說，這裡類似過去放 @Transactional 的 service class。
 * 差別是本專案不靠 EntityManager flush 寫 DB，而是呼叫 Repository 內的 Stored Procedure。
 */
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
        /*
         * 這些檢查是為了提早回 400，讓錯誤訊息更友善。
         * 真正的一致性防線仍在 DB constraint 與 sp_assign_seats 內。
         */
        validateBatch(request.changes());
        return repository.assign(request.snapshotVersion(), request.changes());
    }

    private void validateBatch(List<AssignmentChangeRequest> changes) {
        Set<String> employeeIds = new HashSet<>();
        Set<Integer> targetSeats = new HashSet<>();
        /*
         * 這裡讀座位序號只是做 request validation。
         * 即使這裡漏檢，SP 仍會檢查座位是否存在。
         */
        Set<Integer> existingSeats = repository.getSeatSequences();

        for (AssignmentChangeRequest change : changes) {
            /*
             * Set.add 回傳 false 代表已存在。
             * 同一員工同批出現兩次語意不明，所以直接拒絕。
             */
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
