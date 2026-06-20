package io.mrarm.irc.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.appcompat.view.ActionMode;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import io.mrarm.irc.ChannelNotificationManager;
import io.mrarm.irc.IRCChooserTargetService;
import io.mrarm.irc.MainActivity;
import io.mrarm.irc.NotificationManager;
import io.mrarm.irc.R;
import io.mrarm.irc.chatlib.ChannelInfoListener;
import io.mrarm.irc.chatlib.StatusMessageListener;
import io.mrarm.irc.chatlib.dto.ChannelInfo;
import io.mrarm.irc.chatlib.dto.MessageFilterOptions;
import io.mrarm.irc.chatlib.dto.MessageId;
import io.mrarm.irc.chatlib.dto.MessageInfo;
import io.mrarm.irc.chatlib.dto.MessageList;
import io.mrarm.irc.chatlib.dto.MessageListAfterIdentifier;
import io.mrarm.irc.chatlib.dto.MessageSenderInfo;
import io.mrarm.irc.chatlib.dto.NickWithPrefix;
import io.mrarm.irc.chatlib.dto.RoomMessageId;
import io.mrarm.irc.chatlib.dto.StatusMessageInfo;
import io.mrarm.irc.chatlib.dto.StatusMessageList;
import io.mrarm.irc.chatlib.irc.ServerConnectionApi;
import io.mrarm.irc.chatlib.message.MessageListener;
import io.mrarm.irc.config.ChatSettings;
import io.mrarm.irc.config.MessageFormatSettings;
import io.mrarm.irc.config.SettingsHelper;
import io.mrarm.irc.config.UiSettingChangeCallback;
import io.mrarm.irc.connection.ServerConnectionManager;
import io.mrarm.irc.connection.ServerConnectionSession;
import io.mrarm.irc.job.RemoveDataTask;
import io.mrarm.irc.message.MessageBus;
import io.mrarm.irc.storage.MessageStorageRepository;
import io.mrarm.irc.util.LongPressSelectTouchListener;

