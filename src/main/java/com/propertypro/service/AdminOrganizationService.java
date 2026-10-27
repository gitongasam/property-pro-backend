package com.propertypro.service;

import com.propertypro.dto.AdminDtos.OrganizationDetail;
import com.propertypro.dto.AdminDtos.OrganizationRow;
import com.propertypro.dto.PageResponse;

import java.util.UUID;

/**
 * Platform admin view of every organisation: search, detail with usage, suspend /
 * reactivate and trial extensions. Runs across organisations (SystemExecutor), and
 * every command is recorded in admin_actions with the admin and the reason.
 */
public interface AdminOrganizationService {

    PageResponse<OrganizationRow> list(String query, String status, String planCode, int page, int size);

    OrganizationDetail get(UUID organizationId);

    /** Blocks every user of the organisation (except sign-in) and stops its billing jobs. */
    OrganizationDetail suspend(UUID organizationId, String reason);

    /** Lifts a suspension; the status goes back to what the subscription says. */
    OrganizationDetail reactivate(UUID organizationId, String reason);

    /** Adds days to a running or expired trial (counted from now if it has already ended). */
    OrganizationDetail extendTrial(UUID organizationId, int days, String reason);
}
