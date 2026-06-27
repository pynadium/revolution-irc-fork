package io.mrarm.irc.chatlog;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.mrarm.irc.R;
import io.mrarm.irc.model.ConversationMessage;
import io.mrarm.irc.protocol.dto.RoomMessageId;
import io.mrarm.irc.util.DayIntHelper;

public class ChatLogAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_MESSAGE = 0;
    private static final int TYPE_DAY_MARKER = 1;

    private final SimpleDateFormat mTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final List<ConversationMessage> mRawMessages = new ArrayList<>();
    private final List<Object> mItems = new ArrayList<>();

    public boolean hasMessages() {
        return !mRawMessages.isEmpty();
    }

    public long getOldestId() {
        return ((RoomMessageId) mRawMessages.get(0).getId()).getId();
    }

    public void setMessages(List<ConversationMessage> messages) {
        mRawMessages.clear();
        mRawMessages.addAll(messages);
        rebuildItems();
        notifyDataSetChanged();
    }

    public void addToTop(List<ConversationMessage> olderMessages) {
        if (olderMessages.isEmpty())
            return;
        mRawMessages.addAll(0, olderMessages);
        rebuildItems();
        notifyDataSetChanged();
    }

    private void rebuildItems() {
        mItems.clear();
        int lastDay = -1;
        for (ConversationMessage msg : mRawMessages) {
            if (msg.getTimestamp() == null)
                continue;
            int day = DayIntHelper.getDayInt(msg.getTimestamp());
            if (day != lastDay) {
                mItems.add(new DaySeparator(day));
                lastDay = day;
            }
            mItems.add(msg);
        }
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        return mItems.get(position) instanceof DaySeparator ? TYPE_DAY_MARKER : TYPE_MESSAGE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_DAY_MARKER)
            return new DayMarkerHolder(inflater.inflate(R.layout.chat_day_marker, parent, false));
        return new MessageHolder(inflater.inflate(R.layout.chat_message, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = mItems.get(position);
        if (holder instanceof DayMarkerHolder)
            ((DayMarkerHolder) holder).bind((DaySeparator) item);
        else
            ((MessageHolder) holder).bind((ConversationMessage) item);
    }

    class MessageHolder extends RecyclerView.ViewHolder {
        final TextView text;

        MessageHolder(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.chat_message);
        }

        void bind(ConversationMessage msg) {
            String sender = msg.getSenderDisplayName();
            String line = "[" + mTimeFormat.format(msg.getTimestamp()) + "] "
                    + (sender != null ? sender + ": " : "")
                    + (msg.getText() != null ? msg.getText() : "");
            text.setText(line);
        }
    }

    static class DayMarkerHolder extends RecyclerView.ViewHolder {
        final TextView text;

        DayMarkerHolder(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.text);
        }

        void bind(DaySeparator sep) {
            text.setText(DateUtils.formatDateTime(
                    text.getContext(),
                    DayIntHelper.getDateIntMs(sep.day),
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR));
        }
    }

    static class DaySeparator {
        final int day;
        DaySeparator(int day) { this.day = day; }
    }
}
