package com.dtcomg.dio_up.internal;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface DioUpDao {
    // 按 taskId 查询任务记录；服务端每次下发新任务时 taskId 必然变化，
    // 用它判断是否需要重新执行，避免被"文件已存在"等误判条件短路。
    @Query("SELECT * FROM task_record WHERE targetId = :targetId LIMIT 1")
    TaskRecord findByTargetId(long targetId);

    @Query("SELECT * FROM task_record WHERE taskId = :taskId LIMIT 1")
    TaskRecord findByTaskId(long taskId);

    // 系统安装 Intent 无回调，因此单独查询等待宿主再次验证的记录。
    // 注意：WAITING_FOR_USER 状态已不再主动上报服务端，宿主需在合适的时机调用
    // DioUp.reportInstallResult(targetId, true, message) 才能推进到 SUCCEEDED。
    @Query("SELECT * FROM task_record WHERE status = 'WAITING_FOR_USER' AND filePath IS NOT NULL")
    List<TaskRecord> waitingForUser();

    // 目标 ID 唯一；状态推进时覆盖同一条任务记录。
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertTask(TaskRecord record);

    // 每次写入下载进度时覆盖断点快照；实际续传偏移以 .part 文件长度为准。
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertDownload(DownloadRecord record);
}
