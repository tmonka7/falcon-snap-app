package com.falcon.snap;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.falcon.snap.ui.Dialogs;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;

/** Home screen, with the navigation drawer and bottom navigation. */
public class MainActivity extends BaseActivity {
    private DrawerLayout drawer;
    private NavigationView navView;
    private BottomNavigationView bottomNav;
    private ActivityResultLauncher<String> galleryLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        galleryLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), this::openResult);

        drawer = findViewById(R.id.drawer);
        navView = findViewById(R.id.nav_view);
        bottomNav = findViewById(R.id.bottom_nav);

        findViewById(R.id.home_logo).setOnClickListener(v -> drawer.openDrawer(GravityCompat.START));
        findViewById(R.id.home_settings).setOnClickListener(v -> open(SettingsActivity.class));
        findViewById(R.id.card_capture).setOnClickListener(v -> open(CameraActivity.class));

        ViewGroup cards = findViewById(R.id.home_cards);
        addCard(cards, R.drawable.ic_gallery, R.string.nav_gallery, R.string.home_gallery_sub, v -> pickFromGallery());
        addCard(cards, R.drawable.ic_history, R.string.nav_history, R.string.home_history_sub, v -> open(HistoryActivity.class));
        addCard(cards, R.drawable.ic_settings, R.string.nav_settings, R.string.home_settings_sub, v -> open(SettingsActivity.class));

        bindLanguageBar();
        setupDrawer();
        setupBottomNav();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Languages can also be changed from Settings; Home is always the selected destination here.
        refreshLanguageBar();
        navView.setCheckedItem(R.id.drawer_home);
    }

    @Override
    protected void refreshLanguageBar() {
        super.refreshLanguageBar();
        if (navView != null) {
            TextView drawerLanguages = navView.getHeaderView(0).findViewById(R.id.drawer_lang);
            drawerLanguages.setText(prefs.source().shortName + "  ⇄  " + prefs.target().shortName);
        }
    }

    @SuppressWarnings("deprecation") // Simple drawer-aware back handling.
    @Override
    public void onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    private void addCard(ViewGroup parent, int iconRes, int titleRes, int subtitleRes, View.OnClickListener listener) {
        View card = getLayoutInflater().inflate(R.layout.item_home_card, parent, false);
        ((ImageView) card.findViewById(R.id.card_icon)).setImageResource(iconRes);
        ((TextView) card.findViewById(R.id.card_title)).setText(titleRes);
        ((TextView) card.findViewById(R.id.card_subtitle)).setText(subtitleRes);
        card.setOnClickListener(listener);
        parent.addView(card);
    }

    private void setupDrawer() {
        navView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            drawer.closeDrawer(GravityCompat.START);
            if (id == R.id.drawer_history) {
                open(HistoryActivity.class);
            } else if (id == R.id.drawer_gallery) {
                pickFromGallery();
            } else if (id == R.id.drawer_settings) {
                open(SettingsActivity.class);
            } else if (id == R.id.drawer_help) {
                Dialogs.showHelp(this);
            } else if (id == R.id.drawer_about) {
                open(AboutActivity.class);
            }
            // Home stays the checked item: every other entry opens a separate screen.
            return false;
        });
    }

    private void setupBottomNav() {
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.bottom_history) {
                open(HistoryActivity.class);
                return false;
            } else if (id == R.id.bottom_settings) {
                open(SettingsActivity.class);
                return false;
            }
            return true;
        });
    }

    private void open(Class<?> activity) {
        startActivity(new Intent(this, activity));
    }

    private void pickFromGallery() {
        galleryLauncher.launch("image/*");
    }

    private void openResult(@Nullable Uri image) {
        if (image == null) {
            return;
        }
        Intent intent = new Intent(this, ResultActivity.class);
        intent.setData(image);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }
}
