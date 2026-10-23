-- =====================================================================
-- V13: Rent and service charge per square foot (commercial space)
--
-- Commercial space is let at a rate per sq ft per month, e.g. KES 120/sq ft
-- rent plus KES 35/sq ft service charge. The rate is kept next to the
-- amount it produces, so invoices, reports and allocation keep working on
-- amounts and the rate shows where each amount came from.
--   amount per billing period = area (sq ft) × rate × months in the period
-- NULL rate = the amount was entered directly (residential, flat-rate shops).
-- =====================================================================

ALTER TABLE units
    ADD COLUMN rent_per_sqft            numeric(12,4) CHECK (rent_per_sqft >= 0),
    ADD COLUMN service_charge_per_sqft  numeric(12,4) CHECK (service_charge_per_sqft >= 0),
    ADD CONSTRAINT units_rate_needs_size
        CHECK ((rent_per_sqft IS NULL AND service_charge_per_sqft IS NULL) OR size_sqft IS NOT NULL);

-- The area is copied onto the lease: it is what the tenant signed for, even if
-- the unit is re-measured later.
ALTER TABLE leases
    ADD COLUMN area_sqft        numeric(10,2) CHECK (area_sqft > 0),
    ADD COLUMN rent_per_sqft    numeric(12,4) CHECK (rent_per_sqft >= 0),
    ADD CONSTRAINT leases_rate_needs_area CHECK (rent_per_sqft IS NULL OR area_sqft IS NOT NULL);

ALTER TABLE lease_charges
    ADD COLUMN rate_per_sqft    numeric(12,4) CHECK (rate_per_sqft >= 0),
    ADD CONSTRAINT lease_charges_sqft_not_metered CHECK (rate_per_sqft IS NULL OR NOT is_metered);
