package com.twinguard;

import com.twinguard.cli.GateCli;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the dashboard server, or with a command such as "gate" as the first argument, runs the CI command line. */
@SpringBootApplication
public class TwinGuardApplication {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && GateCli.COMMANDS.contains(args[0])) {
            System.exit(GateCli.run(args[0], Arrays.copyOfRange(args, 1, args.length)));
        }
        SpringApplication.run(TwinGuardApplication.class, args);
    }
}
