package me.devsaki.hentoid.services;

import android.app.IntentService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Pair;
import android.util.SparseIntArray;

import com.android.volley.Request;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import me.devsaki.fakku.FakkuDecode;
import me.devsaki.fakku.PageInfo;
import me.devsaki.fakku.PointTranslation;
import me.devsaki.hentoid.HentoidApp;
import me.devsaki.hentoid.database.HentoidDB;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.ImageFile;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.events.DownloadEvent;
import me.devsaki.hentoid.notification.download.DownloadErrorNotification;
import me.devsaki.hentoid.notification.download.DownloadProgressNotification;
import me.devsaki.hentoid.notification.download.DownloadSuccessNotification;
import me.devsaki.hentoid.notification.download.DownloadWarningNotification;
import me.devsaki.hentoid.parsers.ContentParser;
import me.devsaki.hentoid.parsers.ContentParserFactory;
import me.devsaki.hentoid.util.FileHelper;
import me.devsaki.hentoid.util.JsonHelper;
import me.devsaki.hentoid.util.MimeTypes;
import me.devsaki.hentoid.util.NetworkStatus;
import me.devsaki.hentoid.util.Preferences;
import me.devsaki.hentoid.util.notification.NotificationManager;
import timber.log.Timber;


/**
 * Created by Robb_w on 2018/04
 * Book download service; 1 instance everytime a new book of the queue has to be downloaded
 * NB : As per IntentService behaviour, only one thread can be active at a time (no parallel runs of ContentDownloadService)
 */
public class ContentDownloadService extends IntentService {

    private HentoidDB db;                                   // Hentoid database
    private NotificationManager notificationManager;
    private NotificationManager warningNotificationManager;
    private boolean downloadCanceled;                       // True if a Cancel event has been processed; false by default
    private boolean downloadSkipped;                        // True if a Skip event has been processed; false by default

    public ContentDownloadService() {
        super(ContentDownloadService.class.getName());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        db = HentoidDB.getInstance(this);

        notificationManager = new NotificationManager(this, 0);
        notificationManager.cancel();

        warningNotificationManager = new NotificationManager(this, 1);
        warningNotificationManager.cancel();

        EventBus.getDefault().register(this);

        Timber.d("Download service created");
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().unregister(this);

        Timber.d("Download service destroyed");
        super.onDestroy();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (!NetworkStatus.isOnline()) {
            Timber.w("No connection!");
            return;
        }

        Timber.d("New intent processed");

        Content content = downloadFirstInQueue();
        if (content != null) watchProgress(content);
    }

