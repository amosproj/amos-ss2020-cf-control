package cloud.foundry.cli.logic.apply;

import static com.google.common.base.Preconditions.checkNotNull;

import cloud.foundry.cli.crosscutting.exceptions.ApplyException;
import cloud.foundry.cli.crosscutting.logging.Log;
import cloud.foundry.cli.crosscutting.mapping.beans.ApplicationBean;
import cloud.foundry.cli.crosscutting.mapping.beans.ApplicationManifestBean;
import cloud.foundry.cli.crosscutting.mapping.validation.Field;
import cloud.foundry.cli.crosscutting.mapping.validation.ObjectPropertyValidation;
import cloud.foundry.cli.crosscutting.mapping.validation.ScalarField;
import cloud.foundry.cli.logic.diff.change.CfChange;
import cloud.foundry.cli.logic.diff.change.ChangeType;
import cloud.foundry.cli.logic.diff.change.container.CfContainerChange;
import cloud.foundry.cli.logic.diff.change.container.CfContainerValueChanged;
import cloud.foundry.cli.logic.diff.change.map.CfMapChange;
import cloud.foundry.cli.logic.diff.change.map.CfMapValueChanged;
import cloud.foundry.cli.logic.diff.change.object.CfNewObject;
import cloud.foundry.cli.logic.diff.change.object.CfRemovedObject;
import cloud.foundry.cli.operations.ApplicationsOperations;
import org.cloudfoundry.operations.applications.ApplicationHealthCheck;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.function.Predicate;

/**
 * This class is responsible to build the requests in the context of
 * applications according to the CfChanges. The class does create the request
 * tasks by implementing the {@link CfChangeVisitor} interface.
 */
public class ApplicationRequestsPlaner {

    private static final Log log = Log.getLog(ApplicationRequestsPlaner.class);

    private static final Map<String, Field> FIELDS_REQUIRE_RESTART = new HashMap<String, Field>() {{
        put("meta", new ScalarField(ApplicationBean.class, "meta", String.class));
        put("path", new ScalarField(ApplicationBean.class, "path", String.class));
        put("buildpack", new ScalarField(ApplicationManifestBean.class, "buildpack", String.class));
        put("command", new ScalarField(ApplicationManifestBean.class, "command", String.class));
        put("stack", new ScalarField(ApplicationManifestBean.class, "stack", String.class));
        put("healthCheckType", new ScalarField(ApplicationManifestBean.class,
                "healthCheckType",
                ApplicationHealthCheck.class));
        put("healthCheckHttpEndpoint",
                new ScalarField(ApplicationManifestBean.class,
                "healthCheckHttpEndpoint",
                        String.class));
        put("memory", new ScalarField(ApplicationManifestBean.class, "memory", Integer.class));
        put("disk", new ScalarField(ApplicationManifestBean.class, "disk", Integer.class));
    }};

    static {
        ObjectPropertyValidation.checkPropertiesExist(FIELDS_REQUIRE_RESTART.values());
    }

    private final ApplicationsOperations appOperations;
    private String applicationName;

    /**
     *
     * @param appOperations the ApplicationOperations object used for
     */
    public ApplicationRequestsPlaner(ApplicationsOperations appOperations) {
        this.appOperations = appOperations;
    }

    /**
     * Creates the remove/create requests for one application.
     *
     * @param applicationName    the name of the application
     * @param applicationChanges a list with all the Changes found during diff for
     *                           that specific application
     * @throws NullPointerException if any of the arguments are null
     * @throws ApplyException if during the planing process a non recoverable error occurs
     * @return flux of all requests that are required to apply the changes
     */
    public Flux<Void> createApplyRequests(@Nonnull String applicationName,
        @Nonnull List<CfChange> applicationChanges) {
        checkNotNull(applicationName);
        checkNotNull(applicationChanges);

        try {
            this.applicationName = applicationName;
            return this.doCreateApplyRequests(applicationChanges);
        } catch (Exception exception) {
            throw new ApplyException(exception);
        }
    }

    private Flux<Void> doCreateApplyRequests(List<CfChange> changes) {
        List<Publisher<Void>> requests = new LinkedList<>();

        if (hasNewObject(changes)) {
            log.debug("Add create app request for app: " + applicationName);

            ApplicationBean bean = (ApplicationBean) getChange(changes, change -> change instanceof CfNewObject)
                    .get()
                    .getAffectedObject();

            return Flux.merge(this.appOperations.create(applicationName, bean, false));
        } else if (hasRemovedObject(changes)) {
            log.debug("Add remove app request for app: " + applicationName);

            return Flux.merge(this.appOperations.remove(applicationName));
        } else if (hasFieldsThatRequireRestart(changes)) {
            log.debug("Add redeploying update request for app: " + applicationName);

            for (CfChange change : changes) {
                logChange(change);
            }

            ApplicationBean bean = (ApplicationBean) changes.get(0).getAffectedObject();
            return Flux.concat(appOperations.update(applicationName, bean, false));
        } else if (changes.size() > 0) {
            log.debug("Add rolling update requests for app: " + applicationName);
            requests.add(getScaleInstancesRequest(changes));
            requests.add(getChangedEnvironmentVariablesRequests(changes));
            requests.add(getChangedServicesRequests(changes));
            requests.add(getChangedRoutesRequests(changes));
        }

        return Flux.concat(requests);
    }

