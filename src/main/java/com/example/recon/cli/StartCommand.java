package com.example.recon.cli;

import java.nio.file.Path;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.example.recon.config.ReconProperties;
import com.example.recon.dto.FileResult;
import com.example.recon.service.FileIntake;
import com.example.recon.view.FileResultPrinter;
import com.example.recon.view.TemporalUiLinks;

import io.temporal.client.WorkflowClient;

@Component
@Profile("start")
public class StartCommand implements ApplicationRunner {

    private final FileIntake intake;
    private final WorkflowClient client;
    private final ReconProperties props;

    public StartCommand(FileIntake intake, WorkflowClient client, ReconProperties props) {
        this.intake = intake;
        this.client = client;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        CommandLineArgs args = new CommandLineArgs(arguments);
        Path file = Path.of(args.requirePositional(0, "file path"));
        String workflowId = intake.submit(file, StartOptions.resolve(args, props.getStart()));

        System.out.printf("Workflow: %s%nUI:       %s%n", workflowId, TemporalUiLinks.workflow(
                props.getUiBaseUrl(), client.getOptions().getNamespace(), workflowId));
        if (args.flag("wait")) {
            FileResult result = client.newUntypedWorkflowStub(workflowId).getResult(FileResult.class);
            FileResultPrinter.print(result, System.out);
        }
    }
}
