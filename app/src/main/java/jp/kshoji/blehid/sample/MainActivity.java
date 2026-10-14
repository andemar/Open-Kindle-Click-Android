package jp.kshoji.blehid.sample;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.view.View.OnClickListener;

import jp.kshoji.blehid.sample.R.id;
import jp.kshoji.blehid.sample.R.layout;
import jp.kshoji.blehid.sample.R.string;

/**
 * Main Activity
 * 
 * @author K.Shoji
 */
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(layout.activity_main);

        setTitle(getString(string.kindle_click));
        
        findViewById(id.kindleClickButton).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(getApplicationContext(), KindleClickActivity.class));
            }
        });
    }
}
