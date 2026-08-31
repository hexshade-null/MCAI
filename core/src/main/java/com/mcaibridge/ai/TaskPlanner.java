package com.mcaibridge.ai;

import com.mcaibridge.core.ActionExecutor;

import java.util.List;

/**
 * 任务规划器：动作序列提交 + 失败重试（上限 N 次）+ 失败播报。
 * 复合动作（chop/mine）内部自带"扫描→走近→挖→拾取"状态机，此处负责序列级重试。
 */
public class TaskPlanner {
    private final ActionExecutor executor;
    private volatile java.util.function.Consumer<String> reporter;

    private volatile List<ActionExecutor.Action> pending;
    private volatile int retriesLeft;

    public TaskPlanner(ActionExecutor executor) {
        this.executor = executor;
        executor.setFailureHandler(this::onFail);
    }

    public void setReporter(java.util.function.Consumer<String> reporter) {
        this.reporter = reporter;
    }

    /** 提交动作序列；失败自动重试 maxRetries 次。 */
    public void submit(List<ActionExecutor.Action> actions, int maxRetries) {
        if (actions == null || actions.isEmpty()) return;
        this.pending = actions;
        this.retriesLeft = maxRetries;
        executor.submit(actions);
    }

    private void onFail() {
        List<ActionExecutor.Action> again = pending;
        if (again != null && retriesLeft-- > 0) {
            executor.submit(again); // 重投
        } else {
            java.util.function.Consumer<String> r = reporter;
            if (r != null) r.accept("任务失败了，先歇会儿");
            pending = null;
        }
    }
}
