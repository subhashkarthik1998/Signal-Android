/*
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.util.Pair;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.lifecycle.ViewModelProviders;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import org.thoughtcrime.securesms.animation.DepthPageTransformer;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.components.viewpager.ExtendedOnPageChangedListener;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.MediaDatabase;
import org.thoughtcrime.securesms.database.MediaDatabase.MediaRecord;
import org.thoughtcrime.securesms.database.loaders.PagingMediaLoader;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.mediapreview.MediaPreviewFragment;
import org.thoughtcrime.securesms.mediapreview.MediaPreviewViewModel;
import org.thoughtcrime.securesms.mediapreview.MediaRailAdapter;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.AttachmentUtil;
import org.thoughtcrime.securesms.util.DateUtils;
import org.thoughtcrime.securesms.util.SaveAttachmentTask;
import org.thoughtcrime.securesms.util.SaveAttachmentTask.Attachment;
import org.thoughtcrime.securesms.util.Util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Activity for displaying media attachments in-app
 */
public final class MediaPreviewActivity extends PassphraseRequiredActionBarActivity
  implements LoaderManager.LoaderCallbacks<Pair<Cursor, Integer>>,
             MediaRailAdapter.RailItemListener,
             MediaPreviewFragment.Events
{

  private final static String TAG = MediaPreviewActivity.class.getSimpleName();

  public static final String RECIPIENT_EXTRA      = "recipient_id";
  public static final String DATE_EXTRA           = "date";
  public static final String SIZE_EXTRA           = "size";
  public static final String CAPTION_EXTRA        = "caption";
  public static final String OUTGOING_EXTRA       = "outgoing";
  public static final String LEFT_IS_RECENT_EXTRA = "left_is_recent";

  private ViewPager             mediaPager;
  private View                  detailsContainer;
  private TextView              caption;
  private View                  captionContainer;
  private RecyclerView          albumRail;
  private MediaRailAdapter      albumRailAdapter;
  private ViewGroup             playbackControlsContainer;
  private Uri                   initialMediaUri;
  private String                initialMediaType;
  private long                  initialMediaSize;
  private String                initialCaption;
  private Recipient             conversationRecipient;
  private boolean               leftIsRecent;
  private MediaPreviewViewModel viewModel;
  private ViewPagerListener     viewPagerListener;

  private int restartItem = -1;

  @SuppressWarnings("ConstantConditions")
  @Override
  protected void onCreate(Bundle bundle, boolean ready) {
    this.setTheme(R.style.TextSecure_MediaPreview);
    setContentView(R.layout.media_preview_activity);

    setSupportActionBar(findViewById(R.id.toolbar));

    viewModel = ViewModelProviders.of(this).get(MediaPreviewViewModel.class);

    getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                         WindowManager.LayoutParams.FLAG_FULLSCREEN);

    showSystemUI();

    getSupportActionBar().setDisplayHomeAsUpEnabled(true);

    initializeViews();
    initializeResources();
    initializeObservers();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  @Override
  public void onRailItemClicked(int distanceFromActive) {
    mediaPager.setCurrentItem(mediaPager.getCurrentItem() + distanceFromActive);
  }

  @Override
  public void onRailItemDeleteClicked(int distanceFromActive) {
    throw new UnsupportedOperationException("Callback unsupported.");
  }

  @SuppressWarnings("ConstantConditions")
  private void initializeActionBar() {
    MediaItem mediaItem = getCurrentMediaItem();

    if (mediaItem != null) {
      CharSequence relativeTimeSpan;

      if (mediaItem.date > 0) {
        relativeTimeSpan = DateUtils.getExtendedRelativeTimeSpanString(this, Locale.getDefault(), mediaItem.date);
      } else {
        relativeTimeSpan = getString(R.string.MediaPreviewActivity_draft);
      }

      if      (mediaItem.outgoing)          getSupportActionBar().setTitle(getString(R.string.MediaPreviewActivity_you));
      else if (mediaItem.recipient != null) getSupportActionBar().setTitle(mediaItem.recipient.toShortString());
      else                                  getSupportActionBar().setTitle("");

      getSupportActionBar().setSubtitle(relativeTimeSpan);
    }
  }

  @Override
  public void onResume() {
    super.onResume();

    initializeMedia();
  }

  @Override
  public void onPause() {
    super.onPause();
    restartItem = cleanupMedia();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    initializeResources();
  }

  private void initializeViews() {
    mediaPager = findViewById(R.id.media_pager);
    mediaPager.setOffscreenPageLimit(1);
    mediaPager.setPageTransformer(true, new DepthPageTransformer());

    viewPagerListener = new ViewPagerListener();
    mediaPager.addOnPageChangeListener(viewPagerListener);

    albumRail        = findViewById(R.id.media_preview_album_rail);
    albumRailAdapter = new MediaRailAdapter(GlideApp.with(this), this, false);

    albumRail.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
    albumRail.setAdapter(albumRailAdapter);

    detailsContainer          = findViewById(R.id.media_preview_details_container);
    caption                   = findViewById(R.id.media_preview_caption);
    captionContainer          = findViewById(R.id.media_preview_caption_container);
    playbackControlsContainer = findViewById(R.id.media_preview_playback_controls_container);

    View toolbarLayout = findViewById(R.id.toolbar_layout);

    anchorMarginsToBottomInsets(detailsContainer);

    anchorMarginsToTopInsets(toolbarLayout);

    showAndHideWithSystemUI(getWindow(), detailsContainer, toolbarLayout);
  }

  private void initializeResources() {
    RecipientId recipientId = getIntent().getParcelableExtra(RECIPIENT_EXTRA);

    initialMediaUri  = getIntent().getData();
    initialMediaType = getIntent().getType();
    initialMediaSize = getIntent().getLongExtra(SIZE_EXTRA, 0);
    initialCaption   = getIntent().getStringExtra(CAPTION_EXTRA);
    leftIsRecent     = getIntent().getBooleanExtra(LEFT_IS_RECENT_EXTRA, false);
    restartItem      = -1;

    if (recipientId != null) {
      conversationRecipient = Recipient.live(recipientId).get();
    } else {
      conversationRecipient = null;
    }
  }

  private void initializeObservers() {
    viewModel.getPreviewData().observe(this, previewData -> {
      if (previewData == null || mediaPager == null || mediaPager.getAdapter() == null) {
        return;
      }

      View playbackControls = ((MediaItemAdapter) mediaPager.getAdapter()).getPlaybackControls(mediaPager.getCurrentItem());

      if (previewData.getAlbumThumbnails().isEmpty() && previewData.getCaption() == null && playbackControls == null) {
        detailsContainer.setVisibility(View.GONE);
      } else {
        detailsContainer.setVisibility(View.VISIBLE);
      }

      albumRail.setVisibility(previewData.getAlbumThumbnails().isEmpty() ? View.GONE : View.VISIBLE);
      albumRailAdapter.setMedia(previewData.getAlbumThumbnails(), previewData.getActivePosition());
      albumRail.smoothScrollToPosition(previewData.getActivePosition());

      captionContainer.setVisibility(previewData.getCaption() == null ? View.GONE : View.VISIBLE);
      caption.setText(previewData.getCaption());

      if (playbackControls != null) {
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        playbackControls.setLayoutParams(params);

        playbackControlsContainer.removeAllViews();
        playbackControlsContainer.addView(playbackControls);
      } else {
        playbackControlsContainer.removeAllViews();
      }
    });
  }

  private void initializeMedia() {
    if (!isContentTypeSupported(initialMediaType)) {
      Log.w(TAG, "Unsupported media type sent to MediaPreviewActivity, finishing.");
      Toast.makeText(getApplicationContext(), R.string.MediaPreviewActivity_unssuported_media_type, Toast.LENGTH_LONG).show();
      finish();
    }

    Log.i(TAG, "Loading Part URI: " + initialMediaUri);

    if (conversationRecipient != null) {
      LoaderManager.getInstance(this).restartLoader(0, null, this);
    } else {
      mediaPager.setAdapter(new SingleItemPagerAdapter(getSupportFragmentManager(), initialMediaUri, initialMediaType, initialMediaSize));

      if (initialCaption != null) {
        detailsContainer.setVisibility(View.VISIBLE);
        captionContainer.setVisibility(View.VISIBLE);
        caption.setText(initialCaption);
      }
    }
  }

  private int cleanupMedia() {
    int restartItem = mediaPager.getCurrentItem();

    mediaPager.removeAllViews();
    mediaPager.setAdapter(null);

    return restartItem;
  }

  private void showOverview() {
    Intent intent = new Intent(this, MediaOverviewActivity.class);
    intent.putExtra(MediaOverviewActivity.RECIPIENT_EXTRA, conversationRecipient.getId());
    startActivity(intent);
  }

  private void forward() {
    MediaItem mediaItem = getCurrentMediaItem();

    if (mediaItem != null) {
      Intent composeIntent = new Intent(this, ShareActivity.class);
      composeIntent.putExtra(Intent.EXTRA_STREAM, mediaItem.uri);
      composeIntent.setType(mediaItem.type);
      startActivity(composeIntent);
    }
  }

  @SuppressWarnings("CodeBlock2Expr")
  @SuppressLint("InlinedApi")
  private void saveToDisk() {
    MediaItem mediaItem = getCurrentMediaItem();

    if (mediaItem != null) {
      SaveAttachmentTask.showWarningDialog(this, (dialogInterface, i) -> {
        Permissions.with(this)
                   .request(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
                   .ifNecessary()
                   .withPermanentDenialDialog(getString(R.string.MediaPreviewActivity_signal_needs_the_storage_permission_in_order_to_write_to_external_storage_but_it_has_been_permanently_denied))
                   .onAnyDenied(() -> Toast.makeText(this, R.string.MediaPreviewActivity_unable_to_write_to_external_storage_without_permission, Toast.LENGTH_LONG).show())
                   .onAllGranted(() -> {
                     SaveAttachmentTask saveTask = new SaveAttachmentTask(MediaPreviewActivity.this);
                     long saveDate = (mediaItem.date > 0) ? mediaItem.date : System.currentTimeMillis();
                     saveTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, new Attachment(mediaItem.uri, mediaItem.type, saveDate, null));
                   })
                   .execute();
      });
    }
  }

  @SuppressLint("StaticFieldLeak")
  private void deleteMedia() {
    MediaItem mediaItem = getCurrentMediaItem();
    if (mediaItem == null || mediaItem.attachment == null) {
      return;
    }

    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setTitle(R.string.MediaPreviewActivity_media_delete_confirmation_title);
    builder.setMessage(R.string.MediaPreviewActivity_media_delete_confirmation_message);
    builder.setCancelable(true);

    builder.setPositiveButton(R.string.delete, (dialogInterface, which) -> {
      new AsyncTask<Void, Void, Void>() {
        @Override
        protected Void doInBackground(Void... voids) {
          AttachmentUtil.deleteAttachment(MediaPreviewActivity.this.getApplicationContext(),
                                          mediaItem.attachment);
          return null;
        }
      }.execute();

      finish();
    });
    builder.setNegativeButton(android.R.string.cancel, null);
    builder.show();
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    super.onPrepareOptionsMenu(menu);

    menu.clear();
    MenuInflater inflater = this.getMenuInflater();
    inflater.inflate(R.menu.media_preview, menu);

    if (!isMediaInDb()) {
      menu.findItem(R.id.media_preview__overview).setVisible(false);
      menu.findItem(R.id.delete).setVisible(false);
    }

    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);

    switch (item.getItemId()) {
      case R.id.media_preview__overview: showOverview(); return true;
      case R.id.media_preview__forward:  forward();      return true;
      case R.id.save:                    saveToDisk();   return true;
      case R.id.delete:                  deleteMedia();  return true;
      case android.R.id.home:            finish();       return true;
    }

    return false;
  }

  private boolean isMediaInDb() {
    return conversationRecipient != null;
  }

  private @Nullable MediaItem getCurrentMediaItem() {
    MediaItemAdapter adapter = (MediaItemAdapter)mediaPager.getAdapter();

    if (adapter != null) {
      return adapter.getMediaItemFor(mediaPager.getCurrentItem());
    } else {
      return null;
    }
  }

  public static boolean isContentTypeSupported(final String contentType) {
    return contentType != null && (contentType.startsWith("image/") || contentType.startsWith("video/"));
  }

  @Override
  public @NonNull Loader<Pair<Cursor, Integer>> onCreateLoader(int id, Bundle args) {
    return new PagingMediaLoader(this, conversationRecipient, initialMediaUri, leftIsRecent);
  }

  @Override
  public void onLoadFinished(@NonNull Loader<Pair<Cursor, Integer>> loader, @Nullable Pair<Cursor, Integer> data) {
    if (data != null) {
      @SuppressWarnings("ConstantConditions")
      CursorPagerAdapter adapter = new CursorPagerAdapter(getSupportFragmentManager(),this, data.first, data.second, leftIsRecent);
      mediaPager.setAdapter(adapter);
      adapter.setActive(true);

      int item = restartItem >= 0 ? restartItem : data.second;
      mediaPager.setCurrentItem(item);

      if (item == 0) {
        viewPagerListener.onPageSelected(0);
      }

      Util.postToMain(() -> viewModel.setCursor(this, data.first, leftIsRecent));
    }
  }

  @Override
  public void onLoaderReset(@NonNull Loader<Pair<Cursor, Integer>> loader) {

  }

  @Override
  public boolean singleTapOnMedia() {
    toggleUiVisibility();
    return true;
  }

  private void toggleUiVisibility() {
    int systemUiVisibility = getWindow().getDecorView().getSystemUiVisibility();
    if ((systemUiVisibility & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0) {
      showSystemUI();
    } else {
      hideSystemUI();
    }
  }

  private void hideSystemUI() {
    getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_IMMERSIVE              |
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE          |
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN      |
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION        |
        View.SYSTEM_UI_FLAG_FULLSCREEN              );
  }

  private void showSystemUI() {
    getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE          |
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN       );
  }

  private class ViewPagerListener extends ExtendedOnPageChangedListener {

    @Override
    public void onPageSelected(int position) {
      super.onPageSelected(position);

      MediaItemAdapter adapter = (MediaItemAdapter)mediaPager.getAdapter();

      if (adapter != null) {
        MediaItem item = adapter.getMediaItemFor(position);
        if (item.recipient != null) item.recipient.live().observe(MediaPreviewActivity.this, r -> initializeActionBar());
        viewModel.setActiveAlbumRailItem(MediaPreviewActivity.this, position);
        initializeActionBar();
      }
    }


    @Override
    public void onPageUnselected(int position) {
      MediaItemAdapter adapter = (MediaItemAdapter)mediaPager.getAdapter();

      if (adapter != null) {
        MediaItem item = adapter.getMediaItemFor(position);
        if (item.recipient != null) item.recipient.live().removeObservers(MediaPreviewActivity.this);

        adapter.pause(position);
      }
    }
  }

  private static class SingleItemPagerAdapter extends FragmentStatePagerAdapter implements MediaItemAdapter {

    private final Uri    uri;
    private final String mediaType;
    private final long   size;

    private MediaPreviewFragment mediaPreviewFragment;

    SingleItemPagerAdapter(@NonNull FragmentManager fragmentManager,
                           @NonNull Uri uri,
                           @NonNull String mediaType,
                           long size)
    {
      super(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
      this.uri       = uri;
      this.mediaType = mediaType;
      this.size      = size;
    }

    @Override
    public int getCount() {
      return 1;
    }

    @NonNull
    @Override
    public Fragment getItem(int position) {
      mediaPreviewFragment = MediaPreviewFragment.newInstance(uri, mediaType, size, true);
      return mediaPreviewFragment;
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
      if (mediaPreviewFragment != null) {
        mediaPreviewFragment.cleanUp();
        mediaPreviewFragment = null;
      }
    }

    @Override
    public MediaItem getMediaItemFor(int position) {
      return new MediaItem(null, null, uri, mediaType, -1, true);
    }

    @Override
    public void pause(int position) {
      if (mediaPreviewFragment != null) {
        mediaPreviewFragment.pause();
      }
    }

    @Override
    public @Nullable View getPlaybackControls(int position) {
      if (mediaPreviewFragment != null) {
        return mediaPreviewFragment.getPlaybackControls();
      }
      return null;
    }
  }

  private static void anchorMarginsToBottomInsets(@NonNull View viewToAnchor) {
    ViewCompat.setOnApplyWindowInsetsListener(viewToAnchor, (view, insets) -> {
      ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) view.getLayoutParams();

      layoutParams.setMargins(insets.getSystemWindowInsetLeft(),
                              layoutParams.topMargin,
                              insets.getSystemWindowInsetRight(),
                              insets.getSystemWindowInsetBottom());

      view.setLayoutParams(layoutParams);

      return insets;
    });
  }

  private static void anchorMarginsToTopInsets(@NonNull View viewToAnchor) {
    ViewCompat.setOnApplyWindowInsetsListener(viewToAnchor, (view, insets) -> {
      ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) view.getLayoutParams();

      layoutParams.setMargins(insets.getSystemWindowInsetLeft(),
                              insets.getSystemWindowInsetTop(),
                              insets.getSystemWindowInsetRight(),
                              layoutParams.bottomMargin);

      view.setLayoutParams(layoutParams);

      return insets;
    });
  }

  private static void showAndHideWithSystemUI(@NonNull Window window, @NonNull View... views) {
    window.getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
      boolean hide = (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0;

      for (View view : views) {
        view.animate()
            .alpha(hide ? 0 : 1)
            .start();
      }
    });
  }

  private static class CursorPagerAdapter extends FragmentStatePagerAdapter implements MediaItemAdapter {

    @SuppressLint("UseSparseArrays")
    private final Map<Integer, MediaPreviewFragment> mediaFragments = new HashMap<>();

    private final Context context;
    private final Cursor  cursor;
    private final boolean leftIsRecent;

    private boolean active;
    private int     autoPlayPosition;

    CursorPagerAdapter(@NonNull FragmentManager fragmentManager,
                       @NonNull Context context,
                       @NonNull Cursor cursor,
                       int autoPlayPosition,
                       boolean leftIsRecent)
    {
      super(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
      this.context          = context.getApplicationContext();
      this.cursor           = cursor;
      this.autoPlayPosition = autoPlayPosition;
      this.leftIsRecent     = leftIsRecent;
    }

    public void setActive(boolean active) {
      this.active = active;
      notifyDataSetChanged();
    }

    @Override
    public int getCount() {
      if (!active) return 0;
      else         return cursor.getCount();
    }

    @NonNull
    @Override
    public Fragment getItem(int position) {
      boolean autoPlay = autoPlayPosition == position;
      int cursorPosition = getCursorPosition(position);

      autoPlayPosition = -1;

      cursor.moveToPosition(cursorPosition);

      MediaDatabase.MediaRecord mediaRecord = MediaDatabase.MediaRecord.from(context, cursor);
      DatabaseAttachment        attachment  = mediaRecord.getAttachment();
      MediaPreviewFragment      fragment    = MediaPreviewFragment.newInstance(attachment, autoPlay);

      mediaFragments.put(position, fragment);

      return fragment;
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
      MediaPreviewFragment removed = mediaFragments.remove(position);

      if (removed != null) {
        removed.cleanUp();
      }

      super.destroyItem(container, position, object);
    }

    public MediaItem getMediaItemFor(int position) {
      cursor.moveToPosition(getCursorPosition(position));
      MediaRecord mediaRecord = MediaRecord.from(context, cursor);
      RecipientId recipientId = mediaRecord.getRecipientId();

      if (mediaRecord.getAttachment().getDataUri() == null) throw new AssertionError();

      return new MediaItem(Recipient.live(recipientId).get(),
                           mediaRecord.getAttachment(),
                           mediaRecord.getAttachment().getDataUri(),
                           mediaRecord.getContentType(),
                           mediaRecord.getDate(),
                           mediaRecord.isOutgoing());
    }

    @Override
    public void pause(int position) {
      MediaPreviewFragment mediaView = mediaFragments.get(position);
      if (mediaView != null) mediaView.pause();
    }

    @Override
    public @Nullable View getPlaybackControls(int position) {
      MediaPreviewFragment mediaView = mediaFragments.get(position);
      if (mediaView != null) return mediaView.getPlaybackControls();
      return null;
    }

    private int getCursorPosition(int position) {
      if (leftIsRecent) return position;
      else              return cursor.getCount() - 1 - position;
    }
  }

  private static class MediaItem {
    private final @Nullable Recipient          recipient;
    private final @Nullable DatabaseAttachment attachment;
    private final @NonNull  Uri                uri;
    private final @NonNull  String             type;
    private final           long               date;
    private final           boolean            outgoing;

    private MediaItem(@Nullable Recipient recipient,
                      @Nullable DatabaseAttachment attachment,
                      @NonNull Uri uri,
                      @NonNull String type,
                      long date,
                      boolean outgoing)
    {
      this.recipient  = recipient;
      this.attachment = attachment;
      this.uri        = uri;
      this.type       = type;
      this.date       = date;
      this.outgoing   = outgoing;
    }
  }

  interface MediaItemAdapter {
    MediaItem getMediaItemFor(int position);
    void pause(int position);
    @Nullable View getPlaybackControls(int position);
  }
}
