package cloud.foundry.cli.logic;

import static com.google.common.base.Preconditions.checkNotNull;

import cloud.foundry.cli.crosscutting.exceptions.ApplyException;
import cloud.foundry.cli.crosscutting.exceptions.GetException;
import cloud.foundry.cli.crosscutting.logging.Log;
import cloud.foundry.cli.crosscutting.mapping.beans.ConfigBean;
import cloud.foundry.cli.logic.apply.ApplicationRequestsPlanner;
import cloud.foundry.cli.logic.apply.ServiceRequestsPlanner;
import cloud.foundry.cli.logic.apply.SpaceDevelopersRequestsPlanner;
import cloud.foundry.cli.logic.diff.DiffResult;
import cloud.foundry.cli.logic.diff.change.CfChange;
import cloud.foundry.cli.logic.diff.change.container.CfContainerChange;
import cloud.foundry.cli.operations.*;
import cloud.foundry.cli.services.LoginCommandOptions;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

/**
 * This class takes care of applying desired cloud foundry configurations to a
 * live system.
 */
public class ApplyLogic {

    private static final Log log = Log.getLog(ApplyLogic.class);

    private GetLogic getLogic;
    private DiffLogic diffLogic;

    private SpaceDevelopersOperations spaceDevelopersOperations;
    private ServicesOperations servicesOperations;
    private ApplicationsOperations applicationsOperations;

    private SpaceOperations spaceOperations;
    private ClientOperations clientOperations;

    /**
     * Creates a new instance that will use the provided cf operations internally.
     *
     * @param cfOperations the cf operations that should be used to communicate with
     *                     the cf instance
     * @throws NullPointerException if the argument is null
     */
    public ApplyLogic(@Nonnull DefaultCloudFoundryOperations cfOperations) {
        checkNotNull(cfOperations);

        this.spaceDevelopersOperations = new SpaceDevelopersOperations(cfOperations);
        this.servicesOperations = new ServicesOperations(cfOperations);
        this.applicationsOperations = new ApplicationsOperations(cfOperations);
        this.spaceOperations = new SpaceOperations(cfOperations);
        this.spaceDevelopersOperations = new SpaceDevelopersOperations(cfOperations);
        this.clientOperations = new ClientOperations(cfOperations);

        this.getLogic = new GetLogic();
        this.diffLogic = new DiffLogic();
    }

    public void setApplicationsOperations(ApplicationsOperations applicationsOperations) {
        this.applicationsOperations = applicationsOperations;
    }

    public void setSpaceOperations(SpaceOperations spaceOperations) {
        this.spaceOperations = spaceOperations;
    }

    public void setSpaceDevelopersOperations(SpaceDevelopersOperations spaceDevelopersOperations) {
        this.spaceDevelopersOperations = spaceDevelopersOperations;
    }

    public void setServicesOperations(ServicesOperations servicesOperations) {
        this.servicesOperations = servicesOperations;
    }

    public void setDiffLogic(DiffLogic diffLogic) {
        this.diffLogic = diffLogic;
    }

    public void setGetLogic(GetLogic getLogic) {
        this.getLogic = getLogic;
    }

    /**
     * Provides the service of manipulating the state of a cloud foundry instance
     * such that it matches with a desired configuration ({@link ConfigBean}).
     *
     * @param desiredConfigBean   desired configuration for a cloud foundry instance
     * @param loginCommandOptions LoginCommandOptions
     * @throws NullPointerException if one of the desired parameter is null.
     */
    public void applyAll(ConfigBean desiredConfigBean, LoginCommandOptions loginCommandOptions) {
        checkNotNull(desiredConfigBean);
        checkNotNull(loginCommandOptions);

        ConfigBean liveConfigBean = getLogic.getAll(
                spaceDevelopersOperations,
                servicesOperations,
                applicationsOperations,
                clientOperations,
                loginCommandOptions);

        DiffResult wrappedDiff = diffLogic.createDiffResult(liveConfigBean, desiredConfigBean);

        CfContainerChange spaceDevelopersChange = wrappedDiff.getSpaceDevelopersChange();
        Map<String, List<CfChange>> servicesChanges = wrappedDiff.getServiceChanges();
        Map<String, List<CfChange>> appsChanges = wrappedDiff.getApplicationChanges();

        if (spaceDevelopersChange == null && servicesChanges.isEmpty() && appsChanges.isEmpty()) {
            log.info("No changes found, no applying necessary.");
            return;
        }

        Flux<Void> spaceDevelopersRequests = Flux.empty();
        if (spaceDevelopersChange != null) {
            spaceDevelopersRequests = SpaceDevelopersRequestsPlanner
                    .createSpaceDevelopersRequests(spaceDevelopersOperations, spaceDevelopersChange);
        }

        Flux<Void> servicesRequests = Flux.fromIterable(servicesChanges.entrySet())
                .flatMap(element -> ServiceRequestsPlanner.createApplyRequests(
                        servicesOperations,
                        element.getKey(),
                        element.getValue()));

        ApplicationRequestsPlanner appRequestsPlanner = new ApplicationRequestsPlanner(applicationsOperations);

        Flux<Void> appsRequests = Flux.fromIterable(appsChanges.entrySet())
                        .flatMap(element -> appRequestsPlanner.createApplyRequests(element.getKey(),
                                element.getValue()));

        Flux.merge(spaceDevelopersRequests, servicesRequests)
                .concatWith(appsRequests)
                .doOnError(log::error)
                .onErrorStop()
                .blockLast();
    }

    /**
     * Creates a space with the desired name if a space with such a name does not
     * exist in the live cf instance.
     *
     * @param desiredSpaceName the name of the desired space
     * @throws NullPointerException if the desired parameter is null
     * @throws ApplyException       in case of errors during the creation of the
     *                              desired space
     * @throws GetException         in case of errors during querying the space
     *                              names
     */
    public void applySpace(String desiredSpaceName) {
        checkNotNull(desiredSpaceName);

        Mono<List<String>> getAllRequest = spaceOperations.getAll();

        log.info("Fetching names of all spaces");
        List<String> spaceNames;
        try {
            spaceNames = getAllRequest.block();
        } catch (Exception e) {
            throw new GetException(e);
        }
        log.verbose("Fetching names of all spaces completed");

        if (!spaceNames.contains(desiredSpaceName)) {
            log.info("Creating space", desiredSpaceName);

            Mono<Void> createRequest = spaceOperations.create(desiredSpaceName);
            try {
                createRequest.block();
            } catch (Exception e) {
                throw new ApplyException(e);
            }

            log.verbose("Creating space", desiredSpaceName, "completed");
        } else {
            log.info("Space", desiredSpaceName, "already exists, skipping");
        }
    }

}

