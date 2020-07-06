package cloud.foundry.cli.operations;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import cloud.foundry.cli.crosscutting.logging.Log;
import cloud.foundry.cli.crosscutting.mapping.beans.ApplicationBean;
import cloud.foundry.cli.crosscutting.exceptions.CreationException;
import cloud.foundry.cli.crosscutting.mapping.beans.ApplicationManifestBean;

import org.cloudfoundry.client.v3.applications.GetApplicationRequest;
import org.cloudfoundry.client.v3.applications.GetApplicationResponse;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.ApplicationSummary;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.Docker;
import org.cloudfoundry.operations.applications.GetApplicationManifestRequest;
import org.cloudfoundry.operations.applications.PushApplicationManifestRequest;
import org.cloudfoundry.operations.applications.Route;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Handles the operations for querying and manipulating applications on a cloud
 * foundry instance.
 *
 * To retrieve the data from resulting Mono or Flux objects you can use
 * subscription methods (block, subscribe, etc.) provided by the reactor
 * library. For more details on how to work with Mono's visit:
 * https://projectreactor.io/docs/core/release/reference/index.html#core-features
 */
public class ApplicationsOperations extends AbstractOperations<DefaultCloudFoundryOperations> {

    private static final Log log = Log.getLog(ApplicationsOperations.class);

    /**
     * Name of the environment variable that hold the docker password.
     */
    private static final String DOCKER_PASSWORD_VAR_NAME = "CF_DOCKER_PASSWORD";

    /**
     * Name of the key of label for metadata.
     */
    private static final String METADATA_KEY = "CF_METADATA_KEY";

    public ApplicationsOperations(DefaultCloudFoundryOperations cloudFoundryOperations) {
        super(cloudFoundryOperations);
    }

    /**
     * Prepares a request for fetching applications data from the cloud foundry
     * instance. The resulting mono will not perform any logging by default.
     *
     * @return mono object of all applications as map of the application names as
     *         key and the ApplicationBeans as value
     */
    public Mono<Map<String, ApplicationBean>> getAll() {
        // Flux.zip(..) zips Mono<ApplicationManifest> and Mono<String> together as
        // a Flux<Tuple2<ApplicationManifest, String>>
        // collectMap(..) converts each Tuple2<ApplicationManifest, String> into a
        // Mono<Map<String, ApplicationBean>>
        // because flatMap returns Flux<Map<String, ApplicationBean>>
        // reduce(..) helps to convert from Flux<Map<String, ApplicationBean>> into
        // Mono<Map<String, ApplicationBean>>
        return this.cloudFoundryOperations
            .applications()
            .list()
            .flatMap(applicationSummary -> Flux.zip(
                getApplicationManifest(applicationSummary),
                getMetadata(applicationSummary))
                .collectMap(tuple -> tuple.getT1().getName(),
                    tuple -> new ApplicationBean(tuple.getT1(), tuple.getT2())))
            .reduce(new HashMap<String, ApplicationBean>(), (newMap, currentMap) -> {
                newMap.putAll(currentMap);
                return newMap;
            });
    }

    private Mono<ApplicationManifest> getApplicationManifest(ApplicationSummary applicationSummary) {
        return this.cloudFoundryOperations
            .applications()
            .getApplicationManifest(GetApplicationManifestRequest
                .builder()
                .name(applicationSummary.getName())
                .build());

    }

    private Mono<String> getMetadata(ApplicationSummary applicationSummary) {

        GetApplicationRequest request = GetApplicationRequest.builder()
            .applicationId(applicationSummary.getId())
            .build();
        return this.cloudFoundryOperations.getCloudFoundryClient()
            .applicationsV3()
            .get(request)
            .flatMap(this::doGetMetadata);
    }

    private Mono<String> doGetMetadata(GetApplicationResponse getApplicationResponse) {
        Map<String, String> labels = getApplicationResponse.getMetadata().getLabels();
        return Mono.just(labels.get(METADATA_KEY));
    }

    /**
     * Prepares a request for deleting a specific application associated with the
     * provided name. The resulting mono is preconfigured such that it will perform
     * logging.
     *
     * @param applicationName applicationName Name of an application.
     * @throws NullPointerException when the applicationName is null
     * @return mono which can be subscribed on to trigger the removal of the app.
     *         The mono also handles the logging.
     */
    public Mono<Void> remove(String applicationName) {
        checkNotNull(applicationName);

        DeleteApplicationRequest request = DeleteApplicationRequest
            .builder()
            .name(applicationName)
            .build();

        return this.cloudFoundryOperations.applications()
            .delete(request)
            .doOnSuccess(aVoid -> log.info("App removed: ", applicationName))
            .onErrorStop();
    }

