package com.tailor.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Root command. Subcommands are added one per Phase 1 build step (spec
 * section 12) — only {@code render} exists after step 1.1.
 */
@Command(
        name = "tailor",
        mixinStandardHelpOptions = true,
        version = "tailor 0.1 (Phase 1, step 1.1)",
        subcommands = {RenderCommand.class, NormalizeCommand.class, DetectCommand.class, CorpusCheckCommand.class})
public final class TailorCli implements Runnable {

    @Override
    public void run() {
        // No subcommand given: picocli prints usage via the top-level spec.
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new TailorCli()).execute(args);
        System.exit(exitCode);
    }
}
