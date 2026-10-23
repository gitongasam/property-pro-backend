package com.propertypro.service;

import com.propertypro.dto.OrganizationDtos.ProfileRequest;
import com.propertypro.dto.OrganizationDtos.ProfileResponse;
import com.propertypro.entity.Organization;

/** Settings → Organisation: business profile and organisation-wide defaults. */
public interface OrganizationService {

    ProfileResponse get();

    ProfileResponse update(ProfileRequest req);

    Organization current();
}
