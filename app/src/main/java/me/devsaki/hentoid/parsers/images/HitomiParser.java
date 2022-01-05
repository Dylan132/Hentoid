package me.devsaki.hentoid.parsers.images;

import static me.devsaki.hentoid.util.network.HttpHelper.getOnlineDocument;

import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jsoup.nodes.Document;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.Single;
import me.devsaki.hentoid.BuildConfig;
import me.devsaki.hentoid.core.HentoidApp;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.ImageFile;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.json.sources.HitomiGalleryInfo;
import me.devsaki.hentoid.parsers.ParseHelper;
import me.devsaki.hentoid.util.FileHelper;
import me.devsaki.hentoid.util.exception.ParseException;
import me.devsaki.hentoid.util.network.HttpHelper;
import me.devsaki.hentoid.views.SingleLoadWebView;
import okhttp3.Response;
import okhttp3.ResponseBody;
import timber.log.Timber;

/**
 * Handles parsing of content from hitomi.la
 */
public class HitomiParser extends BaseImageListParser {

    // Reproduction of the Hitomi.la Javascript to find the hostname of the image server
    private static final int NUMBER_OF_FRONTENDS = 3;
    private static final String HOSTNAME_SUFFIX_JPG = "b";
    private static final String HOSTNAME_SUFFIX_WEBP = "a";
    private static final char HOSTNAME_PREFIX_BASE = 97;

    public List<ImageFile> parseImageListImpl(@NonNull Content onlineContent, @Nullable Content storedContent) throws Exception {
        String pageUrl = onlineContent.getReaderUrl();

        Document doc = getOnlineDocument(pageUrl);
        if (null == doc) throw new ParseException("Document unreachable : " + pageUrl);

        Timber.d("Parsing: %s", pageUrl);

        List<ImageFile> result = new ArrayList<>();
        result.add(ImageFile.newCover(onlineContent.getCoverImageUrl(), StatusContent.SAVED));

        String galleryJsonUrl = "https://ltn.hitomi.la/galleries/" + onlineContent.getUniqueSiteId() + ".js";

        // Get the gallery JSON
        List<Pair<String, String>> headers = new ArrayList<>();
        headers.add(new Pair<>(HttpHelper.HEADER_REFERER_KEY, pageUrl));
        Response response = HttpHelper.getOnlineResource(galleryJsonUrl, headers, Site.HITOMI.useMobileAgent(), Site.HITOMI.useHentoidAgent(), Site.HITOMI.useWebviewAgent());

        ResponseBody body = response.body();
        if (null == body) throw new IOException("Empty body");
        String galleryInfo = body.string();

        final Object _lock = new Object();
/*
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            SingleLoadWebView wv = new SingleLoadWebView(HentoidApp.getInstance(), Site.HITOMI);
            wv.loadUrl(pageUrl);
            Timber.i(">> loading");
            while (wv.isLoading()) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Timber.w(e);
                    Thread.currentThread().interrupt();
                }
            }
            Timber.i(">> done");
//                    wv.evaluateJavascript(getJsPagesScript(galleryInfo), listCallback::accept);
            String jsResult = getDataFromJs(getJsPagesScript(galleryInfo), wv).blockingGet();
            Timber.w("JS RESULT = %s", jsResult);
            synchronized (_lock) {
                _lock.notifyAll();
            }
        });

 */
//        AppCompatActivity activity = HentoidApp.getCurrentActivity();
//        if (null == activity) return result;

        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);
            SingleLoadWebView wv = new SingleLoadWebView(HentoidApp.getInstance(), Site.HITOMI);
//            wv.setVisibility(View.GONE);
//            ViewGroup rootView = (ViewGroup) activity.findViewById(android.R.id.content).getRootView();
//            rootView.addView(wv);
            wv.loadUrl(pageUrl);
            Timber.i(">> loading wv");

            /*
            while (wv.isLoading()) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Timber.w(e);
                    Thread.currentThread().interrupt();
                }
            }
            Timber.i(">> done");
//                    wv.evaluateJavascript(getJsPagesScript(galleryInfo), listCallback::accept);
            String jsResult = getDataFromJs(getJsPagesScript(galleryInfo), wv).blockingGet();
            Timber.w("JS RESULT = %s", jsResult);
            synchronized (_lock) {
                _lock.notifyAll();
            }
             */
        });
/*
        ContextCompat.getMainExecutor(HentoidApp.getInstance()).execute(() -> {
                    SingleLoadWebView wv = new SingleLoadWebView(HentoidApp.getInstance(), Site.HITOMI);
                    wv.loadUrl(pageUrl);
                    Timber.i(">> loading");
                    while (wv.isLoading()) {
                        try {
                            Thread.sleep(150);
                        } catch (InterruptedException e) {
                            Timber.w(e);
                            Thread.currentThread().interrupt();
                        }
                    }
                    Timber.i(">> done");
//                    wv.evaluateJavascript(getJsPagesScript(galleryInfo), listCallback::accept);
                    String jsResult = getDataFromJs(getJsPagesScript(galleryInfo), wv).blockingGet();
                    Timber.w("JS RESULT = %s", jsResult);
                    synchronized (_lock) {
                        _lock.notifyAll();
                    }
                }
        );
 */

        synchronized (_lock) {
            Timber.w("Waiting for lock");
            _lock.wait();
        }

