package com.mcaibot.companion.runtime;

import com.mcaibot.companion.TaskRuntimeSnapshot;

import java.util.UUID;

public record NpcTaskState(
        String taskName,
        boolean active,
        boolean paused,
        int stepsDone,
        int pauseSeconds,
        int searchRemainingSeconds,
        UUID ownerUuid,
        String targetBlock,
        UUID targetItemUuid,
        String taskId,
        String controllerName,
        String status,
        String phase,
        String blockerCode,
        String blockerReason
) {
    private static final NpcTaskState IDLE = new NpcTaskState(
            "idle",
            false,
            false,
            0,
            0,
            0,
            null,
            "",
            null,
            "",
            "",
            "idle",
            "idle",
            "",
            ""
    );

    public NpcTaskState {
        taskName = textOrDefault(taskName, "idle");
        stepsDone = Math.max(0, stepsDone);
        pauseSeconds = Math.max(0, pauseSeconds);
        searchRemainingSeconds = Math.max(0, searchRemainingSeconds);
        targetBlock = textOrDefault(targetBlock, "");
        taskId = textOrDefault(taskId, "");
        controllerName = textOrDefault(controllerName, active ? taskName : "");
        status = textOrDefault(status, active ? (paused ? "paused" : "running") : "idle");
        phase = textOrDefault(phase, active ? "executing" : "idle");
        blockerCode = textOrDefault(blockerCode, "");
        blockerReason = textOrDefault(blockerReason, "");
    }

    public static NpcTaskState idle() {
        return IDLE;
    }

    public static NpcTaskState fromLegacyGlobal(
            String taskName,
            boolean active,
            boolean paused,
            int stepsDone,
            int pauseSeconds,
            int searchRemainingSeconds,
            UUID ownerUuid,
            String targetBlock,
            UUID targetItemUuid
    ) {
        if (!active) {
            return idle();
        }
        return new NpcTaskState(
                taskName,
                true,
                paused,
                stepsDone,
                pauseSeconds,
                searchRemainingSeconds,
                ownerUuid,
                targetBlock,
                targetItemUuid,
                "",
                taskName,
                paused ? "paused" : "running",
                "executing",
                "",
                ""
        );
    }

    public static NpcTaskState fromLegacyGlobalWithTaskId(
            String taskId,
            String taskName,
            boolean active,
            boolean paused,
            int stepsDone,
            int pauseSeconds,
            int searchRemainingSeconds,
            UUID ownerUuid,
            String targetBlock,
            UUID targetItemUuid
    ) {
        if (!active) {
            return idle();
        }
        return new NpcTaskState(
                taskName,
                true,
                paused,
                stepsDone,
                pauseSeconds,
                searchRemainingSeconds,
                ownerUuid,
                targetBlock,
                targetItemUuid,
                taskId,
                taskName,
                paused ? "paused" : "running",
                "executing",
                "",
                ""
        );
    }

    public static NpcTaskState fromSnapshot(TaskRuntimeSnapshot snapshot) {
        if (snapshot == null || !snapshot.active()) {
            return idle();
        }
        return new NpcTaskState(
                snapshot.taskName(),
                snapshot.active(),
                snapshot.paused(),
                snapshot.stepsDone(),
                snapshot.pauseSeconds(),
                snapshot.searchRemainingSeconds(),
                parseUuid(snapshot.ownerUuid()),
                snapshot.targetBlock(),
                parseUuid(snapshot.targetItemUuid()),
                snapshot.taskId(),
                snapshot.controllerName(),
                snapshot.status(),
                snapshot.phase(),
                snapshot.blockerCode(),
                snapshot.blockerReason()
        );
    }

    public TaskRuntimeSnapshot toSnapshot() {
        return new TaskRuntimeSnapshot(
                taskName,
                active,
                paused,
                stepsDone,
                pauseSeconds,
                searchRemainingSeconds,
                ownerUuid == null ? "" : ownerUuid.toString(),
                targetBlock,
                targetItemUuid == null ? "" : targetItemUuid.toString(),
                taskId,
                controllerName,
                status,
                phase,
                blockerCode,
                blockerReason
        );
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
