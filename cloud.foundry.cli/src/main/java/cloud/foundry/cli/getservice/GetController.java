package cloud.foundry.cli.getservice;

import cloud.foundry.cli.crosscutting.mapping.CfOperationsCreator;
import cloud.foundry.cli.getservice.logic.SpaceDevelopersProvider;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * Controller for the Get-Commands
 */
@Command(name = "Get-Controller",
        header = "%n@|green Get-Controller|@",
        subcommands = {
                GetController.GetServicesCommand.class,
                GetController.GetSpaceDevelopersCommand.class,
                GetController.GetApplicationsCommand.class})
public class GetController implements Runnable {

    @Override
    public void run() {

    }

    @Command(name = "get-space-developers",
            description = "List all space developers in the target space")
    static class GetSpaceDevelopersCommand implements Runnable {
        @Mixin
        GetControllerCommandOptions commandOptions;

        @Override
        public void run() {
            DefaultCloudFoundryOperations cfOperations = CfOperationsCreator
                    .createCfOperations(commandOptions);

            SpaceDevelopersProvider provider = new SpaceDevelopersProvider(cfOperations);
            String  spaceDevelopers =  provider.getSpaceDevelopers();
            System.out.println(spaceDevelopers);
        }
    }

    @Command(name = "get-services", description = "List all applications in the target space")
    static class GetServicesCommand implements Runnable {
        @Mixin
        GetControllerCommandOptions commandOptions;

        @Override
        public void run() {
            // FIXME
            System.out.println("SOME DUMMY SERVICES");

            // RUFE SERVICE AUF
            DefaultCloudFoundryOperations cfOperations = CfOperationsCreator
                    .createCfOperations(commandOptions);
        }
    }

    @Command(name = "get-applications", description = "List all applications in the target space")
    static class GetApplicationsCommand implements Runnable {
        @Mixin
        GetControllerCommandOptions commandOptions;

        @Override
        public void run() {
            // FIXME
            System.out.println("SOME DUMMY APPLICATIONS");
            // RUFE SERVICE AUF
            DefaultCloudFoundryOperations cfOperations = CfOperationsCreator
                    .createCfOperations(commandOptions);
        }
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new GetController()).execute(args);
        System.exit(exitCode);
    }
}