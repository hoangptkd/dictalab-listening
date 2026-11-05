package com.dictalab.scenario;

/** A declared goal, never an assessed score. OVERALL is retained without inferring LISTENING. */
public record ScenarioGoalRequest(String contractVersion, String programCode, int programVersion,
                                  Target target, long expectedVersion) {
    public record Target(String scaleId, String component, String value) {}
    public ScenarioGoalRequest {
        ScenarioProgram.require("scenario-goal.v1".equals(contractVersion), "Unknown goal contract");
        ScenarioProgram.require(programCode != null && programCode.matches("[a-z][a-z0-9-]{0,63}")
                && programVersion > 0 && expectedVersion >= 0 && expectedVersion <= 9007199254740991L, "Invalid goal reference/version");
        ScenarioProgram.require(target != null && target.scaleId() != null && target.component() != null
                && target.value() != null && target.value().length() <= 32, "Invalid target");
    }
}
