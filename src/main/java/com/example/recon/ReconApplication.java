package com.example.recon;

import com.example.recon.cli.CommandLauncher;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReconApplication {

    public static void main(String[] args) {
        CommandLauncher.launch(ReconApplication.class, args);
    }
}
