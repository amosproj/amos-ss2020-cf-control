package cloud.foundry.cli.logic.apply;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import cloud.foundry.cli.crosscutting.exceptions.ApplyException;
import cloud.foundry.cli.crosscutting.exceptions.CreationException;
import cloud.foundry.cli.crosscutting.mapping.beans.ApplicationBean;
import cloud.foundry.cli.crosscutting.mapping.beans.ServiceBean;
import cloud.foundry.cli.logic.diff.change.CfChange;
import cloud.foundry.cli.logic.diff.change.object.CfNewObject;
import cloud.foundry.cli.operations.ApplicationsOperations;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.LinkedList;

class ApplicationRequestPlanerTest {

    @Test
    void applyTest_WithSingleChangeObject_AcceptMethodCalledOnChangeObject() {
        //given
        ApplicationsOperations appOperations = Mockito.mock(ApplicationsOperations.class);
        String appName = "testApp";
        LinkedList<CfChange> cfChanges = new LinkedList<>();
        CfNewObject newObject = Mockito.mock(CfNewObject.class);
        CfNewObject newObject2 = Mockito.mock(CfNewObject.class);
        cfChanges.add(newObject);
        cfChanges.add(newObject2);

        //when
        ApplyApplicationsPlaner.create(appOperations, appName, cfChanges);
        //then
        verify(newObject, times(1)).accept(any());
        verify(newObject2, times(1)).accept(any());
    }

    @Test
    void applyTest_WithChangeObjectNotAppBeanOrAppManifestBean() {
        //given
        ApplicationsOperations appOperations = Mockito.mock(ApplicationsOperations.class);
        String appName = "testApp";
        LinkedList<CfChange> cfChanges = new LinkedList<>();
        ServiceBean serviceBeanMock = mock(ServiceBean.class);
        CfNewObject newObject = new CfNewObject(serviceBeanMock, "", Arrays.asList("path"));
        cfChanges.add(newObject);
        //when
        assertThrows(IllegalArgumentException.class,
                () -> ApplyApplicationsPlaner.create(appOperations, appName, cfChanges));
    }

    @Test
    void applyTest_WithNewChangeObject_AppCreated() throws CreationException {
        //given
        ApplicationsOperations appOperations = Mockito.mock(ApplicationsOperations.class);
        String appName = "testApp";
        LinkedList<CfChange> cfChanges = new LinkedList<>();
        ApplicationBean appBeanMock = mock(ApplicationBean.class);
        CfNewObject newObject = new CfNewObject(appBeanMock, "", Arrays.asList("path"));
        cfChanges.add(newObject);
        Void voidMock = mock(Void.class);
        Mono<Void> monoMock = Mono.just(voidMock);
        when(appOperations.create(appName, appBeanMock, false)).thenReturn(monoMock);

        //when
        Flux<Void> requests = ApplyApplicationsPlaner.create(appOperations, appName, cfChanges);
        //then
        verify(appOperations, times(1)).create(appName, appBeanMock, false);
        StepVerifier.create(requests)
                .expectNext(voidMock)
                .expectComplete()
                .verify();
    }

    @Test
    void applyTest_WithNewChangeObject_CreationException() throws CreationException {
        //given
        ApplicationsOperations appOperations = Mockito.mock(ApplicationsOperations.class);
        String appName = "testApp";
        LinkedList<CfChange> cfChanges = new LinkedList<>();
        ApplicationBean appBeanMock = mock(ApplicationBean.class);
        CfNewObject newObject = new CfNewObject(appBeanMock, "", Arrays.asList("path"));
        cfChanges.add(newObject);
        doThrow(new CreationException("Test")).when(appOperations).create(appName, appBeanMock, false);
        //when
        ApplyException applyException = assertThrows(ApplyException.class,
                () -> ApplyApplicationsPlaner.create(appOperations, appName, cfChanges));
        //then
        assertThat(applyException.getCause(), is(instanceOf(CreationException.class)));
    }

}
