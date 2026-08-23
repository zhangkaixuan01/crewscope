package io.crewscope.domain.action;

/** User-visible risk classification used by confirmation and organization policy. */
public enum ActionRiskLevel {
    READ_ONLY,
    LOW_RISK_WRITE,
    HIGH_RISK_WRITE,
    DESTRUCTIVE
}
