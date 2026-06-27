package io.mrarm.irc;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SortedList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import io.mrarm.irc.connection.ServerConnectionManager;
import io.mrarm.irc.connection.ServerConnectionSession;
import io.mrarm.irc.infrastructure.threading.AppAsyncExecutor;
import io.mrarm.irc.protocol.dto.ChannelList;
import io.mrarm.irc.view.ProgressBar;
import io.mrarm.irc.view.RecyclerViewScrollbar;

public class ChannelListActivity extends ThemedActivity {

    public static final String ARG_SERVER_UUID = "server_uuid";

    public static final int SORT_UNSORTED = 0;
    public static final int SORT_NAME = 1;
    public static final int SORT_MEMBER_COUNT = 2;

    private ServerConnectionSession mConnection;
    private View mMainAppBar;
    private View mSearchAppBar;
    private SearchView mSearchView;
    private RecyclerView mList;
    private ListAdapter mListAdapter;
    private ProgressBar mProgressBar;

    private String mFilterQuery;
    private int mSortMode = SORT_NAME;

    private SortedList<DisplayEntry> mSortedEntries;

    private final LinkedHashMap<String, DisplayEntry> mAllEntries = new LinkedHashMap<>();
    private long mNextSequence = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null)
            actionBar.setDisplayHomeAsUpEnabled(true);

        Toolbar searchToolbar = findViewById(R.id.search_toolbar);
        searchToolbar.setNavigationOnClickListener(v -> setSearchMode(false));

        mMainAppBar = findViewById(R.id.appbar);
        mSearchAppBar = findViewById(R.id.search_appbar);
        mSearchView = findViewById(R.id.search_view);
        mProgressBar = findViewById(R.id.progress);
        mList = findViewById(R.id.list);

        UUID serverUUID = UUID.fromString(getIntent().getStringExtra(ARG_SERVER_UUID));
        mConnection = ServerConnectionManager.getInstance(this).getConnection(serverUUID);

        mList.setLayoutManager(new LinearLayoutManager(this));
        mListAdapter = new ListAdapter();
        mList.setAdapter(mListAdapter);
        mList.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));

        setupSortedList();

        mSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                mFilterQuery = newText.toLowerCase();
                refreshFilteredEntries();
                return true;
            }
        });

        showProgress(true);
        mConnection.getApiInstance().listChannels(list -> {
            Log.d("ChannelList", "Received full list of " + list.getEntries().size() + " channels");
            AppAsyncExecutor.ui(() -> addChannels(list.getEntries(), () -> showProgress(false)));
        }, entry -> {
            // Called per new entry during live fetching
            if (entry != null) {
                mList.post(() -> {
                    mSortedEntries.beginBatchedUpdates();
                    addOrUpdateEntryInternal(entry);
                    mSortedEntries.endBatchedUpdates();
                });
            }
        }, null);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mSearchAppBar.getVisibility() == View.VISIBLE) {
                    setSearchMode(false);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private Comparator<DisplayEntry> mCurrentComparator = BY_NAME;

    private static final Comparator<DisplayEntry> BY_NAME = (a, b) -> {
        String an = a.entry.getChannel() == null ? "" : a.entry.getChannel();
        String bn = b.entry.getChannel() == null ? "" : b.entry.getChannel();
        return an.compareToIgnoreCase(bn);
    };

    private static final Comparator<DisplayEntry> BY_MEMBERS = (a, b) -> {
        int r = Integer.compare(b.entry.getMemberCount(), a.entry.getMemberCount());
        if (r != 0) return r;
        return BY_NAME.compare(a, b);
    };

    private void setupSortedList() {
        mSortedEntries = new SortedList<>(DisplayEntry.class, new SortedList.Callback<>() {
            @Override
            public int compare(DisplayEntry a, DisplayEntry b) {
                return mCurrentComparator.compare(a, b);
            }

            @Override
            public void onInserted(int position, int count) {
                Log.d("ChannelList", "Inserted " + count + " item(s) at " + position);
                mListAdapter.notifyItemRangeInserted(position, count);
            }

            @Override
            public void onRemoved(int position, int count) {
                Log.d("ChannelList", "Removed " + count + " item(s) at " + position);
                mListAdapter.notifyItemRangeRemoved(position, count);
            }

            @Override
            public void onMoved(int from, int to) {
                mListAdapter.notifyItemMoved(from, to);
            }

            @Override
            public void onChanged(int position, int count) {
                mListAdapter.notifyItemRangeChanged(position, count);
            }

            @Override
            public boolean areContentsTheSame(DisplayEntry oldItem, DisplayEntry newItem) {
                ChannelList.Entry oldEntry = oldItem.entry;
                ChannelList.Entry newEntry = newItem.entry;
                String oldTopic = oldEntry.getTopic() == null ? "" : oldEntry.getTopic();
                String newTopic = newEntry.getTopic() == null ? "" : newEntry.getTopic();
                return oldEntry.getMemberCount() == newEntry.getMemberCount()
                        && oldEntry.getChannel().equals(newEntry.getChannel())
                        && oldTopic.equals(newTopic);
            }

            @Override
            public boolean areItemsTheSame(DisplayEntry oldItem, DisplayEntry newItem) {
                return oldItem.entry.getChannel().equals(newItem.entry.getChannel());
            }
        });
    }


    private static boolean filterEntry(ChannelList.Entry entry, String query) {
        return query == null || query.isEmpty() ||
                entry.getChannel().toLowerCase().contains(query);
    }


    /**
     * Called by Async background job once the full list arrives
     */
    private void addChannels(List<ChannelList.Entry> entries, Runnable onComplete) {
        if (entries == null || entries.isEmpty()) {
            if (onComplete != null)
                runOnUiThread(onComplete);
            return;
        }
        runOnUiThread(() -> scheduleBatchAdd(entries.iterator(), onComplete));
    }

    /**
     * Re-applies filter on already-fetched entries (search text changed)
     */
    private void refreshFilteredEntries() {
        showProgress(true);
        mList.post(() -> {
            rebuildVisibleEntries();
            showProgress(false);
        });
    }

    private void showProgress(boolean show) {
        if (mProgressBar != null)
            mProgressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_channel_list, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            manager.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
            finish();
            return true;
        } else if (id == R.id.action_search) {
            setSearchMode(true);
            mSearchView.setIconified(false);
            return true;
        } else if (id == R.id.action_sort_none || id == R.id.action_sort_name || id == R.id.action_sort_member_count) {
            if (id == R.id.action_sort_name)
                setSortMode(SORT_NAME);
            else if (id == R.id.action_sort_member_count)
                setSortMode(SORT_MEMBER_COUNT);
            else
                setSortMode(SORT_UNSORTED);

            Log.d("ChannelList", "Sort mode changed → " + mSortMode);

            resortVisibleEntries();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    void setSortMode(int mode) {
        if (mode == mSortMode) return;
        mSortMode = mode;
        mCurrentComparator = switch (mode) {
            case SORT_MEMBER_COUNT -> BY_MEMBERS;
            case SORT_NAME -> BY_NAME;
            default -> Comparator.comparingLong((DisplayEntry a) -> a.sequence);
        };

        mList.post(() -> {
            mSortedEntries.beginBatchedUpdates();
            for (int i = mSortedEntries.size() - 1; i >= 0; i--)
                mSortedEntries.recalculatePositionOfItemAt(i);
            mSortedEntries.endBatchedUpdates();
            mList.scrollToPosition(0);
        });
    }

    public void setSearchMode(boolean searchMode) {
        mMainAppBar.setVisibility(searchMode ? View.GONE : View.VISIBLE);
        mSearchAppBar.setVisibility(searchMode ? View.VISIBLE : View.GONE);

        int color = ContextCompat.getColor(this,
                searchMode ? R.color.searchColorPrimaryDark : R.color.colorPrimaryDark
        );

        Window window = getWindow();
        window.setStatusBarColor(color);

        WindowInsetsControllerCompat controller =
                new WindowInsetsControllerCompat(window, window.getDecorView());

        controller.setAppearanceLightStatusBars(searchMode);

        if (!searchMode) {
            mFilterQuery = null;
            refreshFilteredEntries();
        }
    }


    // ------------------------------------------------------------
    // Adapter + ViewHolder
    // ------------------------------------------------------------
    public class ListAdapter extends RecyclerView.Adapter<ListEntry>
            implements RecyclerViewScrollbar.LetterAdapter {

        @Override
        @NonNull
        public ListEntry onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.channel_list_item, parent, false);
            return new ListEntry(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ListEntry holder, int position) {
            if (mSortedEntries == null)
                return;
            holder.bind(mSortedEntries.get(position).entry);
        }

        @Override
        public String getLetterFor(int position) {
            if (mSortMode != SORT_NAME) return null;
            if (mSortedEntries == null || position < 0 || position >= mSortedEntries.size())
                return null;
            String channel = mSortedEntries.get(position).entry.getChannel();
            return channel.length() >= 2 ? channel.substring(1, 2).toUpperCase() : "?";
        }

        @Override
        public int getItemCount() {
            return mSortedEntries != null ? mSortedEntries.size() : 0;
        }
    }

    public class ListEntry extends RecyclerView.ViewHolder {

        private final TextView mName;
        private final TextView mTopic;

        public ListEntry(View itemView) {
            super(itemView);
            mName = itemView.findViewById(R.id.name);
            mTopic = itemView.findViewById(R.id.topic);
            itemView.setOnClickListener(view -> {
                List<String> channels = new ArrayList<>();
                channels.add((String) mName.getTag());
                mConnection.getApiInstance().joinChannels(channels, v -> runOnUiThread(() -> {
                    finish();
                    startActivity(MainActivity.getLaunchIntent(ChannelListActivity.this,
                            mConnection, channels.get(0)));
                }), null);
            });
        }

        public void bind(ChannelList.Entry entry) {
            mName.setText(mName.getResources().getQuantityString(
                    R.plurals.channel_list_title_with_member_count, entry.getMemberCount(),
                    entry.getChannel(), entry.getMemberCount()));
            mName.setTag(entry.getChannel());
            mTopic.setText(entry.getTopic().trim());
            mTopic.setVisibility(!TextUtils.isEmpty(mTopic.getText()) ? View.VISIBLE : View.GONE);
        }
    }

    private void scheduleBatchAdd(Iterator<ChannelList.Entry> iterator, Runnable onComplete) {
        if (iterator == null)
            return;
        mList.post(new Runnable() {
            @Override
            public void run() {
                int processed = 0;
                mSortedEntries.beginBatchedUpdates();
                while (iterator.hasNext() && processed < 200) {
                    ChannelList.Entry next = iterator.next();
                    addOrUpdateEntryInternal(next);
                    processed++;
                }
                mSortedEntries.endBatchedUpdates();
                if (iterator.hasNext()) {
                    mList.post(this);
                } else if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
    }

    private void rebuildVisibleEntries() {
        List<DisplayEntry> filtered = new ArrayList<>();
        for (DisplayEntry entry : mAllEntries.values()) {
            if (filterEntry(entry.entry, mFilterQuery))
                filtered.add(entry);
        }
        mSortedEntries.beginBatchedUpdates();
        mSortedEntries.clear();
        for (DisplayEntry entry : filtered)
            mSortedEntries.add(entry);
        mSortedEntries.endBatchedUpdates();
    }

    private void resortVisibleEntries() {
        mList.post(() -> {
            mSortedEntries.beginBatchedUpdates();
            for (int i = mSortedEntries.size() - 1; i >= 0; i--)
                mSortedEntries.recalculatePositionOfItemAt(i);
            mSortedEntries.endBatchedUpdates();
        });
    }

    private void addOrUpdateEntryInternal(ChannelList.Entry entry) {
        DisplayEntry previous = mAllEntries.get(entry.getChannel());
        DisplayEntry updated;
        if (previous == null) {
            updated = new DisplayEntry(entry, mNextSequence++);
        } else {
            updated = previous.withEntry(entry);
        }
        mAllEntries.put(entry.getChannel(), updated);

        boolean passesFilter = filterEntry(updated.entry, mFilterQuery);
        if (!passesFilter) {
            if (previous != null) {
                int index = mSortedEntries.indexOf(previous);
                if (index != SortedList.INVALID_POSITION)
                    mSortedEntries.removeItemAt(index);
            }
            return;
        }
        mSortedEntries.add(updated);
    }

    private record DisplayEntry(ChannelList.Entry entry, long sequence) {

        DisplayEntry withEntry(ChannelList.Entry newEntry) {
            return new DisplayEntry(newEntry, sequence);
        }
    }
}