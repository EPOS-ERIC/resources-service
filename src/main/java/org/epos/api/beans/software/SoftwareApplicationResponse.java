package org.epos.api.beans.software;

import java.util.ArrayList;
import java.util.List;

import org.epos.api.beans.AvailableContactPoints;
import org.epos.api.beans.AvailableFormat;
import org.epos.api.facets.Node;
import org.epos.eposdatamodel.SoftwareApplication;

public class SoftwareApplicationResponse {

	private String name;
	private String description;
	private String downloadURL;
	private String installURL;
	private String licenseURL;
	private String mainEntityOfPage;
	private String softwareRequirements;
	private String softwareVersion;
	private String softwareStatus;
	private String spatial;
	private String temporal;
	private String fileSize;
	private String timeRequired;
	private String processorRequirements;
	private String memoryRequirements;
	private String storageRequirements;
	private List<String> citation;
	private List<String> operatingSystem;
	private List<String> keywords;
	private String id;
	private List<String> doi;
	private List<String> identifiers;
	private List<AvailableContactPoints> availableContactPoints;
    private List<AvailableFormat> availableFormats;
	private Node categories;

	public SoftwareApplicationResponse(SoftwareApplication softwareApplication) {
		this.name = softwareApplication.getName();
		this.description = softwareApplication.getDescription();
		this.downloadURL = softwareApplication.getDownloadURL();
		this.installURL = softwareApplication.getInstallURL();
		this.licenseURL = softwareApplication.getLicenseURL();
		this.mainEntityOfPage = softwareApplication.getMainEntityOfPage();
		this.softwareRequirements = softwareApplication.getRequirements();
		this.softwareVersion = softwareApplication.getSoftwareVersion();
		this.softwareStatus = softwareApplication.getSoftwareStatus();
		this.spatial = softwareApplication.getSpatial();
		this.temporal = softwareApplication.getTemporal();
		this.fileSize = softwareApplication.getFileSize();
		this.timeRequired = softwareApplication.getTimeRequired();
		this.processorRequirements = softwareApplication.getProcessorRequirements();
		this.memoryRequirements = softwareApplication.getMemoryrequirements();
		this.storageRequirements = softwareApplication.getStorageRequirements();
		this.citation = softwareApplication.getCitation();
		this.operatingSystem = softwareApplication.getOperatingSystem();
		// Keywords will be set by the generation class
		this.keywords = null;
		this.id = softwareApplication.getInstanceId();
		this.availableContactPoints = new ArrayList<>();
	}

	public SoftwareApplicationResponse() {
		this.availableContactPoints = new ArrayList<>();
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

	public String getDownloadURL() {
		return downloadURL;
	}

	public void setDownloadURL(String downloadURL) {
		this.downloadURL = downloadURL;
	}

	public String getInstallURL() {
		return installURL;
	}

	public void setInstallURL(String installURL) {
		this.installURL = installURL;
	}

	public String getLicenseURL() {
		return licenseURL;
	}

	public void setLicenseURL(String licenseURL) {
		this.licenseURL = licenseURL;
	}

	public String getMainEntityOfPage() {
		return mainEntityOfPage;
	}

	public void setMainEntityOfPage(String mainEntityOfPage) {
		this.mainEntityOfPage = mainEntityOfPage;
	}

	public String getSoftwareRequirements() {
		return softwareRequirements;
	}

	public void setSoftwareRequirements(String softwareRequirements) {
		this.softwareRequirements = softwareRequirements;
	}

	public String getSoftwareVersion() {
		return softwareVersion;
	}

	public void setSoftwareVersion(String softwareVersion) {
		this.softwareVersion = softwareVersion;
	}

	public String getSoftwareStatus() {
		return softwareStatus;
	}

	public void setSoftwareStatus(String softwareStatus) {
		this.softwareStatus = softwareStatus;
	}

	public String getSpatial() {
		return spatial;
	}

	public void setSpatial(String spatial) {
		this.spatial = spatial;
	}

	public String getTemporal() {
		return temporal;
	}

	public void setTemporal(String temporal) {
		this.temporal = temporal;
	}

	public String getFileSize() {
		return fileSize;
	}

	public void setFileSize(String fileSize) {
		this.fileSize = fileSize;
	}

	public String getTimeRequired() {
		return timeRequired;
	}

	public void setTimeRequired(String timeRequired) {
		this.timeRequired = timeRequired;
	}

	public String getProcessorRequirements() {
		return processorRequirements;
	}

	public void setProcessorRequirements(String processorRequirements) {
		this.processorRequirements = processorRequirements;
	}

	public String getMemoryRequirements() {
		return memoryRequirements;
	}

	public void setMemoryRequirements(String memoryRequirements) {
		this.memoryRequirements = memoryRequirements;
	}

	public String getStorageRequirements() {
		return storageRequirements;
	}

	public void setStorageRequirements(String storageRequirements) {
		this.storageRequirements = storageRequirements;
	}

	public List<String> getCitation() {
		return citation;
	}

	public void setCitation(List<String> citation) {
		this.citation = citation;
	}

	public List<String> getOperatingSystem() {
		return operatingSystem;
	}

	public void setOperatingSystem(List<String> operatingSystem) {
		this.operatingSystem = operatingSystem;
	}

	public List<String> getKeywords() {
		return keywords;
	}

	public void setKeywords(List<String> keywords) {
		this.keywords = keywords;
	}

	public String getId() {
		return this.id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public List<String> getDoi() {
		return doi;
	}

	public void setDoi(List<String> doi) {
		this.doi = doi;
	}

	public List<String> getIdentifiers() {
		return identifiers;
	}

	public void setIdentifiers(List<String> identifiers) {
		this.identifiers = identifiers;
	}

	public List<AvailableContactPoints> getAvailableContactPoints() {
		return availableContactPoints;
	}

	public void setAvailableContactPoints(List<AvailableContactPoints> availableContactPoints) {
		this.availableContactPoints = availableContactPoints;
	}

	public Node getCategories() {
		return categories;
	}

	public void setCategories(Node categories) {
		this.categories = categories;
	}

    public List<AvailableFormat> getAvailableFormats() {
        return availableFormats;
    }

    public void setAvailableFormats(List<AvailableFormat> availableFormats) {
        this.availableFormats = availableFormats;
    }
}
