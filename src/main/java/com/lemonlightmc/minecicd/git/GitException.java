package com.lemonlightmc.minecicd.git;

public class GitException extends RuntimeException {

    public GitException(String message) {
        super(message);
    }

    public GitException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class PullAborted extends GitException {
        public PullAborted(String message) {
            super(message);
        }
    }
}