    private boolean hasFieldsThatRequireRestart(List<CfChange> changes) {
        return changes.stream().anyMatch(change -> FIELDS_REQUIRE_RESTART.containsKey(change.getPropertyName()));
    }

    private boolean hasRemovedObject(List<CfChange> changes) {
        return changes.stream().anyMatch(change -> change instanceof CfRemovedObject);
    }

    private boolean hasNewObject(List<CfChange> changes) {
        return changes.stream().anyMatch(change -> change instanceof CfNewObject);
    }

    private Flux<Void> getChangedServicesRequests(List<CfChange> changes) {
        Optional<CfChange> optionalServicesChange = getChange(changes,
                change -> change.getPropertyName().equals("services"));
        List<Mono<Void>> requests = new LinkedList<>();

        if (optionalServicesChange.isPresent()) {

            CfContainerChange servicesChange = (CfContainerChange) optionalServicesChange.get();
            logChange(servicesChange);

            for (CfContainerValueChanged valueChanged : servicesChange.getValueChangesBy(ChangeType.ADDED)) {
                log.debug("Adding request to bind service",
                        valueChanged.getValue(),
                        "to application",
                        applicationName);
                requests.add(this.appOperations.bindToService(applicationName, valueChanged.getValue()));
            }

            for (CfContainerValueChanged valueChanged : servicesChange.getValueChangesBy(ChangeType.REMOVED)) {
                log.debug("Adding request to unbind service",
                        valueChanged.getValue(),
                        "from application",
                        applicationName);
                requests.add(this.appOperations.unbindFromService(applicationName, valueChanged.getValue()));
            }

        }
        return Flux.merge(requests);
    }

    private Flux<Void> getChangedEnvironmentVariablesRequests(List<CfChange> changes) {
        Optional<CfChange> optionalEnvVarsChange = getChange(changes,
                change -> change.getPropertyName().equals("environmentVariables"));

        if (optionalEnvVarsChange.isPresent()) {
            List<Mono<Void>> requests = new LinkedList<>();
            CfMapChange enVarsChange = (CfMapChange) optionalEnvVarsChange.get();
            logChange(enVarsChange);

            for (CfMapValueChanged valueChanged : enVarsChange.getChangedValues()) {
                switch (valueChanged.getChangeType()) {
                    case ADDED:
                        log.debug("Adding request to add environment variable",
                                valueChanged.getKey(),
                                "with value",
                                valueChanged.getValueAfter(),
                                "to application",
                                applicationName);
                        requests.add(this.appOperations.addEnvironmentVariable(applicationName,
                                valueChanged.getKey(),
                                valueChanged.getValueAfter()));
                        break;
                    case CHANGED:
                        log.debug("Adding request to change environment variable",
                                valueChanged.getKey(),
                                "from value",
                                valueChanged.getValueBefore(),
                                "to value",
                                valueChanged.getValueAfter(),
                                "for  application",
                                applicationName);
                        requests.add(this.appOperations.addEnvironmentVariable(applicationName,
                                valueChanged.getKey(),
                                valueChanged.getValueAfter()));
                        break;
                    case REMOVED:
                        log.debug("Adding request to remove environment variable",
                                valueChanged.getKey(),
                                "from application",
                                applicationName);
                        requests.add(this.appOperations.removeEnvironmentVariable(applicationName,
                                valueChanged.getKey()));
                        break;
                    default:
                        throw new AssertionError("Encountered an unknown change type");
                }
            }
            return Flux.concat(requests);
        }
        return Flux.empty();
    }


    private Publisher<Void> getChangedRoutesRequests(List<CfChange> changes) {
        Optional<CfChange> optionalRoutesChange = getChange(changes,
                change -> change.getPropertyName().equals("routes"));
        List<Mono<Void>> requests = new LinkedList<>();

        if (optionalRoutesChange.isPresent()) {
            CfContainerChange routesChanges = (CfContainerChange) optionalRoutesChange.get();
            logChange(routesChanges);

            for (CfContainerValueChanged valueChanged : routesChanges.getValueChangesBy(ChangeType.ADDED)) {
                log.debug("Adding request to add route",
                        valueChanged.getValue(),
                        "to application",
                        applicationName);
                requests.add(this.appOperations.addRoute(applicationName, valueChanged.getValue()));
            }

            for (CfContainerValueChanged valueChanged : routesChanges.getValueChangesBy(ChangeType.REMOVED)) {
                log.debug("Adding request to remove route",
                        valueChanged.getValue(),
                        "from application",
                        applicationName);
                requests.add(this.appOperations.removeRoute(applicationName, valueChanged.getValue()));
            }

        }
        return Flux.concat(requests);
    }


    private Mono<Void> getScaleInstancesRequest(List<CfChange> changes) {
        Optional<CfChange> instancesChange = getChange(changes,
                change -> change.getPropertyName().equals("instances"));

        if (instancesChange.isPresent()) {
            logChange(instancesChange.get());

            ApplicationBean bean = (ApplicationBean) instancesChange.get().getAffectedObject();
            return this.appOperations.scale(applicationName,
                    null,
                    null,
                    bean.getManifest().getInstances());
        }
        return Mono.empty();
    }

    private Optional<CfChange> getChange(List<CfChange> changes, Predicate<CfChange> predicate) {
        return changes
                .stream()
                .filter(predicate)
                .findFirst();
    }

    private void logChange(CfChange change) {
        log.debug("Property <" + change.getPropertyName() + "> for app <" + applicationName + "> will be updated.");
    }
}
