package org.epos.api.beans;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.epos.api.facets.Node;

import model.StatusType;

public class Distribution implements Serializable {

	private static final long serialVersionUID = 1L;

	private String errorMessage;

	private transient String productid;
	private transient String distributionid;
	private String operationid;

	private String href;
	private String hrefExtended;

	private Node categories;

	private String id;
	private String uid;
	private String metaId;
	private String type;
	private String title;
	private String description;
	private String license;
	private String downloadURL;
	private List<String> keywords;
	private List<DataServiceProvider> dataProvider;
	private String frequencyUpdate;
	private List<String> internalID;
	private List<String> DOI;
	private SpatialInfo spatial;
	private TemporalCoverage temporalCoverage;
	private List<String> scienceDomain;
	private String qualityAssurance;
	private String accessRight;
	private List<AvailableFormat> availableFormats;
	private List<AvailableContactPoints> availableContactPoints;
	private StatusType versioningStatus;
	private String editorId;

	// WEBSERVICE

	private String serviceName;
	private String serviceDescription;
	private DataServiceProvider serviceProvider;
	private SpatialInfo serviceSpatial;
	private TemporalCoverage serviceTemporalCoverage;
	private String serviceEndpoint;
	private String serviceDocumentation;
	private List<String> serviceType;
	private String endpoint;

	private List<ServiceParameter> serviceParameters;

	public Distribution() {
	}

