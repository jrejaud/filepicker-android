package io.filepicker.services;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

import de.greenrobot.event.EventBus;
import io.filepicker.api.FpApiClient;
import io.filepicker.events.ApiErrorEvent;
import io.filepicker.events.FileExportedEvent;
import io.filepicker.events.FpFilesReceivedEvent;
import io.filepicker.events.GotContentEvent;
import io.filepicker.events.UploadFileErrorEvent;
import io.filepicker.events.UploadProgressEvent;
import io.filepicker.models.FPFile;
import io.filepicker.models.Folder;
import io.filepicker.models.Node;
import io.filepicker.models.UploadLocalFileResponse;
import io.filepicker.utils.FilesUtils;
import io.filepicker.utils.Utils;
import retrofit.Callback;
import retrofit.RetrofitError;
import retrofit.client.Response;
import retrofit.mime.TypedFile;


/**
 * An {@link IntentService} subclass for handling asynchronous task requests in
 * a service on a separate handler thread.
 * <p>
 */
public class ContentService extends IntentService {

    private static final String LOG_TAG = ContentService.class.getSimpleName();

    private static final String ACTION_GET_CONTENT = "io.filepicker.services.action.get_content";
    private static final String ACTION_UPLOAD_FILE = "io.filepicker.services.action.upload_file";
    private static final String ACTION_PICK_FILES = "io.filepicker.services.action.pick_files";
    private static final String ACTION_EXPORT_FILE = "io.filepicker.services.action.export_file";

    private static final String EXTRA_BACK_PRESSED = "io.filepicker.services.extra.back_pressed";
    private static final String EXTRA_NODE = "io.filepicker.services.extra.node";
    private static final String EXTRA_FILENAME = "io.filepicker.services.extra.filename";

    // Used for upload file action and uri looks like content://<path to local file>
    private static final String EXTRA_FILE_URI = "io.filepicker.services.extra.file_uri";

    FilepickerListener filepickerListener;
    public interface FilepickerListener {
        void onLocalFileUploaded(List<FPFile> files);
    }

    public ContentService() {
        super("ContentService");
    }

    public static void getContent(Context context, Node node, boolean backPressed) {
        if(context == null) {
            return;
        }

        Intent intent = new Intent(context, ContentService.class);
        intent.setAction(ACTION_GET_CONTENT);
        intent.putExtra(EXTRA_NODE, node);
        intent.putExtra(EXTRA_BACK_PRESSED, backPressed);
        context.startService(intent);
    }

    public static void pickFiles(Context context, ArrayList<Node> files) {
        if(context == null) {
            return;
        }

        Intent intent = new Intent(context, ContentService.class);
        intent.setAction(ACTION_PICK_FILES);
        intent.putParcelableArrayListExtra(EXTRA_NODE, files);
        context.startService(intent);
    }

    public static void uploadFile(Context context, Uri fileUri) {
        if(context == null) {
            return;
        }

        Intent intent = new Intent(context, ContentService.class);
        intent.setAction(ACTION_UPLOAD_FILE);
        intent.putExtra(EXTRA_FILE_URI, fileUri);
        context.startService(intent);
    }

    public static void exportFile(Context context, Node node, Uri fileUri, String filename) {
        if(context == null) {
            return;
        }

        Intent intent = new Intent(context, ContentService.class);
        intent.setAction(ACTION_EXPORT_FILE);
        intent.putExtra(EXTRA_NODE, node);
        intent.putExtra(EXTRA_FILENAME, filename);
        intent.putExtra(EXTRA_FILE_URI, fileUri);
        context.startService(intent);
    }

    public static void cancelAll() {
        FpApiClient.cancelAll();
    }

    @Override
    protected  void onHandleIntent(Intent intent) {
        if(intent != null) {
            final String action = intent.getAction();

            Node node;
            switch(action) {
                case ACTION_GET_CONTENT:
                    node = intent.getParcelableExtra(EXTRA_NODE);
                    boolean backPressed = intent.getBooleanExtra(EXTRA_BACK_PRESSED, false);
                    handleActionGetContent(node, backPressed);
                    break;
                case ACTION_UPLOAD_FILE:
                    Uri uri = intent.getParcelableExtra(EXTRA_FILE_URI);
                    handleActionUploadFile(uri);
                    break;
                case ACTION_PICK_FILES:
                    ArrayList<Node> files = intent.getParcelableArrayListExtra(EXTRA_NODE);
                    handleActionPickFiles(files);
                    break;
                case ACTION_EXPORT_FILE:
                    node = intent.getParcelableExtra(EXTRA_NODE);
                    String filename = intent.getStringExtra(EXTRA_FILENAME);
                    Uri fileUri = intent.getParcelableExtra(EXTRA_FILE_URI);
                    handleActionExportFile(node, fileUri, filename);
                    break;
                default:
                    break;
            }
        }
    }

    private void handleActionGetContent(Node node, final boolean backPressed) {
        FpApiClient.getFpApiClient(this)
                .getFolder(node.linkPath, "info",
                    FpApiClient.getJsSession(this),
                    new Callback<Folder>() {
                        @Override
                        public void success(Folder folder, retrofit.client.Response response) {
                            EventBus.getDefault().post(new GotContentEvent(folder, backPressed));
                        }

                        @Override
                        public void failure(RetrofitError error) {
                            handleError(error);
                        }
                    });
    }

