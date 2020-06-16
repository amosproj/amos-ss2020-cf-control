package cloud.foundry.cli.crosscutting.mapping.beans;

import org.cloudfoundry.operations.services.ServiceInstance;
import org.cloudfoundry.operations.services.ServiceInstanceSummary;

import java.util.List;
import java.util.Map;

/**
 * Bean holding all data that is related to an instance of a service.
 */
public class ServiceBean implements Bean {

    private String service;
    private String plan;
    private List<String> tags;
    

    public ServiceBean(ServiceInstance serviceInstance) {
        this.service = serviceInstance.getService();
        this.plan = serviceInstance.getPlan();
        this.tags = serviceInstance.getTags();
    }

    public ServiceBean() {
    }

    public String getPlan() {
        return plan;
    }

    public void setPlan(String plan) {
        this.plan = plan;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    @Override
    public String toString() {
        return "ServiceBean{" +
                "service='" + service + '\'' +
                ", plan='" + plan + '\'' +
                ", tags=" + tags +
                '}';
    }
}
