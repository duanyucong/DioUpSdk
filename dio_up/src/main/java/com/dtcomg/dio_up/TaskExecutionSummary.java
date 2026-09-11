package com.dtcomg.dio_up;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TaskExecutionSummary {
    // 同一轮任务执行产生的逐目标结果。
    private final List<TaskExecutionResult> results;

    public TaskExecutionSummary(List<TaskExecutionResult> results) {
        // 对外暴露只读快照，避免执行结果被宿主意外篡改。
        this.results = Collections.unmodifiableList(new ArrayList<>(results));
    }

    public List<TaskExecutionResult> getResults() {
        return results;
    }
}
