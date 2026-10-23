package com.propertypro.dto;

import java.math.BigDecimal;
import java.util.List;

/** Results of CSV imports (units, tenants). */
public final class ImportDtos {

    private ImportDtos() {}

    public record RowError(int row, String column, String message) {}

    public record ImportResult(boolean dryRun, int rows, int created, List<RowError> errors,
                               BigDecimal openingBalances, List<String> createdRefs) {}
}
