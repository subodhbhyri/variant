package com.tailor.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** {@code tailor aliases review} — PHASE5_SPEC.md section 9. Parent for alias-queue subcommands. */
@Command(name = "aliases", description = "Alias suggestion queue (spec section 7.1).",
        subcommands = {AliasesReviewCommand.class})
public final class AliasesCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
