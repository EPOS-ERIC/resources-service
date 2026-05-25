package org.epos.api.core.organizations;

import abstractapis.AbstractAPI;
import commonapis.LinkedEntityAPI;
import metadataapis.EntityNames;
import metadataapis.OrganizationAPI;
import metadataapis.WebServiceAPI;
import model.EdmEntityId;
import model.StatusType;
import org.epos.api.beans.DataServiceProvider;
import org.epos.api.beans.OrganizationBean;
import org.epos.api.core.DataServiceProviderGeneration;
import org.epos.api.core.filtersearch.OrganizationFilterSearch;
import org.epos.api.routines.DatabaseConnections;
import org.epos.eposdatamodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            organisations = (List<Organization>) AbstractAPI.retrieveAPI(EntityNames.ORGANIZATION.name()).retrieveAll();

            if(parameters.containsKey("type")) {
                List<Distribution> distributions = (List<Distribution>) AbstractAPI.retrieveAPI(EntityNames.DISTRIBUTION.name()).retrieveAll();

                List<Organization> tempOrganizationList;

                Set<Organization> organizationsEntityIds = new HashSet<>();

                distributions.forEach(distribution -> {
                    if(parameters.get("type").toString().toLowerCase().contains("dataproviders")) {
                        if(distribution.getDataProduct()!=null){
                            for (LinkedEntity dataproduct : distribution.getDataProduct()) {
                                DataProduct dataProduct = (DataProduct) LinkedEntityAPI.retrieveFromLinkedEntity(dataproduct);
                                if(Objects.nonNull(dataProduct)){
                                    if(dataProduct.getPublisher()!=null)
                                        dataProduct.getPublisher().forEach(publisher -> {
                                            Organization organization = (Organization) LinkedEntityAPI.retrieveFromLinkedEntity(publisher);
                                            organizationsEntityIds.add(organization);
                                        });
                                }
                            }
                        }
                    }
                    if(parameters.get("type").toString().toLowerCase().contains("serviceproviders") && distribution.getAccessService()!=null) {
                        if(distribution.getAccessService()!=null){
                            for (LinkedEntity webservice : distribution.getAccessService()) {
                                WebService webService = (WebService) LinkedEntityAPI.retrieveFromLinkedEntity(webservice);
                                if(Objects.nonNull(webService)){
                                    if(webService.getProvider()!=null){
                                        Organization organization = (Organization) LinkedEntityAPI.retrieveFromLinkedEntity(webService.getProvider());
                                        organizationsEntityIds.add(organization);
                                    }
                                }
                            }
                        }
                    }
                });
//                if(parameters.get("type").toString().toLowerCase().contains("facilitiesproviders")) {  //COMMENTED OUT BECAUSE NO MORE FILTERS EXISTS
//                    organizationsEntityIds.addAll(organisations.stream()
//                            .filter(org -> org.getOwns()!=null)
//                            .collect(Collectors.toList()));
//                }

                List<DataServiceProvider> providers = DataServiceProviderGeneration.getProviders(new ArrayList<Organization>(organizationsEntityIds));
                List<String> providersInstanceIds = new ArrayList<String>();
                providers.forEach(resource->{
                    providersInstanceIds.add(resource.getInstanceid());

                    resource.getRelatedDataProvider().forEach(relatedData ->{
                        providersInstanceIds.add(relatedData.getInstanceid());
                    });
                    resource.getRelatedDataServiceProvider().forEach(relatedDataService ->{
                        providersInstanceIds.add(relatedDataService.getInstanceid());
                    });
                });

                tempOrganizationList = organisations.stream().filter(org -> providersInstanceIds.contains(org.getInstanceId())).collect(Collectors.toList());
                organisations = tempOrganizationList;
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

}
