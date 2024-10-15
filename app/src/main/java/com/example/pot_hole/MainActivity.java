package com.example.pot_hole;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.provider.MediaStore;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import com.example.pot_hole.ml.Model;

public class MainActivity extends AppCompatActivity implements LocationListener {

    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final int REQUEST_LOCATION_PERMISSION = 2;
    private static final int REQUEST_SEND_SMS = 3;
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "PotholePrefs";
    private static final String POTHOLE_KEY = "potholes";

    private ImageView imageView;
    private WebView webView;
    private LocationManager locationManager;
    private Location currentLocation;
    private ArrayList<String> potholeList = new ArrayList<>();

    private Model model;
    private boolean toastMessagesEnabled = true; // Variable to control Toast messages
    private boolean sendSmsEnabled = true; // Variable to control sending SMS

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
        ImageButton refreshButton = findViewById(R.id.refresh_button);
        ImageButton potholeListButton = findViewById(R.id.pothole_list_button);
        ImageButton settingsButton = findViewById(R.id.settings_button);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Re-send the current location to the WebView after it finishes loading
                if (currentLocation != null) {
                    String jsCode = "window.postMessage({type: 'currentLocation', lat: " + currentLocation.getLatitude() + ", lon: " + currentLocation.getLongitude() + "}, '*');";
                    webView.evaluateJavascript(jsCode, null);
                }
                loadSavedPotholes();
            }
        });

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webView.loadUrl("file:///android_asset/map.html");

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        // Check if location services are enabled
        checkLocationServices();

        if (ContextCompat.checkSelfPermission(  this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
        } else {
            startLocationUpdates();
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }

        takePictureButton.setOnClickListener(v -> dispatchTakePictureIntent());
        refreshButton.setOnClickListener(v -> refreshMap());
        potholeListButton.setOnClickListener(v -> showPotholeListDialog());
        settingsButton.setOnClickListener(v -> showSettingsDialog());

        // Load TensorFlow Lite model
        try {
            model = Model.newInstance(this);
            Log.d(TAG, "Model loaded successfully");
            if (toastMessagesEnabled) {
                Toast.makeText(this, "Model loaded successfully", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Failed to load model", e);
            if (toastMessagesEnabled) {
                Toast.makeText(this, "Failed to load model", Toast.LENGTH_LONG).show();
            }
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
            if (toastMessagesEnabled) {
                Toast.makeText(this, "Model is not loaded", Toast.LENGTH_LONG).show();
            }
            Log.e(TAG, "Model is not loaded");
            return;
        }

        try {
            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, 224, 224, 3}, DataType.FLOAT32);
            ByteBuffer byteBuffer = convertBitmapToByteBuffer(bitmap);
            inputFeature0.loadBuffer(byteBuffer);
            Log.d(TAG, "Image loaded into tensor buffer");
            if (toastMessagesEnabled) {
                Toast.makeText(this, "Image prepared for classification", Toast.LENGTH_SHORT).show();
            }

            Model.Outputs outputs = model.process(inputFeature0);
            TensorBuffer outputFeature0 = outputs.getOutputFeature0AsTensorBuffer();

            float[] probabilities = outputFeature0.getFloatArray();
            if (probabilities.length != 2 || Float.isNaN(probabilities[0]) || Float.isNaN(probabilities[1])) {
                Log.e(TAG, "Invalid output from model: NaN values detected");
                if (toastMessagesEnabled) {
                    Toast.makeText(this, "Invalid output from model (NaN values)", Toast.LENGTH_LONG).show();
                }
                return;
            }
            Log.d(TAG, "Model output probabilities: [" + probabilities[0] + ", " + probabilities[1] + "]");
            if (toastMessagesEnabled) {
                Toast.makeText(this, "Inference completed: Pothole prob: " + probabilities[0] + ", Not Pothole prob: " + probabilities[1], Toast.LENGTH_LONG).show();
            }

            float threshold = 0.5f;
            String resultString = probabilities[0] > threshold ? "pothole" : "not a pothole";

            // Ensure currentLocation is updated just before using it
            if ("pothole".equals(resultString) && currentLocation != null) {
                double lat = currentLocation.getLatitude();
                double lon = currentLocation.getLongitude();
                Log.d(TAG, "Using location for pothole: " + lat + ", " + lon); // Debug log

                // Send location to the WebView
                String jsCode = "window.postMessage({type: 'location', lat: " + lat + ", lon: " + lon + "}, '*');";
                webView.evaluateJavascript(jsCode, null);

                String pothole = "Pothole at: " + lat + ", " + lon;
                if (!potholeList.contains(pothole)) {
                    potholeList.add(pothole);
                    savePotholes();
                    if (sendSmsEnabled) {
                        sendSms(lat, lon); // Send SMS automatically when a pothole is detected
                    }
                }

                if (toastMessagesEnabled) {
                    Toast.makeText(MainActivity.this, "Pothole detected at: " + lat + ", " + lon, Toast.LENGTH_LONG).show();
                }
                Log.d(TAG, "Pothole detected at: " + lat + ", " + lon);
            } else {
                if (toastMessagesEnabled) {
                    Toast.makeText(MainActivity.this, "Not a Pothole", Toast.LENGTH_LONG).show();
                }
                Log.d(TAG, "Image classified as not a pothole");
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "Error during model inference", e);
            if (toastMessagesEnabled) {
                Toast.makeText(this, "Error during model inference", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void sendSms(double lat, double lon) {
        String phoneNumber = "9902956437"; // Replace with the actual phone number
        String message = "Pothole detected at coordinates:\nLatitude: " + lat + "\nLongitude: " + lon + "\n\n- Team idkWhatWe'reDoing";

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "SMS permission not granted. Requesting permission.");
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.SEND_SMS}, REQUEST_SEND_SMS);
        } else {
            Log.d(TAG, "Sending SMS to: " + phoneNumber);
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(phoneNumber, null, message, null, null);
            Toast.makeText(this, "SMS sent successfully!", Toast.LENGTH_SHORT).show();
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
            byteBuffer.putFloat(((value >> 16) & 0xFF) / 255.0f);
            byteBuffer.putFloat(((value >> 8) & 0xFF) / 255.0f);
            byteBuffer.putFloat((value & 0xFF) / 255.0f);
        }

        return byteBuffer;
    }

    private void startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // Use both GPS and Network providers for location updates
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 10, this);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 10, this);

            // Fallback to last known location
            Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnownLocation != null) {
                currentLocation = lastKnownLocation;
                onLocationChanged(lastKnownLocation); // Handle the location
            }
        } else {
            Log.d(TAG, "Location permission not granted. Unable to start location updates.");
        }
    }

    private void checkLocationServices() {
        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if (!gpsEnabled && !networkEnabled) {
            new AlertDialog.Builder(this)
                    .setTitle("Enable Location")
                    .setMessage("Your location settings are off. Please enable location to use this app.")
                    .setPositiveButton("Location Settings", (dialog, which) -> {
                        startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        currentLocation = location;
        Log.d(TAG, "Location updated: " + location.getLatitude() + ", " + location.getLongitude());
        String jsCode = "window.postMessage({type: 'currentLocation', lat: " + location.getLatitude() + ", lon: " + location.getLongitude() + "}, '*');";
        webView.evaluateJavascript(jsCode, null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                dispatchTakePictureIntent();
            } else {
                // Permission denied, show a message to the user
                new AlertDialog.Builder(this)
                        .setTitle("Permission Required")
                        .setMessage("Camera permission is required to take pictures.")
                        .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                        .show();
            }
        } else if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                // Permission denied, show a message to the user
                new AlertDialog.Builder(this)
                        .setTitle("Permission Required")
                        .setMessage("Location permission is required to access location.")
                        .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                        .show();
            }
        } else if (requestCode == REQUEST_SEND_SMS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, retry sending SMS
                Log.d(TAG, "SMS permission granted. Retrying SMS sending.");
                if (currentLocation != null) {
                    sendSms(currentLocation.getLatitude(), currentLocation.getLongitude());
                }
            } else {
                Toast.makeText(this, "SMS permission denied.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void refreshMap() {
        // Clear existing potholes from the map
        webView.evaluateJavascript("window.clearPotholes();", null);

        // Reload the map
        webView.reload();

        if (toastMessagesEnabled) {
            Toast.makeText(this, "Map refreshed", Toast.LENGTH_SHORT).show();
        }
    }

    private void showPotholeListDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Pothole List");

        ListView listView = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, potholeList);
        listView.setAdapter(adapter);

        builder.setView(listView);
        builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Settings");

        // Inflate the custom layout
        LayoutInflater inflater = getLayoutInflater();
        View settingsView = inflater.inflate(R.layout.dialog_settings, null);

        CheckBox toastMessagesCheckBox = settingsView.findViewById(R.id.toast_messages_checkbox);
        toastMessagesCheckBox.setChecked(toastMessagesEnabled);

        CheckBox sendSmsCheckBox = settingsView.findViewById(R.id.send_sms_checkbox);
        sendSmsCheckBox.setChecked(sendSmsEnabled);

        builder.setView(settingsView);
        builder.setPositiveButton("Save", (dialog, which) -> {
            toastMessagesEnabled = toastMessagesCheckBox.isChecked();
            sendSmsEnabled = sendSmsCheckBox.isChecked();
            if (toastMessagesEnabled) {
                Toast.makeText(MainActivity.this, "Toast messages enabled", Toast.LENGTH_SHORT).show();
            }
            if (sendSmsEnabled) {
                Toast.makeText(MainActivity.this, "SMS sending enabled", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void savePotholes() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        Set<String> potholes = new HashSet<>(potholeList);
        editor.putStringSet(POTHOLE_KEY, potholes);
        editor.apply();
        Log.d(TAG, "Potholes saved: " + potholes);
    }

    private void loadSavedPotholes() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Set<String> potholes = prefs.getStringSet(POTHOLE_KEY, new HashSet<>());

        // Clear existing potholes from the list
        potholeList.clear();

        for (String pothole : potholes) {
            potholeList.add(pothole);
            String[] parts = pothole.split(": ");
            String[] coords = parts[1].split(", ");
            double lat = Double.parseDouble(coords[0]);
            double lon = Double.parseDouble(coords[1]);
            webView.evaluateJavascript("window.postMessage({type: 'location', lat: " + lat + ", lon: " + lon + "}, '*');", null);
        }
        Log.d(TAG, "Potholes loaded: " + potholes);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (model != null) {
            model.close();
        }
    }
}
