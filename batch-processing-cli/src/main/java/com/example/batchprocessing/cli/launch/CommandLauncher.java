package com.example.batchprocessing.cli.launch;

import java.util.Arrays;
import java.util.Optional;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import com.example.batchprocessing.cli.view.UsagePrinter;

// Reads the first argument, turns on the Spring profile with that command's name, and runs Spring Boot with the rest.
public final class CommandLauncher {

    public static void launch(Class<?> applicationClass, String[] args) {
        Optional<Command> found = commandOf(args);

        if (found.isEmpty()) {
            UsagePrinter.print(System.out);
            int exitCode = args.length == 0 ? 0 : 2;
            System.exit(exitCode);
        }

        Command command = found.get();
        String[] commandArgs = Arrays.copyOfRange(args, 1, args.length);

        SpringApplication app = new SpringApplication(applicationClass);
        app.setAdditionalProfiles(command.profile());
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setKeepAlive(command.longRunning());

        ConfigurableApplicationContext context = app.run(commandArgs);

        if (!command.longRunning()) {
            int exitCode = SpringApplication.exit(context);
            System.exit(exitCode);
        }
    }

    private static Optional<Command> commandOf(String[] args) {
        if (args.length == 0) {
            return Optional.empty();
        }

        return Command.find(args[0]);
    }

    private CommandLauncher() {}
}
