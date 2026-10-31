package io.studentcloud.content.learning;

import java.nio.file.Files;
import java.nio.file.Path;

/** Executed only in a limited test child; never part of the production artifact. */
public final class PdfWorkerFixture {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[1]).getParent(), output = Path.of(args[2]);
        Files.writeString(directory.resolve("started.pid"), Long.toString(ProcessHandle.current().pid()));
        switch (args[0]) {
            case "sleep" -> Thread.sleep(30_000);
            case "secret" -> PdfTextProtocol.write(output, new PdfTextProtocol.Result(Boolean.toString(System.getenv("PATH") != null || System.getenv("SC_PDF_TEST_SECRET") != null || System.getenv("JAVA_TOOL_OPTIONS") != null), false, 1, 1));
            case "spam" -> {
                byte[] block = new byte[8192];
                for (int i = 0; i < 1024; i++) { System.out.write(block); System.err.write(block); }
                PdfTextProtocol.write(output, new PdfTextProtocol.Result("stdout was discarded", false, 1, 1));
            }
            case "invalid" -> Files.writeString(output, "not a protocol result");
            default -> System.exit(7);
        }
    }
}
