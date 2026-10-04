package com.baher.phonescreencast;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(40, 40, 40, 40);

        TextView title = new TextView(this);
        title.setText("PhoneScreenCast");
        title.setTextSize(28);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);

        TextView status = new TextView(this);
        status.setText("Ready to stream your screen");
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 30, 0, 30);

        Button startButton = new Button(this);
        startButton.setText("START STREAMING");

        startButton.setOnClickListener(v -> {
            status.setText("Screen streaming will be added next.");
        });

        layout.addView(title);
        layout.addView(status);
        layout.addView(startButton);

        setContentView(layout);
    }
}
