package com.example.pot_hole;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.example.pot_hole.ml.Model;

public class MainActivity extends AppCompatActivity implements LocationListener {

    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final int REQUEST_LOCATION_PERMISSION = 2;
    private static final String TAG = "MainActivity";

    private ImageView imageView;
    private WebView webView;
    private LocationManager locationManager;
    private Location currentLocation;

    private Model model;

    private final ActivityResultLauncher<Intent> takePictureLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Bundle extras = result.getData().getExtras();
                        Bitmap imageBitmap = (Bitmap) extras.get("data");
                        imageView.setImageBitmap(imageBitmap);
                        classifyImage(imageBitmap);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageView = findViewById(R.id.image_view);
        webView = findViewById(R.id.webview);
        ImageButton takePictureButton = findViewById(R.id.take_picture_button);

        webView.setWebViewClient(new WebViewClient());
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webView.loadUrl("file:///android_asset/map.html");

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
        } else {
            startLocationUpdates();
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }

        takePictureButton.setOnClickListener(v -> dispatchTakePictureIntent());

        // Load TensorFlow Lite model
        try {
            model = Model.newInstance(this);
            Log.d(TAG, "Model loaded successfully");
            Toast.makeText(this, "Model loaded successfully", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Failed to load model", e);
            Toast.makeText(this, "Failed to load model", Toast.LENGTH_LONG).show();
        }
    }

    private void dispatchTakePictureIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            takePictureLauncher.launch(takePictureIntent);
        }
    }

    private void classifyImage(Bitmap bitmap) {
        if (model == null) {
            Toast.makeText(this, "Model is not loaded", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Model is not loaded");
            return;
        }

        try {
            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, 224, 224, 3}, DataType.FLOAT32);
            ByteBuffer byteBuffer = convertBitmapToByteBuffer(bitmap);
            inputFeature0.loadBuffer(byteBuffer);
            Log.d(TAG, "Image loaded into tensor buffer");
            Toast.makeText(this, "Image prepared for classification", Toast.LENGTH_SHORT).show();

            Model.Outputs outputs = model.process(inputFeature0);
            TensorBuffer outputFeature0 = outputs.getOutputFeature0AsTensorBuffer();

            float[] probabilities = outputFeature0.getFloatArray();
            if (probabilities.length != 2 || Float.isNaN(probabilities[0]) || Float.isNaN(probabilities[1])) {
                Log.e(TAG, "Invalid output from model: NaN values detected");
                Toast.makeText(this, "Invalid output from model (NaN values)", Toast.LENGTH_LONG).show();
                return;
            }
            Log.d(TAG, "Model output probabilities: [" + probabilities[0] + ", " + probabilities[1] + "]");
            Toast.makeText(this, "Inference completed: Pothole prob: " + probabilities[0] + ", Not Pothole prob: " + probabilities[1], Toast.LENGTH_LONG).show();

            float threshold = 0.5f;
            String resultString = probabilities[0] > threshold ? "pothole" : "not a pothole";

            if ("pothole".equals(resultString) && currentLocation != null) {
                double lat = currentLocation.getLatitude();
                double lon = currentLocation.getLongitude();
                webView.evaluateJavascript("window.postMessage({type: 'location', lat: " + lat + ", lon: " + lon + "}, '*');", null);
                Toast.makeText(MainActivity.this, "Pothole detected at: " + lat + ", " + lon, Toast.LENGTH_LONG).show();
                Log.d(TAG, "Pothole detected at: " + lat + ", " + lon);
            } else {
                Toast.makeText(MainActivity.this, "Not a Pothole", Toast.LENGTH_LONG).show();
                Log.d(TAG, "Image classified as not a pothole");
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "Error during model inference", e);
            Toast.makeText(this, "Error during model inference", Toast.LENGTH_LONG).show();
        }
    }

    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, 224, 224, true);
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * 224 * 224 * 3);
        byteBuffer.order(ByteOrder.nativeOrder());
        byteBuffer.rewind();

        int[] intValues = new int[224 * 224];
        resizedBitmap.getPixels(intValues, 0, 224, 0, 0, 224, 224);

        for (int value : intValues) {
            byteBuffer.putFloat(((value >> 16) & 0xFF) / 255.f);
            byteBuffer.putFloat(((value >> 8) & 0xFF) / 255.f);
            byteBuffer.putFloat((value & 0xFF) / 255.f);
        }
        Log.d(TAG, "Bitmap converted to ByteBuffer with normalization");
        Toast.makeText(this, "Image converted to ByteBuffer", Toast.LENGTH_SHORT).show();
        return byteBuffer;
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
            return;
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
        Log.d(TAG, "Location updates started");
        Toast.makeText(this, "Location updates started", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        currentLocation = location;
        Log.d(TAG, "Location updated: " + location.getLatitude() + ", " + location.getLongitude());

        if (webView != null && currentLocation != null) {
            String jsCode = "window.postMessage({type: 'currentLocation', lat: " + location.getLatitude() + ", lon: " + location.getLongitude() + "}, '*');";
            webView.evaluateJavascript(jsCode, null);
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(@NonNull String provider) {
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (model != null) {
            model.close();
            Log.d(TAG, "Model closed");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                dispatchTakePictureIntent();
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
