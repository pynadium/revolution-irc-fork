package io.mrarm.irc.drawer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import io.mrarm.irc.NotificationManager;
import io.mrarm.irc.R;
import io.mrarm.irc.SettingsActivity;
import io.mrarm.irc.app.navigation.NavigationHost;
import io.mrarm.irc.chatlib.irc.ServerConnectionApi;
import io.mrarm.irc.chatlog.ChatLogViewerActivity;
import io.mrarm.irc.config.AppSettings;
import io.mrarm.irc.config.SettingsHelper;
import io.mrarm.irc.config.UiSettingChangeCallback;
import io.mrarm.irc.connection.ServerConnectionManager;
import io.mrarm.irc.connection.ServerConnectionSession;
import io.mrarm.irc.dialog.ChannelSearchDialog;
import io.mrarm.irc.view.LockableDrawerLayout;

public class DrawerHelper implements ServerConnectionManager.ConnectionsListener,
        ServerConnectionSession.InfoChangeListener, ServerConnectionSession.ChannelListChangeListener,
        NotificationManager.UnreadMessageCountCallback,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private Activity mActivity;
    private LockableDrawerLayout mDrawerLayout;
    private RecyclerView mRecyclerView;
    private LinearLayoutManager mLayoutManager;
    private DrawerMenuListAdapter mAdapter;
    private DrawerMenuItem mSearchItem;
    private DrawerMenuItem mManageServersItem;
    private DrawerMenuItem mChatLogsItem;
    private DrawerMenuItem mSettingsItem;
    private boolean mHasRegisteredListeners = false;
    private final NavigationHost mNavigationHost;

    public DrawerHelper(Activity activity, NavigationHost navigationHost) {
        mActivity = activity;
        mNavigationHost = navigationHost;
        mDrawerLayout = activity.findViewById(R.id.drawer_layout);
        mRecyclerView = activity.findViewById(R.id.nav_list);
        mLayoutManager = new LinearLayoutManager(activity);
        mRecyclerView.setLayoutManager(mLayoutManager);

        Resources r = activity.getResources();

        mAdapter = new DrawerMenuListAdapter(activity, mDrawerLayout,
                AppSettings.shouldDrawerAlwaysShowServer());

        mSearchItem = new DrawerMenuItem(r.getString(R.string.action_search), R.drawable.ic_search_white);
        mSearchItem.setOnClickListener((View view) -> {
            ChannelSearchDialog dialog;
            dialog = new ChannelSearchDialog(activity,
                    (server, channel) -> mNavigationHost.getNavigator().openServer(server, channel));
            dialog.show();
            mDrawerLayout.closeDrawers();
        });
        mAdapter.addTopMenuItem(mSearchItem);
        mManageServersItem = new DrawerMenuItem(r.getString(R.string.action_servers), R.drawable.ic_edit);
        mAdapter.addMenuItem(mManageServersItem);
        mChatLogsItem = new DrawerMenuItem(r.getString(R.string.action_chat_logs), R.drawable.ic_history);
        mChatLogsItem.setOnClickListener((View v) -> {
            activity.startActivity(new Intent(activity, ChatLogViewerActivity.class));
            mDrawerLayout.closeDrawers();
        });
        mAdapter.addMenuItem(mChatLogsItem);
        mSettingsItem = new DrawerMenuItem(r.getString(R.string.action_settings), R.drawable.ic_settings);
        mSettingsItem.setOnClickListener((View view) -> {
            activity.startActivity(new Intent(activity, SettingsActivity.class));
        });
        mAdapter.addMenuItem(mSettingsItem);

        mDrawerLayout.addDrawerListener(new DrawerLayout.DrawerListener() {
            boolean wasClosed = false;

            @Override
            public void onDrawerSlide(View drawerView, float slideOffset) {
            }

            @Override
            public void onDrawerOpened(View drawerView) {
                wasClosed = false;
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                wasClosed = true;
            }

            @Override
            public void onDrawerStateChanged(int newState) {
                if (newState == DrawerLayout.STATE_DRAGGING && wasClosed) {
                    updateScrollPosition();
                    wasClosed = false;
                }
            }
        });
        mRecyclerView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            int oldPadding;

            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (oldLeft == left && oldTop == top && oldRight == right && oldBottom == bottom && mRecyclerView.getPaddingBottom() == oldPadding)
                    return;
                oldPadding = mRecyclerView.getPaddingBottom();
                if (mDrawerLayout.isDrawerVisible(GravityCompat.START))
                    updateScrollPosition();
            }
        });
        mRecyclerView.setAdapter(mAdapter);

        ItemTouchHelper.SimpleCallback callback =
                new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

                    @Override
                    public int getSwipeDirs(@NonNull RecyclerView recyclerView,
                                            @NonNull RecyclerView.ViewHolder viewHolder) {

                        int pos = viewHolder.getAdapterPosition();
                        int type = mAdapter.getItemViewType(pos);

                        // Allow swipe ONLY for channels
                        if (type != DrawerMenuListAdapter.TYPE_CHANNEL)
                            return 0;

                        DrawerMenuListAdapter.ChannelHolder ch =
                                (viewHolder instanceof DrawerMenuListAdapter.ChannelHolder)
                                        ? (DrawerMenuListAdapter.ChannelHolder) viewHolder
                                        : null;

                        if (ch == null || ch.mChannel == null)
                            return 0;

                        // Determine if it's a direct message (no #)
                        boolean isDirect =
                                !((ServerConnectionApi) ch.mConnection
                                        .getApiInstance())
                                        .getServerConnectionData()
                                        .getSupportList()
                                        .getSupportedChannelTypes()
                                        .contains(ch.mChannel.charAt(0));

                        return isDirect ? ItemTouchHelper.RIGHT : 0;
                    }

                    @Override
                    public boolean onMove(@NonNull RecyclerView rv,
                                          @NonNull RecyclerView.ViewHolder vh,
                                          @NonNull RecyclerView.ViewHolder target) {
                        return false;
                    }

                    @Override
                    public void onSwiped(RecyclerView.ViewHolder vh, int direction) {
                        int pos = vh.getAdapterPosition();
                        int type = mAdapter.getItemViewType(pos);

                        // only channels → ignore others
                        if (type != 2 /* TYPE_CHANNEL */) {
                            mAdapter.notifyItemChanged(pos);
                            return;
                        }

                        DrawerMenuListAdapter.ChannelHolder ch =
                                (DrawerMenuListAdapter.ChannelHolder) vh;

                        String channel = ch.mChannel;

                        // only private messages allowed → no swipe for #channels or server pseudo-items
                        if (channel == null || channel.startsWith("#")) {
                            mAdapter.notifyItemChanged(pos);
                            return;
                        }


                        ServerConnectionSession server = ch.mConnection;
                        if (server != null) {
                            server.getApiInstance().leaveChannel(
                                    ch.mChannel,
                                    AppSettings.getDefaultPartMessage(),
                                    null,
                                    null);
                        }

                        mAdapter.notifyServerListChanged();
                    }
                };

        new ItemTouchHelper(callback).attachToRecyclerView(mRecyclerView);
    }

    public void registerListeners() {
        if (mHasRegisteredListeners)
            return;
        ServerConnectionManager.getInstance(mActivity).addListener(this);
        ServerConnectionManager.getInstance(mActivity).addGlobalConnectionInfoListener(this);
        ServerConnectionManager.getInstance(mActivity).addGlobalChannelListListener(this);
        NotificationManager.getInstance().addGlobalUnreadMessageCountCallback(this);
        SettingsHelper.registerCallbacks(this);
        mHasRegisteredListeners = true;
    }

    public void unregisterListeners() {
        if (!mHasRegisteredListeners)
            return;
        ServerConnectionManager.getInstance(mActivity).removeListener(this);
        ServerConnectionManager.getInstance(mActivity).removeGlobalConnectionInfoListener(this);
        ServerConnectionManager.getInstance(mActivity).removeGlobalChannelListListener(this);
        NotificationManager.getInstance().removeGlobalUnreadMessageCountCallback(this);
        SettingsHelper.unregisterCallbacks(this);
        mHasRegisteredListeners = false;
    }

    @UiSettingChangeCallback(keys = {AppSettings.PREF_DRAWER_ALWAYS_SHOW_SERVER})
    private void onSettingChanged() {
        mAdapter.setAlwaysShowServer(AppSettings.shouldDrawerAlwaysShowServer());
    }

    public void setChannelClickListener(DrawerMenuListAdapter.ChannelClickListener listener) {
        mAdapter.setChannelClickListener(listener);
    }

    public DrawerMenuItem getManageServersItem() {
        return mManageServersItem;
    }

    public void setSelectedChannel(ServerConnectionSession server, String channel) {
        mAdapter.setSelectedChannel(server, channel);
    }

    public void setSelectedMenuItem(DrawerMenuItem menuItem) {
        mAdapter.setSelectedMenuItem(menuItem);
    }

    private void updateScrollPosition() {
        int pos = mAdapter.getSelectedItemIndex();
        if (pos == -1)
            return;
        int s = mLayoutManager.findFirstCompletelyVisibleItemPosition();
        int e = mLayoutManager.findLastCompletelyVisibleItemPosition();
        if (pos < s || pos > e) {
            mLayoutManager.scrollToPositionWithOffset(pos, (mLayoutManager.getHeight()
                    - mLayoutManager.getPaddingTop() - mLayoutManager.getPaddingBottom()) / 3);
        }
    }

    @Override
    public void onConnectionAdded(ServerConnectionSession connection) {
        mActivity.runOnUiThread(mAdapter::notifyServerListChanged);
    }

    @Override
    public void onConnectionRemoved(ServerConnectionSession connection) {
        mActivity.runOnUiThread(mAdapter::notifyServerListChanged);
    }

    @Override
    public void onConnectionInfoChanged(ServerConnectionSession connection) {
        mActivity.runOnUiThread(() -> {
            mAdapter.notifyServerInfoChanged(connection);
        });
    }

    @Override
    public void onChannelListChanged(ServerConnectionSession connection, List<String> newChannels) {
        mActivity.runOnUiThread(mAdapter::notifyServerListChanged);
    }

    @Override
    public void onUnreadMessageCountChanged(ServerConnectionSession info, String channel,
                                            int messageCount, int oldMessageCount) {
        mActivity.runOnUiThread(() -> {
            mAdapter.notifyChannelUnreadCountChanged(info, channel);
        });
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        mActivity.runOnUiThread(() -> {
            mAdapter.setAlwaysShowServer(AppSettings.shouldDrawerAlwaysShowServer());
        });
    }
}
