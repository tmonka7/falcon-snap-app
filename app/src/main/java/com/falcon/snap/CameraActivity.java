package com.falcon.snap;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.ExecutionException;

/** Camera capture screen: preview, flash, flip, tap-to-focus, and a shortcut to the gallery. */
public class CameraActivity extends BaseActivity {
    private static final String CAPTURE_PREFIX = "capture_";
    private static final long STALE_CAPTURE_MILLIS = 24L * 60 * 60 * 1000;

    private PreviewView previewView;
    private ImageButton flashButton;
    private View shutterButton;
    private TextView messageView;

    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private Camera camera;
    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private int flashMode = ImageCapture.FLASH_MODE_OFF;

    private ActivityResultLauncher<String> permissionLauncher;
    private ActivityResultLauncher<String> galleryLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                startCamera();
            } else {
                showMessage(R.string.camera_permission_denied);
            }
        });
        galleryLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), this::openResult);

        previewView = findViewById(R.id.preview_view);
        flashButton = findViewById(R.id.cam_flash);
        shutterButton = findViewById(R.id.cam_shutter);
        messageView = findViewById(R.id.cam_message);

        findViewById(R.id.cam_back).setOnClickListener(v -> finish());
        findViewById(R.id.cam_gallery).setOnClickListener(v -> galleryLauncher.launch("image/*"));
        findViewById(R.id.cam_gallery_top).setOnClickListener(v -> galleryLauncher.launch("image/*"));
        findViewById(R.id.cam_flip).setOnClickListener(v -> flipCamera());
        flashButton.setOnClickListener(v -> cycleFlash());
        shutterButton.setOnClickListener(v -> takePhoto());
        bindLanguageBar();
        setupTapToFocus();
        deleteStaleCaptures();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        messageView.setVisibility(View.GONE);
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                // The view port is only known once the preview has been laid out.
                previewView.post(this::bindUseCases);
            } catch (ExecutionException | InterruptedException e) {
                showMessage(R.string.camera_unavailable);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases() {
        if (cameraProvider == null || isFinishing() || isDestroyed()) {
            return;
        }
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashMode)
                .build();

        UseCaseGroup.Builder group = new UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageCapture);
        // Crop the photo to what the preview shows, so the result matches what the user framed.
        ViewPort viewPort = previewView.getViewPort();
        if (viewPort != null) {
            group.setViewPort(viewPort);
        }

        try {
            cameraProvider.unbindAll();
            CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
            camera = cameraProvider.bindToLifecycle(this, selector, group.build());
        } catch (IllegalArgumentException | IllegalStateException e) {
            camera = null;
            showMessage(R.string.camera_unavailable);
        }
    }

    private void takePhoto() {
        if (imageCapture == null || camera == null) {
            return;
        }
        shutterButton.setEnabled(false);
        File file = new File(getCacheDir(), CAPTURE_PREFIX + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(file).build();
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                shutterButton.setEnabled(true);
                openResult(Uri.fromFile(file));
            }

            @Override
            public void onError(@NonNull ImageCaptureException e) {
                shutterButton.setEnabled(true);
                Toast.makeText(CameraActivity.this, R.string.capture_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void cycleFlash() {
        if (flashMode == ImageCapture.FLASH_MODE_OFF) {
            flashMode = ImageCapture.FLASH_MODE_ON;
            flashButton.setImageResource(R.drawable.ic_flash_on);
        } else if (flashMode == ImageCapture.FLASH_MODE_ON) {
            flashMode = ImageCapture.FLASH_MODE_AUTO;
            flashButton.setImageResource(R.drawable.ic_flash_auto);
        } else {
            flashMode = ImageCapture.FLASH_MODE_OFF;
            flashButton.setImageResource(R.drawable.ic_flash_off);
        }
        if (imageCapture != null) {
            imageCapture.setFlashMode(flashMode);
        }
    }

    private void flipCamera() {
        if (cameraProvider == null) {
            return;
        }
        int other = lensFacing == CameraSelector.LENS_FACING_BACK
                ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        CameraSelector selector = new CameraSelector.Builder().requireLensFacing(other).build();
        try {
            if (cameraProvider.hasCamera(selector)) {
                lensFacing = other;
                bindUseCases();
            }
        } catch (CameraInfoUnavailableException e) {
            // Keep the current camera.
        }
    }

    @SuppressLint("ClickableViewAccessibility") // Tap-to-focus has no meaningful click action.
    private void setupTapToFocus() {
        previewView.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP && camera != null) {
                MeteringPoint point = previewView.getMeteringPointFactory().createPoint(event.getX(), event.getY());
                camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(point).build());
            }
            return true;
        });
    }

    private void openResult(@Nullable Uri image) {
        if (image == null) {
            return;
        }
        Intent intent = new Intent(this, ResultActivity.class);
        intent.setData(image);
        if ("content".equals(image.getScheme())) {
            // Gallery picks: pass our temporary read grant on to the result screen.
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        startActivity(intent);
    }

    private void showMessage(int messageRes) {
        messageView.setText(messageRes);
        messageView.setVisibility(View.VISIBLE);
    }

    /** Captures are only needed until they have been processed; clear out old ones. */
    private void deleteStaleCaptures() {
        File[] files = getCacheDir().listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - STALE_CAPTURE_MILLIS;
        for (File file : files) {
            if (file.getName().startsWith(CAPTURE_PREFIX) && file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }
}
