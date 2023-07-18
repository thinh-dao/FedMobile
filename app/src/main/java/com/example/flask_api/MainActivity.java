package com.example.flask_api;

import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.NonNull;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.icu.text.SimpleDateFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.storage.FileDownloadTask;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();
    private EditText address;
    private EditText port;
    private EditText client;
    private EditText nEpochs;
    private TextView status;
    private Button connectBtn;
    private Button loadDataBtn;
    private Button startTrainingBtn;
    private String url;
    private final String POST = "POST";
    private final String GET = "GET";
    private TransferLearning tl;
    private int current_round = 1;
    private String project_name;
    FirebaseStorage storage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = (TextView) findViewById(R.id.resultID);
        status.setMovementMethod(new ScrollingMovementMethod());
        status.setFocusableInTouchMode(false);
        status.clearFocus();

        address = (EditText) findViewById(R.id.addressID);
        port = (EditText) findViewById(R.id.portID);
        client = (EditText) findViewById(R.id.client_id);
        nEpochs = (EditText) findViewById(R.id.epochs_ID);

        connectBtn = (Button) findViewById(R.id.connectID);
        loadDataBtn = (Button) findViewById(R.id.loadDataID) ;
        startTrainingBtn = (Button) findViewById(R.id.trainingID);

        tl = new TransferLearning(
                getApplicationContext(),
                "model",
                Arrays.asList(CIFAR10BatchFileParser.getClasses()),
                10);
        storage = FirebaseStorage.getInstance();
    }
    public void connect(View view) {
        hideKeyboard(this);
        if (TextUtils.isEmpty(address.getText().toString())) {
            Toast.makeText(this, "Please enter the server IP", Toast.LENGTH_SHORT).show();
        }
        if (TextUtils.isEmpty(port.getText().toString())) {
            Toast.makeText(this, "Please enter the server port", Toast.LENGTH_SHORT).show();
        }
        else if (TextUtils.isEmpty(client.getText().toString())) {
            Toast.makeText(this, "Please enter a client partition ID", Toast.LENGTH_SHORT).show();
        }
        else if (Integer.parseInt(client.getText().toString()) > 10 ||  Integer.parseInt(client.getText().toString()) < 1)
        {
            Toast.makeText(this, "Please enter a client partition ID between 1 and 10 (inclusive)", Toast.LENGTH_LONG).show();
        }
        else if (TextUtils.isEmpty(nEpochs.getText().toString())) {
            Toast.makeText(this, "Please enter training epochs", Toast.LENGTH_SHORT).show();
        }
        else {
            url = "http://" + address.getText().toString() + ":" + port.getText().toString();
            String client_id = String.valueOf(client.getText());
            setResultText("Connecting to server...");
            sendRequest(GET, "connect", "client_id", client_id);
        }
    }
    public void loadData(View view) {
        hideKeyboard(this);
        Toast.makeText(this, "Loading the local training dataset in memory.", Toast.LENGTH_LONG).show();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());
        String dataFile = "client_" + client.getText() + ".bin";

        executor.execute(new Runnable() {
            private String result;

            @Override
            public void run() {
                try {
                    AssetFileDescriptor fileDescriptor = getApplicationContext().getAssets().openFd("data/"+dataFile);
                    FileInputStream stream = fileDescriptor.createInputStream();
                    boolean isSucess = dataLoader(stream);
                    if (isSucess) {
                        result = "Training dataset is loaded in memory.";
                    }
                    else {
                        result = "Failed to load training dataset";
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                    result = "Exception occurred! Failed to load training dataset";
                }
                handler.post(() -> {
                    setResultText(result);
                    loadDataBtn.setEnabled(false);
                    startTrainingBtn.setEnabled(true);
                });
            }
        });
    }
    public void load_and_train(int current_round) throws IOException  {
        // Create a storage reference from our app
        StorageReference storageRef = storage.getReference();

        // Create a reference with an initial file path and name
        String path = String.format("%s/Round_%d/global_model.bin", project_name, current_round);
        Log.d("PATH", path);
        StorageReference pathReference = storageRef.child(path);
        File localFile = new File(getApplicationContext().getFilesDir(), "global_model.bin");
        pathReference.getFile(localFile).addOnCompleteListener(new OnCompleteListener<FileDownloadTask.TaskSnapshot>() {
            @Override
            public void onComplete(@NonNull Task<FileDownloadTask.TaskSnapshot> task) {
                if (task.isSuccessful()) {
                    setResultText("Global Model for round " + String.valueOf(current_round) + " is downloaded");
//                    tl.loadParameters(localFile);
                    setResultText("Training started..");
                    tl.startTraining();

                    String global_model = "global_model_" + String.valueOf(current_round) + ".bin";
                    File globalModelFile = new File(getApplicationContext().getFilesDir(), global_model);

                    // save weights (replace existing file)
                    if (globalModelFile.exists())
                        globalModelFile.delete();
                    setResultText("Training done! Save and upload model to server.");
                    tl.saveParameters(globalModelFile);
                    uploadParameters(globalModelFile);
                    // close TL
                    tl.close();
                    tl = null;
                    uploadParameters(globalModelFile);
                } else {
                    if (!task.isSuccessful()) {
                        Log.e("DOWNLOAD_FAILURE", "Error getting data", task.getException());
                    }
                    setResultText("Failure to download global model!");
                }
            }
        });
    }
    public void startTraining(View view) throws IOException {
        load_and_train(current_round);
    }
    void sendRequest(String type, String method, String paramname, String param){
        /* if url is of our get request, it should not have parameters according to our implementation.
         * But our post request should have 'name' parameter. */
        String fullURL=url+"/" + method + (param==null?"":"/"+param);
        Log.d("URL", fullURL);
        Log.d(TAG, fullURL);
        Request request = null;

        OkHttpClient client = new OkHttpClient().newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS).build();

        /* If it is a post request, then we have to pass the parameters inside the request body*/
        if(type.equals(POST)){
            RequestBody formBody = new FormBody.Builder()
                    .add(paramname, param)
                    .build();

            request=new Request.Builder()
                    .url(fullURL)
                    .post(formBody)
                    .build();
        } else if (type.equals(GET)) {
            /*If it's our get request, it doesn't require parameters, hence just sending with the url*/
            request = new Request.Builder()
                    .url(fullURL)
                    .build();
        }
        else {
            setResultText("Unknown request!");
        }
        /* this is how the callback get handled */
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                e.printStackTrace();
                setResultText("Unsuccessful!");
            }
            @Override
            public void onResponse(Call call, final Response response) throws IOException {
                if (!response.isSuccessful()) {
                    setResultText("Response code " + response);
                    throw new IOException("Unexpected code " + response);
                }
                else {
                    if (method.equals("connect")) {
                        String responseData = response.body().string();
                        setResultText(responseData);
                        if (responseData.startsWith("Successful")) {
                            project_name = responseData.split(" ")[4];
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    connectBtn.setEnabled(false);
                                    loadDataBtn.setEnabled(true);
                                }
                            });
                        }
                    }
                }
            }
        });
    }
    public static void hideKeyboard(Activity activity) {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        View view = activity.getCurrentFocus();
        if (view == null) {
            view = new View(activity);
        }
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    public void setResultText(String text) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss", Locale.GERMANY);
        String time = dateFormat.format(new Date());
        MainActivity.this.runOnUiThread(() -> status.append("\n" + time + "   " + text));
    }

    public boolean dataLoader(FileInputStream stream) {
        CIFAR10BatchFileParser dataManager;
        try {
            // Init dataManager
            dataManager = new CIFAR10BatchFileParser(
                    stream,
                    0,
                    224);

        } catch (IOException e) {
            e.printStackTrace();
            setResultText("Exception occurred!");
            return false;
        }
        // add samples
        while (dataManager.hasNext()){
            dataManager.next();
            Random random = new Random();
            // get data
            float[] data = dataManager.getData(random.nextBoolean());
            int label = dataManager.getLabel();

            try {
                tl.addSample(data, CIFAR10BatchFileParser.getClass(label)).get();
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
                setResultText("Exception occurred!");
                return false;
            }
        }

        // close dataManager (not needed any more)
        dataManager.close();
        return true;
    }
    public void uploadParameters(File model_weight) {
        StorageReference storageRef = storage.getReference();
        Uri file = Uri.fromFile(model_weight);
        String path = String.format("%s/Round_%d/client_%d.bin", project_name, current_round, String.valueOf(client));
        Log.d("PATH", path);
        StorageReference ref = storageRef.child(path);
        UploadTask uploadTask = ref.putFile(file);

        // Register observers to listen for when the download is done or if it fails
        uploadTask.addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception exception) {
                exception.printStackTrace();
                setResultText("Failure to upload weight");
            }
        }).addOnSuccessListener(new OnSuccessListener<UploadTask.TaskSnapshot>() {
            @Override
            public void onSuccess(UploadTask.TaskSnapshot taskSnapshot) {
                setResultText("Weight uploaded to server");
            }
        });
    }
}