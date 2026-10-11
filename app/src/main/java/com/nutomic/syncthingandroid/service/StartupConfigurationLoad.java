package com.nutomic.syncthingandroid.service;

import com.nutomic.syncthingandroid.runtime.RootFailure;
import com.nutomic.syncthingandroid.runtime.RootTransportException;

import java.util.Objects;

/** Runs one potentially privileged configuration read on a dedicated daemon worker. */
final class StartupConfigurationLoad {
    interface Source {
        StartupConfiguration load() throws Exception;
    }

    interface Callback {
        void onComplete(Result result);
    }

    /** Immutable completion data; exception objects and mutable XML never cross this boundary. */
    static final class Result {
        private final StartupConfiguration configuration;
        private final RootFailure rootFailure;
        private final String detail;

        private Result(
                StartupConfiguration configuration,
                RootFailure rootFailure,
                String detail
        ) {
            this.configuration = configuration;
            this.rootFailure = rootFailure;
            this.detail = detail;
        }

        static Result success(StartupConfiguration configuration) {
            return new Result(Objects.requireNonNull(configuration), null, null);
        }

        static Result failure(Exception error) {
            RootFailure rootFailure = rootFailure(error);
            String detail = error.getMessage();
            if (detail == null || detail.isEmpty()) detail = error.getClass().getSimpleName();
            return new Result(null, rootFailure, detail);
        }

        StartupConfiguration configuration() {
            return configuration;
        }

        RootFailure rootFailure() {
            return rootFailure;
        }

        String detail() {
            return detail;
        }

        boolean succeeded() {
            return configuration != null;
        }

        private static RootFailure rootFailure(Throwable error) {
            Throwable current = error;
            while (current != null) {
                if (current instanceof RootTransportException) {
                    return ((RootTransportException) current).failure();
                }
                current = current.getCause();
            }
            return null;
        }
    }

    private StartupConfigurationLoad() {
    }

    static Thread start(Source source, Callback callback) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(callback);
        Thread worker = new Thread(() -> {
            Result result;
            try {
                result = Result.success(source.load());
            } catch (Exception error) {
                result = Result.failure(error);
            }
            callback.onComplete(result);
        }, "Syncthing startup configuration");
        worker.setDaemon(true);
        worker.start();
        return worker;
    }
}
