package net.sourceforge.servestream.activity;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import net.sourceforge.servestream.utils.CrashReporter;

/**
 * Shows the last crash in a separate process with no app theme or fragments,
 * so it still appears when the main screen itself crashes on start.
 */
public class CrashReportActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String report = CrashReporter.takeJavaCrash(this);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("ServeStream crashed. Copy this report and send it.");
        title.setTextSize(18);
        root.addView(title);

        Button copy = new Button(this);
        copy.setText("Copy");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("crash", report));
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(copy);

        ScrollView scroll = new ScrollView(this);
        TextView body = new TextView(this);
        body.setTextIsSelectable(true);
        body.setTextSize(12);
        body.setText(report != null ? report : "(no report)");
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }
}
