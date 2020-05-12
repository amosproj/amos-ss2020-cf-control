package cloud.foundry.cli.crosscutting.mapping;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import cloud.foundry.cli.getservice.GetControllerCommandOptions;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.junit.jupiter.api.Test;

/**
 * Test für den {@link CfOperationsCreator}
 */
public class CfOperationsCreatorTest {

    public static final String SOME_API = "api.local.pcf.dev.io";
    public static final String SOME_SPACE = "SOME_SPACE";
    public static final String SOME_ORGANIZATION = "SOME_ORGANIZATION";
    public static final String SOME_CREDENTIALS = "SOME_CREDENTIALS ;)";
    public static final String SOME_USER_NAME = "SOME_USER_NAME";

    @Test
    public void createCfOperationsShouldCreateValidInstance() {
        // given
        GetControllerCommandOptions commandOptions = mock(GetControllerCommandOptions.class);
        when(commandOptions.getApiHost()).thenReturn(SOME_API);
        when(commandOptions.getOrganization()).thenReturn(SOME_ORGANIZATION);
        when(commandOptions.getPassword()).thenReturn(SOME_CREDENTIALS);
        when(commandOptions.getSpace()).thenReturn(SOME_SPACE);
        when(commandOptions.getUserName()).thenReturn(SOME_USER_NAME);

        // when
        DefaultCloudFoundryOperations cfOperations = CfOperationsCreator
                .createCfOperations(commandOptions);

        // then
        assertThat(cfOperations, is(not(nullValue())));
    }
}