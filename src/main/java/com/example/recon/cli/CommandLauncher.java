package com.example.recon.cli;

import com.example.recon.view.UsagePrinter;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

public final class CommandLauncher {

    public static void launch(Class<?> applicationClass, String[] args) {
        Optional<Command> command = args.length == 0 ? Optional.empty() : Command.find(args[0]);
        if (command.isEmpty()) {
            UsagePrinter.print(System.out);
            System.exit(args.length == 0 ? 0 : 2);
        }

        SpringApplication app = new SpringApplication(applicationClass);
        app.setAdditionalProfiles(command.get().profile());
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setKeepAlive(command.get().longRunning());
        ConfigurableApplicationContext context =
                app.run(Arrays.copyOfRange(args, 1, args.length));

        if (!command.get().longRunning()) {
            System.exit(SpringApplication.exit(context));
        }
    }

    private CommandLauncher() {}
}
