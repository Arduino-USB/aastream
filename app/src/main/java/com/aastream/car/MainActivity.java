package com.example.mycarapp;

import android.os.Bundle;
import android.widget.TextView;
import android.view.Gravity;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // For now, we will just create a simple text view programmatically
        TextView textView = new TextView(this);
        textView.setText("Welcome to the Mobile Screen!\nPlug your phone into your car.");
        textView.setTextSize(20);
        textView.setGravity(Gravity.CENTER);

        setContentView(textView);
    }
}
