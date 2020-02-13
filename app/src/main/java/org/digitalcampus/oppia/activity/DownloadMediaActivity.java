/*
 * This file is part of OppiaMobile - https://digital-campus.org/
 *
 * OppiaMobile is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OppiaMobile is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OppiaMobile. If not, see <http://www.gnu.org/licenses/>.
 */

package org.digitalcampus.oppia.activity;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.splunk.mint.Mint;

import org.digitalcampus.mobile.learning.R;
import org.digitalcampus.oppia.adapter.DownloadMediaAdapter;
import org.digitalcampus.oppia.listener.DownloadMediaListener;
import org.digitalcampus.oppia.model.Media;
import org.digitalcampus.oppia.service.DownloadBroadcastReceiver;
import org.digitalcampus.oppia.service.DownloadService;
import org.digitalcampus.oppia.utils.ConnectionUtils;
import org.digitalcampus.oppia.utils.MultiChoiceHelper;
import org.digitalcampus.oppia.utils.UIUtils;
import org.digitalcampus.oppia.utils.storage.ExternalStorageStrategy;
import org.digitalcampus.oppia.utils.storage.FileUtils;
import org.digitalcampus.oppia.utils.storage.Storage;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;

public class DownloadMediaActivity extends AppActivity implements DownloadMediaListener {

    public static final String MISSING_MEDIA = "missing_media";

    private SharedPreferences prefs;
    private ArrayList<Media> missingMedia;
    private DownloadBroadcastReceiver receiver;
    Button downloadViaPCBtn;
    private TextView emptyState;
    private boolean isSortByCourse;
    private TextView downloadSelected;
    private View missingMediaContainer;
    private RecyclerView recyclerMedia;
    private ArrayList<Media> mediaSelected;
    private DownloadMediaAdapter adapterMedia;
    private MultiChoiceHelper multiChoiceHelper;

    public enum DownloadMode {INDIVIDUALLY, DOWNLOAD_ALL, STOP_ALL}

    @Override
    public void onStart() {
        super.onStart();
        initialize();
    }

    private void findViews() {

        missingMediaContainer = findViewById(R.id.home_messages);
        downloadSelected = findViewById(R.id.download_selected);
        downloadViaPCBtn = findViewById(R.id.download_media_via_pc_btn);
        emptyState = findViewById(R.id.empty_state);
        recyclerMedia = findViewById(R.id.missing_media_list);

    }

