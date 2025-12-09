package org.epos.api.core.filtersearch;

import java.util.*;
import java.util.stream.Collectors;

import commonapis.LinkedEntityAPI;
import org.epos.api.routines.DatabaseConnections;
import org.epos.eposdatamodel.Address;
import org.epos.eposdatamodel.Identifier;
import org.epos.eposdatamodel.LinkedEntity;
import org.epos.eposdatamodel.Organization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class OrganizationFilterSearch {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrganizationFilterSearch.class);

    public static List<Organization> doFilters(List<Organization> organisationsList, Map<String,Object> parameters) {

        organisationsList = filterOrganisationsByFullText(organisationsList, parameters);
        organisationsList = filterOrganisationsByCountry(organisationsList, parameters);

        return organisationsList;
    }

    private static List<Organization> filterOrganisationsByFullText(List<Organization> organisationsList, Map<String,Object> parameters) {
        if(parameters.containsKey("q")) {
            HashSet<Organization> tempDatasetList = new HashSet<>();
            String[] qs = parameters.get("q").toString().toLowerCase().split(",");

            for (Organization edmOrganisation : organisationsList) {
                Map<String, Boolean> qSMap = Arrays.stream(qs)
                        .collect(Collectors.toMap(
                                key -> key, value -> Boolean.FALSE
                        ));

                if (edmOrganisation.getLegalName() != null && !edmOrganisation.getLegalName().isEmpty()) {
                    for (String title : edmOrganisation.getLegalName()) {
                        for (String q : qSMap.keySet()) {
                            if (title.toLowerCase().contains(q)) {
                                qSMap.put(q, Boolean.TRUE);
                            }
                        }
                    }
                }

                if(Objects.nonNull(edmOrganisation.getIdentifier())){
                    for(LinkedEntity identifierLe : edmOrganisation.getIdentifier()) {
                        Identifier identifier = (Identifier) LinkedEntityAPI.retrieveFromLinkedEntity(identifierLe);
                        if(Objects.nonNull(identifier)) {
                            for (String q : qSMap.keySet()) {
                                if (identifier.getIdentifier().contains(q)) qSMap.put(q, Boolean.TRUE);
                                if (identifier.getType().contains(q)) qSMap.put(q, Boolean.TRUE);
                                if ((identifier.getType().toLowerCase()+identifier.getIdentifier().toLowerCase()).contains(q)) qSMap.put(q, Boolean.TRUE);
                            }
                        }
                    }

                }

                if(Objects.nonNull(edmOrganisation.getUid())){
                    for (String q : qSMap.keySet()) {
                        if (edmOrganisation.getUid().contains(q)) qSMap.put(q, Boolean.TRUE);
                    }
                }

                //take a dataproduct onlly if every element of the freetext search is satisfied
                if (qSMap.values().stream().allMatch(b -> b)) tempDatasetList.add(edmOrganisation);
            }

            organisationsList = new ArrayList<>(tempDatasetList);
        }

        return organisationsList;

    }

    private static List<Organization> filterOrganisationsByCountry(List<Organization> organisationsList, Map<String,Object> parameters) {
        if(parameters.containsKey("country")) {
            HashSet<Organization> tempOrganizationList = new HashSet<>();
            String[] qs = parameters.get("country").toString().toLowerCase().split(",");

            for (Organization edmOrganisation : organisationsList) {
                Map<String, Boolean> qSMap = Arrays.stream(qs)
                        .collect(Collectors.toMap(
                                key -> key, value -> Boolean.FALSE
                        ));
                for (String q : qSMap.keySet()) {
                    if(edmOrganisation.getAddress()!=null){
                        Address address = (Address) LinkedEntityAPI.retrieveFromLinkedEntity(edmOrganisation.getAddress());
                        if(Objects.nonNull(address) && (address.getCountry()!=null || q.equals(address.getCountry().toLowerCase())))
                            tempOrganizationList.add(edmOrganisation);
                    }
                }
            }
            organisationsList = new ArrayList<>(tempOrganizationList);
        }
        return organisationsList;

    }


}
