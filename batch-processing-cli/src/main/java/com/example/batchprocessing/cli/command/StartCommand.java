package com.example.batchprocessing.cli.command;

import java.nio.file.Path;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.example.batchprocessing.cli.config.StartProperties;
import com.example.batchprocessing.cli.launch.CommandLineArgs;
import com.example.batchprocessing.cli.start.FileIntake;
import com.example.batchprocessing.cli.start.StartOptions;
import com.example.batchprocessing.cli.start.StartSettings;
import com.example.batchprocessing.cli.view.FileResultPrinter;
import com.example.batchprocessing.cli.view.TemporalUiLinks;
import com.example.batchprocessing.core.config.BatchProcessingProperties;
import com.example.batchprocessing.core.dto.FileResult;

import io.temporal.client.WorkflowClient;

@Component
@Profile("start")
public class StartCommand implements ApplicationRunner {

    private final FileIntake intake;
    private final WorkflowClient client;
    private final BatchProcessingProperties props;
    private final StartProperties startDefaults;

    public StartCommand(FileIntake intake, WorkflowClient client, BatchProcessingProperties props, StartProperties startDefaults) {
        this.intake = intake;
        this.client = client;
        this.props = props;
        this.startDefaults = startDefaults;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        CommandLineArgs args = new CommandLineArgs(arguments);
        Path file = Path.of(args.requirePositional(0, "file path"));
        StartSettings settings = StartOptions.resolve(args, startDefaults);
        boolean waitForResult = args.flag("wait");

        String workflowId = intake.submit(file, settings);

        String namespace = client.getOptions().getNamespace();
        String uiLink = TemporalUiLinks.workflow(props.getUiBaseUrl(), namespace, workflowId);
        System.out.printf("Workflow: %s%n", workflowId);
        System.out.printf("UI:       %s%n", uiLink);

        if (waitForResult) {
            FileResult result = client.newUntypedWorkflowStub(workflowId).getResult(FileResult.class);
            FileResultPrinter.print(result, System.out);
        }
    }
}