	public Distribution(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public Distribution(
			String productid,
			String distributionid,
			String operationid,
			String href,
			String id,
			String uid,
			String metaid,
			String type,
			String title,
			String description,
			String license,
			String downloadURL,
			List<String> keywords,
			List<DataServiceProvider> dataProvider,
			String frequencyUpdate,
			List<String> internalID,
			List<String> DOI,
			SpatialInfo spatial,
			TemporalCoverage temporalCoverage,
			List<AvailableFormat> availableFormats,
			String serviceName,
			String serviceDescription,
			DataServiceProvider serviceProvider,
			SpatialInfo serviceSpatial,
			TemporalCoverage serviceTemporalCoverage,
			String serviceEndpoint,
			String serviceDocumentation,
			String endpoint,
			List<ServiceParameter> serviceParameters,
			StatusType versioningStatus,
			String editorid) {
		super();
		this.productid = productid;
		this.distributionid = distributionid;
		this.operationid = operationid;
		this.href = href;
		this.id = id;
		this.uid = uid;
		this.metaId = metaid;
		this.type = type;
		this.title = title;
		this.description = description;
		this.license = license;
		this.downloadURL = downloadURL;
		this.keywords = keywords;
		this.dataProvider = dataProvider;
		this.frequencyUpdate = frequencyUpdate;
		this.internalID = internalID;
		this.DOI = DOI;
		this.spatial = spatial;
		this.temporalCoverage = temporalCoverage;
		this.availableFormats = availableFormats;
		this.serviceName = serviceName;
		this.serviceDescription = serviceDescription;
		this.serviceProvider = serviceProvider;
		this.serviceSpatial = serviceSpatial;
		this.serviceTemporalCoverage = serviceTemporalCoverage;
		this.serviceEndpoint = serviceEndpoint;
		this.serviceDocumentation = serviceDocumentation;
		this.endpoint = endpoint;
		this.serviceParameters = serviceParameters;
		this.versioningStatus = versioningStatus;
		this.editorId = editorid;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public String getProductid() {
		return productid;
	}

	public Node getCategories() {
		return categories;
	}

	public void setCategories(Node categories) {
		this.categories = categories;
	}

	public void setProductid(String productid) {
		this.productid = productid;
	}

	public String getDistributionid() {
		return distributionid;
	}

	public void setDistributionid(String distributionid) {
		this.distributionid = distributionid;
	}

	public String getOperationid() {
		return operationid;
	}

	public void setOperationid(String operationid) {
		this.operationid = operationid;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getUid() {
		return uid;
	}

	public void setUid(String uid) {
		this.uid = uid;
	}

	public String getMetaId() {
		return this.metaId;
	}

	public void setMetaId(String metaId) {
		this.metaId = metaId;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
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

	public String getLicense() {
		return license;
	}

	public void setLicense(String license) {
		this.license = license;
	}

	public String getDownloadURL() {
		return downloadURL;
	}

	public void setDownloadURL(String downloadURL) {
		this.downloadURL = downloadURL;
	}

	public List<String> getKeywords() {
		if (keywords == null)
			keywords = new ArrayList<String>();
		return keywords;
	}

	public void setKeywords(List<String> keywords) {
		this.keywords = keywords;
	}

	public List<DataServiceProvider> getDataProvider() {
		if (dataProvider == null)
			dataProvider = new ArrayList<DataServiceProvider>();
		return dataProvider;
	}

	public void setDataProvider(List<DataServiceProvider> dataServiceProvider) {
		this.dataProvider = dataServiceProvider;
	}

	public String getFrequencyUpdate() {
		return frequencyUpdate;
	}

	public void setFrequencyUpdate(String frequencyUpdate) {
		this.frequencyUpdate = frequencyUpdate;
	}

	public List<String> getInternalID() {
		if (internalID == null)
			internalID = new ArrayList<String>();
		return internalID;
	}

	public void setInternalID(List<String> internalID) {
		this.internalID = internalID;
	}

	public List<String> getDOI() {
		if (DOI == null)
			DOI = new ArrayList<String>();
		return DOI;
	}

	public void setDOI(List<String> dOI) {
		DOI = dOI;
	}

	public SpatialInfo getSpatial() {
		if (spatial == null)
			spatial = new SpatialInfo();
		return spatial;
	}

	public void setSpatial(SpatialInfo spatial) {
		this.spatial = spatial;
	}

	public TemporalCoverage getTemporalCoverage() {
		if (temporalCoverage == null)
			temporalCoverage = new TemporalCoverage();
		return temporalCoverage;
	}

	public void setTemporalCoverage(TemporalCoverage temporalCoverage) {
		this.temporalCoverage = temporalCoverage;
	}

	public List<AvailableFormat> getAvailableFormats() {
		if (availableFormats == null)
			availableFormats = new ArrayList<AvailableFormat>();
		return availableFormats;
	}

	public void setAvailableFormats(List<AvailableFormat> availableFormats) {
		this.availableFormats = availableFormats;
	}

	public List<AvailableContactPoints> getAvailableContactPoints() {
		if (availableContactPoints == null)
			availableContactPoints = new ArrayList<AvailableContactPoints>();
		return availableContactPoints;
	}

	public void setAvailableContactPoints(List<AvailableContactPoints> availableContactPoints) {
		this.availableContactPoints = availableContactPoints;
	}

	public String getServiceName() {
		return serviceName;
	}

	public void setServiceName(String serviceName) {
		this.serviceName = serviceName;
	}

	public String getServiceDescription() {
		return serviceDescription;
	}

	public void setServiceDescription(String serviceDescription) {
		this.serviceDescription = serviceDescription;
	}

	public DataServiceProvider getServiceProvider() {
		return serviceProvider;
	}

	public void setServiceProvider(DataServiceProvider serviceProvider) {
		this.serviceProvider = serviceProvider;
	}

	public SpatialInfo getServiceSpatial() {
		if (serviceSpatial == null)
			serviceSpatial = new SpatialInfo();
		return serviceSpatial;
	}

	public void setServiceSpatial(SpatialInfo serviceSpatial) {
		this.serviceSpatial = serviceSpatial;
	}

	public TemporalCoverage getServiceTemporalCoverage() {
		if (temporalCoverage == null)
			serviceTemporalCoverage = new TemporalCoverage();
		return serviceTemporalCoverage;
	}

	public void setServiceTemporalCoverage(TemporalCoverage serviceTemporalCoverage) {
		this.serviceTemporalCoverage = serviceTemporalCoverage;
	}

	public String getServiceEndpoint() {
		return serviceEndpoint;
	}

	public void setServiceEndpoint(String serviceEndpoint) {
		this.serviceEndpoint = serviceEndpoint;
	}

	public String getServiceDocumentation() {
		return serviceDocumentation;
	}

	public void setServiceDocumentation(String serviceDocumentation) {
		this.serviceDocumentation = serviceDocumentation;
	}

	public List<ServiceParameter> getParameters() {
		if (serviceParameters == null)
			serviceParameters = new ArrayList<ServiceParameter>();
		return serviceParameters;
	}

	public void setParameters(List<ServiceParameter> parameters) {
		this.serviceParameters = parameters;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
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

	public List<String> getScienceDomain() {
		if (scienceDomain == null)
			scienceDomain = new ArrayList<String>();
		return scienceDomain;
	}

	public void setScienceDomain(List<String> scienceDomain) {
		this.scienceDomain = scienceDomain;
	}

	public List<String> getServiceType() {
		if (serviceType == null)
			serviceType = new ArrayList<String>();
		return serviceType;
	}

	public void setServiceType(List<String> serviceType) {
		this.serviceType = serviceType;
	}

	public String getQualityAssurance() {
		return qualityAssurance;
	}

	public void setQualityAssurance(String qualityAssurance) {
		this.qualityAssurance = qualityAssurance;
	}

	public String getAccessRight() {
		return accessRight;
	}

	public void setAccessRight(String accessRight) {
		this.accessRight = accessRight;
	}

	public StatusType getVersioningStatus() {
		return versioningStatus;
	}

	public void setVersioningStatus(StatusType versioningStatus) {
		this.versioningStatus = versioningStatus;
	}

	public String getEditorId() {
		return this.editorId;
	}

	public void setEditorId(String editorid) {
		this.editorId = editorid;
	}

	@Override
	public int hashCode() {
		return Objects.hash(
				errorMessage,
				operationid,
				href,
				hrefExtended,
				categories,
				id,
				uid,
				metaId,
				type,
				title,
				description,
				license,
				downloadURL,
				keywords,
				dataProvider,
				frequencyUpdate,
				internalID,
				DOI,
				spatial,
				temporalCoverage,
				scienceDomain,
				qualityAssurance,
				accessRight,
				availableFormats,
				availableContactPoints,
				versioningStatus,
				editorId,
				serviceName,
				serviceDescription,
				serviceProvider,
				serviceSpatial,
				serviceTemporalCoverage,
				serviceEndpoint,
				serviceDocumentation,
				serviceType,
				endpoint,
				serviceParameters);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		Distribution that = (Distribution) o;
		return Objects.equals(errorMessage, that.errorMessage) &&
				Objects.equals(operationid, that.operationid) &&
				Objects.equals(href, that.href) &&
				Objects.equals(hrefExtended, that.hrefExtended) &&
				Objects.equals(categories, that.categories) &&
				Objects.equals(id, that.id) &&
				Objects.equals(uid, that.uid) &&
				Objects.equals(metaId, that.metaId) &&
				Objects.equals(type, that.type) &&
				Objects.equals(title, that.title) &&
				Objects.equals(description, that.description) &&
				Objects.equals(license, that.license) &&
				Objects.equals(downloadURL, that.downloadURL) &&
				Objects.equals(keywords, that.keywords) &&
				Objects.equals(dataProvider, that.dataProvider) &&
				Objects.equals(frequencyUpdate, that.frequencyUpdate) &&
				Objects.equals(internalID, that.internalID) &&
				Objects.equals(DOI, that.DOI) &&
				Objects.equals(spatial, that.spatial) &&
				Objects.equals(temporalCoverage, that.temporalCoverage) &&
				Objects.equals(scienceDomain, that.scienceDomain) &&
				Objects.equals(qualityAssurance, that.qualityAssurance) &&
				Objects.equals(accessRight, that.accessRight) &&
				Objects.equals(availableFormats, that.availableFormats) &&
				Objects.equals(availableContactPoints, that.availableContactPoints) &&
				Objects.equals(versioningStatus, that.versioningStatus) &&
				Objects.equals(editorId, that.editorId) &&
				Objects.equals(serviceName, that.serviceName) &&
				Objects.equals(serviceDescription, that.serviceDescription) &&
				Objects.equals(serviceProvider, that.serviceProvider) &&
				Objects.equals(serviceSpatial, that.serviceSpatial) &&
				Objects.equals(serviceTemporalCoverage, that.serviceTemporalCoverage) &&
				Objects.equals(serviceEndpoint, that.serviceEndpoint) &&
				Objects.equals(serviceDocumentation, that.serviceDocumentation) &&
				Objects.equals(serviceType, that.serviceType) &&
				Objects.equals(endpoint, that.endpoint) &&
				Objects.equals(serviceParameters, that.serviceParameters);
	}

	@Override
	public String toString() {
		return "Distribution [operationid=" + operationid + ", href=" + href + ", categories=" + categories + ", id="
				+ id + ", uid=" + uid + ", metaId=" + metaId + ", type=" + type + ", title=" + title + ", description="
				+ description
				+ ", license=" + license + ", downloadURL=" + downloadURL + ", keywords=" + keywords + ", dataProvider="
				+ dataProvider + ", frequencyUpdate=" + frequencyUpdate + ", internalID=" + internalID + ", DOI=" + DOI
				+ ", spatial=" + spatial + ", temporalCoverage=" + temporalCoverage + ", scienceDomain=" + scienceDomain
				+ ", hasQualityAnnotation=" + qualityAssurance + ", accessRight=" + accessRight +", availableFormats=" + availableFormats
				+ ", availableContactPoints=" + availableContactPoints + ", serviceName=" + serviceName
				+ ", serviceDescription=" + serviceDescription + ", serviceProvider=" + serviceProvider
				+ ", serviceSpatial=" + serviceSpatial + ", serviceTemporalCoverage=" + serviceTemporalCoverage
				+ ", serviceEndpoint=" + serviceEndpoint + ", serviceDocumentation=" + serviceDocumentation
				+ ", serviceType=" + serviceType + ", endpoint=" + endpoint + ", serviceParameters=" + serviceParameters
				+ ", versioningStatus=" + versioningStatus
				+ "]";
	}
}
