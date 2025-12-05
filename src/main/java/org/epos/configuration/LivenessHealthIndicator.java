package org.epos.configuration;


import dao.EposDataModelDAO;
import model.Person;
import org.epos.api.routines.DatabaseConnections;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class LivenessHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        try {
            if(!DatabaseConnections.getInstance().getRouter().doHealthCheck()){
                return Health.down().withDetail("No Router Connection", 1).build();
            }
        }catch(Exception e){
            return Health.down().withDetail("No Router Connection", 1).build();
        }
        try {
            if(DatabaseConnections.getInstance().getPlugins().isEmpty()){
                return Health.down().withDetail("No Plugins Loaded", 1).build();
            }
        }catch(Exception e){
            return Health.down().withDetail("No Plugins Connection", 1).build();
        }
        try {
            EposDataModelDAO.getInstance().getAllFromDB(Person.class);
        }catch(Exception e){
            return Health.down().withDetail("No Database Connection", 1).build();
        }
        return Health.up().build();
    }
}
