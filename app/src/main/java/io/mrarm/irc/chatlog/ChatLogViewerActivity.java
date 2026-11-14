package io.mrarm.irc.chatlog;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.mrarm.irc.R;
import io.mrarm.irc.ThemedActivity;
import io.mrarm.irc.config.ServerConfigData;
import io.mrarm.irc.config.ServerConfigManager;
import io.mrarm.irc.conversation.ConversationRepository;
import io.mrarm.irc.conversation.ConversationRepositoryImpl;
import io.mrarm.irc.infrastructure.threading.AppAsyncExecutor;
import io.mrarm.irc.storage.MessageStorageRepository;
import io.mrarm.irc.view.AutoResizeSpinner;

public class ChatLogViewerActivity extends ThemedActivity {

    private static final String TAG = "ChatLogViewerActivity";
    private static final int REQUEST_CODE_EXPORT = 200;
    private static final int LOAD_MORE_BEFORE_INDEX = 10;
    private static final int PAGE_SIZE = 100;

    private AutoResizeSpinner mServerSpinner;
    private AutoResizeSpinner mChannelSpinner;
    private AutoResizeSpinner mSenderSpinner;
    private RecyclerView mRecyclerView;
    private LinearLayoutManager mLayoutManager;
    private TextView mEmptyState;
    private ChatLogAdapter mAdapter;
    private MessageStorageRepository mRepo;
    private ConversationRepository mConvRepo;

    private List<ServerConfigData> mServers = new ArrayList<>();
    private List<String> mChannelValues = new ArrayList<>();
    private List<String> mSenderValues = new ArrayList<>();

    private UUID mSelectedServer;
    private String mSelectedChannel;
    private String mSelectedSender;

    private boolean mIsLoadingMore = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_log_viewer);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        mRepo = MessageStorageRepository.getInstance(this);
        mConvRepo = new ConversationRepositoryImpl(mRepo);

        mServerSpinner = findViewById(R.id.filter_server);
        mChannelSpinner = findViewById(R.id.filter_channel);
        mSenderSpinner = findViewById(R.id.filter_sender);
        mEmptyState = findViewById(R.id.empty_state);

        mRecyclerView = findViewById(R.id.list);
        mLayoutManager = new LinearLayoutManager(this);
        mRecyclerView.setLayoutManager(mLayoutManager);
        mAdapter = new ChatLogAdapter();
        mRecyclerView.setAdapter(mAdapter);

        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                int firstVisible = mLayoutManager.findFirstVisibleItemPosition();
                if (firstVisible >= 0 && firstVisible < LOAD_MORE_BEFORE_INDEX)
                    loadOlder();
            }
        });

        setupServerSpinner();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_chat_log_viewer, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        if (item.getItemId() == R.id.action_export) {
            startExport();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupServerSpinner() {
        mServers = new ArrayList<>(ServerConfigManager.getInstance(this).getServers());

        mConvRepo.getDistinctServerIdsAsync(loggedIds -> {
            if (loggedIds == null)
                loggedIds = Collections.emptyList();

            Set<UUID> known = new HashSet<>();
            for (ServerConfigData s : mServers)
                known.add(s.uuid);

            for (UUID id : loggedIds) {
                if (known.contains(id))
                    continue;
                ServerConfigData orphan = new ServerConfigData();
                orphan.uuid = id;
                orphan.name = getString(R.string.chat_log_deleted_server, id.toString().substring(0, 8));
                mServers.add(orphan);
            }

            bindServerSpinner();
        });
    }

    private void bindServerSpinner() {
        if (mServers.isEmpty()) {
            showEmptyState(true);
            return;
        }

        List<String> labels = new ArrayList<>();
        for (ServerConfigData s : mServers)
            labels.add(s.name);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mServerSpinner.setAdapter(adapter);
        mServerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mSelectedServer = mServers.get(position).uuid;
                onServerSelected();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        mServerSpinner.setSelection(0);
    }

    private void onServerSelected() {
        mSelectedChannel = null;
        mSelectedSender = null;

        mConvRepo.getDistinctChannelAsync(mSelectedServer, channels -> {
            bindChannelSpinner(channels != null ? channels : new ArrayList<>());
        });
    }

    private void bindChannelSpinner(List<String> channels) {
        List<String> labels = new ArrayList<>();
        mChannelValues = new ArrayList<>();

        labels.add(getString(R.string.chat_log_all_channels));
        mChannelValues.add(null);
        for (String c : channels) {
            labels.add(c.isEmpty() ? getString(R.string.tab_server) : c);
            mChannelValues.add(c);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mChannelSpinner.setAdapter(adapter);
        mChannelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mSelectedChannel = mChannelValues.get(position);
                onChannelSelected();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        mChannelSpinner.setSelection(0);
    }

    private void onChannelSelected() {
        mSelectedSender = null;

        mConvRepo.getDistinctSendersAsync(mSelectedServer, mSelectedChannel, senders -> {
            bindSenderSpinner(senders != null ? senders : new ArrayList<>());
        });
    }

    private void bindSenderSpinner(List<String> senders) {
        List<String> labels = new ArrayList<>();
        mSenderValues = new ArrayList<>();

        labels.add(getString(R.string.chat_log_all_senders));
        mSenderValues.add(null);
        for (String s : senders) {
            labels.add(s);
            mSenderValues.add(s);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSenderSpinner.setAdapter(adapter);
        mSenderSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mSelectedSender = mSenderValues.get(position);
                reload();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        mSenderSpinner.setSelection(0);

        reload();
    }

    private void reload() {
        mIsLoadingMore = true;
        mConvRepo.loadFilteredBeforeAsync(mSelectedServer, mSelectedChannel, mSelectedSender,
                Long.MAX_VALUE, PAGE_SIZE, Collections.emptyList(), result -> {
                    mIsLoadingMore = false;
                    mAdapter.setMessages(result);
                    showEmptyState(result.isEmpty());
                    if (!result.isEmpty())
                        mRecyclerView.scrollToPosition(mAdapter.getItemCount() - 1);
                });
    }

    private void loadOlder() {
        if (mIsLoadingMore || !mAdapter.hasMessages())
            return;
        mIsLoadingMore = true;
        long beforeId = mAdapter.getOldestId();

        mConvRepo.loadFilteredBeforeAsync(mSelectedServer, mSelectedChannel, mSelectedSender,
                beforeId, PAGE_SIZE, Collections.emptyList(), result -> {
                    mIsLoadingMore = false;
                    mAdapter.addToTop(result);
                });
    }

    private void showEmptyState(boolean empty) {
        mEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        mRecyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void startExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "chat-log-" + System.currentTimeMillis() + ".txt");
        startActivityForResult(intent, REQUEST_CODE_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_EXPORT) {
            if (data == null || data.getData() == null)
                return;
            Uri uri = data.getData();
            UUID serverId = mSelectedServer;
            String channel = mSelectedChannel;
            String sender = mSelectedSender;

            AppAsyncExecutor.io(() -> {
                try (ParcelFileDescriptor desc = getContentResolver().openFileDescriptor(uri, "w");
                     BufferedWriter wr = new BufferedWriter(new FileWriter(desc.getFileDescriptor()))) {
                    mRepo.exportFilteredToWriter(serverId, channel, sender, Collections.emptyList(), wr);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to export chat logs", e);
                    AppAsyncExecutor.ui(() ->
                            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show());
                    return;
                }
                AppAsyncExecutor.ui(() ->
                        Toast.makeText(this, R.string.chat_log_export_success, Toast.LENGTH_SHORT).show());
            });
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
