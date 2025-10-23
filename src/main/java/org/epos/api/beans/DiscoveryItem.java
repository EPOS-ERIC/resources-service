package org.epos.api.beans;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class DiscoveryItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private String href;
    private String hrefExtended;
    private String id;
    private String uid;
    private String metaId;
    private transient String sha256id;
    private transient Set<String> dataprovider;
    private transient Set<String> facilityprovider;
    private transient Set<String> serviceprovider;
    private transient List<String> categories;
    private String title;
    private String description;
    private int status = 0;
    private DataServiceProvider dataServiceProvider;
    private String versioningStatus;
    private String statusTimestamp;
    private List<AvailableFormat> availableFormats;
    private String editorId;
    private String editorFullName;
    private LocalDateTime changeDate;

    public DiscoveryItem() {}

    public DiscoveryItem(DiscoveryItemBuilder builder) {
        this.href = builder.href;
        this.hrefExtended = builder.hrefExtended;
        this.id = builder.id;
        this.uid = builder.uid;
        this.metaId = builder.metaId;
        this.sha256id = builder.sha256id;
        this.dataprovider = builder.dataprovider;
        this.serviceprovider = builder.serviceprovider;
        this.facilityprovider = builder.facilityprovider;
        this.categories = builder.categories;
        this.title = builder.title;
        this.description = builder.description;
        this.dataServiceProvider = builder.dataServiceProvider;
        this.status = builder.status;
        this.versioningStatus = builder.versioningStatus;
        this.statusTimestamp = builder.statusTimestamp;
        this.availableFormats = builder.availableFormats;
        this.editorId = builder.editorId;
        this.editorFullName = builder.editorFullName;
        this.changeDate = builder.changeDate;
    }

    public String getHref() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String getHrefExtended() {
        return hrefExtended;
    }

    public void setHrefExtended(String hrefExtended) {
        this.hrefExtended = hrefExtended;
    }

    public String getId() {
        return id;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getUid() {
        return uid;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMetaId() {
        return metaId;
    }

    public void setMetaId(String metaid) {
        this.metaId = metaid;
    }

    public String getSha256id() {
        return sha256id;
    }

    public void setSha256id(String sha256id) {
        this.sha256id = sha256id;
    }

    public Set<String> getDataprovider() {
        return dataprovider;
    }

    public void setDataprovider(Set<String> dataprovider) {
        this.dataprovider = dataprovider;
    }

    public Set<String> getServiceprovider() {
        return serviceprovider;
    }

    public void setServiceprovider(Set<String> serviceprovider) {
        this.serviceprovider = serviceprovider;
    }

    public List<String> getCategories() {
        return categories;
    }

    public void setCategories(List<String> categories) {
        this.categories = categories;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public DataServiceProvider getDataServiceProvider() {
        return dataServiceProvider;
    }

    public void setDataServiceProvider(DataServiceProvider dataServiceProvider) {
        this.dataServiceProvider = dataServiceProvider;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getVersioningStatus() {
        return versioningStatus;
    }

    public void setVersioningStatus(String versioningStatus) {
        this.versioningStatus = versioningStatus;
    }

    public String getStatusTimestamp() {
        return statusTimestamp;
    }

    public void setStatusTimestamp(String statusTimestamp) {
        this.statusTimestamp = statusTimestamp;
    }

    public List<AvailableFormat> getAvailableFormats() {
        return availableFormats;
    }

    public void setAvailableFormats(List<AvailableFormat> availableFormats) {
        this.availableFormats = availableFormats;
    }

    public Set<String> getFacilityprovider() {
        return facilityprovider;
    }

    public void setFacilityprovider(Set<String> facilityprovider) {
        this.facilityprovider = facilityprovider;
    }

    public String getEditorId() {
        return editorId;
    }

    public void setEditorId(String editorId) {
        this.editorId = editorId;
    }

    public String getEditorFullName() {
        return editorFullName;
    }

    public void setEditorFullName(String editorFullName) {
        this.editorFullName = editorFullName;
    }

    public LocalDateTime getChangeDate() {
        return this.changeDate;
    }

    public void setChangeDate(LocalDateTime changeDate) {
        this.changeDate = changeDate;
    }

    public static class DiscoveryItemBuilder {

        private String href;
        private String hrefExtended;
        private String id;
        private String uid;
        private String metaId;
        private String sha256id;
        private Set<String> dataprovider;
        private Set<String> serviceprovider;
        private Set<String> facilityprovider;
        private List<String> categories;
        private String title;
        private String description;
        private int status = 0;
        private DataServiceProvider dataServiceProvider;
        private String versioningStatus;
        private String statusTimestamp;
        private List<AvailableFormat> availableFormats;
        private String editorId;
        private String editorFullName;
        private LocalDateTime changeDate;

        public DiscoveryItemBuilder(String id, String href, String hrefExtended) {
            this.id = id;
            this.href = href;
            this.hrefExtended = hrefExtended;
        }

        public DiscoveryItemBuilder uid(String uid) {
            this.uid = uid;
            return this;
        }

        public DiscoveryItemBuilder metaId(String metaId) {
            this.metaId = metaId;
            return this;
        }

        public DiscoveryItemBuilder sha256id(String sha256id) {
            this.sha256id = sha256id;
            return this;
        }

        public DiscoveryItemBuilder dataProvider(Set<String> facetsDataProviders) {
            this.dataprovider = facetsDataProviders;
            return this;
        }

        public DiscoveryItemBuilder dataServiceProvider(DataServiceProvider dataServiceProvider) {
            this.dataServiceProvider = dataServiceProvider;
            return this;
        }

        public DiscoveryItemBuilder facilityProvider(Set<String> facilityprovider) {
            this.facilityprovider = facilityprovider;
            return this;
        }

        public DiscoveryItemBuilder categories(List<String> categories) {
            this.categories = categories;
            return this;
        }

        public DiscoveryItemBuilder title(String title) {
            this.title = title;
            return this;
        }

        public DiscoveryItemBuilder description(String description) {
            this.description = description;
            return this;
        }

        public DiscoveryItemBuilder status(int status) {
            this.status = status;
            return this;
        }

        public DiscoveryItemBuilder serviceProvider(Set<String> serviceprovider) {
            this.serviceprovider = serviceprovider;
            return this;
        }

        public DiscoveryItemBuilder versioningStatus(String versioningStatus) {
            this.versioningStatus = versioningStatus;
            return this;
        }

        public DiscoveryItemBuilder statusTimestamp(String statusTimestamp) {
            this.statusTimestamp = statusTimestamp;
            return this;
        }

        public DiscoveryItemBuilder availableFormats(List<AvailableFormat> availableFormats) {
            this.availableFormats = availableFormats;
            return this;
        }

        public DiscoveryItemBuilder editorId(String editorId) {
            this.editorId = editorId;
            return this;
        }

        public DiscoveryItemBuilder editorFullName(String editorFullName) {
            this.editorFullName = editorFullName;
            return this;
        }

        public DiscoveryItemBuilder changeDate(LocalDateTime changeDate) {
            this.changeDate = changeDate;
            return this;
        }

        public DiscoveryItem build() {
            return new DiscoveryItem(this);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                availableFormats,
                description,
                href,
                hrefExtended,
                id,
                dataServiceProvider,
                status,
                statusTimestamp,
                title,
                uid,
                metaId,
                editorId,
                versioningStatus,
                editorFullName,
                changeDate);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        DiscoveryItem other = (DiscoveryItem) obj;
        return Objects.equals(availableFormats, other.availableFormats)
                && Objects.equals(description, other.description)
                && Objects.equals(href, other.href)
                && Objects.equals(hrefExtended, other.hrefExtended)
                && Objects.equals(id, other.id)
                && Objects.equals(status, other.status)
                && Objects.equals(statusTimestamp, other.statusTimestamp)
                && Objects.equals(versioningStatus, other.versioningStatus)
                && Objects.equals(title, other.title)
                && Objects.equals(dataServiceProvider, other.dataServiceProvider)
                && Objects.equals(uid, other.uid)
                && Objects.equals(metaId, other.metaId)
                && Objects.equals(editorId, other.editorId)
                && Objects.equals(editorFullName, other.editorFullName)
                && Objects.equals(changeDate, other.changeDate);
    }

    @Override
    public String toString() {
        return "DiscoveryItem{" +
                "href='" + href + '\'' +
                ", hrefExtended='" + hrefExtended + '\'' +
                ", id='" + id + '\'' +
                ", uid='" + uid + '\'' +
                ", metaId='" + metaId + '\'' +
                ", sha256id='" + sha256id + '\'' +
                ", dataprovider=" + dataprovider +
                ", facilityprovider=" + facilityprovider +
                ", serviceprovider=" + serviceprovider +
                ", categories=" + categories +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", status=" + status +
                ", dataServiceProvider=" + dataServiceProvider +
                ", versioningStatus='" + versioningStatus + '\'' +
                ", statusTimestamp='" + statusTimestamp + '\'' +
                ", availableFormats=" + availableFormats +
                ", editorId='" + editorId + '\'' +
                ", editorFullName='" + editorFullName + '\'' +
                ", changeDate=" + changeDate +
                '}';
    }

}
