package com.example.batchprocessing.cli.command;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.example.batchprocessing.cli.config.StartProperties;
import com.example.batchprocessing.cli.launch.CommandLineArgs;
import com.example.batchprocessing.cli.start.InboxListener;
import com.example.batchprocessing.cli.start.StartOptions;
import com.example.batchprocessing.cli.start.StartSettings;

@Component
@Profile("intake")
public class IntakeCommand implements ApplicationRunner {

    private final InboxListener inbox;
    private final StartProperties startDefaults;

    public IntakeCommand(InboxListener inbox, StartProperties startDefaults) {
        this.inbox = inbox;
        this.startDefaults = startDefaults;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        CommandLineArgs args = new CommandLineArgs(arguments);
        StartSettings settings = StartOptions.resolve(args, startDefaults);

        inbox.listen(settings);
    }
}
