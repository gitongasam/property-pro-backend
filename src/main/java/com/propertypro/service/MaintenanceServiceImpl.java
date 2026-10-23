package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.common.DocumentNumbers;
import com.propertypro.dto.OperationsDtos.AssignRequest;
import com.propertypro.dto.OperationsDtos.ExpenseRequest;
import com.propertypro.dto.OperationsDtos.MaintenanceCreateRequest;
import com.propertypro.dto.OperationsDtos.MaintenanceResponse;
import com.propertypro.dto.OperationsDtos.MaintenanceUpdate;
import com.propertypro.dto.OperationsDtos.StatusRequest;
import com.propertypro.dto.PageResponse;
import com.propertypro.entity.Expense;
import com.propertypro.entity.MaintenanceRequest;
import com.propertypro.repository.MaintenanceRepository;
import com.propertypro.repository.PropertyRepository;
import com.propertypro.repository.TenantRepository;
import com.propertypro.repository.UnitRepository;
import com.propertypro.service.NotificationService.Channel;
import com.propertypro.service.NotificationService.Recipient;
import com.propertypro.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MaintenanceServiceImpl implements MaintenanceService {

    private final MaintenanceRepository requests;
    private final PropertyRepository properties;
    private final UnitRepository units;
    private final TenantRepository tenants;
    private final ExpenseService expenses;
    private final DocumentNumbers numbers;
    private final NotificationService notifications;
    private final PlanGate planGate;
    private final JdbcClient jdbc;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MaintenanceResponse> list(MaintenanceRequest.Status status, UUID propertyId, UUID assignedTo,
                                                  boolean openOnly, Pageable pageable) {
        Specification<MaintenanceRequest> byStatus = (r, q, cb) -> cb.equal(r.get("status"), status);
        Specification<MaintenanceRequest> byProperty = (r, q, cb) -> cb.equal(r.get("property").get("id"), propertyId);
        Specification<MaintenanceRequest> byAssignee = (r, q, cb) -> cb.equal(r.get("assignedTo"), assignedTo);
        Specification<MaintenanceRequest> open = (r, q, cb) -> r.get("status").in(
                MaintenanceRequest.Status.OPEN, MaintenanceRequest.Status.ASSIGNED,
                MaintenanceRequest.Status.IN_PROGRESS, MaintenanceRequest.Status.ON_HOLD);
        var spec = Specification.allOf(status == null ? null : byStatus, propertyId == null ? null : byProperty,
                assignedTo == null ? null : byAssignee, openOnly ? open : null);
        return PageResponse.of(requests.findAll(spec, pageable), r -> toResponse(r, List.of()));
    }

    @Override
    @Transactional(readOnly = true)
    public MaintenanceResponse get(UUID id) {
        MaintenanceRequest r = find(id);
        return toResponse(r, updates(id));
    }

    @Override
    @Transactional
    public MaintenanceResponse create(MaintenanceCreateRequest req) {
        planGate.requireWritable();
        MaintenanceRequest r = new MaintenanceRequest();
        r.setTicketNo(numbers.nextTicket());
        r.setProperty(properties.findById(req.propertyId()).orElseThrow(() -> ApiException.notFound("Property")));
        if (req.unitId() != null) {
            var unit = units.findById(req.unitId()).orElseThrow(() -> ApiException.notFound("Unit"));
            if (!unit.getProperty().getId().equals(req.propertyId())) {
                throw ApiException.badRequest("UNIT_MISMATCH", "This unit is in a different property");
            }
            r.setUnit(unit);
        }
        if (req.tenantId() != null) {
            tenants.findById(req.tenantId()).orElseThrow(() -> ApiException.notFound("Tenant"));
            r.setTenantId(req.tenantId());
        }
        r.setRaisedBy(TenantContext.userId().orElse(null));
        r.setTitle(req.title().trim());
        r.setDescription(req.description());
        if (req.category() != null) r.setCategory(req.category());
        if (req.priority() != null) r.setPriority(req.priority());
        if (req.chargeTo() != null) r.setChargeTo(req.chargeTo());
        requests.saveAndFlush(r);
        log(r, null, MaintenanceRequest.Status.OPEN, "Request raised");
        return toResponse(r, updates(r.getId()));
    }

    @Override
    @Transactional
    public MaintenanceResponse assign(UUID id, AssignRequest req) {
        planGate.requireWritable();
        MaintenanceRequest r = find(id);
        if (r.getStatus() == MaintenanceRequest.Status.CLOSED || r.getStatus() == MaintenanceRequest.Status.CANCELLED) {
            throw ApiException.conflict("REQUEST_CLOSED", "This request is closed");
        }
        if (req.assignedTo() == null && req.vendorId() == null) {
            throw ApiException.badRequest("ASSIGNEE_REQUIRED", "Choose a staff member or a vendor");
        }
        r.setAssignedTo(req.assignedTo());
        r.setVendorId(req.vendorId());
        r.setScheduledDate(req.scheduledDate());
        r.setEstimatedCost(req.estimatedCost());
        MaintenanceRequest.Status from = r.getStatus();
        if (from == MaintenanceRequest.Status.OPEN) {
            r.setStatus(MaintenanceRequest.Status.ASSIGNED);
        }
        log(r, from, r.getStatus(), "Assigned");
        return toResponse(r, updates(id));
    }

    @Override
    @Transactional
    public MaintenanceResponse changeStatus(UUID id, StatusRequest req) {
        planGate.requireWritable();
        MaintenanceRequest r = find(id);
        MaintenanceRequest.Status from = r.getStatus();
        if (!MaintenanceRequest.NEXT.get(from).contains(req.status())) {
            throw ApiException.conflict("INVALID_MOVE",
                    "A request that is " + from.name().toLowerCase() + " cannot move to " + req.status().name().toLowerCase());
        }
        r.setStatus(req.status());
        if (req.actualCost() != null) r.setActualCost(req.actualCost());
        if (req.status() == MaintenanceRequest.Status.RESOLVED) {
            r.setResolvedAt(OffsetDateTime.now());
            if (req.createExpense() && r.getActualCost() != null && r.getActualCost().signum() > 0) {
                expenses.create(new ExpenseRequest(r.getProperty().getId(), r.getUnit() == null ? null : r.getUnit().getId(),
                        r.getId(), Expense.Category.REPAIRS, r.getTicketNo() + " · " + r.getTitle(), r.getActualCost(),
                        null, null, r.getChargeTo(), null, null));
            }
        }
        log(r, from, req.status(), req.comment());
        notifyTenant(r);
        return toResponse(r, updates(id));
    }

    @Override
    @Transactional
    public MaintenanceResponse rate(UUID id, short rating) {
        MaintenanceRequest r = find(id);
        r.setTenantRating(rating);
        return toResponse(r, updates(id));
    }

    @Override
    public MaintenanceRequest find(UUID id) {
        return requests.findById(id).orElseThrow(() -> ApiException.notFound("Maintenance request"));
    }

    private void log(MaintenanceRequest r, MaintenanceRequest.Status from, MaintenanceRequest.Status to, String comment) {
        jdbc.sql("""
                        INSERT INTO maintenance_updates (organization_id, request_id, user_id, comment, status_from, status_to)
                        VALUES (?, ?, ?, ?, ?, ?)""")
                .param(r.getOrganizationId()).param(r.getId()).param(TenantContext.userId().orElse(null))
                .param(comment).param(from == null ? null : from.name()).param(to.name())
                .update();
    }

    private void notifyTenant(MaintenanceRequest r) {
        if (r.getTenantId() == null) return;
        tenants.findById(r.getTenantId()).ifPresent(t -> notifications.queue(Channel.SMS, Recipient.TENANT, t.getId(),
                t.getPhone(), "MAINTENANCE_UPDATE", Map.of(
                        "ticket_no", r.getTicketNo(), "title", r.getTitle(),
                        "status", r.getStatus().name().replace('_', ' ').toLowerCase(),
                        "org_name", "")));
    }

    private List<MaintenanceUpdate> updates(UUID id) {
        return jdbc.sql("""
                        SELECT mu.created_at, mu.user_id, trim(coalesce(u.first_name,'') || ' ' || coalesce(u.last_name,'')) AS who,
                               mu.status_from, mu.status_to, mu.comment
                          FROM maintenance_updates mu LEFT JOIN users u ON u.id = mu.user_id
                         WHERE mu.request_id = ? ORDER BY mu.created_at""")
                .param(id)
                .query((rs, n) -> new MaintenanceUpdate(rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("user_id", UUID.class), rs.getString("who"), rs.getString("status_from"),
                        rs.getString("status_to"), rs.getString("comment")))
                .list();
    }

    private static MaintenanceResponse toResponse(MaintenanceRequest r, List<MaintenanceUpdate> updates) {
        var u = r.getUnit();
        return new MaintenanceResponse(r.getId(), r.getTicketNo(), r.getProperty().getId(), r.getProperty().getName(),
                u == null ? null : u.getId(), u == null ? null : u.getUnitNo(), r.getTenantId(), r.getTitle(),
                r.getDescription(), r.getCategory().name(), r.getPriority().name(), r.getStatus().name(),
                r.getAssignedTo(), r.getVendorId(), r.getScheduledDate(), r.getEstimatedCost(), r.getActualCost(),
                r.getChargeTo().name(), r.getCreatedAt(), r.getResolvedAt(),
                MaintenanceRequest.NEXT.get(r.getStatus()).stream().map(Enum::name).sorted().toList(), updates);
    }
}
