package io.mrarm.irc;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import io.mrarm.irc.app.interaction.ChatOptionsActionHandler;
import io.mrarm.irc.app.menu.ChatMenuApplier;
import io.mrarm.irc.app.menu.ChatMenuState;
import io.mrarm.irc.app.menu.ChatMenuStateResolver;
import io.mrarm.irc.app.navigation.DrawerToolbarHost;
import io.mrarm.irc.app.navigation.MainNavigator;
import io.mrarm.irc.app.navigation.NavigationHost;
import io.mrarm.irc.chat.ChannelInfoAdapter;
import io.mrarm.irc.chat.ChatFragment;
import io.mrarm.irc.chatlib.ChatApi;
import io.mrarm.irc.chatlib.dto.NickWithPrefix;
import io.mrarm.irc.config.AppSettings;
import io.mrarm.irc.connection.ServerConnectionManager;
import io.mrarm.irc.connection.ServerConnectionSession;
import io.mrarm.irc.dialog.DialogHost;
import io.mrarm.irc.dialog.MenuBottomSheetDialog;
import io.mrarm.irc.dialog.UserBottomSheetDialog;
import io.mrarm.irc.dialog.UserSearchDialog;
import io.mrarm.irc.drawer.DrawerHelper;
import io.mrarm.irc.util.LinkHelper;
import io.mrarm.irc.util.NightModeRecreateHelper;
import io.mrarm.irc.util.StyledAttributesHelper;
import io.mrarm.irc.util.WarningHelper;
import io.mrarm.irc.view.ChipsEditText;
import io.mrarm.irc.view.LockableDrawerLayout;

// TODO(architecture): mostly done
// MainActivity currently acts as a central UI orchestrator:
// - navigation & fragment switching
// - drawer / toolbar state
// - menu state derivation
// - bridging user actions to domain operations (connections, channels, DCC)
//
// Future refactor direction:
// - extract navigation logic into a Navigator / Coordinator
// - extract DCC-related UI flows into a dedicated component
// - keep MainActivity focused on lifecycle + high-level orchestration

