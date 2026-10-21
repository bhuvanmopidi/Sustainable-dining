package com.sustainabledining.semester;

import com.sustainabledining.meal.Phase;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SemesterDtos {
    private SemesterDtos() {}

    /** configVersion defaults to "v1"; eligibleSlots defaults to 3; fixedSlots defaults to 2.
     *  simSeed and simScenario are required for SIMULATED_DEMO and forbidden for LIVE. */
    public record CreateSemesterRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull SemesterMode mode,
            @NotNull LocalDate startDate,
            @NotNull @Min(1) Integer enrolledStudents,
            @Size(max = 32) String configVersion,
            @Min(1) @Max(10) Integer eligibleSlots,
            @Min(0) @Max(10) Integer fixedSlots,
            @Min(0) Integer simSeed,
            @Size(max = 40) String simScenario) {}

    public record SlotResponse(int slotIndex, BigDecimal visibility, boolean eligibleSlot) {}

    public record SemesterResponse(UUID id, String name, SemesterMode mode, LocalDate startDate, int enrolledStudents,
                                   String configVersion, Integer simSeed, String simScenario, boolean referenceFitted,
                                   long mealCount, Map<Phase, Long> mealsByPhase, List<SlotResponse> slots) {}
}