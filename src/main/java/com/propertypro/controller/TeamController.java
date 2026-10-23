package com.propertypro.controller;

import com.propertypro.dto.TeamDtos.InviteRequest;
import com.propertypro.dto.TeamDtos.InviteResult;
import com.propertypro.dto.TeamDtos.Member;
import com.propertypro.dto.TeamDtos.RoleOption;
import com.propertypro.dto.TeamDtos.RoleRequest;
import com.propertypro.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Team")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService team;

    @GetMapping
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public List<Member> list() {
        return team.list();
    }

    @Operation(summary = "Roles that can be given to staff")
    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public List<RoleOption> roles() {
        return team.roles();
    }

    @Operation(summary = "Invite a team member by email; they get a link to set a password (valid 7 days)")
    @PostMapping("/invite")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public InviteResult invite(@Valid @RequestBody InviteRequest body) {
        return team.invite(body);
    }

    @PostMapping("/{id}/resend-invite")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public InviteResult resend(@PathVariable UUID id) {
        return team.resendInvite(id);
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasAuthority('ROLE_MANAGE') or hasAuthority('USER_MANAGE')")
    public Member changeRole(@PathVariable UUID id, @Valid @RequestBody RoleRequest body) {
        return team.changeRole(id, body);
    }

    @PatchMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public Member disable(@PathVariable UUID id) {
        return team.disable(id);
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public Member enable(@PathVariable UUID id) {
        return team.enable(id);
    }
}
