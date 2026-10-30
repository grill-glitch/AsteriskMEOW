package org.asterisk.meow.poc;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.asterisk.meow.Libmeow;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int VPN_REQUEST = 1;

    private TextView status;
    private Button toggle;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.START);

        TextView title = new TextView(this);
        title.setText("AsteriskMEOW · libmeow PoC");
        title.setTextSize(20);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        toggle = new Button(this);
        toggle.setText("Start VPN");
        toggle.setOnClickListener(v -> onToggle());
        root.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        String version;
        try {
            Libmeow.nativeInit();
            version = Libmeow.nativeVersion();
        } catch (Throwable t) {
            version = "libmeow load failed: " + t;
        }
        status.setText(version);
        handler.post(ticker);
    }

    private void onToggle() {
        if (Libmeow.nativeIsRunning()) {
            startService(new Intent(this, MeowVpnService.class)
                    .setAction(MeowVpnService.ACTION_STOP));
            return;
        }
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            startActivityForResult(prepare, VPN_REQUEST);
        } else {
            startVpnService();
        }
    }

    private void startVpnService() {
        startForegroundService(new Intent(this, MeowVpnService.class)
                .setAction(MeowVpnService.ACTION_START));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            startVpnService();
        }
    }

    private void updateStatus() {
        boolean running;
        try {
            running = Libmeow.nativeIsRunning();
        } catch (Throwable t) {
            status.setText("native error: " + t);
            return;
        }
        toggle.setText(running ? "Stop VPN" : "Start VPN");
        if (running) {
            long up = Libmeow.nativeUploadTotal();
            long down = Libmeow.nativeDownloadTotal();
            status.setText(String.format(Locale.US,
                    "engine: running\nupload: %,d B\ndownload: %,d B\n%s",
                    up, down, MeowVpnService.lastStatus));
        } else {
            status.setText("engine: stopped\n" + MeowVpnService.lastStatus);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        super.onDestroy();
    }
}
