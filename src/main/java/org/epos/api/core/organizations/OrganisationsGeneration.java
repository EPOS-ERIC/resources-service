package org.epos.api.core.organizations;

import abstractapis.AbstractAPI;
import commonapis.LinkedEntityAPI;
import metadataapis.EntityNames;
import org.epos.api.beans.DataServiceProvider;
import org.epos.api.beans.OrganizationBean;
import org.epos.api.core.DataServiceProviderGeneration;
import org.epos.api.core.filtersearch.OrganizationFilterSearch;
import org.epos.handler.dbapi.service.EntityManagerService;
import org.epos.eposdatamodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.*;
import java.util.stream.Collectors;

public class OrganisationsGeneration {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrganisationsGeneration.class);


    public static List<OrganizationBean> generate(Map<String,Object> parameters) {

        LOGGER.info("Requests start - JPA method");

        List<Organization> organisations;

        if(parameters.containsKey("id")) {
            organisations = List.of((Organization) AbstractAPI.retrieveAPI(EntityNames.ORGANIZATION.name()).retrieve(parameters.get("id").toString()));
        }else {
            if(parameters.containsKey("type")) {
                organisations = fetchOrganizationsByType(parameters.get("type").toString());
            } else {
                organisations = (List<Organization>) AbstractAPI.retrieveAPI(EntityNames.ORGANIZATION.name()).retrieveAll();
            }

            LOGGER.info("Apply filter using input parameters: "+parameters.toString());
            organisations = OrganizationFilterSearch.doFilters(organisations, parameters);
        }

        List<OrganizationBean> organisationsReturn = new ArrayList<OrganizationBean>();

        for(Organization singleOrganization : organisations) {
            if(singleOrganization.getLegalName()!=null){
                String legalName = String.join(";", singleOrganization.getLegalName());
                Address address = null;
                if(singleOrganization.getAddress()!=null) {
                    Address addressOptional = (Address) LinkedEntityAPI.retrieveFromLinkedEntity(singleOrganization.getAddress());
                    if(Objects.nonNull(addressOptional)){
                        address = addressOptional;
                    }
                }
                OrganizationBean ob = new OrganizationBean(singleOrganization.getInstanceId(), singleOrganization.getLogo(), singleOrganization.getURL(), legalName,address!=null? address.getCountry() : null);
                organisationsReturn.add(ob);
            }
        }

        return organisationsReturn;

    }

    private static List<Organization> fetchOrganizationsByType(String typeValue) {
        if (typeValue == null || typeValue.isBlank()) {
            return Collections.emptyList();
        }

        String normalizedType = typeValue.toLowerCase(Locale.ROOT);
        boolean includeDataProviders = normalizedType.contains("dataproviders");
        boolean includeServiceProviders = normalizedType.contains("serviceproviders");

        if (!includeDataProviders && !includeServiceProviders) {
            return Collections.emptyList();
        }

        Set<String> seedOrgIds = queryOrganizationIdsByType(includeDataProviders, includeServiceProviders);
        if (seedOrgIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Organization> seedOrganizations = (List<Organization>) AbstractAPI
                .retrieveAPI(EntityNames.ORGANIZATION.name())
                .retrieveBunch(new ArrayList<>(seedOrgIds));

        if (seedOrganizations == null || seedOrganizations.isEmpty()) {
            return Collections.emptyList();
        }

        List<DataServiceProvider> providers = DataServiceProviderGeneration.getProviders(seedOrganizations);
        Set<String> expandedOrgIds = new HashSet<>();
        providers.forEach(resource -> {
            expandedOrgIds.add(resource.getInstanceid());
            resource.getRelatedDataProvider().forEach(relatedData -> expandedOrgIds.add(relatedData.getInstanceid()));
            resource.getRelatedDataServiceProvider().forEach(relatedDataService -> expandedOrgIds.add(relatedDataService.getInstanceid()));
        });

        if (expandedOrgIds.isEmpty()) {
            return seedOrganizations;
        }

        List<Organization> expandedOrganizations = (List<Organization>) AbstractAPI
                .retrieveAPI(EntityNames.ORGANIZATION.name())
                .retrieveBunch(new ArrayList<>(expandedOrgIds));

        return expandedOrganizations != null ? expandedOrganizations : seedOrganizations;
    }

    private static Set<String> queryOrganizationIdsByType(boolean includeDataProviders, boolean includeServiceProviders) {
        Set<String> organizationIds = new HashSet<>();
        EntityManager em = null;
        try {
            em = EntityManagerService.getInstance().createEntityManager();
            if (includeDataProviders) {
                String sql = "SELECT DISTINCT dpp.organization_instance_id "
                        + "FROM metadata_catalogue.distribution_dataproduct ddp "
                        + "JOIN metadata_catalogue.dataproduct_publisher dpp ON ddp.dataproduct_instance_id = dpp.dataproduct_instance_id";
                Query query = em.createNativeQuery(sql);
                ((List<?>) query.getResultList()).forEach(id -> {
                    if (id != null) {
                        organizationIds.add(id.toString());
                    }
                });
            }

            if (includeServiceProviders) {
                String sql = "SELECT DISTINCT ws.provider "
                        + "FROM metadata_catalogue.webservice_distribution wd "
                        + "JOIN metadata_catalogue.webservice ws ON wd.webservice_instance_id = ws.instance_id "
                        + "WHERE ws.provider IS NOT NULL";
                Query query = em.createNativeQuery(sql);
                ((List<?>) query.getResultList()).forEach(id -> {
                    if (id != null) {
                        organizationIds.add(id.toString());
                    }
                });
            }
        } finally {
            if (em != null) {
                em.close();
            }
        }

        return organizationIds;
    }

}