/*
        ContextCompat.getMainExecutor(HentoidApp.getInstance()).execute(() -> {
                    WebView wv = new WebView(HentoidApp.getInstance());
                    wv.loadUrl(pageUrl);
                }
        );

 */

        /*
        String json = body.string().replace("var galleryinfo = ", "");
        HitomiGalleryInfo gallery = JsonHelper.jsonToObject(json, HitomiGalleryInfo.class);

        Map<String, String> downloadParams = new HashMap<>();
        // Add referer information to downloadParams for future image download
        downloadParams.put(HttpHelper.HEADER_REFERER_KEY, pageUrl);
        String downloadParamsStr = JsonHelper.serializeToJson(downloadParams, JsonHelper.MAP_STRINGS);

        ImageFile img;
        int order = 1;
        boolean isHashAvailable;
        for (HitomiGalleryInfo.HitomiGalleryPage page : gallery.getFiles()) {
            isHashAvailable = (page.getHash() != null && !page.getHash().isEmpty());
            if (1 == page.getHaswebp() && isHashAvailable && Preferences.isDlHitomiWebp())
                img = buildWebpPicture(page, order++, gallery.getFiles().size());
            else if (isHashAvailable)
                img = buildHashPicture(page, order++, gallery.getFiles().size());
            else img = buildSimplePicture(onlineContent, page, order++, gallery.getFiles().size());
            img.setDownloadParams(downloadParamsStr);
            result.add(img);
        }
         */

        return result;
    }

    // TODO optimize
    private String getJsPagesScript(@NonNull String galleryInfo) {
        StringBuilder sb = new StringBuilder();
        FileHelper.getAssetAsString(HentoidApp.getInstance().getAssets(), "hitomi_pages.js", sb);
        return sb.toString().replace("$galleryInfo", galleryInfo);
    }

    private Single<String> getDataFromJs(@NonNull String command, @NonNull WebView webView) {
        return Single.create(emitter -> {
                    try {
                        Timber.v(command);
                        webView.evaluateJavascript(
                                command, emitter::onSuccess
                        );
                    } catch (Exception e) {
                        emitter.onError(e);
                    }
                }
        );
    }

    private ImageFile buildWebpPicture(@NonNull HitomiGalleryInfo.HitomiGalleryPage page, int order, int maxPages) {
        return buildHashPicture(page, order, maxPages, "webp", "webp");
    }

    private ImageFile buildHashPicture(@NonNull HitomiGalleryInfo.HitomiGalleryPage page, int order, int maxPages) {
        return buildHashPicture(page, order, maxPages, "images", FileHelper.getExtension(page.getName()));
    }

    private ImageFile buildHashPicture(@NonNull HitomiGalleryInfo.HitomiGalleryPage page, int order, int maxPages, String folder, String extension) {
        String hash = page.getHash();
        String componentA = hash.substring(hash.length() - 1);
        String componentB = hash.substring(hash.length() - 3, hash.length() - 1);

        int nbFrontends = NUMBER_OF_FRONTENDS;
        int varG = Integer.valueOf(componentB, 16);
        int varO = 0;
        if (varG < 0x80) nbFrontends = 2;
        if (varG < 0x59) varG = 1;

        String imageSubdomain = subdomainFromGalleryId(varG, nbFrontends, getSuffixFromExtension(extension));
        //String imageSubdomain = (char) (HOSTNAME_PREFIX_BASE + varO) + getSuffixFromExtension(extension);
        String pageUrl = "https://" + imageSubdomain + ".hitomi.la/" + folder + "/" + componentA + "/" + componentB + "/" + hash + "." + extension;

        return ParseHelper.urlToImageFile(pageUrl, order, maxPages, StatusContent.SAVED);
    }

    private ImageFile buildSimplePicture(@NonNull Content content, @NonNull HitomiGalleryInfo.HitomiGalleryPage page, int order, int maxPages) {
        int referenceId = Integer.parseInt(content.getUniqueSiteId()) % 10;
        String imageSubdomain = subdomainFromGalleryId(referenceId, NUMBER_OF_FRONTENDS, getSuffixFromExtension(FileHelper.getExtension(page.getName())));
        String pageUrl = "https://" + imageSubdomain + ".hitomi.la/galleries/" + content.getUniqueSiteId() + "/" + page.getName();

        return ParseHelper.urlToImageFile(pageUrl, order, maxPages, StatusContent.SAVED);
    }

    private String getSuffixFromExtension(String extension) {
        return extension.equalsIgnoreCase("webp") || extension.equalsIgnoreCase("avif") ? HOSTNAME_SUFFIX_WEBP : HOSTNAME_SUFFIX_JPG;
    }

    private String subdomainFromGalleryId(int referenceId, int nbFrontends, String suffix) {
        return ((char) (HOSTNAME_PREFIX_BASE + (referenceId % nbFrontends))) + suffix;
    }

    @Override
    protected List<String> parseImages(@NonNull Content content) {
        /// We won't use that as parseImageListImpl is overriden directly
        return null;
    }
}
