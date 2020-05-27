package cloud.foundry.cli.operations;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.foundry.cli.crosscutting.beans.ApplicationBean;
import cloud.foundry.cli.crosscutting.beans.ApplicationManifestBean;
import cloud.foundry.cli.crosscutting.exceptions.CreationException;
import cloud.foundry.cli.crosscutting.util.YamlCreator;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationHealthCheck;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.ApplicationSummary;
import org.cloudfoundry.operations.applications.Applications;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.GetApplicationManifestRequest;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.PushApplicationManifestRequest;
import org.cloudfoundry.operations.applications.Route;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Test for {@link ApplicationOperations}
 */
public class ApplicationOperationsTest {

    @Test
    public void testGetApplicationsWithEmptyMockData() {
        // prepare mock CF API client with an empty applications list
        DefaultCloudFoundryOperations cfMock = createMockCloudFoundryOperations(new ArrayList<>(), new ArrayList<>());

        // forge YAML document
        ApplicationOperations applicationOperations = new ApplicationOperations(cfMock);
        String yamlDoc = YamlCreator.createDefaultYamlProcessor().dump(applicationOperations.getAll());

        // check if it's really empty
        assertEquals(yamlDoc, "[\n  ]\n");
    }

    @Test
    public void testGetApplicationsWithMockData() {
        // create a mock CF API client
        // first, we need to prepare some ApplicationSummary and ApplicationManifest
        // (we're fine with one of both for now)
        // those are then used to create a CF mock API object, which will be able to return those then the right way
        ApplicationManifest manifest = createMockApplicationManifest();
        ApplicationSummary summary = createMockApplicationSummary(manifest);

        List<ApplicationManifest> manifests = new ArrayList<>();
        manifests.add(manifest);

        List<ApplicationSummary> summaries = new ArrayList<>();
        summaries.add(summary);

        // now, let's create the mock object from that list
        DefaultCloudFoundryOperations cfMock = createMockCloudFoundryOperations(summaries, manifests);

        // now, we can generate a YAML doc for our ApplicationSummary
        ApplicationOperations applicationOperations = new ApplicationOperations(cfMock);
        String yamlDoc = YamlCreator.createDefaultYamlProcessor().dump(applicationOperations.getAll());

        // ... and make sure it contains exactly what we'd expect
        assertThat(yamlDoc, is(
                "- manifest:\n" +
                "    buildpack: test_buildpack\n" +
                "    command: test command\n" +
                "    disk: 1234\n" +
                "    dockerImage: null\n" +
                "    dockerUsername: null\n" +
                "    domains: null\n" +
                "    environmentVariables:\n" +
                "      key: value\n" +
                "    healthCheckHttpEndpoint: http://healthcheck.local\n" +
                "    healthCheckType: HTTP\n" +
                "    hosts: null\n" +
                "    instances: 42\n" +
                "    memory: 2147483647\n" +
                "    noHostname: null\n" +
                "    noRoute: false\n" +
                "    randomRoute: true\n" +
                "    routePath: null\n" +
                "    routes:\n" +
                "    - route1\n" +
                "    - route2\n" +
                "    services:\n" +
                "    - serviceomega\n" +
                "    stack: nope\n" +
                "    timeout: 987654321\n" +
                "  name: notyetrandomname\n" +
                "  path: " + Paths.get("/test/uri").toString() + "\n"
        ));
    }

    @Test
    public void testCreateApplicationsPushesAppManifestSucceeds() throws CreationException {
        //given
        ApplicationManifest appManifest = createMockApplicationManifest();
        DefaultCloudFoundryOperations cfoMock = Mockito.mock(DefaultCloudFoundryOperations.class);
        Applications applicationsMock = Mockito.mock(Applications.class);
        Mono<Void> monoMock = Mockito.mock(Mono.class);

        ApplicationOperations applicationOperations = new ApplicationOperations(cfoMock);

        when(cfoMock.applications()).thenReturn(applicationsMock);
        when(cfoMock.applications().get(any(GetApplicationRequest.class)))
                .thenThrow(new IllegalArgumentException());
        when(applicationsMock.pushManifest(any(PushApplicationManifestRequest.class)))
                .thenReturn(monoMock);
        when(monoMock.onErrorContinue( any(Predicate.class), any())).thenReturn(monoMock);
        when(monoMock.block()).thenReturn(null);

        ApplicationBean applicationsBean = new ApplicationBean(appManifest);

        //when
        applicationOperations.create(applicationsBean, false);

        //then
        verify(applicationsMock, times(1)).pushManifest(any(PushApplicationManifestRequest.class));
        verify(monoMock, times(2)).onErrorContinue( any(Predicate.class), any());
        verify(monoMock, times(1)).block();
    }

