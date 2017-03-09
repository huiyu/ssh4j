package io.github.huiyu.ssh4j;

import com.google.common.base.Strings;

public class ExecuteResult {

    public final String out;
    public final String err;
    public final int exitCode;

    public ExecuteResult(String out, String err, int exitCode) {
        this.out = out;
        this.err = err;
        this.exitCode = exitCode;
    }

    public boolean hasError() {
        return !Strings.isNullOrEmpty(err);
    }
}
