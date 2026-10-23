package com.propertypro.service;

import com.propertypro.dto.TeamDtos.InviteRequest;
import com.propertypro.dto.TeamDtos.InviteResult;
import com.propertypro.dto.TeamDtos.Member;
import com.propertypro.dto.TeamDtos.RoleOption;
import com.propertypro.dto.TeamDtos.RoleRequest;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Staff accounts in an organisation: list, invite by email (one-time link),
 * change role, disable and re-enable. The plan's user limit applies.
 */
public interface TeamService {

    /** Roles an organisation can hand out to staff. */
    Set<String> STAFF_ROLES = Set.of("ORG_OWNER", "PROPERTY_MANAGER", "ACCOUNTANT", "CARETAKER");

    List<Member> list();

    List<RoleOption> roles();

    InviteResult invite(InviteRequest req);

    InviteResult resendInvite(UUID userId);

    Member changeRole(UUID userId, RoleRequest req);

    Member disable(UUID userId);

    Member enable(UUID userId);
}