@Keep
public class ChatMessagesFragment extends Fragment implements StatusMessageListener,
        MessageListener, ChannelInfoListener, NotificationManager.UnreadMessageCountCallback {

    private static final String TAG = "ChatMessagesFragment";

    private static final String ARG_SERVER_UUID = "server_uuid";
    private static final String ARG_DISPLAY_STATUS = "display_status";
    private static final String ARG_CHANNEL_NAME = "channel";

    private static final int LOAD_MORE_BEFORE_INDEX = 10;

    private static final MessageFilterOptions sFilterJoinParts;

    private List<NickWithPrefix> mMembers = null;

    private ServerConnectionSession mConnection;
    private String mChannelName;
    private String mChannelTopic;
    private MessageSenderInfo mChannelTopicSetBy;
    private Date mChannelTopicSetOn;
    private RecyclerView mRecyclerView;
    private LinearLayoutManager mLayoutManager;
    private ChatMessagesAdapter mAdapter;
    private ServerStatusMessagesAdapter mStatusAdapter;
    private List<StatusMessageInfo> mStatusMessages;
    private boolean mNeedsUnsubscribeChannelInfo = false;
    private boolean mNeedsUnsubscribeMessages = false;
    private boolean mNeedsUnsubscribeStatusMessages = false;
    private MessageListAfterIdentifier mLoadOlderIdentifier;
    private MessageListAfterIdentifier mLoadNewerIdentifier;
    private boolean mIsLoadingMore;
    private MessageFilterOptions mMessageFilterOptions;
    private View mUnreadCtr;
    private TextView mUnreadText;
    private View mUnreadDiscard;
    private long mUnreadCheckedFirst = -1;
    private long mUnreadCheckedLast = -1;
    private MessageId mUnreadCheckFor;

    // Tracks "is the user following the live feed" independent of layout snapshots taken at
    // message-arrival time - LinearLayoutManager only reflects the last completed layout pass,
    // so back-to-back messages arriving faster than a layout/draw cycle would otherwise see a
    // stale last-visible position and silently skip the auto-scroll for everything after the
    // first message in the burst.
    private volatile boolean mAtBottom = true;

    private MessageStorageRepository mRoomRepo;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    static {
        sFilterJoinParts = new MessageFilterOptions();
        sFilterJoinParts.excludeMessageTypes = new ArrayList<>();
        sFilterJoinParts.excludeMessageTypes.add(MessageInfo.MessageType.JOIN);
        sFilterJoinParts.excludeMessageTypes.add(MessageInfo.MessageType.PART);
        sFilterJoinParts.excludeMessageTypes.add(MessageInfo.MessageType.QUIT);
    }

    public ChatMessagesFragment() {
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (isVisibleToUser && getParentFragment() != null)
            updateParentCurrentChannel();
        if (isVisibleToUser && getParentFragment() != null)
            ((ChatFragment) getParentFragment()).getSendMessageHelper()
                    .setCurrentChannel(mChannelName);
        if (!isVisibleToUser) {
            hideMessagesActionMenu();
        }
        if (mConnection != null && mChannelName != null) {
            mConnection.getNotificationManager().getChannelManager(mChannelName, true).setOpened(getContext(), isVisibleToUser);

            if (isVisibleToUser) {
                updateUnreadCounter();
                mConnection.getNotificationManager().addUnreadMessageCountCallback(this);
            } else {
                mConnection.getNotificationManager().removeUnreadMessageCountCallback(this);
            }
        }
        if (mConnection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (isVisibleToUser)
                IRCChooserTargetService.setChannel(mConnection.getUUID(), mChannelName);
            else
                IRCChooserTargetService.unsetChannel(mConnection.getUUID(), mChannelName);
        }
    }

    public static ChatMessagesFragment newInstance(ServerConnectionSession server,
                                                   String channelName) {
        ChatMessagesFragment fragment = new ChatMessagesFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SERVER_UUID, server.getUUID().toString());
        if (channelName != null)
            args.putString(ARG_CHANNEL_NAME, channelName);
        fragment.setArguments(args);
        return fragment;
    }

    public static ChatMessagesFragment newStatusInstance(ServerConnectionSession server) {
        ChatMessagesFragment fragment = new ChatMessagesFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SERVER_UUID, server.getUUID().toString());
        args.putBoolean(ARG_DISPLAY_STATUS, true);
        fragment.setArguments(args);
        return fragment;
    }

    private MessageFilterOptions getFilterOptions() {
        return mMessageFilterOptions;
    }

    private List<Integer> getExcludeTypesForQuery() {
        if (mMessageFilterOptions == null || mMessageFilterOptions.excludeMessageTypes == null)
            return Collections.emptyList();
        List<Integer> ret = new ArrayList<>(mMessageFilterOptions.excludeMessageTypes.size());
        for (MessageInfo.MessageType type : mMessageFilterOptions.excludeMessageTypes)
            ret.add(type.asInt());
        return ret;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UUID connectionUUID = UUID.fromString(getArguments().getString(ARG_SERVER_UUID));
        ServerConnectionSession connectionInfo = ServerConnectionManager.getInstance(getContext())
                .getConnection(connectionUUID);
        mConnection = connectionInfo;
        mChannelName = getArguments().getString(ARG_CHANNEL_NAME);

        this.mRoomRepo = ((ServerConnectionApi) mConnection.getApiInstance()).getServerConnectionData().getMessageStorageRepository();

        if (mChannelName != null) {
            mAdapter = new ChatMessagesAdapter(this, new ArrayList<>(), new ArrayList<>());
            mAdapter.setMessageFont(ChatSettings.getFont(), ChatSettings.getFontSize());

            Log.i(TAG, "Request message list for: " + mChannelName);
            connectionInfo.getApiInstance().getChannelInfo(mChannelName,
                    (ChannelInfo channelInfo) -> {
                        Log.i(TAG, "Got channel info " + mChannelName);
                        // Callback fires on the send executor thread. Marshal all field writes
                        // and the subsequent UI update to the main thread.
                        mMainHandler.post(() -> {
                            mChannelTopic = channelInfo.getTopic();
                            mChannelTopicSetBy = channelInfo.getTopicSetBy();
                            mChannelTopicSetOn = channelInfo.getTopicSetOn();
                            onMemberListChanged(channelInfo.getMembers());
                        });
                    }, null);

            connectionInfo.getApiInstance().subscribeChannelInfo(mChannelName, this, null, null);
            mNeedsUnsubscribeChannelInfo = true;

            Long jumpId = ((ChatFragment) getParentFragment()).getAndClearMessageJump(mChannelName);
            reloadMessages(jumpId);

            MessageBus bus =
                    ((ServerConnectionApi) mConnection.getApiInstance())
                            .getServerConnectionData()
                            .getMessageBus();

            bus.subscribe(mChannelName, this);
            mNeedsUnsubscribeMessages = true;


        } else if (getArguments().getBoolean(ARG_DISPLAY_STATUS)) {
            mStatusAdapter = new ServerStatusMessagesAdapter(mConnection, new StatusMessageList(new ArrayList<>()));
            mStatusAdapter.setMessageFont(ChatSettings.getFont(), ChatSettings.getFontSize());

            Log.i(TAG, "Request status message list");
            connectionInfo.getApiInstance().getStatusMessages(100, null,
                    (StatusMessageList messages) -> {
                        Log.i(TAG, "Got server status message list: " +
                                messages.getMessages().size() + " messages");
                        mStatusMessages = messages.getMessages();
                        mNeedsUnsubscribeStatusMessages = true;
                        updateMessageList(() -> {
                            mStatusAdapter.setMessages(messages);
                            if (mRecyclerView != null)
                                mRecyclerView.scrollToPosition(mStatusAdapter.getItemCount() - 1);
                        });

                        connectionInfo.getApiInstance().subscribeStatusMessages(ChatMessagesFragment.this, null, null);
                    }, null);
        }

        SettingsHelper.registerCallbacks(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        SettingsHelper.unregisterCallbacks(this);

        if (mNeedsUnsubscribeChannelInfo)
            mConnection.getApiInstance().unsubscribeChannelInfo(getArguments().getString(ARG_CHANNEL_NAME), ChatMessagesFragment.this, null, null);
        if (mNeedsUnsubscribeMessages) {
            MessageBus bus =
                    ((ServerConnectionApi) mConnection.getApiInstance())
                            .getServerConnectionData()
                            .getMessageBus();
            bus.unsubscribe(mChannelName, this);
        }
        if (mNeedsUnsubscribeStatusMessages)
            mConnection.getApiInstance().unsubscribeStatusMessages(ChatMessagesFragment.this, null, null);

        mConnection.getNotificationManager().removeUnreadMessageCountCallback(this);

        hideMessagesActionMenu();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View rootView = inflater.inflate(R.layout.chat_messages_fragment, container, false);
        synchronized (this) {
            mRecyclerView = rootView.findViewById(R.id.messages);
            mUnreadCtr = rootView.findViewById(R.id.unread_counter_ctr);
            mUnreadText = rootView.findViewById(R.id.unread_counter);
            mUnreadDiscard = rootView.findViewById(R.id.unread_counter_discard);
        }
        mLayoutManager = new LinearLayoutManager(getContext());
        mLayoutManager.setStackFromEnd(true);
        mRecyclerView.setLayoutManager(mLayoutManager);
        // notifyItemInserted() is immediately followed by scrollToPosition() on every
        // incoming message (see onMessage()) - with the default animator active those two
        // fight each other and RecyclerView aborts mid-animation by detaching and
        // re-attaching every visible child, which flashes as the whole list briefly
        // going blank.
        mRecyclerView.setItemAnimator(null);

        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                int count = mAdapter == null
                        ? (mStatusAdapter == null ? 0 : mStatusAdapter.getItemCount())
                        : mAdapter.getItemCount();
                int last = mLayoutManager.findLastVisibleItemPosition();
                mAtBottom = last >= 0 && last >= count - 1;

                if (mAdapter == null)
                    return;
                checkForUnreadMessages();
                int firstVisible = mLayoutManager.findFirstVisibleItemPosition();
                if (firstVisible >= 0 && firstVisible < LOAD_MORE_BEFORE_INDEX) {
                    if (mIsLoadingMore || !mAdapter.hasMessages())
                        return;
                    Log.i(TAG, "Load more (older): " + mChannelName);
                    long firstId = mAdapter.getFirstMessageId();  // oldest currently displayed
                    mIsLoadingMore = true;

                    mRoomRepo.loadOlderAsync(
                            mConnection.getUUID(),
                            mChannelName,
                            firstId,
                            100,
                            getExcludeTypesForQuery(),
                            (olderList) -> {
                                MessageList messages = mRoomRepo.toMessageListFromRoom(olderList);
                                updateMessageList(() -> {
                                    mAdapter.addMessagesToTop(messages.getMessages(), messages.getMessageIds());
                                    mIsLoadingMore = false;
                                });
                            }
                    );
                }
                int lastVisible = mLayoutManager.findLastVisibleItemPosition();
                if (lastVisible <= mAdapter.getItemCount() &&
                        lastVisible > mAdapter.getItemCount() - LOAD_MORE_BEFORE_INDEX) {
                    if (mIsLoadingMore || !mAdapter.hasMessages())
                        return;
                    Log.i(TAG, "Load more (newer): " + mChannelName);
                    long lastId = mAdapter.getLastMessageId();
                    mIsLoadingMore = true;

                    mRoomRepo.loadNewerAsync(
                            mConnection.getUUID(),
                            mChannelName,
                            lastId,
                            100,
                            getExcludeTypesForQuery(),
                            (newerList) -> {
                                MessageList messages = mRoomRepo.toMessageListFromRoom(newerList);
                                updateMessageList(() -> {
                                    mAdapter.addMessagesToBottom(messages.getMessages(), messages.getMessageIds());
                                    mIsLoadingMore = false;
                                });
                            }
                    );
                }
            }
        });

        mUnreadCtr.setOnClickListener((v) -> {
            ChannelNotificationManager mgr =
                    mConnection.getNotificationManager().getChannelManager(mChannelName, true);
            MessageId msgId = mgr.getFirstUnreadMessage();
            int index = mAdapter.findMessageWithId(msgId);
            if (index != -1) {
                ((LinearLayoutManager) mRecyclerView.getLayoutManager())
                        .scrollToPositionWithOffset(index, 0);
            } else {
                // We don't have a matching message in the current list,
                // just reload using the Room-based path (no jump for now).
                reloadMessages(null);
            }
            mgr.clearUnreadMessages();
        });
        mUnreadDiscard.setOnClickListener((v) -> {
            ChannelNotificationManager mgr = mConnection.getNotificationManager().getChannelManager(mChannelName, true);
            mgr.clearUnreadMessages();
        });

        if (mAdapter != null) {
            mRecyclerView.setAdapter(mAdapter);

            LongPressSelectTouchListener selectTouchListener =
                    new LongPressSelectTouchListener(mRecyclerView);
            mAdapter.setMultiSelectListener(selectTouchListener);
            mRecyclerView.addOnItemTouchListener(selectTouchListener);

            if (!ChatSettings.shouldUseOnlyMultiSelectMode()) {
                ChatSelectTouchListener newSelectTouchListener =
                        new ChatSelectTouchListener(mRecyclerView);
                newSelectTouchListener.setMultiSelectListener(selectTouchListener);
                newSelectTouchListener.setActionModeStateCallback((android.view.ActionMode actionMode,
                                                                   boolean b) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                            actionMode.getType() == android.view.ActionMode.TYPE_FLOATING)
                        return;
                    ((ChatFragment) getParentFragment()).setTabsHidden(b);
                });
                mAdapter.setSelectListener(newSelectTouchListener);
                mRecyclerView.addOnItemTouchListener(newSelectTouchListener);
            }
        } else if (mStatusAdapter != null) {
            mRecyclerView.setAdapter(mStatusAdapter);
        }

        if (getUserVisibleHint())
            ((ChatFragment) getParentFragment()).getSendMessageHelper()
                    .setCurrentChannel(mChannelName);

        updateUnreadCounter();

        return rootView;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        synchronized (this) {
            mRecyclerView = null;
        }
        if (mAdapter != null) {
            mAdapter.setSelectListener(null);
        }
        if (mConnection != null && getUserVisibleHint() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            IRCChooserTargetService.unsetChannel(mConnection.getUUID(), mChannelName);
    }

    private void reloadMessages(Long nearMessageRoomId) {

        if (ChatSettings.shouldHideJoinPartMessages())
            mMessageFilterOptions = sFilterJoinParts;
        else
            mMessageFilterOptions = null;

        mUnreadCheckedFirst = -1;
        mUnreadCheckedLast = -1;

        mAdapter.setNewMessagesStart(
                mConnection.getNotificationManager()
                        .getChannelManager(mChannelName, true)
                        .getFirstUnreadMessage()
        );

        UUID serverId = mConnection.getUUID();

        // === CASE 1: Jump to a specific Room message ID ===
        if (nearMessageRoomId != null) {

            mRoomRepo.loadNearAsync(serverId, mChannelName, nearMessageRoomId, 100,
                    getExcludeTypesForQuery(),
                    (list) -> {
                        MessageList msgList = mRoomRepo.toMessageListFromRoom(list);
                        updateMessageList(() -> {
                            mAdapter.setMessages(msgList.getMessages(), msgList.getMessageIds());

                            int index = mAdapter.findMessageWithId(
                                    new RoomMessageId(nearMessageRoomId)
                            );
                            if (index >= 0) {
                                ((LinearLayoutManager) mRecyclerView.getLayoutManager())
                                        .scrollToPositionWithOffset(index, 0);
                            }
                        });
                    }
            );

            return;
        }

        // === CASE 2: Normal first loadConnectedServers (most recent 100 messages) ===
        mRoomRepo.loadRecentAsync(serverId, mChannelName, 100, getExcludeTypesForQuery(), (msgList) -> {
            updateMessageList(() -> {
                mAdapter.setMessages(msgList.getMessages(), msgList.getMessageIds());

                if (mRecyclerView != null && mAdapter.getItemCount() > 0) {
                    mRecyclerView.scrollToPosition(mAdapter.getItemCount() - 1);
                }
            });
        });
    }


    private void updateUnreadCounter() {
        if (mConnection == null || mRecyclerView == null || mAdapter == null)
            return;
        ChannelNotificationManager mgr = mConnection.getNotificationManager().getChannelManager(mChannelName, true);
        int unread = mgr.getUnreadMessageCount();
        MessageId unreadMsg = mgr.getFirstUnreadMessage();
        if (unreadMsg == null && unread > 0 && getUserVisibleHint()) {
            unread = 0;
            mgr.clearUnreadMessages();
        }
        if (unread > 0) {
            int index = mAdapter.findMessageWithId(unreadMsg);
            View v = mRecyclerView.getLayoutManager().findViewByPosition(index);
            if (v != null && getUserVisibleHint() && !isInScrollAnimation() &&
                    mRecyclerView.getLayoutManager().isViewPartiallyVisible(v, true, true)) {
                // Already actively looking at the unread message (e.g. auto-scrolled to the
                // live bottom) - don't flash the "new messages" divider just to clear it again
                // on the next message.
                unread = 0;
                mgr.clearUnreadMessages();
                mAdapter.setNewMessagesStart(null);
            } else {
                mAdapter.setNewMessagesStart(unreadMsg);
            }
        } else {
            mAdapter.setNewMessagesStart(null);
        }
        mUnreadCtr.setVisibility(View.GONE);
        if (unread > 0) {
            if (mUnreadCheckFor == null || !mUnreadCheckFor.equals(unreadMsg)) {
                mUnreadCheckFor = unreadMsg;
                mUnreadCheckedFirst = -1;
                mUnreadCheckedLast = -1;
            }
            mUnreadCtr.setVisibility(View.VISIBLE);
            mUnreadText.setText(getResources().getQuantityString(R.plurals.unread_message_counter, unread, unread));
        }
    }

    private boolean isInScrollAnimation() {
        Fragment parent = getParentFragment();
        return parent instanceof ChatFragment && ((ChatFragment) parent).isScrolling();
    }

    // Called by ChatFragment once the ViewPager swipe settles, so a page that was only
    // passed through mid-swipe (and thus skipped the clear above) gets a fair re-check.
    void recheckUnread() {
        updateUnreadCounter();
    }

    private void checkForUnreadMessages() {
        if (mUnreadCtr.getVisibility() == View.GONE)
            return;
        // onScrolled() also fires from a programmatic scrollToBottom() on a non-visible,
        // preloaded (offscreenPageLimit) fragment when a new message arrives - its
        // RecyclerView is laid out even off-screen, so the "completely visible" check
        // below would false-positive and clear unread on a chat nobody looked at.
        if (!getUserVisibleHint())
            return;
        LinearLayoutManager llm = (LinearLayoutManager) mRecyclerView.getLayoutManager();
        int firstPos = llm.findFirstCompletelyVisibleItemPosition();
        int lastPos = llm.findLastCompletelyVisibleItemPosition();
        long firstId = firstPos != RecyclerView.NO_POSITION ? mAdapter.getItemId(firstPos) : -1;
        long lastId = lastPos != RecyclerView.NO_POSITION ? mAdapter.getItemId(lastPos) : -1;
        boolean found = false;
        if (mUnreadCheckedFirst == -1 && firstId != -1) {
            mUnreadCheckedFirst = firstId;
            mUnreadCheckedLast = firstId;
            found = checkItemForUnread(
                    mAdapter.getMessage(mAdapter.getItemPosition(firstId)), mUnreadCheckFor);
        }
        while (firstId != -1 && firstId < mUnreadCheckedFirst) {
            found |= checkItemForUnread(mAdapter.getMessage(
                    mAdapter.getItemPosition(mUnreadCheckedFirst)), mUnreadCheckFor);
            if (found)
                break;
            --mUnreadCheckedFirst;
        }
        while (lastId != -1 && lastId > mUnreadCheckedLast) {
            found |= checkItemForUnread(mAdapter.getMessage(
                    mAdapter.getItemPosition(mUnreadCheckedLast)), mUnreadCheckFor);
            if (found)
                break;
            ++mUnreadCheckedLast;
        }
        if (found) {
            ChannelNotificationManager mgr = mConnection.getNotificationManager()
                    .getChannelManager(mChannelName, true);
            mgr.clearUnreadMessages();
            mUnreadCtr.setVisibility(View.GONE);
            mUnreadCheckedFirst = -1;
            mUnreadCheckedLast = -1;
        }
    }

    private boolean checkItemForUnread(ChatMessagesAdapter.Item item, MessageId lookingFor) {
        if (item instanceof ChatMessagesAdapter.MessageItem) {
            return ((ChatMessagesAdapter.MessageItem) item).mMessageId.equals(lookingFor);
        }
        return false;
    }

    @Override
    public void onUnreadMessageCountChanged(ServerConnectionSession info, String channel, int messageCount, int oldMessageCount) {
        if (channel.equals(mChannelName)) {
            updateMessageList(this::updateUnreadCounter);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getUserVisibleHint()) {
            mConnection.getNotificationManager().getChannelManager(mChannelName, true).setOpened(getContext(), true);
            mConnection.getNotificationManager().addUnreadMessageCountCallback(this);
            updateUnreadCounter();
        }
        if (mConnection != null && getUserVisibleHint() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            IRCChooserTargetService.setChannel(mConnection.getUUID(), mChannelName);
    }

    @Override
    public void onPause() {
        super.onPause();
        MainActivity activity = (MainActivity) getActivity();
        if (getUserVisibleHint() && (activity == null || !activity.isAppExiting()))
            mConnection.getNotificationManager().getChannelManager(mChannelName, true).setOpened(getContext(), false);
        if (mConnection != null && getUserVisibleHint() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            IRCChooserTargetService.setChannel(mConnection.getUUID(), mChannelName);
        mConnection.getNotificationManager().removeUnreadMessageCountCallback(this);
        mUnreadCtr.setVisibility(View.GONE);
    }

    @UiSettingChangeCallback(keys = {
            ChatSettings.PREF_FONT,
            ChatSettings.PREF_FONT_SIZE,
            ChatSettings.PREF_HIDE_JOIN_PART_MESSAGES,
            // it's enough to only register to the last format preference, as all preferences are always rewritten
            MessageFormatSettings.PREF_MESSAGE_FORMAT_EVENT_HOSTNAME
    })
    private void onSettingChanged() {
        if (mAdapter != null) {
            mAdapter.setMessageFont(ChatSettings.getFont(), ChatSettings.getFontSize());
            mAdapter.notifyDataSetChanged();
        }
        if (mStatusAdapter != null) {
            mStatusAdapter.setMessageFont(ChatSettings.getFont(), ChatSettings.getFontSize());
            mStatusAdapter.notifyDataSetChanged();
        }
        if (ChatSettings.shouldHideJoinPartMessages() != (mMessageFilterOptions != null) &&
                mChannelName != null) {
            reloadMessages(null);
        }
    }

    private void updateParentCurrentChannel() {
        Activity activity = getActivity();
        if (activity == null)
            return;
        activity.runOnUiThread(() -> {
            if (!isAdded()) return;
            Fragment parent = getParentFragment();
            if (parent instanceof ChatFragment) {
                ((ChatFragment) parent).setCurrentChannelInfo(
                        mChannelTopic,
                        mChannelTopicSetBy != null ? mChannelTopicSetBy.getNick() : null,
                        mChannelTopicSetOn,
                        mMembers
                );
            }
        });
    }

    private void updateMessageList(Runnable r) {
        synchronized (this) {
            if (mRecyclerView != null) {
                mRecyclerView.post(r);
            } else {
                // View not yet inflated (onCreate before onCreateView). Still post to the main
                // thread — never run adapter mutations directly on the caller's thread, which
                // may be the MessagePipeline executor.
                mMainHandler.post(r);
            }
        }
    }

    public ServerConnectionSession getConnectionInfo() {
        return mConnection;
    }

    public String getChannelName() {
        return mChannelName;
    }

    public boolean isServerStatus() {
        return mStatusAdapter != null;
    }

    private void scrollToBottom() {
        int count = mAdapter == null ? mStatusAdapter.getItemCount() : mAdapter.getItemCount();
        mRecyclerView.scrollToPosition(count - 1);
    }

    @Override
    public void onMessage(String channel, MessageInfo messageInfo, MessageId messageId) {
        updateMessageList(() -> {
            if (mLoadNewerIdentifier != null)
                return;
            MessageFilterOptions opt = getFilterOptions();
            if (opt != null) {
                if (opt.restrictToMessageTypes != null &&
                        !opt.restrictToMessageTypes.contains(messageInfo.getType()))
                    return;
                if (opt.excludeMessageTypes != null &&
                        opt.excludeMessageTypes.contains(messageInfo.getType()))
                    return;
            }

            if (!getUserVisibleHint() && mAdapter.getNewMessagesStart() == null)
                mAdapter.setNewMessagesStart(messageId);
            boolean wasAtBottom = mRecyclerView != null && mAtBottom;
            mAdapter.appendMessage(messageInfo, messageId);
            if (wasAtBottom) {
                scrollToBottom();
                // Re-arm immediately: onScrolled() won't confirm this scroll until the next
                // layout pass, so a second message arriving before then must still see "at
                // bottom" rather than a stale pre-append layout snapshot.
                mAtBottom = true;
            }
        });
    }

    @Override
    public void onStatusMessage(StatusMessageInfo statusMessageInfo) {
        updateMessageList(() -> {
            boolean wasAtBottom = mRecyclerView != null && mAtBottom;
            mStatusMessages.add(statusMessageInfo);
            mStatusAdapter.notifyItemInserted(mStatusMessages.size() - 1);
            if (wasAtBottom) {
                scrollToBottom();
                mAtBottom = true;
            }
        });
    }

    @Override
    public void onMemberListChanged(List<NickWithPrefix> list) {
        this.mMembers = list;
        Collections.sort(list, (NickWithPrefix left, NickWithPrefix right) -> {
            if (left.getNickPrefixes() != null && right.getNickPrefixes() != null) {
                char leftPrefix = left.getNickPrefixes().get(0);
                char rightPrefix = right.getNickPrefixes().get(0);
                for (char c : ((ServerConnectionApi) mConnection.getApiInstance())
                        .getServerConnectionData().getSupportList().getSupportedNickPrefixes()) {
                    if (leftPrefix == c && rightPrefix != c)
                        return -1;
                    if (rightPrefix == c && leftPrefix != c)
                        return 1;
                }
            } else if (left.getNickPrefixes() != null || right.getNickPrefixes() != null)
                return left.getNickPrefixes() != null ? -1 : 1;
            return left.getNick().compareToIgnoreCase(right.getNick());
        });
        if (getUserVisibleHint())
            updateParentCurrentChannel();
    }

    @Override
    public void onTopicChanged(String topic, MessageSenderInfo topicSetBy, Date topicSetOn) {
        mChannelTopic = topic;
        mChannelTopicSetBy = topicSetBy;
        mChannelTopicSetOn = topicSetOn;
        if (getUserVisibleHint())
            updateParentCurrentChannel();
    }

    public void showMessagesActionMenu() {
        if (mMessagesActionModeCallback == null)
            mMessagesActionModeCallback = new MessagesActionModeCallback();
        if (mMessagesActionModeCallback.mActionMode == null)
            mMessagesActionModeCallback.mActionMode = ((MainActivity) getActivity()).startSupportActionMode(mMessagesActionModeCallback);
    }

    public void hideMessagesActionMenu() {
        if (mMessagesActionModeCallback != null && mMessagesActionModeCallback.mActionMode != null) {
            mMessagesActionModeCallback.mActionMode.finish();
            mMessagesActionModeCallback.mActionMode = null;
        }
    }

    public void copySelectedMessages() {
        CharSequence messages = mAdapter.getSelectedMessages();
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("IRC Messages", messages));
    }

    public void shareSelectedMessages() {
        CharSequence messages = mAdapter.getSelectedMessages();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_TEXT, messages);
        intent.setType("text/plain");
        mRecyclerView.getContext().startActivity(Intent.createChooser(intent,
                getString(R.string.message_share_title)));
    }

    public void deleteSelectedMessages() {
        List<Long> ids = new ArrayList<>(mAdapter.getSelectedItems());

        // 1. Extract ROOM ids from MessageIds
        List<Long> roomIds = new ArrayList<>();
        for (MessageId mid : mAdapter.getSelectedMessageIds()) {
            if (mid instanceof RoomMessageId) {
                roomIds.add(((RoomMessageId) mid).getId());
            }
        }

        if (roomIds.isEmpty())
            return;

        for (Long l : ids) {
            ChatMessagesAdapter.Item i = mAdapter.getMessage(mAdapter.getItemPosition(l));
            if (i instanceof ChatMessagesAdapter.MessageItem) {
                ((ChatMessagesAdapter.MessageItem) i).mHidden = true;
            }
        }
        mAdapter.notifyDataSetChanged();

        RemoveDataTask.start(
                requireContext(),
                false,                  // deleteConfig
                roomIds,                    // deleteMessageEntries
                null,                   // deleteServerLogs
                mRoomRepo,
                null                    // listener (optional)
        );
    }


    private MessagesActionModeCallback mMessagesActionModeCallback;

    private class MessagesActionModeCallback implements ActionMode.Callback {

        public ActionMode mActionMode;

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.menu_context_messages_full, menu);
            ((ChatFragment) getParentFragment()).setTabsHidden(true);
            mActionMode = mode;
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.action_copy:
                    copySelectedMessages();
                    mode.finish();
                    return true;
                case R.id.action_share:
                    shareSelectedMessages();
                    mode.finish();
                    return true;
                case R.id.action_delete: {
                    int cnt = mAdapter.getSelectedItems().size();
                    new AlertDialog.Builder(getContext())
                            .setTitle(R.string.action_delete_confirm_title)
                            .setMessage(getResources().getQuantityString(R.plurals.message_delete_confirm, cnt, cnt) + "\n\n" + getResources().getString(R.string.message_delete_confirm_note))
                            .setPositiveButton(R.string.action_delete, (di, w) -> {
                                deleteSelectedMessages();
                                mode.finish();
                            })
                            .setNegativeButton(R.string.action_cancel, null)
                            .show();
                    return true;
                }
                default:
                    return false;
            }
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            ((ChatFragment) getParentFragment()).setTabsHidden(false);
            mAdapter.clearSelection();
            mActionMode = null;
        }

    }

    ;

}