    private void handleActionPickFiles(ArrayList<Node> nodes) {
        final ArrayList<FPFile> results = new ArrayList<>();

        try {
            for (Node node : nodes) {
                FPFile result = FpApiClient.getFpApiClient(this).pickFile(
                        URLDecoder.decode(node.linkPath, "utf-8"),
                        "fpurl",
                        FpApiClient.getJsSession(this));

                results.add(result);
            }

            EventBus.getDefault().post(new FpFilesReceivedEvent(results));
        } catch (Exception syntaxException) {
            EventBus.getDefault().post(new ApiErrorEvent(ApiErrorEvent.ErrorType.WRONG_RESPONSE));
        }
    }

    private void handleActionUploadFile(final Uri uri) {
        ApiErrorEvent.ErrorType errorType = null;
        TypedFile typedFile = null;

        try {
            typedFile = FilesUtils.getTypedFileFromUri(this, uri);
        } catch (SecurityException e) {
            errorType = ApiErrorEvent.ErrorType.LOCAL_FILE_PERMISSION_DENIAL;
        }

        if(typedFile == null && errorType == null) {
            errorType = ApiErrorEvent.ErrorType.INVALID_FILE;
        }

        if(errorType != null) {
            EventBus.getDefault().post(new UploadFileErrorEvent(uri, errorType));
            return;
        }

        typedFile = new ProgressTypedFile(typedFile, new ProgressTypedFile.Listener() {
            @Override
            public void onProgress(float progress) {
                EventBus.getDefault().post(new UploadProgressEvent(uri, progress));
            }
        });

        FpApiClient.getFpApiClient(this).uploadFile(
                Utils.getUploadedFilename(typedFile.mimeType()),
                FpApiClient.getJsSession(this),
                typedFile,
                uploadLocalFileCallback(uri)
        );
    }

    private Callback<UploadLocalFileResponse> uploadLocalFileCallback(final Uri uri) {
        return new Callback<UploadLocalFileResponse>() {
            @Override
            public void success(UploadLocalFileResponse object, retrofit.client.Response response) {
                onFileUploadSuccess(object, uri);
            }

            @Override
            public void failure(RetrofitError error) {
                ApiErrorEvent.ErrorType errorType = getErrorType(error);
                EventBus.getDefault().post(new UploadFileErrorEvent(uri, errorType));
            }
        };
    }

    private void onFileUploadSuccess(UploadLocalFileResponse response, Uri fileUri) {
        ArrayList<FPFile> fpFiles = new ArrayList<>();

        final FPFile fpFile = response.parseToFpFile();
        if(fpFile != null) {
            fpFile.setLocalPath(fileUri.toString());
            fpFiles.add(fpFile);
        }

        EventBus.getDefault().post(new FpFilesReceivedEvent(fpFiles));
    }

    /** Exports file to service
     node - destination node
     fileUri - uri to file on device
     filename - new filename given by user
    */
    private void handleActionExportFile(Node node, Uri fileUri, String filename) {
        String fileExtension = FilesUtils.getFileExtension(this, fileUri);
        final String path = FilesUtils.getFilePath(node, filename, fileExtension);
        TypedFile content = FilesUtils.buildTypedFile(this, fileUri);

        FpApiClient.getFpApiClient(this)
            .exportFile(path, FpApiClient.getJsSession(this), content,
                    new Callback<FPFile>() {
                        @Override
                        public void success(FPFile fpFile, Response response) {
                            EventBus.getDefault().post(new FileExportedEvent(path, fpFile));
                            Log.d(LOG_TAG, "success");
                        }

                        @Override
                        public void failure(RetrofitError error) {
                            Log.d(LOG_TAG, "failure");
                        }
                    });
    }

    private void handleError(RetrofitError error) {
        ApiErrorEvent.ErrorType errorType = getErrorType(error);
        ApiErrorEvent apiErrorEvent = new ApiErrorEvent(errorType);
        EventBus.getDefault().post(apiErrorEvent);
    }

    public ApiErrorEvent.ErrorType getErrorType(RetrofitError error) {
        ApiErrorEvent.ErrorType errorType = null;

        if(error != null) {
            if(error.getKind().equals(RetrofitError.Kind.NETWORK)) {
                errorType = ApiErrorEvent.ErrorType.NETWORK;
            } else if(error.getResponse().getStatus() == 401){
                errorType = ApiErrorEvent.ErrorType.UNAUTHORIZED;
            }
        }

        if(errorType == null) {
            errorType = ApiErrorEvent.ErrorType.UNKNOWN_ERROR;
        }

        return errorType;
    }

    private static class ProgressTypedFile extends TypedFile {

        private static final int BUFFER_SIZE = 4096;

        interface Listener {
            void onProgress(float progress);
        }

        private final Listener mListener;

        public ProgressTypedFile(TypedFile typedFile, Listener listener) {
            super(typedFile.mimeType(), typedFile.file());
            mListener = listener;
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            byte[] buffer = new byte[BUFFER_SIZE];
            FileInputStream in = new FileInputStream(file());
            float written = 0;

            try {
                int read;
                long length = length();

                while ((read = in.read(buffer)) != -1 && !Thread.interrupted()) {
                    out.write(buffer, 0, read);
                    written += read;
                    mListener.onProgress(written / length);
                }
            } finally {
                in.close();
            }
        }

    }
}

