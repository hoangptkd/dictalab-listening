package com.dictalab.scenario;

final class ScenarioFailure extends RuntimeException {
    final int status;
    final String code;
    ScenarioFailure(int status, String code, String messageVi) {
        super(messageVi); this.status = status; this.code = code;
    }
}
