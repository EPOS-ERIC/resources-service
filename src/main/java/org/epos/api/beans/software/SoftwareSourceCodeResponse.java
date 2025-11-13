package org.epos.api.beans.software;

import java.util.ArrayList;
import java.util.List;

import org.epos.api.beans.AvailableContactPoints;
import org.epos.api.beans.AvailableFormat;
import org.epos.api.facets.Node;
import org.epos.eposdatamodel.SoftwareSourceCode;

public class SoftwareSourceCodeResponse {

	private String name;
	private String description;
	private String codeRepository;
	private String downloadURL;
	private String licenseURL;
	private String mainEntityOfPage;
	private String runtimePlatform;
	private String softwareVersion;
	private String softwareStatus;
	private String spatial;
	private String temporal;
	private String size;
	private String timeRequired;
	private List<String> programmingLanguage;
	private List<String> keywords;
	private List<String> citation;
	private String id;
	private List<String> doi;
	private List<String> identifiers;
	private List<AvailableContactPoints> availableContactPoints;
    private List<AvailableFormat> availableFormats;
	private Node categories;

	public SoftwareSourceCodeResponse(SoftwareSourceCode softwareSourceCode) {
		this.name = softwareSourceCode.getName();
		this.description = softwareSourceCode.getDescription();
		this.codeRepository = softwareSourceCode.getCodeRepository();
		this.downloadURL = softwareSourceCode.getDownloadURL();
		this.licenseURL = softwareSourceCode.getLicenseURL();
		this.mainEntityOfPage = softwareSourceCode.getMainEntityofPage();
		this.programmingLanguage = softwareSourceCode.getProgrammingLanguage();
		this.runtimePlatform = softwareSourceCode.getRuntimePlatform();
		this.softwareVersion = softwareSourceCode.getSoftwareVersion();
		this.softwareStatus = softwareSourceCode.getSoftwareStatus();
		this.spatial = softwareSourceCode.getSpatial();
		this.temporal = softwareSourceCode.getTemporal();
		// Keywords will be set by the generation class
		this.keywords = null;
		this.citation = softwareSourceCode.getCitation();
		this.size = softwareSourceCode.getSize();
		this.timeRequired = softwareSourceCode.getTimeRequired();
		this.id = softwareSourceCode.getInstanceId();
		this.availableContactPoints = new ArrayList<>();
	}

	public SoftwareSourceCodeResponse() {
		this.availableContactPoints = new ArrayList<>();
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

	public String getCodeRepository() {
		return codeRepository;
	}

	public void setCodeRepository(String codeRepository) {
		this.codeRepository = codeRepository;
	}

	public String getDownloadURL() {
		return downloadURL;
	}

	public void setDownloadURL(String downloadURL) {
		this.downloadURL = downloadURL;
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

	public List<String> getProgrammingLanguage() {
		return programmingLanguage;
	}

	public void setProgrammingLanguage(List<String> programmingLanguage) {
		this.programmingLanguage = programmingLanguage;
	}

	public String getRuntimePlatform() {
		return runtimePlatform;
	}

	public void setRuntimePlatform(String runtimePlatform) {
		this.runtimePlatform = runtimePlatform;
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

	public List<String> getKeywords() {
		return keywords;
	}

	public void setKeywords(List<String> keywords) {
		this.keywords = keywords;
	}

	public List<String> getCitation() {
		return citation;
	}

	public void setCitation(List<String> citation) {
		this.citation = citation;
	}

	public String getSize() {
		return size;
	}

	public void setSize(String size) {
		this.size = size;
	}

	public String getTimeRequired() {
		return timeRequired;
	}

	public void setTimeRequired(String timeRequired) {
		this.timeRequired = timeRequired;
	}

	public String getId() {
		return this.id;
	}

	public void setId(String id) {
		this.id = id;
	}

    public List<AvailableFormat> getAvailableFormats() {
        return availableFormats;
    }

    public void setAvailableFormats(List<AvailableFormat> availableFormats) {
        this.availableFormats = availableFormats;
    }
}
