package com.example.recon.cli;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.example.recon.dto.ActionCount;
import com.example.recon.service.ActionCounter;
import com.example.recon.view.ActionCountPrinter;

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
        ActionCount count = new ActionCounter(client).count(workflowId);
        if (args.flag("csv")) {
            ActionCountPrinter.printCsv(args.option("label", workflowId), count, System.out);
        } else {
            ActionCountPrinter.printTable(count, System.out);
        }
    }
}
