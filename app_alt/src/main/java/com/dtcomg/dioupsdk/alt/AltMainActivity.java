package com.dtcomg.dioupsdk.alt;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.dtcomg.dio_up.DioUp;
import com.dtcomg.dio_up.DioUpConfiguration;

public final class AltMainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!DioUp.isInitialized()) {
            DioUp.initialize(
                    this,
                    DioUpConfiguration.builder(
                                    "https://example.invalid",
                                    "alt-sample"
                            )
                            .customDeviceName("DioUp Alt Host")
                            .heartbeatIntervalMillis(10_000)
                            .build()
            );
        }
        TextView content = new TextView(this);
        content.setText("DioUp alternate host integrated");
        setContentView(content);
    }
}
