package com.example.seating;

import com.example.seating.application.SeatingService;
import com.example.seating.common.BusinessException;
import com.example.seating.infrastructure.SeatingRepository;
import com.example.seating.presentation.AssignmentChangeRequest;
import com.example.seating.presentation.AssignmentRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssignmentValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void should_reject_invalid_employee_ids_when_validating_dto() {
        for (String empId : List.of("1234", "123456", "12a45", "  12345", "")) {
            var violations = validator.validate(new AssignmentChangeRequest(empId, 1));
            assertThat(violations).isNotEmpty();
        }
        assertThat(validator.validate(new AssignmentChangeRequest(null, 1))).isNotEmpty();
    }

    @Test
    void should_accept_leading_zero_employee_id_when_validating_dto() {
        assertThat(validator.validate(new AssignmentChangeRequest("00001", 1))).isEmpty();
    }

    @Test
    void should_reject_empty_changes_when_validating_request() {
        assertThat(validator.validate(new AssignmentRequest("0", List.of()))).isNotEmpty();
    }

    @Test
    void should_reject_duplicate_employee_when_assigning() {
        SeatingRepository repository = mock(SeatingRepository.class);
        when(repository.getSeatSequences()).thenReturn(Set.of(1, 2));
        SeatingService service = new SeatingService(repository);

        var request = new AssignmentRequest("0", List.of(
                new AssignmentChangeRequest("12006", 1),
                new AssignmentChangeRequest("12006", 2)
        ));

        assertThatThrownBy(() -> service.assign(request)).isInstanceOf(BusinessException.class);
    }

    @Test
    void should_reject_duplicate_target_seat_when_assigning() {
        SeatingRepository repository = mock(SeatingRepository.class);
        when(repository.getSeatSequences()).thenReturn(Set.of(1, 2));
        SeatingService service = new SeatingService(repository);

        var request = new AssignmentRequest("0", List.of(
                new AssignmentChangeRequest("12006", 1),
                new AssignmentChangeRequest("16142", 1)
        ));

        assertThatThrownBy(() -> service.assign(request)).isInstanceOf(BusinessException.class);
    }

    @Test
    void should_reject_missing_target_seat_when_assigning() {
        SeatingRepository repository = mock(SeatingRepository.class);
        when(repository.getSeatSequences()).thenReturn(Set.of(1, 2));
        SeatingService service = new SeatingService(repository);

        var request = new AssignmentRequest("0", List.of(new AssignmentChangeRequest("12006", 99)));

        assertThatThrownBy(() -> service.assign(request)).isInstanceOf(BusinessException.class);
    }
}
