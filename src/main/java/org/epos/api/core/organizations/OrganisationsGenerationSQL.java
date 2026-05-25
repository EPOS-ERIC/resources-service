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
                    sql.append("AND (");
                    boolean hasPrevious = false;
                    if (hasDataProviders) {
                        sql.append("EXISTS (SELECT 1 FROM metadata_catalogue.dataproduct_publisher dpp ")
                                .append("WHERE dpp.organization_instance_id = o.instance_id)");
                        hasPrevious = true;
                    }
                    if (hasServiceProviders) {
                        if (hasPrevious) {
                            sql.append(" OR ");
                        }
                        sql.append("EXISTS (SELECT 1 FROM metadata_catalogue.webservice ws ")
                                .append("WHERE ws.provider = o.instance_id)");
                    }
                    sql.append(") ");
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
