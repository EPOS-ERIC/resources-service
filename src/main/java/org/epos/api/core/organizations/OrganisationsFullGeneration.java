package org.epos.api.core.organizations;

import abstractapis.AbstractAPI;
import metadataapis.EntityNames;
import model.StatusType;
import org.epos.api.beans.OrganizationFullBean;
import org.epos.eposdatamodel.Address;
import org.epos.eposdatamodel.DataProduct;
import org.epos.eposdatamodel.EPOSDataModelEntity;
import org.epos.eposdatamodel.Identifier;
import org.epos.eposdatamodel.LinkedEntity;
import org.epos.eposdatamodel.Organization;
import org.epos.eposdatamodel.WebService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class OrganisationsFullGeneration {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrganisationsFullGeneration.class);

    public static List<OrganizationFullBean> generate() {
        LOGGER.info("Requests start - full organizations method");

        List<Organization> organizations = (List<Organization>) AbstractAPI.retrieveAPI(EntityNames.ORGANIZATION.name()).retrieveAll();

        if (organizations == null || organizations.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Address> addressesById = fetchEntityMap(extractAddressIds(organizations),
                EntityNames.ADDRESS, Address.class);
        Map<String, Identifier> identifiersById = fetchEntityMap(extractIdentifierIds(organizations),
                EntityNames.IDENTIFIER, Identifier.class);

        List<DataProduct> dataProducts = (List<DataProduct>) AbstractAPI.retrieveAPI(EntityNames.DATAPRODUCT.name())
                .retrieveAllWithStatus(StatusType.PUBLISHED);
        Map<String, LinkedHashMap<String, OrganizationFullBean.RelatedItemBean>> dataProductsByOrganizationId = buildDataProductsByOrganizationId(
                dataProducts);

        List<WebService> webServices = (List<WebService>) AbstractAPI.retrieveAPI(EntityNames.WEBSERVICE.name())
                .retrieveAll();
        Map<String, LinkedHashMap<String, OrganizationFullBean.RelatedItemBean>> webServicesByOrganizationId = buildWebServicesByOrganizationId(
                webServices);

        List<OrganizationFullBean> organizationsReturn = new ArrayList<OrganizationFullBean>();
        for (Organization organization : organizations) {
            organizationsReturn.add(toOrganizationFullBean(organization, addressesById, identifiersById,
                    dataProductsByOrganizationId, webServicesByOrganizationId));
        }

        return organizationsReturn;
    }

    private static OrganizationFullBean toOrganizationFullBean(Organization organization,
                                                               Map<String, Address> addressesById,
                                                               Map<String, Identifier> identifiersById,
                                                               Map<String, LinkedHashMap<String, OrganizationFullBean.RelatedItemBean>> dataProductsByOrganizationId,
                                                               Map<String, LinkedHashMap<String, OrganizationFullBean.RelatedItemBean>> webServicesByOrganizationId) {
        OrganizationFullBean organizationBean = new OrganizationFullBean();
        organizationBean.setInstanceid(organization.getInstanceId());
        organizationBean.setUid(organization.getUid());
        organizationBean.setMetaid(organization.getMetaId());
        organizationBean.setLegalName(firstValue(organization.getLegalName()));
        organizationBean.setAcronym(organization.getAcronym());
        organizationBean.setLogo(organization.getLogo());
        organizationBean.setUrl(organization.getURL());
        organizationBean.setLeiCode(organization.getLeiCode());
        organizationBean.setType(organization.getType());
        organizationBean.setMaturity(organization.getMaturity());
        organizationBean.setEmail(copyList(organization.getEmail()));
        organizationBean.setTelephone(copyList(organization.getTelephone()));

        if (organization.getAddress() != null) {
            Address address = addressesById.get(organization.getAddress().getInstanceId());
            if (address != null) {
                organizationBean.setAddress(toOrganizationAddressBean(address));
            }
        }

        List<OrganizationFullBean.IdentifierBean> identifierBeans = toOrganizationIdentifierBeans(organization.getIdentifier(),
                identifiersById);
        if (!identifierBeans.isEmpty()) {
            organizationBean.setIdentifiers(identifierBeans);
        }

        LinkedHashMap<String, OrganizationFullBean.RelatedItemBean> dataProducts = dataProductsByOrganizationId
                .get(organization.getInstanceId());
        if (dataProducts != null && !dataProducts.isEmpty()) {
            organizationBean.setDataProducts(new ArrayList<OrganizationFullBean.RelatedItemBean>(dataProducts.values()));
        }

        LinkedHashMap<String, OrganizationFullBean.RelatedItemBean> webServices = webServicesByOrganizationId
                .get(organization.getInstanceId());
        if (webServices != null && !webServices.isEmpty()) {
            organizationBean.setWebServices(new ArrayList<OrganizationFullBean.RelatedItemBean>(webServices.values()));
        }

        return organizationBean;
    }

    private static OrganizationFullBean.AddressBean toOrganizationAddressBean(Address address) {
        OrganizationFullBean.AddressBean addressBean = new OrganizationFullBean.AddressBean();
        addressBean.setCountry(address.getCountry());
        addressBean.setLocality(address.getLocality());
        addressBean.setPostalCode(address.getPostalCode());
        addressBean.setStreet(address.getStreet());
        addressBean.setCountryCode(address.getCountryCode());
        return addressBean;
    }

    private static List<OrganizationFullBean.IdentifierBean> toOrganizationIdentifierBeans(List<LinkedEntity> linkedIdentifiers,
                                                                                           Map<String, Identifier> identifiersById) {
        if (linkedIdentifiers == null || linkedIdentifiers.isEmpty()) {
            return Collections.emptyList();
        }

        List<OrganizationFullBean.IdentifierBean> identifierBeans = new ArrayList<OrganizationFullBean.IdentifierBean>();
        for (LinkedEntity linkedIdentifier : linkedIdentifiers) {
            if (linkedIdentifier == null) {
                continue;
            }
            Identifier identifier = identifiersById.get(linkedIdentifier.getInstanceId());
            if (identifier != null) {
                OrganizationFullBean.IdentifierBean identifierBean = new OrganizationFullBean.IdentifierBean();
                identifierBean.setType(identifier.getType());
                identifierBean.setValue(identifier.getIdentifier());
                identifierBeans.add(identifierBean);
            }
        }
        return identifierBeans;
    }

    private static Map<String, LinkedHashMap<String, OrganizationFullBean.RelatedItemBean>> buildDataProductsByOrganizationId(
            List<DataProduct> dataProducts) {
        Map<String, LinkedHashMap<String, OrganizationFullBean.RelatedItemBean>> itemsByOrganizationId = new LinkedHashMap<String, LinkedHashMap<String, OrganizationFullBean.RelatedItemBean>>();

        if (dataProducts == null || dataProducts.isEmpty()) {
            return itemsByOrganizationId;
        }

        for (DataProduct dataProduct : dataProducts) {
            if (dataProduct.getPublisher() == null || dataProduct.getPublisher().isEmpty()
                    || dataProduct.getInstanceId() == null) {
                continue;
            }

            OrganizationFullBean.RelatedItemBean itemBean = toRelatedItemBean(dataProduct.getInstanceId(), dataProduct.getUid(),
                    dataProduct.getMetaId(), joinValues(dataProduct.getTitle()), joinValues(dataProduct.getDescription()));

            for (LinkedEntity publisher : dataProduct.getPublisher()) {
                if (publisher != null && publisher.getInstanceId() != null) {
                    itemsByOrganizationId
                            .computeIfAbsent(publisher.getInstanceId(), key -> new LinkedHashMap<String, OrganizationFullBean.RelatedItemBean>())
                            .putIfAbsent(itemBean.getInstanceid(), itemBean);
                }
            }
        }

        return itemsByOrganizationId;
    }

    private static Map<String, LinkedHashMap<String, OrganizationFullBean.RelatedItemBean>> buildWebServicesByOrganizationId(
            List<WebService> webServices) {
        Map<String, LinkedHashMap<String, OrganizationFullBean.RelatedItemBean>> itemsByOrganizationId = new LinkedHashMap<String, LinkedHashMap<String, OrganizationFullBean.RelatedItemBean>>();

        if (webServices == null || webServices.isEmpty()) {
            return itemsByOrganizationId;
        }

        for (WebService webService : webServices) {
            if (webService == null || webService.getProvider() == null || webService.getProvider().getInstanceId() == null
                    || webService.getInstanceId() == null) {
                continue;
            }

            OrganizationFullBean.RelatedItemBean itemBean = toRelatedItemBean(webService.getInstanceId(), webService.getUid(),
                    webService.getMetaId(), webService.getName(), webService.getDescription());

            itemsByOrganizationId
                    .computeIfAbsent(webService.getProvider().getInstanceId(),
                            key -> new LinkedHashMap<String, OrganizationFullBean.RelatedItemBean>())
                    .putIfAbsent(itemBean.getInstanceid(), itemBean);
        }

        return itemsByOrganizationId;
    }

    private static OrganizationFullBean.RelatedItemBean toRelatedItemBean(String instanceid, String uid, String metaid,
                                                                          String name, String description) {
        return new OrganizationFullBean.RelatedItemBean(instanceid, uid, metaid, name, description);
    }

    private static String joinValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }

        List<String> cleanValues = new ArrayList<String>();
        for (String value : values) {
            if (value != null) {
                String trimmedValue = value.trim();
                if (!trimmedValue.isEmpty()) {
                    cleanValues.add(trimmedValue);
                }
            }
        }

        if (cleanValues.isEmpty()) {
            return null;
        }

        return String.join(";", cleanValues);
    }

    private static String firstValue(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }

        for (String value : values) {
            if (value != null) {
                String trimmedValue = value.trim();
                if (!trimmedValue.isEmpty()) {
                    return trimmedValue;
                }
            }
        }

        return null;
    }

    private static List<String> copyList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return new ArrayList<String>(values);
    }

    private static List<String> extractAddressIds(List<Organization> organizations) {
        LinkedHashSet<String> linkedEntityIds = new LinkedHashSet<String>();
        for (Organization organization : organizations) {
            LinkedEntity linkedEntity = organization.getAddress();
            if (linkedEntity != null && linkedEntity.getInstanceId() != null) {
                linkedEntityIds.add(linkedEntity.getInstanceId());
            }
        }
        return new ArrayList<String>(linkedEntityIds);
    }

    private static List<String> extractIdentifierIds(List<Organization> organizations) {
        LinkedHashSet<String> linkedEntityIds = new LinkedHashSet<String>();
        for (Organization organization : organizations) {
            if (organization.getIdentifier() != null) {
                for (LinkedEntity linkedEntity : organization.getIdentifier()) {
                    if (linkedEntity != null && linkedEntity.getInstanceId() != null) {
                        linkedEntityIds.add(linkedEntity.getInstanceId());
                    }
                }
            }
        }
        return new ArrayList<String>(linkedEntityIds);
    }

    private static <T extends EPOSDataModelEntity> Map<String, T> fetchEntityMap(Collection<String> entityIds,
                                                                                  EntityNames entityName,
                                                                                  Class<T> entityClass) {
        if (entityIds == null || entityIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<?> entities = (List<?>) AbstractAPI.retrieveAPI(entityName.name()).retrieveBunch(new ArrayList<String>(entityIds));
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, T> entitiesById = new LinkedHashMap<String, T>();
        for (Object entity : entities) {
            if (entityClass.isInstance(entity)) {
                T typedEntity = entityClass.cast(entity);
                if (typedEntity.getInstanceId() != null) {
                    entitiesById.putIfAbsent(typedEntity.getInstanceId(), typedEntity);
                }
            }
        }
        return entitiesById;
    }
}