@Keep
public class MainActivity extends ThemedActivity
        implements IRCApplication.ExitCallback,
        MainNavigator.Host,
        NavigationHost,
        DrawerToolbarHost,
        DialogHost {

    public static final String ARG_SERVER_UUID = "server_uuid";
    public static final String ARG_CHANNEL_NAME = "channel";
    public static final String ARG_MESSAGE_ID = "message_id";
    public static final String ARG_MANAGE_SERVERS = "manage_servers";
    private static final String TAG = "[MAIN ACTIVITY]";

    private static final int REQUEST_CODE_DCC_FOLDER_PERMISSION = 101;
    private static final int REQUEST_CODE_DCC_STORAGE_PERMISSION = 102;

    private final NightModeRecreateHelper mNightModeHelper = new NightModeRecreateHelper(this);
    private LockableDrawerLayout mDrawerLayout;
    private DrawerHelper mDrawerHelper;
    private Toolbar mToolbar;
    private View mFakeToolbar;
    private Dialog mCurrentDialog;
    private ChannelInfoAdapter mChannelInfoAdapter;
    private boolean mAppExiting;
    private MainNavigator mNavigator;
    private final ChatMenuStateResolver mMenuResolver = new ChatMenuStateResolver();
    private final ChatMenuApplier mMenuApplier = new ChatMenuApplier();
    private ChatOptionsActionHandler mChatActions;

    private final DCCManager.ActivityDialogHandler mDCCDialogHandler =
            new DCCManager.ActivityDialogHandler(this, REQUEST_CODE_DCC_FOLDER_PERMISSION,
                    REQUEST_CODE_DCC_STORAGE_PERMISSION);

    private AlertDialog mNickDialog = null;
    private ServerConnectionSession mNickDialogSession = null;
    private final ServerConnectionSession.InfoChangeListener mNickExhaustedListener =
            connection -> runOnUiThread(() -> {
                if (connection.isNickExhausted()) {
                    if (mNickDialog == null || mNickDialogSession != connection) {
                        if (mNickDialog != null) mNickDialog.dismiss();
                        showNickUnavailableDialog(connection);
                    }
                } else if (mNickDialogSession == connection && mNickDialog != null) {
                    mNickDialog.dismiss();
                    mNickDialog = null;
                    mNickDialogSession = null;
                }
            });

    private DCCCoordinator dccCoordinator;
    private ActivityResultLauncher<String> dccFilePicker;

    @Override
    public MainNavigator getNavigator() {
        return mNavigator;
    }

    @Override
    public void addDrawerToggle(Toolbar toolbar) {
        addActionBarDrawerToggle(toolbar);
    }

    public static Intent getLaunchIntent(Context context, ServerConnectionSession server, String channel, String messageId) {
        Intent intent = new Intent(context, MainActivity.class);
        if (server != null)
            intent.putExtra(ARG_SERVER_UUID, server.getUUID().toString());
        if (channel != null)
            intent.putExtra(ARG_CHANNEL_NAME, channel);
        if (messageId != null)
            intent.putExtra(ARG_MESSAGE_ID, messageId);
        return intent;
    }

    public static Intent getLaunchIntent(Context context, ServerConnectionSession server, String channel) {
        return getLaunchIntent(context, server, channel, null);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "onCreate() ");
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        ServerConnectionManager.getInstance(this);
        WarningHelper.setAppContext(getApplicationContext());

        initializeActivityCore();
        setupDrawer();
        setupNavigator();
        setupGlobalLinkHandler();
        setupDCCHandlers();
        setupChatActions();
        setupChannelInfoAdapter();
        setupBackHandling();

        if (savedInstanceState == null) {
            handleInitialIntent();
        }

        ServerConnectionManager.getInstance(this).addGlobalConnectionInfoListener(mNickExhaustedListener);

        if (savedInstanceState == null) {
            checkBatteryOptimization();
        }
    }

    private void setupDCCHandlers() {
        Log.i(TAG, "setupGlobalLinkHandler(): Setting up DCC handlers");
        dccFilePicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null && dccCoordinator != null) dccCoordinator.onFilePicked(uri);
                }
        );

        dccCoordinator = new DCCCoordinator(new DCCCoordinator.Host() {
            @Override public Context getContext() { return MainActivity.this; }
            @Override public ChatFragment getCurrentChat() {
                Fragment f = getCurrentFragment();
                return (f instanceof ChatFragment) ? (ChatFragment) f : null;
            }
            @Override public ActivityResultLauncher<String> getFilePicker() { return dccFilePicker; }
        });
    }

    private void setupBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!mNavigator.handleBackPressed()) {
                    finish();
                }
            }
        });
    }

    private void handleInitialIntent() {
        Intent intent = getIntent();

        ChatFragment fragment = mNavigator.handleIntent(
                intent,
                uuid -> ServerConnectionManager
                        .getInstance(this)
                        .getConnection(UUID.fromString(uuid)),
                getCurrentFragment(),
                ARG_SERVER_UUID,
                ARG_CHANNEL_NAME,
                ARG_MESSAGE_ID,
                ARG_MANAGE_SERVERS
        );

        if (fragment != null
                && Intent.ACTION_SEND.equals(intent.getAction())
                && "text/plain".equals(intent.getType())) {
            setFragmentShareText(fragment, intent.getStringExtra(Intent.EXTRA_TEXT));
        }
    }

    private void setupChannelInfoAdapter() {
        mChannelInfoAdapter = new ChannelInfoAdapter((connection, nick) -> {

            UserBottomSheetDialog dialog =
                    new UserBottomSheetDialog(MainActivity.this);

            dialog.setConnection(connection);

            dialog.setOpenHandler((conn, n) -> mNavigator.openServer(conn, n));

            dialog.requestData(nick, connection.getApiInstance());

            Dialog d = dialog.show();
            setFragmentDialog(d);
        });
        RecyclerView membersRecyclerView = findViewById(R.id.members_list);
        membersRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        membersRecyclerView.setAdapter(mChannelInfoAdapter);
        setChannelInfoDrawerVisible(false);
    }

    private void setupChatActions() {
        Log.i(TAG, "setupChatActions(): Setting up Chat Actions");
        mChatActions = new ChatOptionsActionHandler(new ChatOptionsActionHandler.Host() {

            @Override
            public boolean isChatScreen() {
                Log.i(TAG, "setupChatActions(): action -> Is Chat Screen? ");
                return getCurrentFragment() instanceof ChatFragment;
            }

            @Override
            public void showJoinChannelDialog() {
                Log.i(TAG, "setupChatActions(): action -> show Join Channel Dialog ");
                // move nothing yet: just call existing inline code for now
                MainActivity.this.showJoinChannelDialog();
            }

            @Override
            public void showUserSearchDialog() {
                Log.i(TAG, "setupChatActions(): action -> show User Search Dialog ");
                MainActivity.this.showUserSearchDialog();
            }

            @Override
            public void partCurrentChannel() {
                Log.i(TAG, "setupChatActions(): action -> part Current Channel");
                MainActivity.this.partCurrentChannel();
            }

            @Override
            public void pickFileForDccSend() {
                Log.i(TAG, "setupChatActions(): action -> pick File for DCC send");
                dccCoordinator.requestFileSend();
            }

            @Override
            public void openMembersDrawer() {
                Log.i(TAG, "setupChatActions(): action -> open Members Drawer");
                mDrawerLayout.openDrawer(GravityCompat.END);
            }

            @Override
            public void openIgnoreList() {
                Log.i(TAG, "setupChatActions(): action -> open Ignore List");
                MainActivity.this.openIgnoreList();
            }

            @Override
            public void disconnect() {
                Log.i(TAG, "setupChatActions(): action -> disconnect");
                ((ChatFragment) getCurrentFragment()).getConnectionInfo().disconnect();
            }

            @Override
            public void disconnectAndClose() {
                Log.i(TAG, "setupChatActions(): action -> disconnect and close");
                MainActivity.this.disconnectAndClose();
            }

            @Override
            public void reconnect() {
                Log.i(TAG, "setupChatActions(): action -> reconnect");
                ((ChatFragment) getCurrentFragment()).getConnectionInfo().connect();
            }

            @Override
            public void showFormatBar() {
                Log.i(TAG, "setupChatActions(): action -> show format bar");
                ((ChatFragment) getCurrentFragment()).getSendMessageHelper().setFormatBarVisible(true);
            }

            @Override
            public void openDccTransfers() {
                Log.i(TAG, "setupChatActions(): action -> open DCC transfer");
                dccCoordinator.openTransfers();
            }

            @Override
            public void openSettings() {
                Log.i(TAG, "setupChatActions(): action -> open settings");
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }

            @Override
            public void requestExit() {
                Log.i(TAG, "setupChatActions(): action -> request exit");
                ((IRCApplication) getApplication()).requestExit();
            }
        });
    }

    private void setupGlobalLinkHandler() {
        Log.i(TAG, "setupGlobalLinkHandler(): Setting up global link handler");
        LinkHelper.setChannelLinkHandler((channel, view) -> {

            if (!(getCurrentFragment() instanceof ChatFragment fragment)) {
                return;
            }

            MenuBottomSheetDialog dialog =
                    new MenuBottomSheetDialog(view.getContext());

            dialog.addHeader(channel);

            dialog.addItem(R.string.action_open,
                    R.drawable.ic_open_in_new,
                    item -> {

                        ServerConnectionSession connection =
                                fragment.getConnectionInfo();

                        List<String> channels = new ArrayList<>();
                        channels.add(channel);

                        if (connection.hasChannel(channel)) {
                            mNavigator.openServer(connection, channel);
                            return true;
                        }

                        fragment.setAutoOpenChannel(channel);
                        connection.getApiInstance()
                                .joinChannels(channels, null, null);

                        return true;
                    });

            dialog.show();
            setFragmentDialog(dialog);
        });
    }

    private void setupNavigator() {
        Log.i(TAG, "setupNavigator(): Setting up global navigator");
        mNavigator = new MainNavigator(
                getSupportFragmentManager(),
                R.id.content_frame,
                mDrawerHelper,
                this
        );
    }

    private void setupDrawer() {
        Log.i(TAG, "setupDrawer(): Setting up drawer menu");
        mDrawerHelper = new DrawerHelper(this, this);
        mDrawerHelper.registerListeners();

        mDrawerHelper.setChannelClickListener((ServerConnectionSession server, String channel) -> {
            mDrawerLayout.closeDrawers();
            mNavigator.onChannelSelected(server, channel);
        });

        mDrawerHelper.getManageServersItem().setOnClickListener((View v) -> {
            mDrawerLayout.closeDrawers();
            mNavigator.openManageServersSelected();
        });
    }

    private void initializeActivityCore() {
        Log.i(TAG, "initializeActivityCore(): Initializing core activity");
        mAppExiting = false;
        ((IRCApplication) getApplication()).addExitCallback(this);
        mFakeToolbar = findViewById(R.id.fake_toolbar);
        mDrawerLayout = findViewById(R.id.drawer_layout);

        if (AppSettings.isDrawerPinned())
            mDrawerLayout.setLocked(true);
    }

    private void setFragmentShareText(ChatFragment fragment, String text) {
        if (fragment.getSendMessageHelper() != null) {
            fragment.getSendMessageHelper().setMessageText(text);
        } else {
            Bundle bundle = fragment.getArguments();
            bundle.putString(ChatFragment.ARG_SEND_MESSAGE_TEXT, text);
            fragment.setArguments(bundle);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.i(TAG, "onConfigurationChanged()");
        super.onConfigurationChanged(newConfig);
        StyledAttributesHelper ta = StyledAttributesHelper.obtainStyledAttributes(this,
                new int[]{R.attr.actionBarSize});
        ViewGroup.LayoutParams params = mFakeToolbar.getLayoutParams();
        params.height = ta.getDimensionPixelSize(R.attr.actionBarSize, 0);
        mFakeToolbar.setLayoutParams(params);
        ta.recycle();
        if (mToolbar != null) {
            ViewGroup group = (ViewGroup) mToolbar.getParent();
            int i = group.indexOfChild(mToolbar);
            group.removeViewAt(i);
            Toolbar replacement = new Toolbar(group.getContext());
            replacement.setPopupTheme(mToolbar.getPopupTheme());
            AppBarLayout.LayoutParams toolbarParams = new AppBarLayout.LayoutParams(
                    AppBarLayout.LayoutParams.MATCH_PARENT, params.height);
            replacement.setLayoutParams(toolbarParams);
            for (int j = 0; j < mToolbar.getChildCount(); j++) {
                View v = mToolbar.getChildAt(j);
                if (v instanceof TabLayout) {
                    mToolbar.removeViewAt(j);
                    replacement.addView(v);
                    j--;
                }
            }
            group.addView(replacement, i);
            setSupportActionBar(replacement);
            addActionBarDrawerToggle(replacement);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (getCurrentFragment() instanceof ChatFragment chat) {
            outState.putString(ARG_SERVER_UUID, chat.getConnectionInfo().getUUID().toString());
            outState.putString(ARG_CHANNEL_NAME, chat.getCurrentChannel());
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        String serverUUID = savedInstanceState.getString(ARG_SERVER_UUID);
        if (serverUUID != null) {
            ServerConnectionSession server = ServerConnectionManager.getInstance(this).getConnection(UUID.fromString(serverUUID));
            mNavigator.openServer(server, savedInstanceState.getString(ARG_CHANNEL_NAME));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mNightModeHelper.onStart();
    }

    @Override
    protected void onDestroy() {
        ((IRCApplication) getApplication()).removeExitCallback(this);
        mDrawerHelper.unregisterListeners();
        ServerConnectionManager.getInstance(this).removeGlobalConnectionInfoListener(mNickExhaustedListener);
        dismissFragmentDialog();
        if (mNickDialog != null) { mNickDialog.dismiss(); mNickDialog = null; }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        WarningHelper.setActivity(this);
        mDCCDialogHandler.onResume();
        mNavigator.ensureValidConnection(ServerConnectionManager.getInstance(this));
        // Show nick dialog for any session that failed while the activity was in background
        if (mNickDialog == null) {
            for (ServerConnectionSession s : ServerConnectionManager.getInstance(this).getConnections()) {
                if (s.isNickExhausted()) {
                    showNickUnavailableDialog(s);
                    break;
                }
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        WarningHelper.setActivity(null);
        mDCCDialogHandler.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        ChatFragment fragment = mNavigator.handleIntent(intent,
                uuid -> ServerConnectionManager
                        .getInstance(this)
                        .getConnection(UUID.fromString(uuid)),
                getCurrentFragment(),
                ARG_SERVER_UUID,
                ARG_CHANNEL_NAME,
                ARG_MESSAGE_ID,
                ARG_MANAGE_SERVERS);

        if (fragment != null
                && Intent.ACTION_SEND.equals(intent.getAction())
                && "text/plain".equals(intent.getType())) {

            setFragmentShareText(fragment,
                    intent.getStringExtra(Intent.EXTRA_TEXT));
        }
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        mToolbar = toolbar;
    }

    public Toolbar getToolbar() {
        return mToolbar;
    }

    public void addActionBarDrawerToggle(Toolbar toolbar) {
        LockableDrawerLayout.ActionBarDrawerToggle toggle = new LockableDrawerLayout.ActionBarDrawerToggle(
                toolbar, mDrawerLayout, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        // mDrawerLayout.addDrawerListener(toggle);
    }

    public DrawerHelper getDrawerHelper() {
        return mDrawerHelper;
    }

    public Fragment getCurrentFragment() {
        return getSupportFragmentManager().findFragmentById(R.id.content_frame);
    }

    public void setFragmentDialog(Dialog dialog) {
        if (mCurrentDialog != null) {
            mCurrentDialog.setOnDismissListener(null);
            mCurrentDialog.dismiss();
        }
        mCurrentDialog = dialog;
        mCurrentDialog.setOnDismissListener((DialogInterface di) -> {
            if (mCurrentDialog == dialog)
                mCurrentDialog = null;
        });
    }

    private void showNickUnavailableDialog(ServerConnectionSession connection) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_nick_unavailable, null, false);
        android.widget.TextView reasonView = view.findViewById(R.id.nick_unavailable_reason);
        android.widget.TextView triedView = view.findViewById(R.id.nick_unavailable_tried);
        android.widget.EditText input = view.findViewById(R.id.nick_unavailable_input);

        reasonView.setText(connection.getNickExhaustedReason());

        List<String> triedNicks = connection.getNickExhaustedNicks();
        if (triedNicks != null && !triedNicks.isEmpty())
            triedView.setText(getString(R.string.nick_unavailable_tried,
                    android.text.TextUtils.join(", ", triedNicks)));
        else
            triedView.setVisibility(View.GONE);

        mNickDialogSession = connection;
        mNickDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.nick_unavailable_title)
                .setView(view)
                .setPositiveButton(R.string.action_connect, (d, which) -> {
                    mNickDialog = null;
                    mNickDialogSession = null;
                    String nick = input.getText().toString().trim();
                    if (!nick.isEmpty())
                        connection.reconnectWithNick(nick);
                })
                .setNegativeButton(android.R.string.cancel, (d, which) -> {
                    mNickDialog = null;
                    mNickDialogSession = null;
                    ServerConnectionManager.getInstance(this).removeConnection(connection);
                })
                .setOnCancelListener(d -> {
                    mNickDialog = null;
                    mNickDialogSession = null;
                    ServerConnectionManager.getInstance(this).removeConnection(connection);
                })
                .create();
        mNickDialog.show();
    }

    private static final String PREFS_NAME = "main_activity";
    private static final String PREF_BATTERY_OPT_ASKED = "battery_opt_asked";

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()))
            return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_BATTERY_OPT_ASKED, false))
            return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_opt_title)
                .setMessage(R.string.battery_opt_message)
                .setPositiveButton(R.string.battery_opt_grant, (d, which) -> {
                    prefs.edit().putBoolean(PREF_BATTERY_OPT_ASKED, true).apply();
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton(R.string.battery_opt_not_now, (d, which) ->
                        prefs.edit().putBoolean(PREF_BATTERY_OPT_ASKED, true).apply())
                .show();
    }

    public void dismissFragmentDialog() {
        if (mCurrentDialog != null) {
            InputMethodManager manager = (InputMethodManager) getSystemService(
                    Context.INPUT_METHOD_SERVICE);
            manager.hideSoftInputFromWindow(mCurrentDialog.getWindow().getDecorView()
                    .getApplicationWindowToken(), 0);

            mCurrentDialog.setOnDismissListener(null);
            mCurrentDialog.dismiss();
            mCurrentDialog = null;
        }
    }

    public void setCurrentChannelInfo(ServerConnectionSession server, String topic, String topicSetBy,
                                      Date topicSetOn, List<NickWithPrefix> members) {
        if (mChannelInfoAdapter == null)
            return;
        mChannelInfoAdapter.setData(server, topic, topicSetBy, topicSetOn, members);
        setChannelInfoDrawerVisible(topic != null || (members != null && !members.isEmpty()));
    }

    @Override
    public void setChannelInfoDrawerVisible(boolean visible) {
        if (visible) {
            mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.END);
        } else {
            mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.END);
            mDrawerLayout.closeDrawer(GravityCompat.END);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (getCurrentFragment() instanceof ChatFragment) {
            getMenuInflater().inflate(R.menu.menu_chat, menu);
        } else if (getCurrentFragment() instanceof ServerListFragment) {
            getMenuInflater().inflate(R.menu.menu_server_list, menu);
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {

        if (!(getCurrentFragment() instanceof ChatFragment fragment)) {
            return super.onPrepareOptionsMenu(menu);
        }

        boolean membersVisible =
                mDrawerLayout.getDrawerLockMode(GravityCompat.END)
                        != DrawerLayout.LOCK_MODE_LOCKED_CLOSED;

        ChatMenuState state = mMenuResolver.resolve(fragment, membersVisible);

        boolean hasChanges = mMenuApplier.apply(menu, state);

        return super.onPrepareOptionsMenu(menu) | hasChanges;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (mChatActions != null && mChatActions.handle(item.getItemId())) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void disconnectAndClose() {
        ServerConnectionSession info = ((ChatFragment) getCurrentFragment()).getConnectionInfo();
        info.disconnect();
        ServerConnectionManager.getInstance(this).removeConnection(info);
        mNavigator.openManageServers();
    }

    private void openIgnoreList() {
        ServerConnectionSession info = ((ChatFragment) getCurrentFragment()).getConnectionInfo();
        Intent intent = new Intent(this, IgnoreListActivity.class);
        intent.putExtra(IgnoreListActivity.ARG_SERVER_UUID, info.getUUID().toString());
        startActivity(intent);
    }

    private void partCurrentChannel() {
        ChatApi api = ((ChatFragment) getCurrentFragment()).getConnectionInfo().getApiInstance();
        String channel = ((ChatFragment) getCurrentFragment()).getCurrentChannel();
        if (channel != null)
            api.leaveChannel(channel, AppSettings.getDefaultPartMessage(), null, null);
    }

    private void showUserSearchDialog() {
        UserSearchDialog dialog = new UserSearchDialog(this, ((ChatFragment)
                getCurrentFragment()).getConnectionInfo(), mNavigator);
        dialog.show();
        setFragmentDialog(dialog);
    }

    private void showJoinChannelDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_chip_edit_text, null);
        ChipsEditText editText = v.findViewById(R.id.chip_edit_text);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.action_join_channel)
                .setView(v)
                .setPositiveButton(R.string.action_ok, (DialogInterface d, int which) -> {
                    editText.clearFocus();
                    String[] channels = editText.getItems();
                    if (channels.length == 0)
                        return;
                    ChatFragment currentChat = (ChatFragment) getCurrentFragment();
                    ChatApi api = currentChat.getConnectionInfo().getApiInstance();
                    currentChat.setAutoOpenChannel(channels[0]);
                    api.joinChannels(Arrays.asList(channels), null, null);
                })
                .setNeutralButton(R.string.title_activity_channel_list, (DialogInterface d, int which) -> {
                    ServerConnectionSession info = ((ChatFragment) getCurrentFragment()).getConnectionInfo();
                    Intent intent = new Intent(this, ChannelListActivity.class);
                    intent.putExtra(ChannelListActivity.ARG_SERVER_UUID, info.getUUID().toString());
                    startActivity(intent);
                })
                .create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
        dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        setFragmentDialog(dialog);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        mDCCDialogHandler.onRequestPermissionsResult(requestCode, permissions, grantResults);
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onAppExiting() {
        mAppExiting = true;
        if (getCurrentFragment() instanceof ServerListFragment)
            ((ServerListFragment) getCurrentFragment()).getAdapter().unregisterListeners();
        getDrawerHelper().unregisterListeners();
    }

    public boolean isAppExiting() {
        return mAppExiting;
    }
}
