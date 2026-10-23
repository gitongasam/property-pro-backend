package com.propertypro.service;

import com.propertypro.dto.ImportDtos.ImportResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Spreadsheet imports used during onboarding (and any time after):
 *
 * <ul>
 *   <li><b>Units</b> for one property: unit no, type, bedrooms, rent, deposit...</li>
 *   <li><b>Sitting tenants</b>: name, phone, unit, rent, and what they owe today.
 *       Each row creates the tenant, an ACTIVE lease (no move-in invoice), marks
 *       the unit occupied and turns "owed today" into an opening-balance invoice.</li>
 * </ul>
 *
 * Every row is checked first. If any row has a problem nothing is saved and the
 * errors come back by row number, so the user fixes the file and uploads again.
 * {@code dryRun=true} only checks.
 */
public interface ImportService {

    String TENANT_TEMPLATE = """
            unit,tenant name,phone,email,id number,rent,deposit,lease start,owed today
            A1,Wanjiku Kamau,0712345678,wanjiku@example.com,12345678,25000,25000,2025-03-01,0
            A2,Otieno Ochieng,0722000111,,23456789,25000,25000,2024-11-01,12500
            """;

    String UNIT_TEMPLATE = """
            unit no,type,bedrooms,bathrooms,size sqft,rent,deposit,service charge,block,floor,water meter
            A1,ONE_BR,1,1,450,25000,25000,0,A,Ground,WM-0001
            A2,TWO_BR,2,1,650,35000,35000,0,A,Ground,WM-0002
            S1,SHOP,,,300,60000,120000,5000,,Ground,
            """;

    ImportResult importUnits(UUID propertyId, MultipartFile file, boolean dryRun);

    ImportResult importTenants(UUID propertyId, MultipartFile file, boolean dryRun);
}
