package com.mcaibot.companion.runtime;

import com.mcaibot.companion.TaskRuntimeSnapshot;

import java.util.UUID;

public final class NpcRuntime {
    private final UUID npcUuid;
    private NpcTaskState taskState = NpcTaskState.idle();
    private boolean selected;
    private boolean directFollowing;
    private boolean groupFollowing;
    private UUID followTargetUuid;
    private UUID groupFollowTargetUuid;

    NpcRuntime(UUID npcUuid) {
        this.npcUuid = npcUuid;
    }

    public UUID npcUuid() {
        return npcUuid;
    }

    public synchronized NpcTaskState taskState() {
        return taskState;
    }

    public synchronized TaskRuntimeSnapshot taskSnapshot() {
        return taskState.toSnapshot();
    }

    public synchronized void syncTaskState(NpcTaskState taskState) {
        this.taskState = taskState == null ? NpcTaskState.idle() : taskState;
    }

    public synchronized void clearTask() {
        taskState = NpcTaskState.idle();
    }

    public synchronized void syncObservedState(
            boolean selected,
            boolean directFollowing,
            boolean groupFollowing,
            UUID followTargetUuid,
            UUID groupFollowTargetUuid
    ) {
        this.selected = selected;
        this.directFollowing = directFollowing;
        this.groupFollowing = groupFollowing;
        this.followTargetUuid = directFollowing ? followTargetUuid : null;
        this.groupFollowTargetUuid = groupFollowing ? groupFollowTargetUuid : null;
    }

    public synchronized boolean selected() {
        return selected;
    }

    public synchronized boolean directFollowing() {
        return directFollowing;
    }

    public synchronized boolean groupFollowing() {
        return groupFollowing;
    }

    public synchronized UUID followTargetUuid() {
        return followTargetUuid;
    }

    public synchronized UUID groupFollowTargetUuid() {
        return groupFollowTargetUuid;
    }
}
