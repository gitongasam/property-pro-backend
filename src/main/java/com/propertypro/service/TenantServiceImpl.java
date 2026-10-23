package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.dto.LeasingDtos.TenantListItem;
import com.propertypro.dto.LeasingDtos.TenantRequest;
import com.propertypro.dto.LeasingDtos.TenantResponse;
import com.propertypro.dto.PageResponse;
import com.propertypro.entity.Lease;
import com.propertypro.entity.Tenant;
import com.propertypro.repository.LeaseRepository;
import com.propertypro.repository.TenantRepository;
import com.propertypro.security.CryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService {

    private final TenantRepository tenants;
    private final LeaseRepository leases;
    private final CryptoService crypto;
    private final PlanGate planGate;
    private final JdbcClient jdbc;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TenantListItem> list(String q, Tenant.Status status, Pageable pageable) {
        Specification<Tenant> spec = Specification.allOf(matches(q), hasStatus(status));
        Page<Tenant> page = tenants.findAll(spec, pageable);
        List<UUID> ids = page.getContent().stream().map(Tenant::getId).toList();

        Map<UUID, BigDecimal> balances = new HashMap<>();
        Map<UUID, String> units = new HashMap<>();
        if (!ids.isEmpty()) {
            jdbc.sql("SELECT tenant_id, net_balance FROM v_tenant_balances WHERE tenant_id IN (:ids)")
                    .paramSource(new MapSqlParameterSource("ids", ids))
                    .query((rs, n) -> balances.put(rs.getObject("tenant_id", UUID.class), rs.getBigDecimal("net_balance")))
                    .list();
            jdbc.sql("""
                            SELECT l.tenant_id, p.name || ' · ' || u.unit_no AS label
                              FROM leases l JOIN units u ON u.id = l.unit_id JOIN properties p ON p.id = u.property_id
                             WHERE l.tenant_id IN (:ids) AND l.status IN ('ACTIVE','NOTICE_GIVEN') AND l.deleted_at IS NULL
                            """)
                    .paramSource(new MapSqlParameterSource("ids", ids))
                    .query((rs, n) -> units.put(rs.getObject("tenant_id", UUID.class), rs.getString("label")))
                    .list();
        }
        return PageResponse.of(page, t -> new TenantListItem(t.getId(), t.getName(), t.getPhone(), t.getAccountCode(),
                t.getStatus().name(), units.get(t.getId()), balances.getOrDefault(t.getId(), BigDecimal.ZERO)));
    }

    @Override
    @Transactional(readOnly = true)
    public TenantResponse get(UUID id) {
        return TenantResponse.from(find(id), crypto);
    }

    @Override
    @Transactional
    public TenantResponse create(TenantRequest req) {
        planGate.requireWritable();
        return TenantResponse.from(createEntity(req), crypto);
    }

    /** Used by the API and by imports. */
    @Override
    @Transactional
    public Tenant createEntity(TenantRequest req) {
        String idHash = CryptoService.sha256(req.idNumber());
        if (idHash != null && tenants.existsByIdNumberHash(idHash)) {
            throw ApiException.conflict("TENANT_EXISTS", "A tenant with this ID number already exists");
        }
        Tenant t = new Tenant();
        apply(t, req);
        t.setAccountCode(nextAccountCode());
        return tenants.save(t);
    }

    @Override
    @Transactional
    public TenantResponse update(UUID id, TenantRequest req) {
        planGate.requireWritable();
        Tenant t = find(id);
        String newHash = CryptoService.sha256(req.idNumber());
        if (newHash != null && !newHash.equals(t.getIdNumberHash()) && tenants.existsByIdNumberHash(newHash)) {
            throw ApiException.conflict("TENANT_EXISTS", "Another tenant has this ID number");
        }
        apply(t, req);
        return TenantResponse.from(t, crypto);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        planGate.requireWritable();
        Tenant t = find(id);
        if (!leases.findByTenantAndStatus(id, Lease.LIVE).isEmpty()) {
            throw ApiException.conflict("TENANT_HAS_LEASE", "End this tenant's lease before removing them");
        }
        t.softDelete();
    }

    @Override
    public Tenant find(UUID id) {
        return tenants.findById(id).orElseThrow(() -> ApiException.notFound("Tenant"));
    }

    private void apply(Tenant t, TenantRequest r) {
        t.setTenantType(r.tenantType() == null ? Tenant.Type.INDIVIDUAL : r.tenantType());
        t.setName(r.name().trim());
        t.setContactPerson(r.contactPerson());
        t.setIdType(r.idType());
        if (r.idNumber() != null && !r.idNumber().isBlank()) {
            t.setIdNumberEnc(crypto.encrypt(r.idNumber().trim()));
            t.setIdNumberHash(CryptoService.sha256(r.idNumber()));
        }
        t.setKraPin(r.kraPin() == null ? null : r.kraPin().toUpperCase(Locale.ROOT));
        t.setEmail(r.email() == null ? null : r.email().trim().toLowerCase(Locale.ROOT));
        t.setPhone(r.phone());
        t.setAltPhone(r.altPhone());
        t.setEmployer(r.employer());
        t.setPostalAddress(r.postalAddress());
        t.setNextOfKinName(r.nextOfKinName());
        t.setNextOfKinPhone(r.nextOfKinPhone());
        t.setNextOfKinRelationship(r.nextOfKinRelationship());
        t.setNotes(r.notes());
    }

    /** T00001, T00002... unique per organisation. */
    private String nextAccountCode() {
        long n = jdbc.sql("SELECT count(*) FROM tenants").query(Long.class).single() + 1;
        while (true) {
            String code = String.format("T%05d", n);
            if (tenants.findByAccountCode(code).isEmpty()) {
                return code;
            }
            n++;
        }
    }

    private static Specification<Tenant> matches(String q) {
        if (q == null || q.isBlank()) return null;
        String pattern = "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern, '\\'),
                cb.like(cb.lower(root.get("phone")), pattern, '\\'),
                cb.like(cb.lower(root.get("accountCode")), pattern, '\\'),
                cb.like(cb.lower(root.get("email")), pattern, '\\'));
    }

    private static Specification<Tenant> hasStatus(Tenant.Status status) {
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }
}
