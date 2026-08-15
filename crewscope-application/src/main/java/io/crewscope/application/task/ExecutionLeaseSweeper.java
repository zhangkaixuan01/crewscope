package io.crewscope.application.task;

/** Recovers a bounded batch of leases that elapsed by authoritative database time. */
public interface ExecutionLeaseSweeper {

    LeaseSweepResult sweep(int requestedLimit);
}
