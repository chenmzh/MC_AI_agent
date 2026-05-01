package com.mcaibot.companion.tasks;

public interface TaskController {
    TaskControllerMetadata metadata();

    default String name() {
        return metadata().name();
    }
}
