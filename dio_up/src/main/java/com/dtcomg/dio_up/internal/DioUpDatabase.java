package com.dtcomg.dio_up.internal;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {TaskRecord.class, DownloadRecord.class},
        version = 1,
        exportSchema = false
)
public abstract class DioUpDatabase extends RoomDatabase {
    // volatile 配合双重检查，确保所有线程看到完全构造的数据库实例。
    private static volatile DioUpDatabase instance;

    public abstract DioUpDao dao();

    public static DioUpDatabase get(Context context) {
        // 快速路径避免每次任务进度写入都进入同步块。
        DioUpDatabase current = instance;
        if (current != null) {
            return current;
        }
        synchronized (DioUpDatabase.class) {
            // 获得锁后再次检查，防止并发创建两个 Room 实例。
            current = instance;
            if (current == null) {
                // 使用 applicationContext，使数据库生命周期与进程一致。
                current = Room.databaseBuilder(
                        context.getApplicationContext(),
                        DioUpDatabase.class,
                        "dio_up.db"
                ).build();
                instance = current;
            }
            return current;
        }
    }
}
