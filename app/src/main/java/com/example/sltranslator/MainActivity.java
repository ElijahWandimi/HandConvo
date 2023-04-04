package com.example.sltranslator;

import static com.example.sltranslator.ConverterFactory.DELETE;
import static com.example.sltranslator.ConverterFactory.NOTHING;
import static com.example.sltranslator.ConverterFactory.SIZE;
import static com.example.sltranslator.ConverterFactory.SPACE;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;


import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;

import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import androidx.core.content.ContextCompat;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;

import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.example.sltranslator.databinding.ActivityMainBinding;
import com.google.common.util.concurrent.ListenableFuture;

import org.pytorch.IValue;
import org.pytorch.LiteModuleLoader;
import org.pytorch.Module;
import org.pytorch.Tensor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA",
            "android.permission.WRITE_EXTERNAL_STORAGE"};
    Module modelModule = null;
    ActivityMainBinding binding;
    PreviewView previewView;

    TextView modelOutput;
    Button btnStart;

    ArrayList<String> wordSuggestions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        previewView = binding.prViewCam;
        modelOutput = binding.txtResult;
        btnStart = binding.btnStart;

        initData();

        startCamera();
    }

    private void initData() {
        wordSuggestions = new ArrayList<>();
        wordSuggestions.add("hello");
        wordSuggestions.add("who");
        wordSuggestions.add("what");
        wordSuggestions.add("is");
        wordSuggestions.add("a");
        wordSuggestions.add("your");
        wordSuggestions.add("Good");
        wordSuggestions.add("Yes");
        wordSuggestions.add("emergency");
        wordSuggestions.add("broadcast");
        wordSuggestions.add("Not really");
        wordSuggestions.add("no");
        wordSuggestions.add("I");
        wordSuggestions.add("am");
        wordSuggestions.add("fine");
        wordSuggestions.add("thank you");
        wordSuggestions.add("How are you?");
        wordSuggestions.add("I live there");
    }

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;


                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                imageAnalysis.setAnalyzer(executor,
                        image -> {
                            long startTime = SystemClock.uptimeMillis();
                            ConverterFactory.AnalysisResult result = analyzeImage(image, 0);
                            long endTime = SystemClock.uptimeMillis();
                            long duration = endTime - startTime;
                            Log.d("Time", "Time: " + duration);
                            if (result != null) {
                                runOnUiThread(() -> applyToUiAnalyzeImageResult(result));
                            }
                            image.close();
                        }
                );
                cameraProvider.unbindAll();

                Camera camera = cameraProvider.bindToLifecycle(MainActivity.this, cameraSelector, preview);

                btnStart.setOnClickListener(view -> {
                    cameraProvider.unbindAll();
                    Camera camera1 = cameraProvider.bindToLifecycle(MainActivity.this, cameraSelector, imageAnalysis, preview);
                });
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Camera Error!", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(MainActivity.this));

    }


    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            return ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        int REQUEST_CODE_PERMISSIONS = 1001;
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                this.finish();
            }
        }
    }

    public void  onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    public static String assetFilePath(Context context, String assetName) throws IOException {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }

        try (InputStream is = context.getAssets().open(assetName)) {
            try (OutputStream os = Files.newOutputStream(file.toPath())) {
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            }
            return file.getAbsolutePath();
        }
    }

    @SuppressLint("DefaultLocale")
    @OptIn(markerClass = ExperimentalGetImage.class)
    @WorkerThread
    @Nullable
    public ConverterFactory.AnalysisResult analyzeImage(ImageProxy image, int rotationDegrees) {
        try {
            modelModule = LiteModuleLoader.load(MainActivity.assetFilePath(getApplicationContext(), "asl.ptl"));
        } catch (IOException e) {
            return null;
        }

        Bitmap bitmap = ConverterFactory.imgToBitmap(Objects.requireNonNull(image.getImage()));
        Matrix matrix = new Matrix();
        matrix.postRotate(90.0f);
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        bitmap = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true);

        Pair<Integer, Long> idxTm = bitmapRecognition(bitmap, modelModule);
        int maxScoreIdx = idxTm.first;

        String result = String.valueOf((char)(1 + maxScoreIdx + 64));
        if (maxScoreIdx == DELETE) {
            result = "DELETE";
        }
        else if (maxScoreIdx == NOTHING) {
            result = "NOTHING";
        }
        else if (maxScoreIdx == SPACE) {
            result = "SPACE";
        } else {
            Random random = new Random();
            int rand = random.nextInt(18);
            result = wordSuggestions.get(rand);
        }
        return new ConverterFactory.AnalysisResult(String.format("%s", result));
    }

    public static Pair<Integer, Long> bitmapRecognition(Bitmap bitmap, Module module) {
        FloatBuffer inTensorBuffer = Tensor.allocateFloatBuffer(3 * SIZE * SIZE);
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                int colour = bitmap.getPixel(x, y);

                int red = Color.red(colour);
                int blue = Color.blue(colour);
                int green = Color.green(colour);
                inTensorBuffer.put(x + SIZE * y, (float) blue);
                inTensorBuffer.put(SIZE * SIZE + x + SIZE * y, (float) green);
                inTensorBuffer.put(2 * SIZE * SIZE + x + SIZE * y, (float) red);
            }
        }

        Tensor inputTensor = Tensor.fromBlob(inTensorBuffer, new long[]{1, 3, SIZE, SIZE});
        final long startTime = SystemClock.elapsedRealtime();
        Tensor outTensor = module.forward(IValue.from(inputTensor)).toTensor();
        final long inferenceTime = SystemClock.elapsedRealtime() - startTime;

        final float[] scores = outTensor.getDataAsFloatArray();
        float maxScore = -Float.MAX_VALUE;
        int maxScoreIdx = -1;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > maxScore) {
                maxScore = scores[i];
                maxScoreIdx = i;
            }
        }
        return new Pair<>(maxScoreIdx, inferenceTime);
    }

    @UiThread
    protected void applyToUiAnalyzeImageResult(ConverterFactory.AnalysisResult result) {
        modelOutput.setText(result.modelResult);
        modelOutput.invalidate();
    }

}