    /**
     * Start the download of the 1st book of the download queue
     *
     * @return 1st book of the download queue
     */
    @Nullable
    private Content downloadFirstInQueue() {
        // Check if queue is already paused
        if (ContentQueueManager.getInstance().isQueuePaused()) {
            Timber.w("Queue is paused. Aborting download.");
            return null;
        }

        // Works on first item of queue
        List<Pair<Integer, Integer>> queue = db.selectQueue();
        if (0 == queue.size()) {
            Timber.w("Queue is empty. Aborting download.");
            return null;
        }

        Content content = db.selectContentById(queue.get(0).first);

        if (null == content || StatusContent.DOWNLOADED == content.getStatus()) {
            Timber.w("Content is unavailable, or already downloaded. Aborting download.");
            return null;
        }

        content.setStatus(StatusContent.DOWNLOADING);
        db.updateContentStatus(content);

        // Check if images are already known
        List<ImageFile> images = content.getImageFiles();
        if (0 == images.size()) {
            // Create image list in DB
            images = fetchImageURLs(content);
            content.setImageFiles(images);
            db.insertImageFiles(content);
        }

        if (0 == images.size()) {
            Timber.w("Image list is empty. Aborting download.");
            return null;
        }

        File dir = FileHelper.createContentDownloadDir(this, content);
        if (!dir.exists()) {
            String title = content.getTitle();
            String absolutePath = dir.getAbsolutePath();
            Timber.w("Directory could not be created: %s.", absolutePath);
            warningNotificationManager.notify(new DownloadWarningNotification(title, absolutePath));
            // Download _will_ continue and images _will_ fail, so that user can retry downloading later
        }

        String fileRoot = Preferences.getRootFolderName();
        content.setStorageFolder(dir.getAbsolutePath().substring(fileRoot.length()));
        db.updateContentStorageFolder(content);


        // Tracking Event (Download Added)
        HentoidApp.trackDownloadEvent("Added");

        Timber.d("Downloading '%s' [%s]", content.getTitle(), content.getId());
        downloadCanceled = false;
        downloadSkipped = false;

        // Reset ERROR status of images to count them as "to be downloaded" (in DB and in memory)
        db.updateImageFileStatus(content, StatusContent.ERROR, StatusContent.SAVED);
        for (ImageFile img : images) {
            if (img.getStatus().equals(StatusContent.ERROR)) img.setStatus(StatusContent.SAVED);
        }

        // Queue image download requests
        ImageFile cover = new ImageFile().setName("thumb").setUrl(content.getCoverImageUrl());
        RequestQueueManager.getInstance(this, content.getSite().isAllowParallelDownloads()).queueRequest(buildDownloadRequest(cover, dir, content.getSite().canKnowHentoidAgent()));
        for (ImageFile img : images) {
            if (img.getStatus().equals(StatusContent.SAVED))
                RequestQueueManager.getInstance().queueRequest(buildDownloadRequest(img, dir, content.getSite().canKnowHentoidAgent()));
        }

        return content;
    }

