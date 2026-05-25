package org.epos.api.core.organizations;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.epos.api.beans.OrganizationBean;
import org.epos.handler.dbapi.service.EntityManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class OrganisationsGenerationSQL {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrganisationsGenerationSQL.class);

    public static List<OrganizationBean> generate(Map<String, Object> parameters) {
        LOGGER.info("Requests start - SQL method");

        EntityManager em = null;
        try {
            em = EntityManagerService.getInstance().createEntityManager();

            StringBuilder sql = new StringBuilder();
            sql.append("SELECT DISTINCT o.instance_id, o.logo, o.url, o.legalname, a.country ")
                    .append("FROM metadata_catalogue.organization o ")
                    .append("LEFT JOIN metadata_catalogue.address a ON o.address_id = a.instance_id ")
                    .append("WHERE o.legalname IS NOT NULL ");

            List<Object> params = new ArrayList<>();

            if (parameters.containsKey("id")) {
                sql.append("AND o.instance_id = ? ");
                params.add(parameters.get("id").toString());
            }

            if (parameters.containsKey("q")) {
                String q = parameters.get("q").toString().trim().toLowerCase(Locale.ROOT);
                if (!q.isEmpty()) {
                    sql.append("AND (LOWER(o.legalname) LIKE ? OR LOWER(COALESCE(o.acronym, '')) LIKE ? OR LOWER(COALESCE(o.uid, '')) LIKE ?) ");
                    String likeValue = "%" + q + "%";
                    params.add(likeValue);
                    params.add(likeValue);
                    params.add(likeValue);
                }
            }

            if (parameters.containsKey("country")) {
                sql.append("AND LOWER(COALESCE(a.country, '')) = ? ");
                params.add(parameters.get("country").toString().toLowerCase(Locale.ROOT));
            }

            if (parameters.containsKey("type")) {
                String type = parameters.get("type").toString().toLowerCase(Locale.ROOT);
                List<String> tokens = Arrays.stream(type.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();

                boolean hasDataProviders = tokens.contains("dataproviders");
                boolean hasServiceProviders = tokens.contains("serviceproviders");

                if (hasDataProviders || hasServiceProviders) {
                    sql.append("AND o.instance_id IN (")
                            .append("WITH seed_organizations AS (");

                    boolean hasPrevious = false;
                    if (hasDataProviders) {
                        sql.append("SELECT DISTINCT dpp.organization_instance_id AS instance_id ")
                                .append("FROM metadata_catalogue.distribution_dataproduct ddp ")
                                .append("JOIN metadata_catalogue.dataproduct_publisher dpp ON ddp.dataproduct_instance_id = dpp.dataproduct_instance_id ");
                        hasPrevious = true;
                    }
                    if (hasServiceProviders) {
                        if (hasPrevious) {
                            sql.append("UNION ");
                        }
                        sql.append("SELECT DISTINCT ws.provider AS instance_id ")
                                .append("FROM metadata_catalogue.webservice_distribution wd ")
                                .append("JOIN metadata_catalogue.webservice ws ON wd.webservice_instance_id = ws.instance_id ")
                                .append("WHERE ws.provider IS NOT NULL ");
                    }

                    sql.append("), related_organizations AS (")
                            .append("SELECT so.instance_id FROM seed_organizations so ")
                            .append("UNION ")
                            .append("SELECT CASE ")
                            .append("WHEN om.organization1_instance_id = so.instance_id THEN om.organization2_instance_id ")
                            .append("ELSE om.organization1_instance_id END AS instance_id ")
                            .append("FROM metadata_catalogue.organization_memberof om ")
                            .append("JOIN seed_organizations so ")
                            .append("ON om.organization1_instance_id = so.instance_id OR om.organization2_instance_id = so.instance_id ")
                            .append(") SELECT DISTINCT instance_id FROM related_organizations) ");
                }
            }

            sql.append("ORDER BY o.legalname");

            Query query = em.createNativeQuery(sql.toString());
            for (int i = 0; i < params.size(); i++) {
                query.setParameter(i + 1, params.get(i));
            }

            List<Object[]> rows = query.getResultList();
            List<OrganizationBean> result = new ArrayList<>();

            for (Object[] row : rows) {
                String instanceId = row[0] != null ? row[0].toString() : null;
                String logo = row[1] != null ? row[1].toString() : null;
                String url = row[2] != null ? row[2].toString() : null;
                String legalName = row[3] != null ? row[3].toString() : null;
                String country = row[4] != null ? row[4].toString() : null;

                if (instanceId != null && legalName != null && !legalName.isBlank()) {
                    result.add(new OrganizationBean(instanceId, logo, url, legalName, country));
                }
            }

            return result;
        } finally {
            if (em != null) {
                em.close();
            }
        }
    }
}
