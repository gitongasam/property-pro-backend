package com.propertypro.repository.spec;

import com.propertypro.entity.Owner;
import com.propertypro.entity.Property;
import com.propertypro.entity.Unit;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;
import java.util.UUID;

/**
 * Optional list filters. Each returns null when the filter is not used, and
 * Spring Data skips null specifications, so no "(:param is null or ...)"
 * queries are needed (PostgreSQL cannot type a bare null parameter).
 */
public final class PortfolioSpecs {

    private PortfolioSpecs() {}

    public static String likePattern(String q) {
        return "%" + q.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public static boolean present(String q) {
        return q != null && !q.isBlank();
    }

    // Owners -------------------------------------------------------------
    public static Specification<Owner> ownerMatches(String q) {
        if (!present(q)) return null;
        String pattern = likePattern(q);
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern, '\\'),
                cb.like(cb.lower(root.get("phone")), pattern, '\\'),
                cb.like(cb.lower(root.get("email")), pattern, '\\'));
    }

    // Properties ---------------------------------------------------------
    public static Specification<Property> propertyMatches(String q) {
        if (!present(q)) return null;
        String pattern = likePattern(q);
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern, '\\'),
                cb.like(cb.lower(root.get("code")), pattern, '\\'),
                cb.like(cb.lower(root.get("estate")), pattern, '\\'),
                cb.like(cb.lower(root.get("town")), pattern, '\\'));
    }

    public static Specification<Property> propertyType(Property.Type type) {
        return type == null ? null : (root, query, cb) -> cb.equal(root.get("propertyType"), type);
    }

    public static Specification<Property> propertyOwner(UUID ownerId) {
        return ownerId == null ? null : (root, query, cb) -> cb.equal(root.get("owner").get("id"), ownerId);
    }

    public static Specification<Property> propertyStatus(Property.Status status) {
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    // Units --------------------------------------------------------------
    public static Specification<Unit> unitInProperty(UUID propertyId) {
        return (root, query, cb) -> cb.equal(root.get("property").get("id"), propertyId);
    }

    public static Specification<Unit> unitStatus(Unit.Status status) {
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Unit> unitType(Unit.Type type) {
        return type == null ? null : (root, query, cb) -> cb.equal(root.get("unitType"), type);
    }

    public static Specification<Unit> unitMatches(String q) {
        if (!present(q)) return null;
        String pattern = likePattern(q);
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("unitNo")), pattern, '\\'),
                cb.like(cb.lower(root.get("paymentCode")), pattern, '\\'));
    }
}
