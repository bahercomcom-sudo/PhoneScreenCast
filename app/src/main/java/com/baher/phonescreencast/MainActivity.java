package com.baher.phonescreencast;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.zxing.WriterException;

public class MainActivity extends AppCompatActivity {

    private View root;
    private TextView statusText;
    private TextView viewerStatusText;
    private TextView errorText;
    private TextView hintText;
    private TextView urlText;
    private View urlCard;
    private ImageView qrImage;
    private MaterialButton startButton;
    private MaterialButton stopButton;
    private MaterialButton copyButton;

    private ActivityResultLauncher<Intent> projectionLauncher;
    private ActivityResultLauncher<String> notificationLauncher;
    private NetworkUtils.Monitor networkMonitor;
    private boolean networkAvailable;
    private String renderedQrUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this,
                SystemBarStyle.dark(Color.TRANSPARENT),
                SystemBarStyle.dark(Color.TRANSPARENT));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        statusText = findViewById(R.id.statusText);
        viewerStatusText = findViewById(R.id.viewerStatusText);
        errorText = findViewById(R.id.errorText);
        hintText = findViewById(R.id.hintText);
        urlText = findViewById(R.id.urlText);
        urlCard = findViewById(R.id.urlCard);
        qrImage = findViewById(R.id.qrImage);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        copyButton = findViewById(R.id.copyButton);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        projectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    Intent data = result.getData();
                    if (result.getResultCode() == RESULT_OK && data != null) {
                        launchService(result.getResultCode(), data);
                    } else {
                        StreamRepository.publish(StreamRepository.Status.idle(
                                getString(R.string.error_permission_denied)));
                    }
                });

        // Notification permission only affects whether the notification is visible; streaming
        // works either way, so continue to the screen-capture prompt regardless of the answer.
        notificationLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> requestProjection());

        startButton.setOnClickListener(v -> onStartClicked());
        stopButton.setOnClickListener(v ->
                startService(ScreenCaptureService.createStopIntent(this)));
        copyButton.setOnClickListener(v -> copyUrl());

        networkMonitor = new NetworkUtils.Monitor(this, this::refreshNetwork);
        StreamRepository.status().observe(this, this::render);
    }

    @Override
    protected void onStart() {
        super.onStart();
        networkMonitor.start();
        refreshNetwork();
    }

    @Override
    protected void onStop() {
        networkMonitor.stop();
        super.onStop();
    }

    // -------------------------------------------------------------- actions

    private void onStartClicked() {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            refreshNetwork();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            requestProjection();
        }
    }

    private void requestProjection() {
        if (StreamRepository.current().state != StreamRepository.State.IDLE) return;
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            StreamRepository.publish(StreamRepository.Status.idle(
                    "Screen capture is not available on this device."));
            return;
        }
        try {
            projectionLauncher.launch(mpm.createScreenCaptureIntent());
        } catch (RuntimeException e) {
            StreamRepository.publish(StreamRepository.Status.idle(
                    "Could not open the screen capture dialog: " + e.getMessage()));
        }
    }

    private void launchService(int resultCode, Intent data) {
        try {
            ContextCompat.startForegroundService(this,
                    ScreenCaptureService.createStartIntent(this, resultCode, data));
        } catch (RuntimeException e) {
            StreamRepository.publish(StreamRepository.Status.idle(
                    getString(R.string.error_service_start) + " (" + e.getMessage() + ")"));
        }
    }

    private void copyUrl() {
        StreamRepository.Status status = StreamRepository.current();
        if (status.url == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.viewer_url), status.url));
            Toast.makeText(this, R.string.url_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshNetwork() {
        networkAvailable = NetworkUtils.isNetworkAvailable(this);
        render(StreamRepository.current());
    }

    // ------------------------------------------------------------ rendering

    private void render(StreamRepository.Status s) {
        boolean idle = s.state == StreamRepository.State.IDLE;
        boolean streaming = s.state == StreamRepository.State.STREAMING;

        switch (s.state) {
            case STREAMING:
                statusText.setText(R.string.status_streaming);
                statusText.setBackgroundResource(R.drawable.bg_status_live);
                statusText.setTextColor(ContextCompat.getColor(this, R.color.live));
                break;
            case STARTING:
                statusText.setText(R.string.status_starting);
                statusText.setBackgroundResource(R.drawable.bg_status_idle);
                statusText.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
                break;
            default:
                statusText.setText(R.string.status_ready);
                statusText.setBackgroundResource(R.drawable.bg_status_idle);
                statusText.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
                break;
        }

        viewerStatusText.setVisibility(streaming ? View.VISIBLE : View.GONE);
        viewerStatusText.setText(s.viewerStatus);

        boolean showError = idle && s.error != null && !s.error.isEmpty();
        errorText.setVisibility(showError ? View.VISIBLE : View.GONE);
        errorText.setText(showError ? s.error : "");

        hintText.setVisibility(idle && !networkAvailable ? View.VISIBLE : View.GONE);

        startButton.setVisibility(idle || s.state == StreamRepository.State.STARTING ? View.VISIBLE : View.GONE);
        startButton.setEnabled(idle && networkAvailable);
        stopButton.setVisibility(streaming ? View.VISIBLE : View.GONE);

        String url = streaming ? s.url : null;
        if (url != null) {
            urlCard.setVisibility(View.VISIBLE);
            urlText.setText(url);
            showQr(url);
        } else {
            urlCard.setVisibility(View.GONE);
            qrImage.setImageDrawable(null);
            renderedQrUrl = null;
        }
    }

    private void showQr(String url) {
        if (url.equals(renderedQrUrl)) return;
        try {
            Bitmap bitmap = QRCodeUtils.generate(url, 480);
            BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
            drawable.setFilterBitmap(false); // keep the modules crisp
            qrImage.setImageDrawable(drawable);
            qrImage.setVisibility(View.VISIBLE);
            renderedQrUrl = url;
        } catch (WriterException e) {
            qrImage.setVisibility(View.GONE);
        }
    }
}
