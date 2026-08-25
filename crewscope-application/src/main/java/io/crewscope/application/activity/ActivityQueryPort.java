package io.crewscope.application.activity;

/** Persistence boundary returning canonical Activity events without view-specific remapping. */
public interface ActivityQueryPort {

    ActivityPage find(ActivityQuery query);
}
