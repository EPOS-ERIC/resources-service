package org.epos.api.beans;

import java.util.ArrayList;
import java.util.List;

public class OrganizationFullBean {

    private String instanceid;
    private String uid;
    private String metaid;
    private String legalName;
    private String acronym;
    private String logo;
    private String url;
    private String leiCode;
    private String type;
    private String maturity;
    private List<String> email;
    private List<String> telephone;
    private AddressBean address;
    private List<IdentifierBean> identifiers;
    private List<RelatedItemBean> dataProducts = new ArrayList<RelatedItemBean>();
    private List<RelatedItemBean> webServices = new ArrayList<RelatedItemBean>();

    public String getInstanceid() {
        return instanceid;
    }

    public void setInstanceid(String instanceid) {
        this.instanceid = instanceid;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getMetaid() {
        return metaid;
    }

    public void setMetaid(String metaid) {
        this.metaid = metaid;
    }

    public String getLegalName() {
        return legalName;
    }

    public void setLegalName(String legalName) {
        this.legalName = legalName;
    }

    public String getAcronym() {
        return acronym;
    }

    public void setAcronym(String acronym) {
        this.acronym = acronym;
    }

    public String getLogo() {
        return logo;
    }

    public void setLogo(String logo) {
        this.logo = logo;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getLeiCode() {
        return leiCode;
    }

    public void setLeiCode(String leiCode) {
        this.leiCode = leiCode;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMaturity() {
        return maturity;
    }

    public void setMaturity(String maturity) {
        this.maturity = maturity;
    }

    public List<String> getEmail() {
        return email;
    }

    public void setEmail(List<String> email) {
        this.email = email;
    }

    public List<String> getTelephone() {
        return telephone;
    }

    public void setTelephone(List<String> telephone) {
        this.telephone = telephone;
    }

    public AddressBean getAddress() {
        return address;
    }

    public void setAddress(AddressBean address) {
        this.address = address;
    }

    public List<IdentifierBean> getIdentifiers() {
        return identifiers;
    }

    public void setIdentifiers(List<IdentifierBean> identifiers) {
        this.identifiers = identifiers;
    }

    public List<RelatedItemBean> getDataProducts() {
        if (dataProducts == null) {
            dataProducts = new ArrayList<RelatedItemBean>();
        }
        return dataProducts;
    }

    public void setDataProducts(List<RelatedItemBean> dataProducts) {
        this.dataProducts = dataProducts;
    }

    public List<RelatedItemBean> getWebServices() {
        if (webServices == null) {
            webServices = new ArrayList<RelatedItemBean>();
        }
        return webServices;
    }

    public void setWebServices(List<RelatedItemBean> webServices) {
        this.webServices = webServices;
    }

    public static class AddressBean {

        private String country;
        private String locality;
        private String postalCode;
        private String street;
        private String countryCode;

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }

        public String getLocality() {
            return locality;
        }

        public void setLocality(String locality) {
            this.locality = locality;
        }

        public String getPostalCode() {
            return postalCode;
        }

        public void setPostalCode(String postalCode) {
            this.postalCode = postalCode;
        }

        public String getStreet() {
            return street;
        }

        public void setStreet(String street) {
            this.street = street;
        }

        public String getCountryCode() {
            return countryCode;
        }

        public void setCountryCode(String countryCode) {
            this.countryCode = countryCode;
        }
    }

    public static class IdentifierBean {

        private String type;
        private String value;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    public static class RelatedItemBean {

        private String instanceid;
        private String uid;
        private String metaid;
        private String name;
        private String description;

        public RelatedItemBean() {
        }

        public RelatedItemBean(String instanceid, String uid, String metaid, String name, String description) {
            this.instanceid = instanceid;
            this.uid = uid;
            this.metaid = metaid;
            this.name = name;
            this.description = description;
        }

        public String getInstanceid() {
            return instanceid;
        }

        public void setInstanceid(String instanceid) {
            this.instanceid = instanceid;
        }

        public String getUid() {
            return uid;
        }

        public void setUid(String uid) {
            this.uid = uid;
        }

        public String getMetaid() {
            return metaid;
        }

        public void setMetaid(String metaid) {
            this.metaid = metaid;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

}
