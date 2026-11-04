package com.example.recon.cli;

import com.example.recon.config.ReconProperties;
import com.example.recon.listener.InboxListener;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("intake")
public class IntakeCommand implements ApplicationRunner {

    private final InboxListener inbox;
    private final ReconProperties props;

    public IntakeCommand(InboxListener inbox, ReconProperties props) {
        this.inbox = inbox;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        CommandLineArgs args = new CommandLineArgs(arguments);
        inbox.listen(StartOptions.resolve(args, props.getStart()));
    }
}
