package com.dtcomg.dioupsdk;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.dtcomg.dio_up.DioUp;
import com.dtcomg.dio_up.DioUpLocation;
import com.dtcomg.dio_up.TaskExecutionResult;
import com.dtcomg.dio_up.TaskExecutionSummary;

import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private final ExecutorService sdkExecutor = Executors.newSingleThreadExecutor();
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("DioUp SDK 示例");
        setContentView(buildContent());
    }

    @Override
    protected void onDestroy() {
        sdkExecutor.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        status = new TextView(this);
        status.setText("SDK 已在 Application 中初始化，等待操作");
        root.addView(button("上报示例位置", this::reportLocation));
        root.addView(button("执行待处理任务", this::executeTasks));
        root.addView(status);
        return root;
    }

    private Button button(String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void reportLocation() {
        sdkExecutor.execute(() -> {
            try {
                DioUp.reportLocation(
                        new DioUpLocation(31.2304, 121.4737, 100.0, new Date())
                );
                showStatus("位置上报成功");
            } catch (RuntimeException exception) {
                showStatus("位置上报失败：" + exception.getMessage());
            }
        });
    }

    private void executeTasks() {
        sdkExecutor.execute(() -> {
            try {
                TaskExecutionSummary summary = DioUp.executePendingTasks();
                StringBuilder text = new StringBuilder("任务执行完成：");
                for (TaskExecutionResult result : summary.getResults()) {
                    if (text.length() > 7) {
                        text.append(", ");
                    }
                    text.append(result.getTargetId()).append(':').append(result.getStatus());
                }
                showStatus(text.toString());
            } catch (RuntimeException exception) {
                showStatus("任务执行失败：" + exception.getMessage());
            }
        });
    }

    private void showStatus(String text) {
        runOnUiThread(() -> status.setText(text));
    }
}
