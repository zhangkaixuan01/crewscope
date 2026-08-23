package io.crewscope.domain.model;

/** Provider policy for using customer data to train or improve models. */
public enum ModelTrainingUsagePolicy {
    PROHIBITED,
    EXPLICIT_OPT_IN,
    PROVIDER_DEFAULT
}