    @Test
    public void testCreateApplicationsOnMissingDockerPasswordThrowsCreationException() {
        //given
        DefaultCloudFoundryOperations cfoMock = Mockito.mock(DefaultCloudFoundryOperations.class);
        Applications applicationsMock = Mockito.mock(Applications.class);

        ApplicationOperations applicationOperations = new ApplicationOperations(cfoMock);

        when(cfoMock.applications()).thenReturn(applicationsMock);
        when(cfoMock.applications().get(any(GetApplicationRequest.class)))
                .thenThrow(new IllegalArgumentException());
        when(applicationsMock.delete(Mockito.mock(DeleteApplicationRequest.class))).then(Mockito.mock(Answer.class));

        ApplicationBean applicationsBean = new ApplicationBean();
        ApplicationManifestBean applicationManifestBean = new ApplicationManifestBean();
        applicationManifestBean.setDockerImage("some/image");
        applicationManifestBean.setDockerUsername("username");

        applicationsBean.setName("someapp");
        applicationsBean.setManifest(applicationManifestBean);

        //when
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> applicationOperations.create(applicationsBean, false));
        assertThat(exception.getMessage(), containsString("Docker password not set"));
    }

    @Test
    public void testCreateApplicationsOnFatalCreationErrorThrowsCreationException() throws CreationException {
        //given
        ApplicationManifest appManifest = createMockApplicationManifest();
        DefaultCloudFoundryOperations cfoMock = Mockito.mock(DefaultCloudFoundryOperations.class);
        Applications applicationsMock = Mockito.mock(Applications.class);

        ApplicationOperations applicationOperations = new ApplicationOperations(cfoMock);

        when(cfoMock.applications()).thenReturn(applicationsMock);
        when(cfoMock.applications().get(any(GetApplicationRequest.class)))
                .thenThrow(new IllegalArgumentException());
        when(applicationsMock.pushManifest(any(PushApplicationManifestRequest.class)))
                .thenThrow(new IllegalArgumentException());
        when(applicationsMock.delete(Mockito.mock(DeleteApplicationRequest.class))).then(Mockito.mock(Answer.class));
        ApplicationBean applicationsBean = new ApplicationBean(appManifest);

        //when
        assertThrows(CreationException.class, () -> applicationOperations.create(applicationsBean, false));
        verify(applicationsMock, times(1)).pushManifest(any());
        verify(applicationsMock, times(1)).delete(any());
    }

    @Test
    public void testCreateApplicationsFailsWhenAlreadyExisting() {
        //given
        ApplicationManifest mockAppManifest = createMockApplicationManifest();
        DefaultCloudFoundryOperations cfoMock = Mockito.mock(DefaultCloudFoundryOperations.class);

        ApplicationOperations applicationOperations = new ApplicationOperations(cfoMock);
        Applications applicationsMock = Mockito.mock(Applications.class);

        when(cfoMock.applications()).thenReturn(applicationsMock);
        when(cfoMock.applications().get(any(GetApplicationRequest.class)))
                .thenReturn(Mockito.mock(Mono.class));

        ApplicationBean applicationsBean = new ApplicationBean(mockAppManifest);

        //then
        assertThrows( CreationException.class,
                () -> applicationOperations.create(applicationsBean, false));
    }

    @Test
    public void testCreateOnNullNameThrowsNullPointerException() throws CreationException {
        //given
        ApplicationOperations applicationOperations = new ApplicationOperations(
                Mockito.mock(DefaultCloudFoundryOperations.class));

        //then
        assertThrows(NullPointerException.class, () -> applicationOperations.create(new ApplicationBean(), false));
    }

    @Test
    public void testCreateOnNullPathAndNullDockerImageThrowsIllegalArgumentException() throws CreationException {
        //given
        ApplicationOperations applicationOperations = new ApplicationOperations(
                Mockito.mock(DefaultCloudFoundryOperations.class));
        ApplicationBean applicationBean = new ApplicationBean();
        applicationBean.setName("app");
        ApplicationManifestBean manifestBean = new ApplicationManifestBean();
        manifestBean.setDockerImage(null);
        applicationBean.setManifest(manifestBean);

        //when
        assertThrows(IllegalArgumentException.class, () -> applicationOperations.create(applicationBean, false));
    }

    @Test
    public void testCreateOnNullPathAndNullManifestThrowsIllegalArgumentException() throws CreationException {
        //given
        ApplicationOperations applicationOperations = new ApplicationOperations(
                Mockito.mock(DefaultCloudFoundryOperations.class));
        ApplicationBean applicationBean = new ApplicationBean();
        applicationBean.setName("app");

        //when
        assertThrows(IllegalArgumentException.class, () -> applicationOperations.create(applicationBean, false));
    }

    @Test
    public void testCreateOnEmptyNameThrowsIllegalArgumentException() {
        //given
        ApplicationOperations applicationOperations = new ApplicationOperations(
                Mockito.mock(DefaultCloudFoundryOperations.class));

        ApplicationBean applicationBean = new ApplicationBean();
        applicationBean.setName("");
        applicationBean.setPath("some/path");

        //then
        assertThrows(IllegalArgumentException.class,
                () -> applicationOperations.create(applicationBean, false));
    }

    @Test
    public void testCreateOnNullBeanThrowsNullPointerException() {
        //given
        ApplicationOperations applicationOperations = new ApplicationOperations(
                Mockito.mock(DefaultCloudFoundryOperations.class));

        //when
        assertThrows(NullPointerException.class, () -> applicationOperations.create(null, false));
    }


    /**
     * Creates and configures mock object for CF API client
     * We only have to patch it so far as that it will return our own list of ApplicationSummary instances
     * @param appSummaries List of ApplicationSummary objects that the mock object shall return
     * @return mock {@link DefaultCloudFoundryOperations} object
     */
    private DefaultCloudFoundryOperations createMockCloudFoundryOperations(List<ApplicationSummary> appSummaries,
                                                                           List<ApplicationManifest> manifests) {
        // the way the ApplicationSummary list is fetched is rather complex thanks to this Mono stuff
        // we need to create _four_ mock objects of which one returns another on a specific method call
        // we do this in reverse order, as it's easier that way

        // first, we create the mock object we want to return later on
        // it's configured after creating all the other mock objects
        DefaultCloudFoundryOperations cfMock = Mockito.mock(DefaultCloudFoundryOperations.class);

        // first, let's have the fun of creating the three objects needed to list the applications
        Mono<List<ApplicationSummary>> summaryListMono = Mockito.mock(Mono.class);
        Mockito.when(summaryListMono.block()).thenReturn(appSummaries);

        Flux<ApplicationSummary> flux = Mockito.mock(Flux.class);
        Mockito.when(flux.collectList()).thenReturn(summaryListMono);

        Applications applicationsMock = Mockito.mock(Applications.class);
        Mockito.when(applicationsMock.list()).thenReturn(flux);

        // now, let's have the same fun for the manifests, which are queried in a different way
        // luckily, we already have the applicationsMock, which we also need to hook on here
        // unfortunately, the method matches a string on some map, so we have to rebuild something similar
        // the following lambda construct does exactly that: search for the right manifest by name in the list we've
        // been passed, and return that if possible (or otherwise throw some exception)
        // TODO: check which exception to throw
        // this.cfOperations.applications().getApplicationManifest(manifestRequest).block();
        Mockito.when(applicationsMock.getApplicationManifest(any(GetApplicationManifestRequest.class)))
                .thenAnswer((Answer<Mono<ApplicationManifest>>) invocation -> {
                    GetApplicationManifestRequest request = invocation.getArgument(0);
                    String name = request.getName();

                    // simple linear search; this is not about performance, really
                    for (ApplicationManifest manifest : manifests) {
                        if (manifest.getName() == name) {
                            // we need to return a mock object that supports the .block()
                            Mono<ApplicationManifest> applicationManifestMono = Mockito.mock(Mono.class);

                            Mockito.when(applicationManifestMono.block()).thenReturn(manifest);

                            return applicationManifestMono;
                        }
                    }

                    throw new RuntimeException("fixme");
                });


        Mockito.when(cfMock.applications()).thenReturn(applicationsMock);

        return cfMock;
    }

    /**
     * Creates an {@link ApplicationManifest} with partially random data to increase test reliability.
     * @return application manifest containing test data
     */
    // FIXME: randomize some data
    private ApplicationManifest createMockApplicationManifest() {
        Map<String, String> envVars = new HashMap<>();
        envVars.put("key", "value");

        // note: here we have to insert a path, too!
        // another note: routes and hosts cannot both be set, so we settle with hosts
        // yet another note: docker image and buildpack cannot both be set, so we settle with buildpack
        ApplicationManifest manifest = ApplicationManifest.builder()
                .buildpack("test_buildpack")
                .command("test command")
                .disk(1234)
                .environmentVariables(envVars)
                .healthCheckHttpEndpoint("http://healthcheck.local")
                .healthCheckType(ApplicationHealthCheck.HTTP)
                .instances(42)
                .memory(Integer.MAX_VALUE)
                .name("notyetrandomname")
                .noRoute(false)
                .path(Paths.get("/test/uri"))
                .randomRoute(true)
                .routes(Route.builder().route("route1").build(), Route.builder().route("route2").build())
                .services("serviceomega")
                .stack("nope")
                .timeout(987654321)
                .build();

        return manifest;
    }

    /**
     * Creates an {@link ApplicationSummary} from an {@link ApplicationManifest} for testing purposes.
     * @return application summary
     */
    // FIXME: randomize some data
    private ApplicationSummary createMockApplicationSummary(ApplicationManifest manifest) {
        // we basically only need the manifest as we need to keep the names the same
        // however, the summary builder complains if a few more attributes aren't set either, so we have to set more
        // than just the name
        ApplicationSummary summary = ApplicationSummary.builder()
                .name(manifest.getName())
                .diskQuota(100)
                .id("summary_id")
                .instances(manifest.getInstances())
                .memoryLimit(manifest.getMemory())
                .requestedState("SOMESTATE")
                .runningInstances(1)
                .build();
        return summary;
    }

}