    /**
     * Prepares a request for pushing an app to the cloud foundry instance specified
     * within the cloud foundry operations instance. The resulting mono is
     * preconfigured such that it will perform logging.
     *
     * @param appName     name of the application
     * @param bean        application bean that holds the configuration settings to
     *                    deploy the app to the cloud foundry instance
     * @param shouldStart if the app should start after being created
     * @throws NullPointerException     when bean or app name is null or docker
     *                                  password was not set in environment
     *                                  variables when creating app via dockerImage
     *                                  and docker credentials
     * @throws IllegalArgumentException when app name empty
     * @throws CreationException        when any fatal error occurs during creation
     *                                  of the app
     * @throws SecurityException        when there is no permission to access
     *                                  environment variable CF_DOCKER_PASSWORD
     * @return mono which can be subscribed on to trigger the creation of the app
     */
    public Mono<Void> create(String appName, ApplicationBean bean, boolean shouldStart) {
        checkNotNull(appName);
        checkArgument(!appName.isEmpty(), "empty name");
        checkNotNull(bean);

        try {
            return doCreate(appName, bean, shouldStart);
        } catch (RuntimeException e) {
            throw new CreationException(e);
        }
    }

    private Mono<Void> doCreate(String appName, ApplicationBean bean, boolean shouldStart) {
        return this.cloudFoundryOperations
            .applications()
            .pushManifest(PushApplicationManifestRequest
                .builder()
                .manifest(buildApplicationManifest(appName, bean))
                .noStart(!shouldStart)
                .build())
            .onErrorContinue(this::whenServiceNotFound, log::warning)
            .doOnSubscribe(subscription -> {
                log.debug("Create app:", appName);
                log.debug("Bean of the app:", bean);
                log.debug("Should the app start:", shouldStart);
            })
            .doOnSuccess(aVoid -> log.info("App created:", appName))
            .onErrorStop();
    }

    private boolean whenServiceNotFound(Throwable throwable) {
        return throwable instanceof IllegalArgumentException
            && throwable.getMessage().contains("Service instance")
            && throwable.getMessage().contains("could not be found");
    }

    private ApplicationManifest buildApplicationManifest(String appName, ApplicationBean bean) {
        if (bean.getManifest() == null) {
            bean.setManifest(new ApplicationManifestBean());
        }

        return ApplicationManifest.builder()
            .name(appName)
            .path(bean.getPath() == null ? null : Paths.get(bean.getPath()))
            .buildpack(bean.getManifest().getBuildpack())
            .command(bean.getManifest().getCommand())
            .disk(bean.getManifest().getDisk())
            .docker(Docker.builder()
                .image(bean.getPath() == null && bean.getManifest().getDockerImage() == null
                    ? ""
                    : bean.getManifest().getDockerImage())
                .username(bean.getManifest().getDockerUsername())
                .password(getDockerPassword(bean.getManifest()))
                .build())
            .healthCheckHttpEndpoint(bean.getManifest().getHealthCheckHttpEndpoint())
            .healthCheckType(bean.getManifest().getHealthCheckType())
            .instances(bean.getManifest().getInstances())
            .memory(bean.getManifest().getMemory())
            .noRoute(bean.getManifest().getNoRoute())
            .routePath(bean.getManifest().getRoutePath())
            .randomRoute(bean.getManifest().getRandomRoute())
            .routes(getAppRoutes(bean.getManifest().getRoutes()))
            .stack(bean.getManifest().getStack())
            .timeout(bean.getManifest().getTimeout())
            .putAllEnvironmentVariables(Optional.ofNullable(bean.getManifest().getEnvironmentVariables())
                .orElse(Collections.emptyMap()))
            .services(bean.getManifest().getServices())
            .build();
    }

    private String getDockerPassword(ApplicationManifestBean bean) {
        if (bean.getDockerImage() == null || bean.getDockerUsername() == null) {
            return null;
        }

        // TODO: Maybe outsource retrieving env variables to a dedicated class in a
        // future feature.
        String password = System.getenv(DOCKER_PASSWORD_VAR_NAME);
        if (password == null) {
            throw new NullPointerException("Docker password is not set in environment variable: "
                + DOCKER_PASSWORD_VAR_NAME);
        }
        return password;
    }

    private List<Route> getAppRoutes(List<String> routes) {
        return routes == null ? null
            : routes
                .stream()
                .filter(Objects::nonNull)
                .map(route -> Route.builder().route(route).build())
                .collect(Collectors.toList());
    }

}
