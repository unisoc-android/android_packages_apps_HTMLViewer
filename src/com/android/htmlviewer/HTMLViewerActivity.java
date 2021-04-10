/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.htmlviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Browser;
import android.util.Log;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.zip.GZIPInputStream;
import java.io.File;
import android.content.ContentResolver;
import android.provider.MediaStore;
import android.database.Cursor;
import android.text.TextUtils;

/**
 * Simple activity that shows the requested HTML page. This utility is
 * purposefully very limited in what it supports, including no network or
 * JavaScript.
 */
public class HTMLViewerActivity extends Activity {
    private static final String TAG = "HTMLViewer";

    private WebView mWebView;
    private View mLoading;
    private Intent mIntent;
    private AlertDialog mAlertDialog;
    /* unisoc:modify for Bug 1110511 @{ */
    private final String MIMETYPE_TEXTPLAIN = "text/plain";
    static final int MAXFILESIZE = 1024 * 1024 * 8;
    /* @} */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);

        mWebView = findViewById(R.id.webview);
        mLoading = findViewById(R.id.loading);

        mWebView.setWebChromeClient(new ChromeClient());
        mWebView.setWebViewClient(new ViewClient());

        WebSettings s = mWebView.getSettings();
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSavePassword(false);
        s.setSaveFormData(false);
        s.setBlockNetworkLoads(true);

        // Javascript is purposely disabled, so that nothing can be
        // automatically run.
        s.setJavaScriptEnabled(false);
        s.setDefaultTextEncodingName("utf-8");

        mIntent = getIntent();
        requestPermissionAndLoad();
    }

    private void loadUrl() {
        if (mIntent.hasExtra(Intent.EXTRA_TITLE)) {
            setTitle(mIntent.getStringExtra(Intent.EXTRA_TITLE));
        }
        mWebView.loadUrl(String.valueOf(mIntent.getData()));
    }

     /* unisoc:modify for Bug 1110511,1141295 @{ */
    private boolean checkFileSize() {
        Uri uri = mIntent.getData();
        if (uri == null) return false;
        if (MIMETYPE_TEXTPLAIN.equals(mIntent.getType())
                && ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            InputStream stream = null;
            try {
                stream = getContentResolver().openInputStream(uri);
                if (stream.available() > MAXFILESIZE) {
                    showMessage();
                    return false;
                }
            } catch (IOException e) {
                Log.e(TAG, "Unable to open content: " + uri);
                return false;
            } finally {
                try {
                    if (stream != null) {
                       stream.close();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "close fail ", e);
                }
            }
        } else if (MIMETYPE_TEXTPLAIN.equals(mIntent.getType())
                && ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            if (new File(uri.getPath()).length() > MAXFILESIZE) {
                showMessage();
                return false;
            }
        }

        return true;

    }
    /* @} */

    private void requestPermissionAndLoad() {
        Uri destination = mIntent.getData();
        if (destination != null) {
            /* SPRD:modify for Bug 900115 @{ */
            // Is this a local file?
            if (/*"file".equals(destination.getScheme())
                        && */PackageManager.PERMISSION_DENIED ==
                                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
            } else {
                /* unisoc:modify for Bug 1110511 @{ */
                if (checkFileSize()) {
                    loadUrl();
                }
            }
            /* @} */
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String permissions[], int[] grantResults) {
        // We only ever request 1 permission, so these arguments should always have the same form.
        assert permissions.length == 1;
        assert Manifest.permission.READ_EXTERNAL_STORAGE.equals(permissions[0]);

        if (grantResults.length == 1 && PackageManager.PERMISSION_GRANTED == grantResults[0]) {
            /* unisoc:modify for Bug 1110511 @{ */
            if (checkFileSize()) {
                // Try again now that we have the permission.
                loadUrl();
            }
            /* @} */
        } else {
            Toast.makeText(HTMLViewerActivity.this,
                    R.string.storage_permission_missed_hint, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mWebView.destroy();
        /* unisoc: modify for bug1203092 @{ */
        if (mAlertDialog != null && mAlertDialog.isShowing()) {
             mAlertDialog.dismiss();
             mAlertDialog = null;
        }
        /* @} */
    }

    private class ChromeClient extends WebChromeClient {
        @Override
        public void onReceivedTitle(WebView view, String title) {
            if (!getIntent().hasExtra(Intent.EXTRA_TITLE)) {
                HTMLViewerActivity.this.setTitle(title);
            }
        }
    }

    private class ViewClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            mLoading.setVisibility(View.GONE);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            Intent intent;
            // Perform generic parsing of the URI to turn it into an Intent.
            try {
                intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            } catch (URISyntaxException ex) {
                Log.w(TAG, "Bad URI " + url + ": " + ex.getMessage());
                Toast.makeText(HTMLViewerActivity.this,
                        R.string.cannot_open_link, Toast.LENGTH_SHORT).show();
                return true;
            }
            // Sanitize the Intent, ensuring web pages can not bypass browser
            // security (only access to BROWSABLE activities).
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            Intent selector = intent.getSelector();
            if (selector != null) {
                selector.addCategory(Intent.CATEGORY_BROWSABLE);
                selector.setComponent(null);
            }
            // Pass the package name as application ID so that the intent from the
            // same application can be opened in the same tab.
            intent.putExtra(Browser.EXTRA_APPLICATION_ID,
                            view.getContext().getPackageName());
            try {
                view.getContext().startActivity(intent);
                /* SPRD:modify for Bug 850118 @{ */
                finish();
                /* @} */
            } catch (ActivityNotFoundException ex) {
                Log.w(TAG, "No application can handle " + url);
                /* SPRD: modify for Bug 849536  @{ */
                //Toast.makeText(HTMLViewerActivity.this,
                        //R.string.cannot_open_link, Toast.LENGTH_SHORT).show();
                return false;
                /* @} */
            }
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view,
                WebResourceRequest request) {
            final Uri uri = request.getUrl();
            if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())
                    && uri.getPath().endsWith(".gz")) {
                Log.d(TAG, "Trying to decompress " + uri + " on the fly");
                try {
                    final InputStream in = new GZIPInputStream(
                            getContentResolver().openInputStream(uri));
                    final WebResourceResponse resp = new WebResourceResponse(
                            getIntent().getType(), "utf-8", in);
                    resp.setStatusCodeAndReasonPhrase(200, "OK");
                    return resp;
                } catch (IOException e) {
                    Log.w(TAG, "Failed to decompress; falling back", e);
                }
            }
            return null;
        }
    }

    /* unisoc: bug1110511,1203092 show warning dialog @{ */
    private void showMessage() {
        mAlertDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.app_label)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setMessage(R.string.file_size)
                .setCancelable(true)
                .setOnDismissListener(new DialogInterface.OnDismissListener(){
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                           finish();
                        }
                })
                .setPositiveButton(R.string.ok,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                               finish();
                            }
                })
                .show();
    }
    /* @} */
}
