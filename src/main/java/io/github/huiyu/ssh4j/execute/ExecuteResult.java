package io.github.huiyu.ssh4j.execute;

public class ExecuteResult {

    public final String stdout;
    public final String stderr;
    public final int exitCode;

    public ExecuteResult(String stdout, String stderr, int exitCode) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.exitCode = exitCode;
    }
}