    @SuppressWarnings("unchecked")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download_media);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        findViews();

        Bundle bundle = this.getIntent().getExtras();
        if (bundle != null) {
            missingMedia = (ArrayList<Media>) bundle.getSerializable(MISSING_MEDIA);
        } else {
            missingMedia = new ArrayList<>();
        }

        mediaSelected = new ArrayList<>();

        adapterMedia = new DownloadMediaAdapter(this, missingMedia);
        adapterMedia.sortByFilename();
        multiChoiceHelper = adapterMedia.getMultiChoiceHelper(); //https://medium.com/@BladeCoder/implementing-a-modal-selection-helper-for-recyclerview-1e888b4cd5b9

        multiChoiceHelper.setMultiChoiceModeListener(new MultiChoiceHelper.MultiChoiceModeListener() {
            @Override
            public void onItemCheckedStateChanged(androidx.appcompat.view.ActionMode mode, int position, long id, boolean checked) {
                Log.v(TAG, "Count: " + multiChoiceHelper.getCheckedItemCount());
                if (checked) {
                    mediaSelected.add(missingMedia.get(position));
                } else {
                    mediaSelected.remove(missingMedia.get(position));
                }

                int count = mediaSelected.size();
                mode.setSubtitle(count == 1 ? count + " item selected" : count + " items selected");

                for (Media m : mediaSelected) {
                    if (!m.isDownloading()) {
                        downloadSelected.setText(getString(R.string.missing_media_download_selected));
                        break;
                    }
                }
            }

            @Override
            public boolean onCreateActionMode(final androidx.appcompat.view.ActionMode mode, Menu menu) {

                onPrepareOptionsMenu(menu);
                mode.setTitle(R.string.title_download_media);

                if (missingMediaContainer.getVisibility() != View.VISIBLE) {
                    missingMediaContainer.setVisibility(View.VISIBLE);
                    downloadSelected.setOnClickListener(new OnClickListener() {

                        public void onClick(View v) {
                            DownloadMode downloadMode = downloadSelected.getText()
                                    .equals(getString(R.string.missing_media_download_selected)) ? DownloadMode.DOWNLOAD_ALL
                                    : DownloadMode.STOP_ALL;
                            downloadSelected.setText(downloadSelected.getText()
                                    .equals(getString(R.string.missing_media_download_selected)) ? getString(R.string.missing_media_stop_selected)
                                    : getString(R.string.missing_media_download_selected));

                            for (Media m : mediaSelected) {
                                downloadMedia(m, downloadMode);
                            }

                            mode.finish();
                        }
                    });

                    showDownloadMediaMessage();
                }

                adapterMedia.setEnterOnMultiChoiceMode(true);
                adapterMedia.notifyDataSetChanged();

                downloadSelected.setText(getString(R.string.missing_media_stop_selected));

                menu.findItem(R.id.menu_sort_by).setVisible(false);

                return true;
            }

            @Override
            public boolean onPrepareActionMode(androidx.appcompat.view.ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(androidx.appcompat.view.ActionMode mode, MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.menu_select_all:
                        for (int i = 0; i < adapterMedia.getItemCount(); i++) {
                            if (!multiChoiceHelper.isItemChecked(i)) {
                                multiChoiceHelper.setItemChecked(i, true, true);
                            }
                        }
                        return true;

                    case R.id.menu_unselect_all:
                        mode.finish();
                        return true;

                    default:
                        return false;
                }
            }

            @Override
            public void onDestroyActionMode(androidx.appcompat.view.ActionMode mode) {

                mediaSelected.clear();
                hideDownloadMediaMessage();
                adapterMedia.setEnterOnMultiChoiceMode(false);
                adapterMedia.notifyDataSetChanged();
                multiChoiceHelper.clearChoices();

                mode.getMenu().findItem(R.id.menu_sort_by).setVisible(true);
            }
        });

        recyclerMedia.setAdapter(adapterMedia);

        adapterMedia.setOnItemClickListener(new DownloadMediaAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(View view, int position) {
                // do nothing
            }

            @Override
            public void onDownloadClickListener(int position) {

                Log.d(TAG, "Clicked " + position);
                Media mediaToDownload = missingMedia.get(position);

                downloadMedia(mediaToDownload, DownloadMode.INDIVIDUALLY);
            }
        });

        downloadViaPCBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                downloadViaPC();
            }
        });

        Media.resetMediaScan(prefs);

    }


    @Override
    public void onResume() {
        super.onResume();
        if ((missingMedia != null) && !missingMedia.isEmpty()) {
            // We already have loaded media (coming from orientation change)
            isSortByCourse = false;
            adapterMedia.notifyDataSetChanged();
            emptyState.setVisibility(View.GONE);
            downloadViaPCBtn.setVisibility(View.VISIBLE);
        } else {
            emptyState.setVisibility(View.VISIBLE);
            downloadViaPCBtn.setVisibility(View.GONE);
        }
        receiver = new DownloadBroadcastReceiver();
        receiver.setMediaListener(this);
        IntentFilter broadcastFilter = new IntentFilter(DownloadService.BROADCAST_ACTION);
        broadcastFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        registerReceiver(receiver, broadcastFilter);

        invalidateOptionsMenu();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        ArrayList<Media> savedMissingMedia = (ArrayList<Media>) savedInstanceState.getSerializable(TAG);
        this.missingMedia.clear();
        this.missingMedia.addAll(savedMissingMedia);
    }

    @Override
    protected void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putSerializable(TAG, missingMedia);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        getMenuInflater().inflate(R.menu.missing_media_sortby, menu);
        MenuItem selectAll = menu.findItem(R.id.menu_select_all);
        if (selectAll != null) {
            selectAll.setVisible(!missingMedia.isEmpty());
        }

        MenuItem sortBy = menu.findItem(R.id.menu_sort_by);
        if (sortBy != null) {
            sortBy.setVisible(!missingMedia.isEmpty());
            sortBy.setTitle(isSortByCourse ? getString(R.string.menu_sort_by_filename)
                    : getString(R.string.menu_sort_by_course));
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        int itemId = item.getItemId();
        switch (itemId) {
            case R.id.menu_sort_by:
                if (isSortByCourse) {
                    adapterMedia.sortByFilename();
                    isSortByCourse = false;
                } else {
                    adapterMedia.sortByCourse();
                    isSortByCourse = true;
                invalidateOptionsMenu();
                return true;
            }
            case R.id.menu_select_all:
                for (int i = 0; i < recyclerMedia.getAdapter().getItemCount(); i++) {
                    if (!multiChoiceHelper.isItemChecked(i)) {
                        multiChoiceHelper.setItemChecked(i, true, true);
                    }
                }
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void downloadViaPC() {

        if (Storage.getStorageStrategy().getStorageType().equals(PrefsActivity.STORAGE_OPTION_INTERNAL)) {
            UIUtils.showAlert(this, R.string.prefStorageLocation, this.getString(R.string.download_via_pc_extenal_storage));
            return;
        }
        FileOutputStream f = null;
        Writer out = null;
        try {
            String filename = "oppia-media.html";
            String path = ExternalStorageStrategy.getInternalBasePath(this);
            InputStream input = this.getAssets().open("templates/download_via_pc.html");
            String html = FileUtils.readFile(input);

            html = html.replace("##page_title##", getString(R.string.download_via_pc_title));
            html = html.replace("##app_name##", getString(R.string.app_name));
            html = html.replace("##primary_color##", "#" + Integer.toHexString(ContextCompat.getColor(this, R.color.theme_primary) & 0x00ffffff));
            html = html.replace("##secondary_color##", "#" + Integer.toHexString(ContextCompat.getColor(this, R.color.theme_dark) & 0x00ffffff));
            html = html.replace("##download_via_pc_title##", getString(R.string.download_via_pc_title));
            html = html.replace("##download_via_pc_intro##", getString(R.string.download_via_pc_intro));
            html = html.replace("##download_via_pc_final##", getString(R.string.download_via_pc_final, path));

            String downloadData = "";
            for (Media m : missingMedia) {
                downloadData += "<li><a href='" + m.getDownloadUrl() + "'>" + m.getFilename() + "</a></li>";
            }
            html = html.replace("##download_files##", downloadData);

            File file = new File(Environment.getExternalStorageDirectory(), filename);
            f = new FileOutputStream(file);
            out = new OutputStreamWriter(new FileOutputStream(file));
            out.write(html);
            out.close();
            f.close();
            UIUtils.showAlert(this, R.string.info, this.getString(R.string.download_via_pc_message, filename));
        } catch (FileNotFoundException fnfe) {
            Mint.logException(fnfe);
            Log.d(TAG, "File not found", fnfe);
        } catch (IOException ioe) {
            Mint.logException(ioe);
            Log.d(TAG, "IOException", ioe);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ioe) {
                    Log.d(TAG, "couldn't close OutputStreamWriter object", ioe);
                }
            }
            if (f != null) {
                try {
                    f.close();
                } catch (IOException ioe) {
                    Log.d(TAG, "couldn't close FileOutputStream object", ioe);
                }
            }
        }
    }

    @Override
    public void onDownloadProgress(String fileUrl, int progress) {
        Media mediaFile = findMedia(fileUrl);
        if (mediaFile != null) {
            mediaFile.setProgress(progress);
            adapterMedia.notifyDataSetChanged();
        }
    }

    @Override
    public void onDownloadFailed(String fileUrl, String message) {
        Media mediaFile = findMedia(fileUrl);
        if (mediaFile != null) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();

            mediaFile.setDownloading(false);
            mediaFile.setProgress(0);
            adapterMedia.notifyDataSetChanged();
        }
    }

    @Override
    public void onDownloadComplete(String fileUrl) {
        Media mediaFile = findMedia(fileUrl);
        if (mediaFile != null) {
            Toast.makeText(this, this.getString(R.string.download_complete), Toast.LENGTH_LONG).show();

            missingMedia.remove(mediaFile);
            adapterMedia.notifyDataSetChanged();
            emptyState.setVisibility((missingMedia.isEmpty()) ? View.VISIBLE : View.GONE);
            downloadViaPCBtn.setVisibility((missingMedia.isEmpty()) ? View.GONE : View.VISIBLE);
            invalidateOptionsMenu();
        }
    }

    private Media findMedia(String fileUrl) {
        if (!missingMedia.isEmpty()) {
            for (Media mediaFile : missingMedia) {
                if (mediaFile.getDownloadUrl().equals(fileUrl)) {
                    return mediaFile;
                }
            }
        }
        return null;
    }

    private void downloadMedia(Media mediaToDownload, DownloadMode mode) {
        if (!ConnectionUtils.isOnWifi(DownloadMediaActivity.this) && !DownloadMediaActivity.this.prefs.getBoolean(PrefsActivity.PREF_BACKGROUND_DATA_CONNECT, false)) {
            UIUtils.showAlert(DownloadMediaActivity.this, R.string.warning, R.string.warning_wifi_required);
            return;
        }

        if (!mediaToDownload.isDownloading()) {
            if (mode.equals(DownloadMode.DOWNLOAD_ALL) ||
                    mode.equals(DownloadMode.INDIVIDUALLY)) {
                startDownload(mediaToDownload);
            }
        } else {
            if (mode.equals(DownloadMode.STOP_ALL) ||
                    mode.equals(DownloadMode.INDIVIDUALLY)) {
                stopDownload(mediaToDownload);
            }
        }


    }

    private void startDownload(Media mediaToDownload) {
        Intent mServiceIntent = new Intent(DownloadMediaActivity.this, DownloadService.class);
        mServiceIntent.putExtra(DownloadService.SERVICE_ACTION, DownloadService.ACTION_DOWNLOAD);
        mServiceIntent.putExtra(DownloadService.SERVICE_URL, mediaToDownload.getDownloadUrl());
        mServiceIntent.putExtra(DownloadService.SERVICE_DIGEST, mediaToDownload.getDigest());
        mServiceIntent.putExtra(DownloadService.SERVICE_FILENAME, mediaToDownload.getFilename());
        DownloadMediaActivity.this.startService(mServiceIntent);

        mediaToDownload.setDownloading(true);
        mediaToDownload.setProgress(0);
        adapterMedia.notifyDataSetChanged();

        downloadSelected.setText(getString(R.string.missing_media_download_selected));
        for (Media m : mediaSelected) {
            if (m.isDownloading()) {
                downloadSelected.setText(getString(R.string.missing_media_stop_selected));
                break;
            }
        }
    }

    private void stopDownload(Media mediaToDownload) {
        Intent mServiceIntent = new Intent(DownloadMediaActivity.this, DownloadService.class);
        mServiceIntent.putExtra(DownloadService.SERVICE_ACTION, DownloadService.ACTION_CANCEL);
        mServiceIntent.putExtra(DownloadService.SERVICE_URL, mediaToDownload.getDownloadUrl());
        DownloadMediaActivity.this.startService(mServiceIntent);

        mediaToDownload.setDownloading(false);
        mediaToDownload.setProgress(0);
        adapterMedia.notifyDataSetChanged();

        for (Media m : mediaSelected) {
            if (!m.isDownloading()) {
                downloadSelected.setText(getString(R.string.missing_media_download_selected));
                break;
            }
        }
    }

    private void showDownloadMediaMessage() {
        TranslateAnimation anim = new TranslateAnimation(0, 0, -200, 0);
        anim.setDuration(900);
        missingMediaContainer.startAnimation(anim);

        missingMediaContainer.measure(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ValueAnimator animator = ValueAnimator.ofInt(0, missingMediaContainer.getMeasuredHeight());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            //@Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                recyclerMedia.setPadding(0, (Integer) valueAnimator.getAnimatedValue(), 0, 0);
            }
        });
        animator.setStartDelay(200);
        animator.setDuration(700);
        animator.start();
    }

    private void hideDownloadMediaMessage() {

        TranslateAnimation anim = new TranslateAnimation(0, 0, 0, -200);
        anim.setDuration(900);
        missingMediaContainer.startAnimation(anim);

        missingMediaContainer.measure(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ValueAnimator animator = ValueAnimator.ofInt(missingMediaContainer.getMeasuredHeight(), 0);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            //@Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                recyclerMedia.setPadding(0, (Integer) valueAnimator.getAnimatedValue(), 0, 0);
            }
        });
        animator.setStartDelay(0);
        animator.setDuration(700);
        animator.start();


        missingMediaContainer.setVisibility(View.GONE);
    }

}
