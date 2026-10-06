package dev.undertow.changes;

public record ChangeRequest(
        ChangeRequestRef ref, ChangeSnapshot snapshot, String state, boolean draft) {}