    /**
     * Watch download progress
     * <p>
     * NB : download pause is managed at the Volley queue level (see RequestQueueManager.pauseQueue / startQueue)
     *
     * @param content Content to watch (1st book of the download queue)
     */
    private void watchProgress(Content content) {
        boolean isDone;
        int pagesOK, pagesKO;
        List<ImageFile> images = content.getImageFiles();
        ContentQueueManager contentQueueManager = ContentQueueManager.getInstance();

        do {
            SparseIntArray statuses = db.countProcessedImagesById(content.getId());
            pagesOK = statuses.get(StatusContent.DOWNLOADED.getCode());
            pagesKO = statuses.get(StatusContent.ERROR.getCode());

            String title = content.getTitle();
            int totalPages = images.size();
            int progress = pagesOK + pagesKO;
            isDone = progress == totalPages;
            Timber.d("Progress: OK:%s KO:%s Total:%s", pagesOK, pagesKO, totalPages);
            notificationManager.notify(new DownloadProgressNotification(title, progress, totalPages));
            EventBus.getDefault().post(new DownloadEvent(content, DownloadEvent.EV_PROGRESS, pagesOK, pagesKO, totalPages));

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        while (!isDone && !downloadCanceled && !downloadSkipped && !contentQueueManager.isQueuePaused());

        if (contentQueueManager.isQueuePaused()) {
            Timber.d("Content download paused : %s [%s]", content.getTitle(), content.getId());
            if (downloadCanceled) notificationManager.cancel();
        } else {
            downloadCompleted(content, pagesOK, pagesKO);
        }
    }

    /**
     * Completes the download of a book when all images have been processed
     * Then launches a new IntentService
     *
     * @param content Content to mark as downloaded
     */
    private void downloadCompleted(Content content, int pagesOK, int pagesKO) {
        ContentQueueManager contentQueueManager = ContentQueueManager.getInstance();

        if (!downloadCanceled && !downloadSkipped) {
            File dir = FileHelper.createContentDownloadDir(this, content);
            List<ImageFile> images = content.getImageFiles();

            // Mark content as downloaded
            content.setDownloadDate(new Date().getTime());
            content.setStatus((0 == pagesKO) ? StatusContent.DOWNLOADED : StatusContent.ERROR);
            content.setDownloadParams("");
            db.updateContentStatus(content);

            // Save JSON file
            try {
                JsonHelper.saveJson(content, dir);
            } catch (IOException e) {
                Timber.e(e, "I/O Error saving JSON: %s", content.getTitle());
            }

            Timber.d("Content download finished: %s [%s]", content.getTitle(), content.getId());

            // Delete book from queue
            db.deleteQueueById(content.getId());

            // Increase downloads count
            contentQueueManager.downloadComplete();

            if (0 == pagesKO) {
                int downloadCount = contentQueueManager.getDownloadCount();
                notificationManager.notify(new DownloadSuccessNotification(downloadCount));

                // Tracking Event (Download Success)
                HentoidApp.trackDownloadEvent("Success");
            } else {
                notificationManager.notify(new DownloadErrorNotification(content));

                // Tracking Event (Download Error)
                HentoidApp.trackDownloadEvent("Success");
            }

            // Signals current download as completed
            Timber.d("CompleteActivity : OK = %s; KO = %s", pagesOK, pagesKO);
            EventBus.getDefault().post(new DownloadEvent(content, DownloadEvent.EV_COMPLETE, pagesOK, pagesKO, images.size()));

            // Tracking Event (Download Completed)
            HentoidApp.trackDownloadEvent("Completed");
        } else if (downloadCanceled) {
            Timber.d("Content download canceled: %s [%s]", content.getTitle(), content.getId());
            notificationManager.cancel();
        } else {
            Timber.d("Content download skipped : %s [%s]", content.getTitle(), content.getId());
        }

        // Download next content in a new Intent
        contentQueueManager.resumeQueue(this);
    }

    /**
     * Query source to fetch all image file names and URLs of a given book
     *
     * @param content Book whose pages to retrieve
     * @return List of pages with original URLs and file name
     */
    private static List<ImageFile> fetchImageURLs(Content content) {
        // Use ContentParser to query the source
        ContentParser parser = ContentParserFactory.getInstance().getParser(content);
        List<ImageFile> imgs = parser.parseImageList(content);

        for (ImageFile img : imgs) img.setStatus(StatusContent.SAVED);

        return imgs;
    }

    /**
     * Create an image download request an its handler from a given image URL, file name and destination folder
     *
     * @param img Image to download
     * @param dir Destination folder
     * @return Volley request and its handler
     */
    private Request<Object> buildDownloadRequest(ImageFile img, File dir, boolean canKnowHentoidAgent) {

        Map<String, String> headers = new HashMap<>();
        String downloadParamsStr = img.getDownloadParams();
        if (downloadParamsStr != null && !downloadParamsStr.isEmpty()) {
            Type type = new TypeToken<Map<String, String>>() {
            }.getType();
            Map<String, String> downloadParams = new Gson().fromJson(downloadParamsStr, type);

            if (downloadParams.containsKey("cookie"))
                headers.put("cookie", downloadParams.get("cookie"));
            if (downloadParams.containsKey("referer"))
                headers.put("referer", downloadParams.get("referer"));
        }

        return new InputStreamVolleyRequest(
                Request.Method.GET,
                img.getUrl(),
                headers,
                canKnowHentoidAgent,
                parse -> {
                    try {
                        if (parse != null)
                            processAndSaveImage(img, dir, parse.getValue().get("Content-Type"), parse.getKey());
                        updateImageStatus(img, (parse != null));
                    } catch (IOException e) {
                        Timber.w(e, "I/O error - Image %s not saved in dir %s", img.getUrl(), dir.getPath());
                        updateImageStatus(img, false);
                    }
                },
                error -> {
                    String statusCode = (error.networkResponse != null) ? error.networkResponse.statusCode + "" : "N/A";
                    Timber.w("Download error - Image %s not retrieved (HTTP status code %s)", img.getUrl(), statusCode);
                    error.printStackTrace();
                    updateImageStatus(img, false);
                });
    }

    private static byte[] processImage(String downloadParamsStr, byte[] binaryContent) {
        Type type = new TypeToken<Map<String, String>>() {
        }.getType();
        Map<String, String> downloadParams = new Gson().fromJson(downloadParamsStr, type);

        if (!downloadParams.containsKey("pageInfo")) {
            return binaryContent;
        }

//        byte[] imgData = Base64.decode(binaryContent, Base64.DEFAULT);
        Bitmap sourcePicture = BitmapFactory.decodeByteArray(binaryContent, 0, binaryContent.length);

        PageInfo page = new Gson().fromJson(downloadParams.get("pageInfo"), PageInfo.class);
        Bitmap.Config conf = Bitmap.Config.ARGB_8888;
        Bitmap destPicture = Bitmap.createBitmap(page.width, page.height, conf);
        Canvas destCanvas = new Canvas(destPicture);

        FakkuDecode.getTranslations(page);

        for (PointTranslation t : page.translations) {
            Rect sourceRect = new Rect(t.sourceX, t.sourceY, t.sourceX + FakkuDecode.TILE_EDGE_LENGTH, t.sourceY + FakkuDecode.TILE_EDGE_LENGTH);
            Rect destRect = new Rect(t.destX, t.destY, t.destX + FakkuDecode.TILE_EDGE_LENGTH, t.destY + FakkuDecode.TILE_EDGE_LENGTH);

            destCanvas.drawBitmap(sourcePicture, sourceRect, destRect, null);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        destPicture.compress(Bitmap.CompressFormat.PNG, 100, out); // Fakku is _always_ PNG
        return out.toByteArray();
    }

    /**
     * Create the given file in the given destination folder, and write binary data to it
     *
     * @param img           ImageFile that is being processed
     * @param dir           Destination folder
     * @param contentType   Content type of the image
     * @param binaryContent Binary content of the image
     * @throws IOException IOException if image cannot be saved at given location
     */
    private static void processAndSaveImage(ImageFile img, File dir, String contentType, byte[] binaryContent) throws IOException {

        byte[] finalBinaryContent = null;
        if (img.getDownloadParams() != null && !img.getDownloadParams().isEmpty()) {
            finalBinaryContent = processImage(img.getDownloadParams(), binaryContent);
        }

        saveImage(img.getName(), dir, contentType, (null == finalBinaryContent) ? binaryContent : finalBinaryContent);
    }

    /**
     * Create the given file in the given destination folder, and write binary data to it
     *
     * @param fileName      Name of the file to write
     * @param dir           Destination folder
     * @param contentType   Content type of the image
     * @param binaryContent Binary content of the image
     * @throws IOException IOException if image cannot be saved at given location
     */
    private static void saveImage(String fileName, File dir, String contentType, byte[] binaryContent) throws IOException {
        File file = new File(dir, fileName + "." + MimeTypes.getExtensionFromMimeType(contentType));
        FileHelper.saveBinaryInFile(file, binaryContent);
    }

    /**
     * Update given image status in DB
     *
     * @param img     Image to update
     * @param success True if download is successful; false if download failed
     */
    private void updateImageStatus(ImageFile img, boolean success) {
        img.setStatus(success ? StatusContent.DOWNLOADED : StatusContent.ERROR);
        db.updateImageFileStatus(img);

    }

    /**
     * Download event handler called by the event bus
     *
     * @param event Download event
     */
    @Subscribe
    public void onDownloadEvent(DownloadEvent event) {
        switch (event.eventType) {
            case DownloadEvent.EV_PAUSE:
                db.updateContentStatus(StatusContent.DOWNLOADING, StatusContent.PAUSED);
                RequestQueueManager.getInstance().cancelQueue();
                ContentQueueManager.getInstance().pauseQueue();
                notificationManager.cancel();
                break;
            case DownloadEvent.EV_CANCEL:
                RequestQueueManager.getInstance().cancelQueue();
                downloadCanceled = true;
                // Tracking Event (Download Canceled)
                HentoidApp.trackDownloadEvent("Cancelled");
                break;
            case DownloadEvent.EV_SKIP:
                db.updateContentStatus(StatusContent.DOWNLOADING, StatusContent.PAUSED);
                RequestQueueManager.getInstance().cancelQueue();
                downloadSkipped = true;
                // Tracking Event (Download Skipped)
                HentoidApp.trackDownloadEvent("Skipped");
                break;
        }
    }
}
