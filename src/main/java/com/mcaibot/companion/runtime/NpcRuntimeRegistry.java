package com.mcaibot.companion.runtime;

import com.mcaibot.companion.TaskRuntimeSnapshot;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NpcRuntimeRegistry {
    private final Map<UUID, NpcRuntime> runtimes = new ConcurrentHashMap<>();

    public NpcRuntime getOrCreate(UUID npcUuid) {
        if (npcUuid == null) {
            throw new IllegalArgumentException("npcUuid cannot be null");
        }
        return runtimes.computeIfAbsent(npcUuid, NpcRuntime::new);
    }

    public NpcRuntime find(UUID npcUuid) {
        return npcUuid == null ? null : runtimes.get(npcUuid);
    }

    public Collection<NpcRuntime> all() {
        return List.copyOf(runtimes.values());
    }

    public void remove(UUID npcUuid) {
        if (npcUuid != null) {
            runtimes.remove(npcUuid);
        }
    }

    public void clear() {
        runtimes.clear();
    }

    public NpcRuntime syncObservedNpc(
            UUID npcUuid,
            boolean selected,
            boolean directFollowing,
            boolean groupFollowing,
            UUID followTargetUuid,
            UUID groupFollowTargetUuid
    ) {
        if (npcUuid == null) {
            return null;
        }
        NpcRuntime runtime = getOrCreate(npcUuid);
        runtime.syncObservedState(selected, directFollowing, groupFollowing, followTargetUuid, groupFollowTargetUuid);
        return runtime;
    }

    public NpcRuntime syncActiveNpcTask(UUID activeNpcUuid, TaskRuntimeSnapshot snapshot) {
        return syncActiveNpcTask(activeNpcUuid, NpcTaskState.fromSnapshot(snapshot));
    }

    public NpcRuntime syncActiveNpcTask(UUID activeNpcUuid, NpcTaskState taskState) {
        if (activeNpcUuid == null) {
            return null;
        }
        NpcRuntime runtime = getOrCreate(activeNpcUuid);
        runtime.syncTaskState(taskState);
        return runtime;
    }

    public NpcRuntime syncActiveNpcTask(
            UUID activeNpcUuid,
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
        return syncActiveNpcTask(
                activeNpcUuid,
                NpcTaskState.fromLegacyGlobal(
                        taskName,
                        active,
                        paused,
                        stepsDone,
                        pauseSeconds,
                        searchRemainingSeconds,
                        ownerUuid,
                        targetBlock,
                        targetItemUuid
                )
        );
    }

    public void clearNpcTask(UUID npcUuid) {
        NpcRuntime runtime = find(npcUuid);
        if (runtime != null) {
            runtime.clearTask();
        }
    }
}
