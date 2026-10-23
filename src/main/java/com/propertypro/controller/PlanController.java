package com.propertypro.controller;

import com.propertypro.dto.PlanDtos.PlanView;
import com.propertypro.repository.PlanRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public plan list for the pricing page and sign-up screen (plans table has no RLS). */
@Tag(name = "Plans")
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class PlanController {

    private final PlanRepository plans;

    @Operation(summary = "Public subscription plans")
    @GetMapping
    @Transactional(readOnly = true)
    public List<PlanView> list() {
        return plans.findByActiveTrueAndIsPublicTrueOrderByMonthlyPriceAsc().stream()
                .map(p -> new PlanView(p.getCode(), p.getName(), p.getDescription(), p.getCurrency(),
                        p.getMonthlyPrice(), p.getAnnualPrice(), p.getUnitLimit(), p.getUserLimit(),
                        p.getExtraUnitPrice(), p.getSmsQuotaMonthly(), p.getFeatures()))
                .toList();
    }
}
