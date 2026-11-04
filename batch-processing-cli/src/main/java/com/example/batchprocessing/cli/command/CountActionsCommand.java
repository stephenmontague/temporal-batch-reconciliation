package com.example.batchprocessing.cli.command;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.example.batchprocessing.cli.actions.ActionCount;
import com.example.batchprocessing.cli.actions.ActionCounter;
import com.example.batchprocessing.cli.launch.CommandLineArgs;
import com.example.batchprocessing.cli.view.ActionCountPrinter;

import io.temporal.client.WorkflowClient;

@Component
@Profile("count-actions")
public class CountActionsCommand implements ApplicationRunner {

    private final WorkflowClient client;

    public CountActionsCommand(WorkflowClient client) {
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        CommandLineArgs args = new CommandLineArgs(arguments);
        String workflowId = args.requirePositional(0, "workflow ID");
        boolean asCsv = args.flag("csv");

        ActionCount count = new ActionCounter(client).count(workflowId);

        if (asCsv) {
            String label = args.option("label", workflowId);
            ActionCountPrinter.printCsv(label, count, System.out);
        } else {
            ActionCountPrinter.printTable(count, System.out);
        }
    }
}
