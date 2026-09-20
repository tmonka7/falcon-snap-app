package com.falcon.snap;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.falcon.snap.ui.Dialogs;

public class AboutActivity extends BaseActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        setupTopBar(R.string.title_about);

        ((TextView) findViewById(R.id.about_version)).setText(getString(R.string.about_version_fmt, versionName()));

        ViewGroup container = findViewById(R.id.about_container);
        ViewGroup links = SettingRows.addGroup(this, container);
        SettingRows.addRow(this, links, R.string.about_terms,
                v -> Dialogs.showInfo(this, R.string.about_terms, R.string.terms_text));
        SettingRows.addRow(this, links, R.string.about_privacy,
                v -> Dialogs.showInfo(this, R.string.about_privacy, R.string.privacy_text));
        SettingRows.addRow(this, links, R.string.about_licenses,
                v -> Dialogs.showInfo(this, R.string.about_licenses, R.string.licenses_text));
        SettingRows.addRow(this, links, R.string.about_contact, v -> contact());
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void contact() {
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + getString(R.string.contact_email)));
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_email_app, Toast.LENGTH_SHORT).show();
        }
    }
}
